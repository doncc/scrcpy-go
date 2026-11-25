package com.doncc.scrcpygo.control

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

/**
 * JNA 5.14 的 User32 映射里还没有 GetDpiForWindow（Win10 1607+），这里单独声明。
 * 加载失败（老系统）时返回 null，调用方按 100% 缩放处理。
 */
private interface DpiAwareUser32 : Library {
    fun GetDpiForWindow(hwnd: WinDef.HWND): Int
}

/**
 * jna-platform 5.14 没有 Dwmapi 映射，这里只声明用到的 DwmGetWindowAttribute。
 */
private interface DwmApi : Library {
    fun DwmGetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, rect: WinDef.RECT, size: Int): Int
}

private val dpiAwareUser32: DpiAwareUser32? by lazy {
    runCatching { Native.load("user32", DpiAwareUser32::class.java) }.getOrNull()
}

private val dwmApi: DwmApi? by lazy {
    runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
}

/**
 * Windows 平台窗口定位与 Z 轴层级同步适配器。
 *
 * 严格使用无激活的 SetWindowPos 精确调整控制条与投屏窗口的 Z 轴相邻关系，
 * 不跨进程设置 GWLP_HWNDPARENT，避免输入队列隐式绑定导致的卡死或异常销毁。
 */
internal object WindowsWindowAdapter : PlatformWindowAdapter {
    /** DWMWA_EXTENDED_FRAME_BOUNDS：DWM 合成的可见外框，不含四周透明拖拽边框 */
    private const val DWMWA_EXTENDED_FRAME_BOUNDS = 9

    private const val USER_DEFAULT_SCREEN_DPI = 96

    /** Win32 SetWindowPos 标准哨兵句柄 */
    private val HWND_TOP = WinDef.HWND(Pointer.createConstant(0L))
    private val HWND_TOPMOST = WinDef.HWND(Pointer.createConstant(-1L))
    private val HWND_NOTOPMOST = WinDef.HWND(Pointer.createConstant(-2L))

    /** 仅调整 Z 轴顺序：不改位置、不改尺寸、不抢焦点 */
    private const val ZORDER_FLAGS =
        WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOACTIVATE

    override fun isAvailable(): Boolean = runCatching {
        User32.INSTANCE
        true
    }.getOrDefault(false)

    override fun findWindowState(serial: String): ScrcpyWindowState {
        var visibleBounds: ScrcpyWindowBounds? = null
        var scrcpyHwnd: WinDef.HWND? = null
        var scrcpyAlwaysOnTop = false
        var hiddenMatch = false

        val callback = WinUser.WNDENUMPROC { hwnd, _ ->
            if (visibleBounds == null) {
                val length = User32.INSTANCE.GetWindowTextLength(hwnd)
                if (length > 0) {
                    val buffer = CharArray(length + 1)
                    val copied = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
                    if (copied > 0) {
                        val title = String(buffer, 0, copied)
                        if (ControlPadRules.matchesScrcpyWindowTitle(title, serial)) {
                            val minimized = (
                                User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_STYLE) and
                                    WinUser.WS_MINIMIZE
                                ) != 0
                            if (minimized || !User32.INSTANCE.IsWindowVisible(hwnd)) {
                                hiddenMatch = true
                            } else {
                                val rect = visibleFrame(hwnd)
                                if (rect != null) {
                                    val physical = ScrcpyWindowBounds(
                                        x = rect.left,
                                        y = rect.top,
                                        width = rect.right - rect.left,
                                        height = rect.bottom - rect.top
                                    )
                                    visibleBounds = ControlPadRules.toLogicalBounds(
                                        physical,
                                        displayScale(hwnd)
                                    )
                                    scrcpyHwnd = hwnd
                                    scrcpyAlwaysOnTop = isTopMost(hwnd)
                                }
                            }
                        }
                    }
                }
            }
            true
        }

