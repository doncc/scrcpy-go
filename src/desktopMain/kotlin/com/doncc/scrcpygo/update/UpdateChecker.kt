package com.doncc.scrcpygo.update

import com.doncc.scrcpygo.utils.PlatformUtils
import com.doncc.scrcpygo.utils.VersionUtils
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale

/**
 * GitHub Release 更新检测。
 *
 * 设计约定：
 * - 只读取 releases/latest 响应里的 tag_name 与全部 browser_download_url，
 *   不解析完整 JSON，避免引入额外的序列化依赖。
 * - 版本比较、响应解析、资产选择都是纯函数；网络取数通过 ReleaseFetcher 注入，方便单元测试。
 * - 任何异常都收敛为 UpdateCheckResult.Failed，由调用方静默处理，不打扰用户。
 */

/** Release 中的一个下载资产 */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String
)

/** 解析后的发布信息 */
data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>
)

/** 一次更新检测的最终结果 */
sealed interface UpdateCheckResult {
    /** 已是最新，或该版本已被用户跳过 */
    data object NoUpdate : UpdateCheckResult

    /** 发现可更新版本；downloadUrl 为空表示当前平台没有匹配资产 */
    data class Available(
        val newVersion: String,
        val currentVersion: String,
        val downloadUrl: String?,
        val pageUrl: String
    ) : UpdateCheckResult

    /** 网络、解析或版本比较失败，调用方静默忽略 */
    data object Failed : UpdateCheckResult
}

/**
 * 语义化版本比较：提取数字版本段，按点分段逐段比较，缺段补 0。
 * 不处理预发布后缀，因为 releases/latest 本身不会返回预发布版本。
 */
object VersionComparator {
    private val VERSION_PATTERN = Regex("""\d+(?:\.\d+)*""")

    /** 从任意字符串中提取第一个数字版本段，如 v1.0.1 -> 1.0.1；提取不到返回 null */
    fun extract(rawVersion: String): String? =
        VERSION_PATTERN.find(rawVersion.trim())?.value

    /**
     * 比较两个版本号。
     * 返回正数表示 first 更新，负数表示 second 更新，0 表示相等；任一版本无法解析时返回 null。
     */
    fun compare(first: String, second: String): Int? {
        val firstParts = parseParts(first) ?: return null
        val secondParts = parseParts(second) ?: return null
        val size = maxOf(firstParts.size, secondParts.size)
        for (index in 0 until size) {
            val left = firstParts.getOrElse(index) { 0L }
            val right = secondParts.getOrElse(index) { 0L }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    /** first 是否严格高于 second；无法比较时返回 null */
    fun isNewer(first: String, second: String): Boolean? =
        compare(first, second)?.let { it > 0 }

    private fun parseParts(rawVersion: String): List<Long>? {
        val extracted = extract(rawVersion) ?: return null
        return extracted.split('.').map { segment -> segment.toLongOrNull() ?: return null }
    }
}

/**
 * 从 GitHub release JSON 中提取所需字段。
 * 只依赖 tag_name 与 browser_download_url 两个键名，不解析作者的 html_url 等同名字段。
 */
object ReleaseParser {
    const val REPO_PAGE_URL = "https://github.com/doncc/scrcpy-go"
    const val DOWNLOAD_URL_PREFIX = "https://github.com/doncc/scrcpy-go/releases/download/"

    private val TAG_PATTERN = Regex("\"tag_name\"\\s*:\\s*\"([^\"]*)\"")
    private val DOWNLOAD_PATTERN = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]*)\"")

    /** 解析失败（空响应、缺少 tag、tag 里没有版本号）返回 null */
    fun parse(json: String): ReleaseInfo? {
        if (json.isBlank()) return null

        val tagName = TAG_PATTERN.find(json)
            ?.groupValues
            ?.get(1)
            ?.let(::unescapeJson)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val version = VersionComparator.extract(tagName) ?: return null

        val assets = DOWNLOAD_PATTERN.findAll(json)
            .map { match -> unescapeJson(match.groupValues[1]) }
            .filter { url -> url.startsWith(DOWNLOAD_URL_PREFIX) }
            .mapNotNull { url -> assetNameFromUrl(url)?.let { name -> ReleaseAsset(name, url) } }
            .distinctBy { asset -> asset.downloadUrl }
            .toList()

        return ReleaseInfo(
            tagName = tagName,
            version = version,
            pageUrl = REPO_PAGE_URL + "/releases/tag/" + tagName,
            assets = assets
        )
    }

    /** 从下载地址末段还原资产文件名 */
    private fun assetNameFromUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val rawName = path.substringAfterLast('/')
        if (rawName.isBlank()) return null
        return try {
            URLDecoder.decode(rawName, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            rawName
        }
    }

    /** 处理 JSON 字符串里的转义字符，主要是 URL 中可能出现的反斜杠斜杠 */
    private fun unescapeJson(value: String): String {
        if (!value.contains('\\')) return value
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch != '\\' || index == value.length - 1) {
                builder.append(ch)
                index++
                continue
            }
            when (val escaped = value[index + 1]) {
                '"' -> { builder.append('"'); index += 2 }
                '\\' -> { builder.append('\\'); index += 2 }
                '/' -> { builder.append('/'); index += 2 }
                'b' -> { builder.append('\b'); index += 2 }
                'f' -> { builder.append('\u000C'); index += 2 }
                'n' -> { builder.append('\n'); index += 2 }
                'r' -> { builder.append('\r'); index += 2 }
                't' -> { builder.append('\t'); index += 2 }
                'u' -> {
                    val hex = value.substring(index + 2, minOf(index + 6, value.length))
                    if (hex.length == 4) {
                        hex.toIntOrNull(16)?.let { builder.append(it.toChar()) }
                        index += 6
                    } else {
                        builder.append(escaped)
                        index += 2
                    }
                }
                else -> { builder.append(escaped); index += 2 }
            }
        }
        return builder.toString()
    }
}

