package com.doncc.scrcpygo.control

import com.doncc.scrcpygo.utils.PlatformUtils
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * scrcpy 投屏窗口外框的屏幕位置和大小，单位是像素。
 * 外框包含窗口管理器添加的标题栏和边框，便于控制条与窗口视觉边沿对齐。
 */
data class ScrcpyWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * scrcpy 窗口的当前状态。
 *
 * 窗口存在但被最小化或被窗口管理器隐藏时返回 [Hidden]，
 * 控制条据此跟随投屏窗口一起隐藏；窗口完全不存在时返回 [NotFound]。
 */
sealed interface ScrcpyWindowState {
    /**
     * 投屏窗口可见。
     * coveredByForeground 为 true 表示当前前台窗口盖住了投屏窗口中心，
     * 控制条应跟随隐藏；窗口只是失去焦点但仍露着时保持 false。
     */
    data class Visible(
        val bounds: ScrcpyWindowBounds,
        val coveredByForeground: Boolean = false
    ) : ScrcpyWindowState

    object Hidden : ScrcpyWindowState
    object NotFound : ScrcpyWindowState
}

/**
 * 查找指定设备的 scrcpy 窗口。
 *
 * 当前支持 Linux X11 和 Windows；Wayland 下无法读取其他应用窗口位置，
 * macOS 需要额外授权，这两种情况返回不支持，控制条继续固定在屏幕右缘。
 */
object ScrcpyWindowLocator {
    @Volatile
    private var cachedSupport: Boolean? = null
    private val supportLock = Any()

    val isSupported: Boolean
        get() {
            cachedSupport?.let { return it }
            return synchronized(supportLock) {
                cachedSupport ?: detectSupport().also { cachedSupport = it }
            }
        }

    fun findWindowState(serial: String): ScrcpyWindowState {
        if (!isSupported || serial.isBlank()) return ScrcpyWindowState.NotFound
        return runCatching {
            when {
                PlatformUtils.isLinux -> X11WindowLocator.find(serial)
                PlatformUtils.isWindows -> WindowsWindowLocator.find(serial)
                else -> ScrcpyWindowState.NotFound
            }
        }.getOrElse { ScrcpyWindowState.NotFound }
    }

    private fun detectSupport(): Boolean = runCatching {
        when {
            PlatformUtils.isLinux -> !isWaylandSession() && X11WindowLocator.isAvailable()
            PlatformUtils.isWindows -> {
                User32.INSTANCE
                true
            }
            else -> false
        }
    }.getOrDefault(false)

    private fun isWaylandSession(): Boolean {
        val sessionType = System.getenv("XDG_SESSION_TYPE")?.lowercase()
        return sessionType == "wayland" || !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
    }
}

private object X11WindowLocator {
    /** ICCCM 定义的最小化（Iconic）状态值，WM_STATE 属性为 3 时表示窗口已最小化。 */
    private const val ICONIC_STATE = 3L

    fun isAvailable(): Boolean = runCatching {
        val display = X11.INSTANCE.XOpenDisplay(null) ?: return@runCatching false
        X11.INSTANCE.XCloseDisplay(display)
        true
    }.getOrDefault(false)

    @Synchronized
    fun find(serial: String): ScrcpyWindowState = runCatching {
        val display = X11.INSTANCE.XOpenDisplay(null) ?: return@runCatching ScrcpyWindowState.NotFound
        try {
            val root = X11.INSTANCE.XDefaultRootWindow(display)
            findRecursive(display, root, root, serial, 0) ?: ScrcpyWindowState.NotFound
        } finally {
            X11.INSTANCE.XCloseDisplay(display)
        }
    }.getOrElse { ScrcpyWindowState.NotFound }

