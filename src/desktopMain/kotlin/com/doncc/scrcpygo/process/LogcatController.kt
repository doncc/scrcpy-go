package com.doncc.scrcpygo.process

import com.doncc.scrcpygo.utils.PlatformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.regex.Pattern

/**
 * 日志实体类
 */
data class LogEntry(
    val rawText: String,
    val level: String // V, D, I, W, E, F, default
)

/**
 * Logcat 日志控制器
 * 负责与 adb logcat 管道进行数据对接，并做行级别正则拆解、包名解析及时间戳后的包名拼接、合并限流批量派发
 */
class LogcatController(
    private val serial: String,
    private val coroutineScope: CoroutineScope
) {
    private var logcatJob: Job? = null
    private var pidUpdateJob: Job? = null
    
    // 待过滤的目标包名，变更时自动触发比对逻辑
    var filterPackage: String = ""
        set(value) {
            field = value.trim()
        }

    // 线程安全的全局缓存字典：用于保存 PID -> 进程包名/进程名 映射
    private val pidToPackageMap = ConcurrentHashMap<String, String>()

    // 去重集：记录当前正在异步解析中的 PID，防止重复发起 adb 进程查询
    private val resolvingPids = ConcurrentSkipListSet<String>()

    // 无界通道，接收后台线程流式读取到的单条日志
    private val logChannel = Channel<LogEntry>(Channel.UNLIMITED)

    // 合并缓冲后，向 Compose GUI 进行批量派发的回调
    var onLogsBuffered: ((List<LogEntry>) -> Unit)? = null

    var isRunning = false
        private set

    init {
        // 启动一个常驻防抖协程，批量分发日志数据以规避高频重绘卡顿
        coroutineScope.launch(Dispatchers.Default) {
            val batch = mutableListOf<LogEntry>()
            var lastFlushTime = System.currentTimeMillis()

            while (isActive) {
                // 尽可能抽空 Channel 中的数据放入 batch 暂存
                while (true) {
                    val log = logChannel.tryReceive().getOrNull() ?: break
                    batch.add(log)
                    if (batch.size >= 200) break // 单批次最多打包 200 条，立刻分发
                }

                val now = System.currentTimeMillis()
                // 当缓存行数达到阀值，或自上次更新已过去 50 毫秒且包含数据，则批量刷新 UI
                if (batch.isNotEmpty() && (batch.size >= 100 || now - lastFlushTime >= 50)) {
                    val listToSend = batch.toList()
                    batch.clear()
                    lastFlushTime = now
                    withContext(Dispatchers.Main) {
                        onLogsBuffered?.invoke(listToSend)
                    }
                }

                // 挂起一小段时间防止忙等待消耗 CPU
                delay(15)
            }
        }
    }

    /**
     * 开启日志监听与 PID 扫描
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        // 1. 开启全局进程定时扫描扫描器 (每 2.0 秒刷新一次 PID 字典)
        pidUpdateJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                scanActiveProcesses()
                delay(2000)
            }
        }

        // 2. 管道式读取 `adb logcat` 子进程流
        logcatJob = coroutineScope.launch(Dispatchers.IO) {
            val adbPath = PlatformUtils.getBinaryPath("adb")
            // 使用 threadtime 输出，-T 1 限制仅拉取新产生的日志
            val cmd = listOf(adbPath, "-s", serial, "logcat", "-v", "threadtime", "-T", "1")
            
            var process: Process? = null
            try {
                // 启动时同步获取一次进程列表，防止开始阶段抓取到的日志全是 PID 格式
                scanActiveProcesses()

                process = ProcessBuilder(cmd).start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                
                // 正则捕获：[1时间戳] [2PID] [3TID] [4级别] [5Tag]: [6消息内容]
                val logPattern = Pattern.compile(
                    """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?):(.*)$"""
                )

                while (isActive) {
                    val line = reader.readLine() ?: break
                    val lineStripped = line.trim()
                    
                    val matcher = logPattern.matcher(lineStripped)
                    if (matcher.matches()) {
                        val timestamp = matcher.group(1)
                        val pid = matcher.group(2)
                        val tid = matcher.group(3)
                        val level = matcher.group(4)
                        val tag = matcher.group(5)
                        val msg = matcher.group(6)
                        
                        // 查找 PID 关联的包名来源
                        val packageSource = pidToPackageMap[pid]
                        val packageSourceStr = if (packageSource != null) {
                            packageSource
                        } else {
                            // 第一次遇到此未知 PID，触发轻量级单点异步解析，当前行降级展示 [PID:xxx]
                            resolveUnknownPidAsync(pid)
                            "PID:$pid"
                        }
                        
                        // 当指定了过滤包名时，如果本行解析出的包名来源不包含过滤词，则直接丢弃
                        if (filterPackage.isNotEmpty() && !packageSourceStr.lowercase().contains(filterPackage.lowercase())) {
                            continue
                        }
                        
                        // 重组日志行：在时间戳之后插入 [包名来源]
                        val formattedLine = "$timestamp [$packageSourceStr] $pid $tid $level $tag:$msg\n"
                        logChannel.send(LogEntry(formattedLine, level))
                    } else {
                        // 对于非标准控制台提示日志，在没有配置包名过滤时保留输出
                        if (filterPackage.isEmpty()) {
                            logChannel.send(LogEntry(line + "\n", "default"))
                        }
                    }
                }
            } catch (e: Exception) {
                logChannel.send(LogEntry("日志抓取进程中断: ${e.message}\n", "E"))
            } finally {
                process?.destroy()
            }
        }
    }

    /**
     * 暂停/停止当前日志抓取并清空内存缓存
     */
    fun stop() {
        isRunning = false
        logcatJob?.cancel()
        logcatJob = null
        pidUpdateJob?.cancel()
        pidUpdateJob = null
        pidToPackageMap.clear()
        resolvingPids.clear()
    }

    /**
     * 同步扫描全系统运行中的进程列表，填充本地 PID 映射字典
     */
    private suspend fun scanActiveProcesses() {
        val adbPath = PlatformUtils.getBinaryPath("adb")
        try {
            val pb = ProcessBuilder(adbPath, "-s", serial, "shell", "ps -A -o PID,NAME || ps -o PID,NAME")
            val process = pb.start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            
            // 跳过首行 "PID NAME" 标题
            reader.readLine()
            
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: break
                if (currentLine.isEmpty()) continue
                val parts = currentLine.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val pid = parts[0]
                    val name = parts[1]
                    if (pid.all { it.isDigit() }) {
                        pidToPackageMap[pid] = name
                    }
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            // 忽略读取异常
        }
    }

    /**
     * 对新检测到的未知 PID 进行异步反查，补充字典缓存
     */
    private fun resolveUnknownPidAsync(pid: String) {
        if (resolvingPids.add(pid)) {
            coroutineScope.launch(Dispatchers.IO) {
                val adbPath = PlatformUtils.getBinaryPath("adb")
                try {
                    // 读取 /proc/PID/cmdline 获取精准包名，速度极快且开销极小
                    val pb = ProcessBuilder(adbPath, "-s", serial, "shell", "cat /proc/$pid/cmdline")
                    val process = pb.start()
                    val rawName = process.inputStream.bufferedReader().readText().trim()
                    process.waitFor()
                    
                    // 去除 cmdline 文件尾部可能存在的 Null 字符与控制字符
                    val cleanedName = rawName.replace(Regex("[\\u0000-\\u001F]"), "").trim()
                    if (cleanedName.isNotEmpty()) {
                        pidToPackageMap[pid] = cleanedName
                    } else {
                        pidToPackageMap[pid] = "Unknown"
                    }
                } catch (e: Exception) {
                    // 屏蔽反查异常
                } finally {
                    resolvingPids.remove(pid)
                }
            }
        }
    }
}