/**
 * 按当前平台与架构挑选 release 资产。
 * 优先匹配“平台 + 架构”，没有精确命中时退一步只按平台匹配，仍找不到返回 null。
 */
object ReleaseAssetSelector {
    fun pick(assets: List<ReleaseAsset>, platform: String, arch: String): ReleaseAsset? {
        val platformKey = platform.lowercase(Locale.ROOT)
        val archAliases = archAliases(arch)
        val zipAssets = assets.filter { asset -> asset.name.endsWith(".zip", ignoreCase = true) }
        if (zipAssets.isEmpty()) return null

        val exactMatch = zipAssets.firstOrNull { asset ->
            val name = asset.name.lowercase(Locale.ROOT)
            name.contains(platformKey) && archAliases.any { alias -> name.contains(alias) }
        }
        if (exactMatch != null) return exactMatch

        return zipAssets.firstOrNull { asset ->
            asset.name.lowercase(Locale.ROOT).contains(platformKey)
        }
    }

    private fun archAliases(arch: String): Set<String> = when (arch.lowercase(Locale.ROOT)) {
        "x64", "amd64", "x86_64" -> setOf("x64", "amd64", "x86_64")
        "aarch64", "arm64" -> setOf("aarch64", "arm64")
        else -> setOf(arch.lowercase(Locale.ROOT))
    }
}

/** 当前运行环境的平台与架构标识，供资产匹配使用 */
object HostPlatform {
    fun platformKey(): String = when {
        PlatformUtils.isWindows -> "windows"
        PlatformUtils.isMac -> "macos"
        else -> "linux"
    }

    fun archKey(): String = if (
        PlatformUtils.osArch.contains("aarch64") || PlatformUtils.osArch.contains("arm64")
    ) {
        "aarch64"
    } else {
        "x64"
    }
}

/** 可注入的取数接口：生产实现走 HTTP，测试直接返回固定 JSON */
fun interface ReleaseFetcher {
    fun fetchLatestReleaseJson(): String
}

/** 通过 JDK 内置 HttpClient 请求 GitHub releases/latest */
class HttpReleaseFetcher(
    private val currentVersion: String = VersionUtils.version,
    private val endpoint: String = RELEASE_API_ENDPOINT
) : ReleaseFetcher {
    override fun fetchLatestReleaseJson(): String {
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "scrcpy-go/" + currentVersion)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("GitHub API 返回 HTTP " + response.statusCode())
        }

        response.body().use { input ->
            val bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) {
                throw IOException("GitHub API 响应体超过上限")
            }
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    companion object {
        const val RELEASE_API_ENDPOINT = "https://api.github.com/repos/doncc/scrcpy-go/releases/latest"
        private const val CONNECT_TIMEOUT_SECONDS = 3L
        private const val REQUEST_TIMEOUT_SECONDS = 5L
        private const val MAX_RESPONSE_BYTES = 1_000_000

        // HttpClient 内部持有连接池，复用单例避免每次检测重建线程
        private val client: HttpClient by lazy {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        }
    }
}

/** 统一检测入口：取数、解析、版本比较、跳过判定、资产选择 */
object UpdateChecker {
    fun evaluate(
        currentVersion: String,
        skippedVersion: String?,
        platform: String,
        arch: String,
        fetcher: ReleaseFetcher
    ): UpdateCheckResult {
        val json = try {
            fetcher.fetchLatestReleaseJson()
        } catch (e: Exception) {
            return UpdateCheckResult.Failed
        }

        val release = try {
            ReleaseParser.parse(json)
        } catch (e: Exception) {
            null
        } ?: return UpdateCheckResult.Failed

        val remoteIsNewer = VersionComparator.isNewer(release.version, currentVersion)
            ?: return UpdateCheckResult.Failed
        if (!remoteIsNewer) return UpdateCheckResult.NoUpdate

        // 跳过记录只拦截“不高于已跳过版本”的发布；记录损坏时按未跳过处理
        if (!skippedVersion.isNullOrBlank()) {
            val comparison = VersionComparator.compare(release.version, skippedVersion)
            if (comparison != null && comparison <= 0) {
                return UpdateCheckResult.NoUpdate
            }
        }

        val asset = ReleaseAssetSelector.pick(release.assets, platform, arch)
        return UpdateCheckResult.Available(
            newVersion = release.version,
            currentVersion = currentVersion,
            downloadUrl = asset?.downloadUrl,
            pageUrl = release.pageUrl
        )
    }
}
