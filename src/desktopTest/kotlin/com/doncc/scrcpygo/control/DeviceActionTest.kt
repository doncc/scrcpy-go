package com.doncc.scrcpygo.control

import com.doncc.scrcpygo.process.CommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceActionTest {
    @Test
    fun navigationActionsMapToAndroidKeyEvents() {
        assertEquals(listOf("input", "keyevent", "KEYCODE_BACK"), DeviceAction.BACK.shellArgs)
        assertEquals(listOf("input", "keyevent", "KEYCODE_HOME"), DeviceAction.HOME.shellArgs)
        assertEquals(listOf("input", "keyevent", "KEYCODE_APP_SWITCH"), DeviceAction.RECENTS.shellArgs)
    }

    @Test
    fun systemPanelsMapToStatusBarCommands() {
        assertEquals(listOf("cmd", "statusbar", "expand-notifications"), DeviceAction.NOTIFICATIONS.shellArgs)
        assertEquals(listOf("cmd", "statusbar", "expand-settings"), DeviceAction.QUICK_SETTINGS.shellArgs)
        assertEquals(listOf("cmd", "statusbar", "collapse"), DeviceAction.COLLAPSE_PANELS.shellArgs)
    }

    @Test
    fun volumeActionsMapToVolumeKeyEvents() {
        assertEquals(listOf("input", "keyevent", "KEYCODE_VOLUME_UP"), DeviceAction.VOLUME_UP.shellArgs)
        assertEquals(listOf("input", "keyevent", "KEYCODE_VOLUME_DOWN"), DeviceAction.VOLUME_DOWN.shellArgs)
    }

    @Test
    fun rotateAndPowerUseDynamicExecution() {
        assertTrue(DeviceAction.ROTATE.shellArgs.isEmpty())
        assertTrue(DeviceAction.SCREEN_POWER.shellArgs.isEmpty())
        assertEquals("旋转", DeviceAction.ROTATE.label)
        assertEquals("休眠/唤醒", DeviceAction.SCREEN_POWER.label)
        assertEquals(10, DeviceAction.entries.size)
    }

    @Test
    fun displayRotationParserHandlesAllAngles() {
        assertEquals(0, DeviceActionRunner.parseDisplayRotation("mDisplayRotation=ROTATION_0"))
        assertEquals(1, DeviceActionRunner.parseDisplayRotation("mDisplayRotation=ROTATION_90"))
        assertEquals(2, DeviceActionRunner.parseDisplayRotation("mDisplayRotation=ROTATION_180"))
        assertEquals(3, DeviceActionRunner.parseDisplayRotation("mDisplayRotation=ROTATION_270"))
        assertNull(DeviceActionRunner.parseDisplayRotation("mDisplayRotation=ROTATION_45"))
        assertNull(DeviceActionRunner.parseDisplayRotation("no rotation here"))
    }

    @Test
    fun nextRotationCyclesThroughFourAngles() {
        assertEquals(1, DeviceActionRunner.nextRotation(0))
        assertEquals(2, DeviceActionRunner.nextRotation(1))
        assertEquals(3, DeviceActionRunner.nextRotation(2))
        assertEquals(0, DeviceActionRunner.nextRotation(3))
        assertEquals(0, DeviceActionRunner.nextRotation(99))
    }

    @Test
    fun successfulCommandHasNoMessage() {
        val outcome = DeviceActionRunner.classify(CommandResult(0, "", ""))

        assertTrue(outcome.success)
        assertNull(outcome.message)
    }

    @Test
    fun nonZeroExitUsesFirstStderrLine() {
        val outcome = DeviceActionRunner.classify(CommandResult(1, "", "device offline\nmore detail"))

        assertFalse(outcome.success)
        assertEquals("device offline", outcome.message)
    }

    @Test
    fun timeoutIsReportedSeparately() {
        val outcome = DeviceActionRunner.classify(CommandResult(-1, "", "", timedOut = true))

        assertFalse(outcome.success)
        assertEquals("设备响应超时，请检查连接", outcome.message)
    }

    @Test
    fun stderrOnZeroExitIsTreatedAsFailure() {
        val outcome = DeviceActionRunner.classify(CommandResult(0, "", "adb: error: device not found"))

        assertFalse(outcome.success)
        assertEquals("adb: error: device not found", outcome.message)
    }

    @Test
    fun stdoutErrorMarkerIsTreatedAsFailure() {
        val outcome = DeviceActionRunner.classify(CommandResult(0, "Exception occurred", ""))

        assertFalse(outcome.success)
        assertEquals("Exception occurred", outcome.message)
    }

    @Test
    fun normalStdoutIsTreatedAsSuccess() {
        val outcome = DeviceActionRunner.classify(CommandResult(0, "Status bar commands:", ""))

        assertTrue(outcome.success)
    }
}