        User32.INSTANCE.EnumWindows(callback, null)
        val bounds = visibleBounds
        val hwnd = scrcpyHwnd
        return when {
            bounds != null && hwnd != null -> ScrcpyWindowState.Visible(
                bounds = bounds,
                handle = ScrcpyWindowHandle(clientId = Pointer.nativeValue(hwnd.pointer)),
                isAlwaysOnTop = scrcpyAlwaysOnTop
            )
            hiddenMatch -> ScrcpyWindowState.Hidden
            else -> ScrcpyWindowState.NotFound
        }
    }

    override fun syncPadZOrder(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle,
        mirrorAlwaysOnTop: Boolean
    ) {
        val padHwnd = readAwtHwnd(padWindow) ?: return
        val mirrorHwnd = hwndFromId(mirrorHandle.clientId) ?: return
        if (!User32.INSTANCE.IsWindow(padHwnd) || !User32.INSTANCE.IsWindow(mirrorHwnd)) return

        val padId = Pointer.nativeValue(padHwnd.pointer)
        val padAlwaysOnTop = isTopMost(padHwnd)
        val actualMirrorAlwaysOnTop = isTopMost(mirrorHwnd) || mirrorAlwaysOnTop

        // 查找紧排在投屏窗口正上方的第一个可见窗口（遇到控制条自身直接返回）
        val prevVisible = findAdjacentVisibleWindow(
            start = mirrorHwnd,
            direction = WinUser.GW_HWNDPREV,
            stopAtId = padId
        )
        val prevId = prevVisible?.let { Pointer.nativeValue(it.pointer) }
        val prevTopMost = prevVisible != null && isTopMost(prevVisible)

        val action = ControlPadRules.planPadZOrder(
            padWindowId = padId,
            padAlwaysOnTop = padAlwaysOnTop,
            mirrorAlwaysOnTop = actualMirrorAlwaysOnTop,
            windowAboveMirrorId = prevId,
            windowAboveMirrorAlwaysOnTop = prevTopMost
        )

        when (action) {
            PadZOrderAction.NoOp -> Unit
            PadZOrderAction.PlaceAtTopMost -> {
                User32.INSTANCE.SetWindowPos(padHwnd, HWND_TOPMOST, 0, 0, 0, 0, ZORDER_FLAGS)
            }
            is PadZOrderAction.PlaceAtNormalTop -> {
                if (action.demoteFromTopMost) {
                    User32.INSTANCE.SetWindowPos(padHwnd, HWND_NOTOPMOST, 0, 0, 0, 0, ZORDER_FLAGS)
                }
                User32.INSTANCE.SetWindowPos(padHwnd, HWND_TOP, 0, 0, 0, 0, ZORDER_FLAGS)
            }
            is PadZOrderAction.PlaceBelowSibling -> {
                if (action.changeTopMostBand) {
                    val band = if (action.targetAlwaysOnTop) HWND_TOPMOST else HWND_NOTOPMOST
                    User32.INSTANCE.SetWindowPos(padHwnd, band, 0, 0, 0, 0, ZORDER_FLAGS)
                }
                val siblingHwnd = hwndFromId(action.siblingWindowId) ?: return
                User32.INSTANCE.SetWindowPos(padHwnd, siblingHwnd, 0, 0, 0, 0, ZORDER_FLAGS)
            }
        }
    }

    override fun raiseMirrorBelowPad(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle
    ) {
        val padHwnd = readAwtHwnd(padWindow) ?: return
        val mirrorHwnd = hwndFromId(mirrorHandle.clientId) ?: return
        if (!User32.INSTANCE.IsWindow(padHwnd) || !User32.INSTANCE.IsWindow(mirrorHwnd)) return

        val nextVisible = findAdjacentVisibleWindow(
            start = padHwnd,
            direction = WinUser.GW_HWNDNEXT,
            stopAtId = mirrorHandle.clientId
        )
        val nextId = nextVisible?.let { Pointer.nativeValue(it.pointer) }
        if (!ControlPadRules.shouldRaiseMirrorBelowPad(mirrorHandle.clientId, nextId)) {
            return
        }

        // 不抢占控制条正在处理的点击焦点，将投屏窗口精确拉到控制条正下方
        User32.INSTANCE.SetWindowPos(mirrorHwnd, padHwnd, 0, 0, 0, 0, ZORDER_FLAGS)
    }

    private fun readAwtHwnd(window: Window): WinDef.HWND? {
        if (!window.isDisplayable) return null
        val pointer = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return null
        return if (Pointer.nativeValue(pointer) == 0L) null else WinDef.HWND(pointer)
    }

    private fun hwndFromId(id: Long): WinDef.HWND? {
        return if (id == 0L) null else WinDef.HWND(Pointer.createConstant(id))
    }

    private fun isTopMost(hwnd: WinDef.HWND): Boolean {
        return (User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE) and User32.WS_EX_TOPMOST) != 0
    }

    /**
     * 沿 Z 轴方向查找紧邻的可见窗口，跳过不可见的系统隐藏/消息窗口，
     * 防止控制条与投屏窗口之间夹着隐藏窗口时误判为未对齐而反复重排。
     */
    private fun findAdjacentVisibleWindow(
        start: WinDef.HWND,
        direction: Int,
        stopAtId: Long
    ): WinDef.HWND? {
        var current: WinDef.HWND? = start
        repeat(64) {
            val next = User32.INSTANCE.GetWindow(current, WinDef.DWORD(direction.toLong())) ?: return null
            val nextId = Pointer.nativeValue(next.pointer)
            if (nextId == 0L) return null
            if (nextId == stopAtId || User32.INSTANCE.IsWindowVisible(next)) {
                return next
            }
            current = next
        }
        return null
    }

    /**
     * 优先用 DWM 的可见外框：Win10/11 的 GetWindowRect 会包含四周 7~8px 的透明缩放边框，
     * 直接拿它对齐会让控制条离窗口视觉边沿还差一条缝。
     */
    private fun visibleFrame(hwnd: WinDef.HWND): WinDef.RECT? {
        val dwmRect = WinDef.RECT()
        val hresult = runCatching {
            dwmApi?.DwmGetWindowAttribute(
                hwnd,
                DWMWA_EXTENDED_FRAME_BOUNDS,
                dwmRect,
                dwmRect.size()
            )
        }.getOrNull()
        if (hresult == 0 && dwmRect.right > dwmRect.left && dwmRect.bottom > dwmRect.top) {
            return dwmRect
        }

        val fallback = WinDef.RECT()
        return if (User32.INSTANCE.GetWindowRect(hwnd, fallback)) fallback else null
    }

    /** 窗口所在显示器的缩放比例（100% 为 1.0）；取不到时按 100% 处理 */
    private fun displayScale(hwnd: WinDef.HWND): Double {
        val dpi = runCatching { dpiAwareUser32?.GetDpiForWindow(hwnd) ?: 0 }.getOrDefault(0)
        return if (dpi in 48..960) dpi.toDouble() / USER_DEFAULT_SCREEN_DPI else 1.0
    }
}
