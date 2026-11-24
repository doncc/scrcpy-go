package com.doncc.scrcpygo.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlPadRulesTest {
    @Test
    fun firstPadSitsAtRightEdgeAndVerticallyCentered() {
        val position = ControlPadRules.calculatePosition(
            workAreaX = 0,
            workAreaY = 0,
            workAreaWidth = 1920,
            workAreaHeight = 1080,
            padWidthPx = 76,
            padHeightPx = 560,
            marginPx = 12,
            gapPx = 8,
            slotIndex = 0
        )

        assertEquals(1832, position.x)
        assertEquals(260, position.y)
    }

    @Test
    fun additionalPadsStackLeftwards() {
        val second = ControlPadRules.calculatePosition(
            workAreaX = 0,
            workAreaY = 0,
            workAreaWidth = 1920,
            workAreaHeight = 1080,
            padWidthPx = 76,
            padHeightPx = 560,
            marginPx = 12,
            gapPx = 8,
            slotIndex = 1
        )

        assertEquals(1748, second.x)
        assertEquals(260, second.y)
    }

    @Test
    fun positionIsClampedInsideWorkAreaWhenSpaceRunsOut() {
        val position = ControlPadRules.calculatePosition(
            workAreaX = 100,
            workAreaY = 50,
            workAreaWidth = 100,
            workAreaHeight = 200,
            padWidthPx = 76,
            padHeightPx = 560,
            marginPx = 12,
            gapPx = 8,
            slotIndex = 3
        )

        assertEquals(100, position.x)
        assertEquals(50, position.y)
    }

    @Test
    fun dockedPositionTouchesTheWindowRightAndTopEdges() {
        val position = ControlPadRules.calculateDockedPosition(
            windowX = 760,
            windowY = 30,
            windowWidth = 450,
            padWidthPx = 76,
            padHeightPx = 560,
            workAreaX = 0,
            workAreaY = 0,
            workAreaWidth = 1920,
            workAreaHeight = 1080
        )

        assertEquals(1210, position.x)
        assertEquals(30, position.y)
    }

    @Test
    fun dockedPositionFlipsToTheLeftWhenRightSideIsFull() {
        val position = ControlPadRules.calculateDockedPosition(
            windowX = 1500,
            windowY = 100,
            windowWidth = 400,
            padWidthPx = 76,
            padHeightPx = 560,
            workAreaX = 0,
            workAreaY = 0,
            workAreaWidth = 1920,
            workAreaHeight = 1080
        )

        assertEquals(1424, position.x)
        assertEquals(100, position.y)
    }

    @Test
    fun dockedPositionFallsBackToWorkAreaWhenBothSidesAreFull() {
        val position = ControlPadRules.calculateDockedPosition(
            windowX = 0,
            windowY = 0,
            windowWidth = 1920,
            padWidthPx = 76,
            padHeightPx = 560,
            workAreaX = 0,
            workAreaY = 0,
            workAreaWidth = 1920,
            workAreaHeight = 1080
        )

        assertEquals(1844, position.x)
        assertEquals(0, position.y)
    }

    @Test
    fun dockedPositionClampsTopInsideWorkArea() {
        val position = ControlPadRules.calculateDockedPosition(
            windowX = 0,
            windowY = 700,
            windowWidth = 400,
            padWidthPx = 76,
            padHeightPx = 560,
            workAreaX = 0,
            workAreaY = 0,
            workAreaWidth = 1920,
            workAreaHeight = 1080
        )

        assertEquals(400, position.x)
        assertEquals(520, position.y)
    }

    @Test
    fun windowTitleMatchingExcludesControlPadAndUnrelatedWindows() {
        assertTrue(ControlPadRules.matchesScrcpyWindowTitle("Android_SDK_built_for_x86_64 (emulator-5554)", "emulator-5554"))
        assertFalse(ControlPadRules.matchesScrcpyWindowTitle("控制条 - Android_SDK_built_for_x86_64 (emulator-5554)", "emulator-5554"))
        assertFalse(ControlPadRules.matchesScrcpyWindowTitle("Android SDK built for x86_64", "emulator-5554"))
        assertFalse(ControlPadRules.matchesScrcpyWindowTitle("Terminal emulator-5554", "emulator-5554"))
    }

    @Test
    fun allocateSlotReusesTheFirstGap() {
        assertEquals(0, ControlPadRules.allocateSlot(emptySet()))
        assertEquals(1, ControlPadRules.allocateSlot(setOf(0)))
        assertEquals(1, ControlPadRules.allocateSlot(setOf(0, 2)))
        assertEquals(2, ControlPadRules.allocateSlot(setOf(0, 1)))
    }

    @Test
    fun windowTitleContainsModelAndFullSerial() {
        assertEquals(
            "LubanCat 5IO (d7f16711cf059026)",
            ControlPadRules.buildWindowTitle("  LubanCat   5IO ", " d7f16711cf059026 ")
        )
    }

    @Test
    fun blankModelFallsBackToAndroidDevice() {
        assertEquals(
            "Android 设备 (d7f16711cf059026)",
            ControlPadRules.buildWindowTitle("   ", "d7f16711cf059026")
        )
    }

    @Test
    fun detectsCustomWindowTitleInBothForms() {
        assertTrue(ControlPadRules.hasCustomWindowTitle(listOf("--no-audio", "--window-title", "自定义标题")))
        assertTrue(ControlPadRules.hasCustomWindowTitle(listOf("--window-title=自定义标题")))
        assertFalse(ControlPadRules.hasCustomWindowTitle(listOf("--no-audio", "--max-size=1024")))
    }

    @Test
    fun foregroundWindowCoveringTheCenterHidesThePad() {
        val mirror = ScrcpyWindowBounds(x = 300, y = 200, width = 1000, height = 600)
        val browser = ScrcpyWindowBounds(x = 0, y = 0, width = 1920, height = 1080)

        assertTrue(ControlPadRules.isCoveredAtCenter(mirror, browser))
    }

    @Test
    fun foregroundWindowBesideTheMirrorDoesNotHideThePad() {
        val mirror = ScrcpyWindowBounds(x = 0, y = 100, width = 900, height = 600)
        val browserOnSecondScreen = ScrcpyWindowBounds(x = 1920, y = 0, width = 1920, height = 1080)

        assertFalse(ControlPadRules.isCoveredAtCenter(mirror, browserOnSecondScreen))
    }

    @Test
    fun partialCoverWithoutReachingTheCenterKeepsThePad() {
        val mirror = ScrcpyWindowBounds(x = 0, y = 100, width = 1000, height = 600)
        val sideWindow = ScrcpyWindowBounds(x = 0, y = 100, width = 300, height = 600)

        assertFalse(ControlPadRules.isCoveredAtCenter(mirror, sideWindow))
    }

    @Test
    fun coverTouchingOnlyTheCenterStillHidesThePad() {
        val mirror = ScrcpyWindowBounds(x = 0, y = 0, width = 1000, height = 600)
        val dialog = ScrcpyWindowBounds(x = 490, y = 290, width = 20, height = 20)

        assertTrue(ControlPadRules.isCoveredAtCenter(mirror, dialog))
    }

    @Test
    fun zeroSizedWindowNeverCountsAsCovering() {
        val mirror = ScrcpyWindowBounds(x = 100, y = 100, width = 800, height = 500)
        val empty = ScrcpyWindowBounds(x = 0, y = 0, width = 0, height = 1080)

        assertFalse(ControlPadRules.isCoveredAtCenter(mirror, empty))
    }

    @Test
    fun physicalBoundsAreConvertedToLogicalAt150Percent() {
        val physical = ScrcpyWindowBounds(x = 757, y = 419, width = 1046, height = 656)

        val logical = ControlPadRules.toLogicalBounds(physical, 1.5)

        assertEquals(505, logical.x)
        assertEquals(279, logical.y)
        // 右边沿 1803 / 1.5 = 1202，下边沿 1075 / 1.5 ≈ 717
        assertEquals(1202, logical.x + logical.width)
        assertEquals(717, logical.y + logical.height)
    }

    @Test
    fun boundsAreUnchangedAt100PercentOrInvalidScale() {
        val physical = ScrcpyWindowBounds(x = 757, y = 419, width = 1046, height = 656)

        assertEquals(physical, ControlPadRules.toLogicalBounds(physical, 1.0))
        assertEquals(physical, ControlPadRules.toLogicalBounds(physical, 0.0))
        assertEquals(physical, ControlPadRules.toLogicalBounds(physical, Double.NaN))
    }

    @Test
    fun scaledWindowDocksFlushRightAndTopAt150Percent() {
        // Windows 150% 缩放下 2560x1600 屏幕的逻辑工作区是 1707x1019
        val logical = ControlPadRules.toLogicalBounds(
            ScrcpyWindowBounds(x = 757, y = 419, width = 1046, height = 656),
            1.5
        )

        val position = ControlPadRules.calculateDockedPosition(
            windowX = logical.x,
            windowY = logical.y,
            windowWidth = logical.width,
            padWidthPx = 76,
            padHeightPx = 620,
            workAreaX = 0,
            workAreaY = 0,
            workAreaWidth = 1707,
            workAreaHeight = 1019
        )

        // 控制条左边缘紧贴投屏窗口右边缘，顶部与窗口顶部平齐
        assertEquals(logical.x + logical.width, position.x)
        assertEquals(logical.y, position.y)
    }

    @Test
    fun scaledWindowFlipsToLeftWhenRightSideRunsOut() {
        val logical = ControlPadRules.toLogicalBounds(
            ScrcpyWindowBounds(x = 1800, y = 200, width = 1000, height = 600),
            1.5
        )

        val position = ControlPadRules.calculateDockedPosition(
            windowX = logical.x,
            windowY = logical.y,
            windowWidth = logical.width,
            padWidthPx = 76,
            padHeightPx = 620,
            workAreaX = 0,
            workAreaY = 0,
            workAreaWidth = 1707,
            workAreaHeight = 1019
        )

        assertEquals(logical.x - 76, position.x)
        assertEquals(logical.y, position.y)
    }
}
