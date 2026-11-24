package com.doncc.scrcpygo.process

import com.doncc.scrcpygo.utils.PlatformUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 命令执行结果。
 * timedOut 为 true 时表示进程在超时时间内没有退出，退出码不可用。
 */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
)

/**
 * 子进程管理器
 * 负责执行 ADB 命令行指令、启动与杀死 scrcpy 投屏进程，以及利用 ShutdownHook 规避孤儿进程残留
 */
object ProcessManager {
    // 线程安全容器：保存当前运行中的投屏进程 (Key: 设备序列号, Value: 对应 Process 实例)
    private val runningProcesses = ConcurrentHashMap<String, Process>()

    init {
        // 注册 JVM 退出钩子。在软件窗口关闭、崩溃或被结束时，自动清理全部衍生出来的投屏进程
        Runtime.getRuntime().addShutdownHook(Thread {
            clearAllProcesses()
        })
    }

    /**
     * 基础命令行同步执行工具
     * 返回 stdout 和 stderr 输出；保留原有返回结构，内部委托给带退出码的实现
     */
    suspend fun runCommand(vararg cmd: String): Pair<String, String> {
        val result = runCommandResult(*cmd)
        return Pair(result.stdout, result.stderr)
    }

    /**
     * 执行命令并返回退出码、标准输出、标准错误和超时标记。
     * stdout/stderr 在独立线程中读取，避免输出较多时阻塞；
     * timeoutMs 为 0 表示不设超时，保持原有调用方行为。
     */
    suspend fun runCommandResult(
        vararg cmd: String,
        timeoutMs: Long = 0
    ): CommandResult = withContext(Dispatchers.IO) {
        var timedOut = false
        try {
            val process = ProcessBuilder(*cmd).start()

            // 两个流分别读取，防止其中一个缓冲区写满后进程无法退出
            val stdoutFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().use { it.readText() }
            }

            val finished = if (timeoutMs > 0) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                process.waitFor()
                true
            }

            if (!finished) {
                timedOut = true
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }

            val stdout = stdoutFuture.get(2, TimeUnit.SECONDS).trim()
            val stderr = stderrFuture.get(2, TimeUnit.SECONDS).trim()
            CommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdout,
                stderr = stderr,
                timedOut = !finished
            )
        } catch (e: Exception) {
            CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "命令行执行发生异常",
                timedOut = timedOut
            )
        }
    }

    /**
     * 自动补全 adb 绝对路径并同步运行
     */
    suspend fun runAdbCommand(vararg args: String): Pair<String, String> {
        val adbPath = PlatformUtils.getBinaryPath("adb")
        val cmd = arrayOf(adbPath) + args
        return runCommand(*cmd)
    }

    /**
     * 自动补全 adb 绝对路径，并返回带退出码的命令结果
     */
    suspend fun runAdbCommandResult(
        vararg args: String,
        timeoutMs: Long = 0
    ): CommandResult {
        val adbPath = PlatformUtils.getBinaryPath("adb")
        val cmd = arrayOf(adbPath) + args
        return runCommandResult(*cmd, timeoutMs = timeoutMs)
    }

    /**
     * 异步拉起特定设备的 scrcpy 投屏进程，并捕获 stderr 以供报错诊断。
     * windowTitle 非空时追加唯一窗口标题，便于区分同型号设备和后续定位。
     */
    fun launchScrcpy(
        serial: String,
        customArgs: List<String>,
        windowTitle: String? = null
    ): Result<Process> {
        val scrcpyPath = PlatformUtils.getBinaryPath("scrcpy")
        val adbPath = PlatformUtils.getBinaryPath("adb")
        
        // 组装最终命令行: scrcpy -s <serial> [附加参数...]
        val cmd = mutableListOf(scrcpyPath, "-s", serial)
        cmd.addAll(customArgs)
        if (!windowTitle.isNullOrBlank()) {
            cmd.add("--window-title")
            cmd.add(windowTitle)
        }

        return try {
            val pb = ProcessBuilder(cmd)
            
            // 注入环境变量，保证本地打包运行环境下的 scrcpy 能够准确找到 adb 与 scrcpy-server
            val env = pb.environment()
            val serverPath = PlatformUtils.getScrcpyServerPath()
            if (serverPath != null) {
                env["SCRCPY_SERVER_PATH"] = serverPath
            }
            env["ADB"] = adbPath

            // 将标准输出重定向以避免缓冲区阻塞，错误流使用 pipe 方式进行异步读取
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)

            // 启动前先终结旧的同一设备连接
            terminateProcessForDevice(serial)

            val process = pb.start()

            // 后台异步读取 stderr 错误日志，防止阻塞并记录最后报错信息
            val stderrBuffer = StringBuilder()
            val errorThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (stderrBuffer.length < 4096) {
                            stderrBuffer.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    // 忽略读取流闭合异常
                }
            }
            errorThread.isDaemon = true
            errorThread.start()

            // 等待一小段时间检测进程是否立即闪退
            Thread.sleep(300)
            if (!process.isAlive && process.exitValue() != 0) {
                val errorMsg = stderrBuffer.toString().trim().ifEmpty { "进程以非零退出码终止: ${process.exitValue()}" }
                return Result.failure(RuntimeException("Scrcpy 投屏服务启动失败:\n$errorMsg"))
            }

            runningProcesses[serial] = process
            Result.success(process)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 销毁指定设备的投屏进程
     */
    fun terminateProcessForDevice(serial: String) {
        val process = runningProcesses.remove(serial)
        if (process != null && process.isAlive) {
            try {
                process.destroy() // 触发 SIGTERM
                if (process.isAlive) {
                    process.destroyForcibly() // 强制杀掉进程
                }
            } catch (e: Exception) {
                // 屏蔽关闭异常
            }
        }
    }

    /**
     * 查询指定设备是否正在被本软件进行投屏
     */
    fun isDeviceRunning(serial: String): Boolean {
        val process = runningProcesses[serial]
        return process != null && process.isAlive
    }

    /**
     * 销毁并清理所有当前存活的进程
     */
    fun clearAllProcesses() {
        val iterator = runningProcesses.keys().iterator()
        while (iterator.hasNext()) {
            val serial = iterator.next()
            terminateProcessForDevice(serial)
        }
    }
}
