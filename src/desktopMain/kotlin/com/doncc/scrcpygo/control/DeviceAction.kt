package com.doncc.scrcpygo.control

import com.doncc.scrcpygo.process.CommandResult
import com.doncc.scrcpygo.process.ProcessManager

/**
 * 控制条上的设备动作。
 *
 * 简单动作直接提供 adb shell 后面的参数；旋转和电源需要在执行时读取状态，
 * 因此 shellArgs 为空，由执行器走专门的分支。
 */
enum class DeviceAction(
    val label: String,
    val shellArgs: List<String>
) {
    BACK("返回", listOf("input", "keyevent", "KEYCODE_BACK")),
    HOME("桌面", listOf("input", "keyevent", "KEYCODE_HOME")),
    RECENTS("最近", listOf("input", "keyevent", "KEYCODE_APP_SWITCH")),
    NOTIFICATIONS("通知", listOf("cmd", "statusbar", "expand-notifications")),
    QUICK_SETTINGS("快捷设置", listOf("cmd", "statusbar", "expand-settings")),
    COLLAPSE_PANELS("收起", listOf("cmd", "statusbar", "collapse")),
    VOLUME_UP("音量+", listOf("input", "keyevent", "KEYCODE_VOLUME_UP")),
    VOLUME_DOWN("音量-", listOf("input", "keyevent", "KEYCODE_VOLUME_DOWN")),
    ROTATE("旋转", emptyList()),
    SCREEN_POWER("休眠/唤醒", emptyList())
}

/**
 * 一次设备动作的执行结果。message 只在失败或需要提示时使用。
 */
data class ActionOutcome(val success: Boolean, val message: String? = null)

/**
 * 通过 adb 旁路执行设备动作，不占用 scrcpy 的控制 socket。
 */
object DeviceActionRunner {
    private const val COMMAND_TIMEOUT_MS = 5_000L

    private val ERROR_MARKERS = listOf(
        "exception", "error", "unknown command", "unknown option", "not found", "failed"
    )

    suspend fun execute(serial: String, action: DeviceAction): ActionOutcome {
        return when (action) {
            DeviceAction.ROTATE -> rotateScreen(serial)
            DeviceAction.SCREEN_POWER -> toggleScreenPower(serial)
            else -> {
                val result = runShell(serial, *action.shellArgs.toTypedArray())
                classify(result)
            }
        }
    }

    private suspend fun runShell(serial: String, vararg args: String): CommandResult {
        return ProcessManager.runAdbCommandResult(
            "-s", serial, "shell", *args,
            timeoutMs = COMMAND_TIMEOUT_MS
        )
    }

    /**
     * 旋转到下一个方向。
     * 优先启用系统的固定用户旋转再锁定角度；命令不可用时退回系统设置。
     */
    private suspend fun rotateScreen(serial: String): ActionOutcome {
        val current = readRotation(serial) ?: 0
        val next = nextRotation(current)

        // 部分设备没有这个命令，失败后继续尝试后面的路径
        runShell(serial, "cmd", "window", "fixed-to-user-rotation", "enabled")
        val lock = runShell(
            serial, "cmd", "window", "user-rotation", "lock", next.toString()
        )
        if (succeeded(lock)) {
            return ActionOutcome(true, "已旋转到 " + (next * 90) + "°")
        }

        val accelerometer = runShell(
            serial, "settings", "put", "system", "accelerometer_rotation", "0"
        )
        val userRotation = runShell(
            serial, "settings", "put", "system", "user_rotation", next.toString()
        )
        return if (succeeded(accelerometer) && succeeded(userRotation)) {
            ActionOutcome(true, "已旋转到 " + (next * 90) + "°")
        } else {
            ActionOutcome(false, errorMessage(lock, userRotation))
        }
    }

    private suspend fun toggleScreenPower(serial: String): ActionOutcome {
        val result = runShell(serial, "input", "keyevent", "KEYCODE_POWER")
        val outcome = classify(result)
        return if (outcome.success) {
            ActionOutcome(true, "已发送电源键")
        } else {
            outcome
        }
    }

    /**
     * 读取设备当前显示方向，返回 0/1/2/3。
     * 先看 dumpsys 的实际显示方向；失败时退回用户旋转设置。
     */
    private suspend fun readRotation(serial: String): Int? {
        val display = runShell(serial, "dumpsys", "window", "displays")
        parseDisplayRotation(display.stdout)?.let { return it }

        val settings = runShell(serial, "settings", "get", "system", "user_rotation")
        return settings.stdout.trim().toIntOrNull()?.takeIf { it in 0..3 }
    }

    internal fun parseDisplayRotation(text: String): Int? {
        val marker = "mDisplayRotation=ROTATION_"
        val markerIndex = text.indexOf(marker)
        if (markerIndex < 0) return null
        val digits = text.substring(markerIndex + marker.length)
            .takeWhile { it.isDigit() }
        return when (digits.toIntOrNull()) {
            0 -> 0
            90 -> 1
            180 -> 2
            270 -> 3
            else -> null
        }
    }

    internal fun nextRotation(current: Int): Int {
        return (current.coerceIn(0, 3) + 1) % 4
    }

    private fun succeeded(result: CommandResult): Boolean {
        if (result.timedOut || result.exitCode != 0) return false
        if (result.stderr.isNotBlank()) return false
        val stdout = result.stdout.trim()
        return stdout.isEmpty() || ERROR_MARKERS.none { stdout.contains(it, ignoreCase = true) }
    }

    private fun errorMessage(vararg results: CommandResult): String {
        for (result in results) {
            val outcome = classify(result)
            if (!outcome.success) {
                return outcome.message ?: "旋转失败"
            }
        }
        return "旋转失败"
    }

    /**
     * 把命令结果转换成界面可直接显示的结果。
     * 退出码非零、超时、stderr 非空或 stdout 含明显错误标记都按失败处理。
     */
    internal fun classify(result: CommandResult): ActionOutcome {
        if (result.timedOut) {
            return ActionOutcome(false, "设备响应超时，请检查连接")
        }

        val stderr = result.stderr.trim()
        if (result.exitCode != 0) {
            val message = when {
                stderr.isNotBlank() -> firstLine(stderr)
                result.stdout.isNotBlank() -> firstLine(result.stdout)
                else -> "执行失败（退出码 " + result.exitCode + "）"
            }
            return ActionOutcome(false, message)
        }

        if (stderr.isNotEmpty()) {
            return ActionOutcome(false, firstLine(stderr))
        }

        val stdout = result.stdout.trim()
        if (stdout.isNotEmpty() && ERROR_MARKERS.any { stdout.contains(it, ignoreCase = true) }) {
            return ActionOutcome(false, firstLine(stdout))
        }

        return ActionOutcome(true)
    }

    private fun firstLine(text: String): String {
        return text.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(100)
            ?: "设备没有返回结果"
    }
}