    /**
     * 递归查找窗口树。scrcpy 窗口通常被窗口管理器包在一层 frame 里，
     * 只遍历 root 的直接子窗口会漏掉它。
     *
     * 找到标题匹配的客户窗口后，先判断它是否被最小化或隐藏，再计算外框几何。
     * GNOME/mutter 最小化窗口时客户窗口仍然保持 IsViewable，必须单独读取
     * WM_STATE / _NET_WM_STATE_HIDDEN 才能识别。
     */
    private fun findRecursive(
        display: X11.Display,
        window: X11.Window,
        root: X11.Window,
        serial: String,
        depth: Int
    ): ScrcpyWindowState? {
        if (depth > 16) return null

        val title = fetchTitle(display, window)
        if (title != null && ControlPadRules.matchesScrcpyWindowTitle(title, serial)) {
            val attributes = X11.XWindowAttributes()
            if (X11.INSTANCE.XGetWindowAttributes(display, window, attributes) != 0 &&
                attributes.width > 0 && attributes.height > 0
            ) {
                if (isWindowHidden(display, window, attributes)) {
                    return ScrcpyWindowState.Hidden
                }

                val absoluteX = IntByReference()
                val absoluteY = IntByReference()
                val childReturn = X11.WindowByReference()
                if (X11.INSTANCE.XTranslateCoordinates(
                        display, window, root, 0, 0,
                        absoluteX, absoluteY, childReturn
                    )
                ) {
                    // 优先取窗口管理器包在客户区外层的 frame 几何，
                    // 这样顶部和右侧对齐的是用户看到的整扇窗口，而不只是内容区。
                    val outerFrame = findOuterFrame(display, window, root)
                    if (outerFrame != null) {
                        val outerAttributes = X11.XWindowAttributes()
                        if (X11.INSTANCE.XGetWindowAttributes(display, outerFrame, outerAttributes) != 0 &&
                            outerAttributes.map_state == X11.IsViewable &&
                            outerAttributes.width > 0 && outerAttributes.height > 0
                        ) {
                            val outerX = IntByReference()
                            val outerY = IntByReference()
                            val outerChild = X11.WindowByReference()
                            if (X11.INSTANCE.XTranslateCoordinates(
                                    display, outerFrame, root, 0, 0,
                                    outerX, outerY, outerChild
                                )
                            ) {
                                val mirrorBounds = ScrcpyWindowBounds(
                                    x = outerX.value,
                                    y = outerY.value,
                                    width = outerAttributes.width,
                                    height = outerAttributes.height
                                )
                                return ScrcpyWindowState.Visible(
                                    mirrorBounds,
                                    isCoveredByForeground(
                                        display, root, window, outerFrame, mirrorBounds
                                    )
                                )
                            }
                        }
                    }

                    // 某些窗口管理器没有提供 frame 时退回内容区，并尝试补偿边框
                    val extents = readFrameExtents(display, window)
                    val left = extents?.left ?: 0
                    val right = extents?.right ?: 0
                    val top = extents?.top ?: 0
                    val bottom = extents?.bottom ?: 0
                    val mirrorBounds = ScrcpyWindowBounds(
                        x = absoluteX.value - left,
                        y = absoluteY.value - top,
                        width = attributes.width + left + right,
                        height = attributes.height + top + bottom
                    )
                    return ScrcpyWindowState.Visible(
                        mirrorBounds,
                        isCoveredByForeground(
                            display, root, window, outerFrame, mirrorBounds
                        )
                    )
                }
            }
        }

        for (child in queryChildren(display, window)) {
            val found = findRecursive(display, child, root, serial, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * 判断客户窗口是否已最小化或隐藏。
     *
     * 部分窗口管理器（GNOME/mutter）最小化时不会 unmap 客户窗口，
     * map_state 仍然是 IsViewable，因此不能只看映射状态：
     * 1. ICCCM 的 WM_STATE 为 Iconic 表示已最小化；
     * 2. EWMH 的 _NET_WM_STATE_HIDDEN 作为兜底；
     * 3. map_state 不是 IsViewable 时说明窗口被 unmap，同样视为隐藏。
     */
    private fun isWindowHidden(
        display: X11.Display,
        window: X11.Window,
        attributes: X11.XWindowAttributes
    ): Boolean {
        if (attributes.map_state != X11.IsViewable) return true
        return readWmState(display, window) == ICONIC_STATE ||
            hasNetWmState(display, window, "_NET_WM_STATE_HIDDEN")
    }

    /**
     * 判断当前前台窗口是否盖住了投屏窗口的中心。
     *
     * 只比较前台窗口和投屏窗口，不遍历整棵窗口树：
     * 切到另一块屏幕上的窗口、或投屏窗口仍然露着时不会误隐藏；
     * 前台窗口把投屏窗口盖住时返回 true，由控制条跟随隐藏。
     */
    private fun isCoveredByForeground(
        display: X11.Display,
        root: X11.Window,
        mirrorClient: X11.Window,
        mirrorFrame: X11.Window?,
        mirrorBounds: ScrcpyWindowBounds
    ): Boolean {
        val activeId = readActiveWindowId(display, root) ?: return false
        if (activeId == 0L) return false
        if (activeId == mirrorClient.toLong() || activeId == mirrorFrame?.toLong()) return false

        val activeBounds = readClientBounds(display, X11.Window(activeId), root) ?: return false
        return ControlPadRules.isCoveredAtCenter(mirrorBounds, activeBounds)
    }

    /**
     * 读取根窗口上的 _NET_ACTIVE_WINDOW，取当前前台窗口的 X11 id。
     */
    private fun readActiveWindowId(display: X11.Display, root: X11.Window): Long? {
        val atom = X11.INSTANCE.XInternAtom(display, "_NET_ACTIVE_WINDOW", false) ?: return null
        if (atom.toLong() == 0L) return null

        val actualType = X11.AtomByReference()
        val actualFormat = IntByReference()
        val itemCount = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val property = PointerByReference()
        val status = X11.INSTANCE.XGetWindowProperty(
            display,
            root,
            atom,
            NativeLong(0),
            NativeLong(1),
            false,
            X11.XA_WINDOW,
            actualType,
            actualFormat,
            itemCount,
            bytesAfter,
            property
        )
        if (status != X11.Success) return null

        val pointer = property.value ?: return null
        return try {
            if (actualFormat.value != 32 || itemCount.value.toLong() < 1) {
                null
            } else {
                pointer.getLong(0)
            }
        } finally {
            X11.INSTANCE.XFree(pointer)
        }
    }

    /**
     * 读取普通客户窗口的屏幕外框几何，用于判断前台窗口是否覆盖投屏窗口。
     * 优先用 _NET_FRAME_EXTENTS 补偿窗口管理器边框，读不到时退回客户区。
     */
    private fun readClientBounds(
        display: X11.Display,
        window: X11.Window,
        root: X11.Window
    ): ScrcpyWindowBounds? {
        val attributes = X11.XWindowAttributes()
        if (X11.INSTANCE.XGetWindowAttributes(display, window, attributes) == 0 ||
            attributes.map_state != X11.IsViewable ||
            attributes.width <= 0 || attributes.height <= 0
        ) {
            return null
        }

        val absoluteX = IntByReference()
        val absoluteY = IntByReference()
        val childReturn = X11.WindowByReference()
        if (!X11.INSTANCE.XTranslateCoordinates(
                display, window, root, 0, 0,
                absoluteX, absoluteY, childReturn
            )
        ) {
            return null
        }

        val extents = readFrameExtents(display, window)
        val left = extents?.left ?: 0
        val right = extents?.right ?: 0
        val top = extents?.top ?: 0
        val bottom = extents?.bottom ?: 0
        return ScrcpyWindowBounds(
            x = absoluteX.value - left,
            y = absoluteY.value - top,
            width = attributes.width + left + right,
            height = attributes.height + top + bottom
        )
    }

    /**
     * 读取 ICCCM 的 WM_STATE 属性。属性类型和属性名都叫 WM_STATE，
     * 所以这里把同一个 atom 同时作为属性名和请求类型。
     */
    private fun readWmState(display: X11.Display, window: X11.Window): Long? {
        val atom = X11.INSTANCE.XInternAtom(display, "WM_STATE", false) ?: return null
        if (atom.toLong() == 0L) return null

        val actualType = X11.AtomByReference()
        val actualFormat = IntByReference()
        val itemCount = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val property = PointerByReference()
        val status = X11.INSTANCE.XGetWindowProperty(
            display,
            window,
            atom,
            NativeLong(0),
            NativeLong(2),
            false,
            atom,
            actualType,
            actualFormat,
            itemCount,
            bytesAfter,
            property
        )
        if (status != X11.Success) return null

        val pointer = property.value ?: return null
        return try {
            if (actualFormat.value != 32 || itemCount.value.toLong() < 1) {
                null
            } else {
                pointer.getLong(0)
            }
        } finally {
            X11.INSTANCE.XFree(pointer)
        }
    }

    /**
     * 判断 _NET_WM_STATE 列表中是否包含指定状态（例如 _NET_WM_STATE_HIDDEN）。
     */
    private fun hasNetWmState(display: X11.Display, window: X11.Window, stateName: String): Boolean {
        val propertyAtom = X11.INSTANCE.XInternAtom(display, "_NET_WM_STATE", false) ?: return false
        if (propertyAtom.toLong() == 0L) return false
        val stateAtom = X11.INSTANCE.XInternAtom(display, stateName, false) ?: return false
        if (stateAtom.toLong() == 0L) return false

        val actualType = X11.AtomByReference()
        val actualFormat = IntByReference()
        val itemCount = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val property = PointerByReference()
        val status = X11.INSTANCE.XGetWindowProperty(
            display,
            window,
            propertyAtom,
            NativeLong(0),
            NativeLong(16),
            false,
            X11.XA_ATOM,
            actualType,
            actualFormat,
            itemCount,
            bytesAfter,
            property
        )
        if (status != X11.Success) return false

        val pointer = property.value ?: return false
        return try {
            if (actualFormat.value != 32 || itemCount.value.toLong() < 1) {
                false
            } else {
                val count = itemCount.value.toInt().coerceAtMost(16)
                val stride = Native.LONG_SIZE.toLong()
                (0 until count).any { index ->
                    pointer.getLong(index * stride) == stateAtom.toLong()
                }
            }
        } finally {
            X11.INSTANCE.XFree(pointer)
        }
    }

    private fun queryChildren(display: X11.Display, window: X11.Window): List<X11.Window> {
        val rootReturn = X11.WindowByReference()
        val parentReturn = X11.WindowByReference()
        val childrenReturn = PointerByReference()
        val childCount = IntByReference()
        if (X11.INSTANCE.XQueryTree(display, window, rootReturn, parentReturn, childrenReturn, childCount) == 0) {
            return emptyList()
        }
        val pointer = childrenReturn.value ?: return emptyList()
        return try {
            val count = childCount.value.coerceAtLeast(0)
            List(count) { index ->
                val offset = index.toLong() * Native.LONG_SIZE
                X11.Window(pointer.getNativeLong(offset).toLong())
            }
        } finally {
            X11.INSTANCE.XFree(pointer)
        }
    }

    /**
     * 向上找到窗口管理器的装饰 frame；如果窗口直接挂在 root 下则返回自身。
     */
    private fun findOuterFrame(
        display: X11.Display,
        window: X11.Window,
        root: X11.Window
    ): X11.Window? {
        var current = window
        repeat(8) {
            val rootReturn = X11.WindowByReference()
            val parentReturn = X11.WindowByReference()
            val childrenReturn = PointerByReference()
            val childCount = IntByReference()
            if (X11.INSTANCE.XQueryTree(
                    display, current, rootReturn, parentReturn, childrenReturn, childCount
                ) == 0
            ) {
                return null
            }
            childrenReturn.value?.let { X11.INSTANCE.XFree(it) }

            val parent = parentReturn.value ?: return current
            if (parent.toLong() == root.toLong() || parent.toLong() == 0L) {
                return current
            }
            current = parent
        }
        return null
    }

    /**
     * 读取窗口管理器边框大小。没有 _NET_FRAME_EXTENTS 时退回内容区坐标。
     */
    private fun readFrameExtents(display: X11.Display, window: X11.Window): FrameExtents? {
        val atom = X11.INSTANCE.XInternAtom(display, "_NET_FRAME_EXTENTS", false) ?: return null
        if (atom.toLong() == 0L) return null

        val actualType = X11.AtomByReference()
        val actualFormat = IntByReference()
        val itemCount = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val property = PointerByReference()
        val status = X11.INSTANCE.XGetWindowProperty(
            display,
            window,
            atom,
            NativeLong(0),
            NativeLong(4),
            false,
            X11.XA_CARDINAL,
            actualType,
            actualFormat,
            itemCount,
            bytesAfter,
            property
        )
        if (status != X11.Success) return null

        val pointer = property.value ?: return null
        return try {
            if (actualFormat.value != 32 || itemCount.value.toLong() < 4) {
                null
            } else {
                val stride = Native.LONG_SIZE.toLong()
                val values = IntArray(4) { index ->
                    pointer.getLong(index * stride).toInt()
                }
                if (values.all { it in 0..500 }) {
                    FrameExtents(
                        left = values[0],
                        right = values[1],
                        top = values[2],
                        bottom = values[3]
                    )
                } else {
                    null
                }
            }
        } finally {
            X11.INSTANCE.XFree(pointer)
        }
    }

    private fun fetchTitle(display: X11.Display, window: X11.Window): String? {
        val nameReference = PointerByReference()
        if (X11.INSTANCE.XFetchName(display, window, nameReference) == 0) return null
        val pointer = nameReference.value ?: return null
        return try {
            pointer.getString(0)
        } finally {
            X11.INSTANCE.XFree(pointer)
        }
    }
}

private data class FrameExtents(
    val left: Int,
    val right: Int,
    val top: Int,
    val bottom: Int
)

private object WindowsWindowLocator {
    fun find(serial: String): ScrcpyWindowState {
        var visibleBounds: ScrcpyWindowBounds? = null
        var scrcpyHwnd: WinDef.HWND? = null
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
                                val rect = WinDef.RECT()
                                if (User32.INSTANCE.GetWindowRect(hwnd, rect)) {
                                    visibleBounds = ScrcpyWindowBounds(
                                        x = rect.left,
                                        y = rect.top,
                                        width = rect.right - rect.left,
                                        height = rect.bottom - rect.top
                                    )
                                    scrcpyHwnd = hwnd
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
        return when {
            bounds != null -> ScrcpyWindowState.Visible(
                bounds,
                isCoveredByForeground(
                    User32.INSTANCE.GetForegroundWindow(),
                    scrcpyHwnd,
                    bounds
                )
            )
            hiddenMatch -> ScrcpyWindowState.Hidden
            else -> ScrcpyWindowState.NotFound
        }
    }

    /**
     * 判断当前前台窗口是否盖住投屏窗口中心。
     * 前台窗口是投屏窗口自身或它的顶层窗口时不算遮挡；
     * 控制条等相邻窗口通常不会覆盖投屏窗口中心。
     */
    private fun isCoveredByForeground(
        foreground: WinDef.HWND?,
        scrcpyHwnd: WinDef.HWND?,
        mirrorBounds: ScrcpyWindowBounds
    ): Boolean {
        if (foreground == null || scrcpyHwnd == null) return false
        val foregroundRoot = User32.INSTANCE.GetAncestor(foreground, WinUser.GA_ROOT) ?: foreground
        if (foregroundRoot == scrcpyHwnd) return false

        val rect = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(foregroundRoot, rect)) return false
        val foregroundBounds = ScrcpyWindowBounds(
            x = rect.left,
            y = rect.top,
            width = rect.right - rect.left,
            height = rect.bottom - rect.top
        )
        return ControlPadRules.isCoveredAtCenter(mirrorBounds, foregroundBounds)
    }
}
