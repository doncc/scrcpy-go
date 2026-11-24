package com.doncc.scrcpygo.update

import com.doncc.scrcpygo.utils.PlatformUtils
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * “跳过此版本”记录的本地存储。
 * 使用 Properties 文件保存已跳过的版本号；读取失败按未跳过处理，写入失败返回 false，
 * 两种情况都不影响应用启动和更新检测主流程。
 */
class UpdateSkipStore(
    private val skipFile: File = PlatformUtils.updateSkipFile
) {
    private val lock = Any()

    /** 读取已跳过的版本号；文件不存在、损坏或内容为空时返回 null */
    fun load(): String? = synchronized(lock) {
        val target = skipFile.absoluteFile
        if (!target.exists()) {
            null
        } else {
            try {
                val properties = Properties()
                target.inputStream().use { properties.load(it) }
                properties.getProperty(KEY_SKIPPED_VERSION)
                    ?.trim()
                    ?.takeIf { version -> version.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 记录跳过的版本号；返回是否成功落盘 */
    fun save(version: String): Boolean = synchronized(lock) {
        val target = skipFile.absoluteFile
        val directory = target.parentFile
        if (directory == null || (!directory.exists() && !directory.mkdirs())) {
            false
        } else {
            try {
                val properties = Properties()
                properties.setProperty(KEY_SKIPPED_VERSION, version.trim())
                val tempFile = File(directory, target.name + ".tmp")
                try {
                    tempFile.outputStream().use { output ->
                        properties.store(output, "scrcpy-go skipped update version")
                    }
                    try {
                        Files.move(
                            tempFile.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (e: AtomicMoveNotSupportedException) {
                        Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    companion object {
        private const val KEY_SKIPPED_VERSION = "skippedVersion"
    }
}
