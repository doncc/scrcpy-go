package com.doncc.scrcpygo.process

import com.doncc.scrcpygo.utils.PlatformUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

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
     * 返回 stdout 和 stderr 输出
     */
    suspend fun runCommand(vararg cmd: String): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(*cmd).start()
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val stdout = stdoutReader.readText().trim()
            val stderr = stderrReader.readText().trim()
            process.waitFor()
            Pair(stdout, stderr)
        } catch (e: Exception) {
            Pair("", e.message ?: "命令行执行发生异常")
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
     * 异步拉起特定设备的 scrcpy 投屏进程
     */
    fun launchScrcpy(serial: String, customArgs: List<String>): Result<Process> {
        val scrcpyPath = PlatformUtils.getBinaryPath("scrcpy")
        val adbPath = PlatformUtils.getBinaryPath("adb")
        
        // 组装最终命令行: scrcpy -s <serial> [附加参数...]
        val cmd = mutableListOf(scrcpyPath, "-s", serial)
        cmd.addAll(customArgs)

        return try {
            val pb = ProcessBuilder(cmd)
            
            // 注入环境变量，保证本地打包运行环境下的 scrcpy 能够准确找到 adb 与 scrcpy-server
            val env = pb.environment()
            val serverPath = PlatformUtils.getScrcpyServerPath()
            if (serverPath != null) {
                env["SCRCPY_SERVER_PATH"] = serverPath
            }
            env["ADB"] = adbPath

            // 将流重定向抛弃，防止输出缓冲区满导致子进程挂起
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)

            val process = pb.start()
            
            // 启动前先终结旧的同一设备连接，并缓存当前实例
            terminateProcessForDevice(serial)
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
