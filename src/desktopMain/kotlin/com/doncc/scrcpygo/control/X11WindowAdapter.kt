package com.doncc.scrcpygo.control

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Window

/**
 * Linux X11 平台窗口定位与 Z 轴层级同步适配器。
 *
 * 采用“瞬态父窗口关系（WM_TRANSIENT_FOR）+ EWMH 堆叠校准（_NET_RESTACK_WINDOW）”组合策略：
 * - 优先建立非模态瞬态父窗口关系，让主流窗口管理器原生维持控制条紧贴在投屏窗口上方；
 * - 同时校验根窗口的 _NET_CLIENT_LIST_STACKING 与 _NET_WM_STATE_ABOVE，必要时发消息精准校准；
 * - 点击控制条激活时，若投屏窗口被压在下面，发送重排消息将其联动抬起至控制条正下方。
 */
internal object X11WindowAdapter : PlatformWindowAdapter {
    /** ICCCM 定义的最小化（Iconic）状态值，WM_STATE 属性为 3 时表示窗口已最小化。 */
    private const val ICONIC_STATE = 3L

    /** EWMH source indication = 2（分页器/窗口控制工具发起），窗口管理器更倾向受理重排请求。 */
    private const val EWMH_SOURCE_PAGER = 2L

    /** _NET_WM_STATE 操作码：移除 / 添加 */
    private const val NET_WM_STATE_REMOVE = 0L
    private const val NET_WM_STATE_ADD = 1L

    /** 是否启用 WM_TRANSIENT_FOR 绑定；若实测特定桌面环境限制了坐标移动，可一键退回纯堆叠重排。 */
    private const val USE_TRANSIENT_FOR_HINT = true

    override fun isAvailable(): Boolean = runCatching {
        if (isWaylandSession()) return@runCatching false
        val display = X11.INSTANCE.XOpenDisplay(null) ?: return@runCatching false
        X11.INSTANCE.XCloseDisplay(display)
        true
    }.getOrDefault(false)

    @Synchronized
    override fun findWindowState(serial: String): ScrcpyWindowState = runCatching {
        val display = X11.INSTANCE.XOpenDisplay(null) ?: return@runCatching ScrcpyWindowState.NotFound
        try {
            val root = X11.INSTANCE.XDefaultRootWindow(display)
            findRecursive(display, root, root, serial, 0) ?: ScrcpyWindowState.NotFound
        } finally {
            X11.INSTANCE.XCloseDisplay(display)
        }
    }.getOrElse { ScrcpyWindowState.NotFound }

    @Synchronized
    override fun syncPadZOrder(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle,
        mirrorAlwaysOnTop: Boolean
    ) {
        val padWindowId = readAwtWindowId(padWindow)
        if (padWindowId == 0L || mirrorHandle.clientId == 0L) return

        val display = X11.INSTANCE.XOpenDisplay(null) ?: return
        try {
            val root = X11.INSTANCE.XDefaultRootWindow(display)
            val padX11Window = X11.Window(padWindowId)
            val mirrorClientWindow = X11.Window(mirrorHandle.clientId)

            var needsFlush = false

            // 1. 核验并维护瞬态父窗口关系（某些窗口管理器如 Mutter 在父窗口最小化时会清除该属性，
            //    因此直接读取真实 X11 属性判断是否缺失，仅在不一致时补写）
            if (USE_TRANSIENT_FOR_HINT && readTransientFor(display, padX11Window) != mirrorHandle.clientId) {
                setTransientFor(display, padX11Window, mirrorHandle.clientId)
                needsFlush = true
            }

            // 2. 校验全局堆叠列表与置顶层状态，由纯规则决定是否需要重排
            val stack = readClientStackingList(display, root)
            val padAlwaysOnTop = hasNetWmState(display, padX11Window, "_NET_WM_STATE_ABOVE")
            val windowAboveMirror = ControlPadRules.findWindowAboveInStack(stack, mirrorHandle.clientId)
            val windowAboveAlwaysOnTop = windowAboveMirror != null &&
                hasNetWmState(display, X11.Window(windowAboveMirror), "_NET_WM_STATE_ABOVE")

            val action = ControlPadRules.planPadZOrder(
                padWindowId = padWindowId,
                padAlwaysOnTop = padAlwaysOnTop,
                mirrorAlwaysOnTop = mirrorAlwaysOnTop,
                windowAboveMirrorId = windowAboveMirror,
                windowAboveMirrorAlwaysOnTop = windowAboveAlwaysOnTop
            )

            when (action) {
                PadZOrderAction.NoOp -> Unit
                PadZOrderAction.PlaceAtTopMost -> {
                    if (!padAlwaysOnTop) {
                        sendNetWmStateAbove(display, root, padX11Window, enable = true)
                    }
                    sendRestackWindow(display, root, padX11Window, mirrorClientWindow, X11.Above)
                    needsFlush = true
                }
                is PadZOrderAction.PlaceAtNormalTop -> {
                    if (action.demoteFromTopMost) {
                        sendNetWmStateAbove(display, root, padX11Window, enable = false)
                    }
                    sendRestackWindow(display, root, padX11Window, mirrorClientWindow, X11.Above)
                    needsFlush = true
                }
                is PadZOrderAction.PlaceBelowSibling -> {
                    if (action.changeTopMostBand) {
                        sendNetWmStateAbove(display, root, padX11Window, enable = action.targetAlwaysOnTop)
                    }
                    // X11 下直接声明放在投屏窗口 Above，比放到兄弟窗口 Below 更贴合 WM 的 transient 堆叠语义
                    sendRestackWindow(display, root, padX11Window, mirrorClientWindow, X11.Above)
                    needsFlush = true
                }
            }

            if (needsFlush) {
                X11.INSTANCE.XFlush(display)
            }
        } finally {
            X11.INSTANCE.XCloseDisplay(display)
        }
    }

    @Synchronized
    override fun raiseMirrorBelowPad(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle
    ) {
        val padWindowId = readAwtWindowId(padWindow)
        if (padWindowId == 0L || mirrorHandle.clientId == 0L) return

        val display = X11.INSTANCE.XOpenDisplay(null) ?: return
        try {
            val root = X11.INSTANCE.XDefaultRootWindow(display)
            val stack = readClientStackingList(display, root)
            val windowBelowPad = ControlPadRules.findWindowBelowInStack(stack, padWindowId)
            if (!ControlPadRules.shouldRaiseMirrorBelowPad(mirrorHandle.clientId, windowBelowPad)) {
                return
            }

            // 将投屏窗口重排到控制条正下方，不抢占当前点击控制条产生的焦点事件
            val padX11Window = X11.Window(padWindowId)
            val mirrorClientWindow = X11.Window(mirrorHandle.clientId)
            sendRestackWindow(display, root, mirrorClientWindow, padX11Window, X11.Below)
            X11.INSTANCE.XFlush(display)
        } finally {
            X11.INSTANCE.XCloseDisplay(display)
        }
    }

    @Synchronized
    override fun clearPadBinding(padWindow: Window) {
        val padWindowId = readAwtWindowId(padWindow)
        if (padWindowId == 0L) return

        val display = X11.INSTANCE.XOpenDisplay(null) ?: return
        try {
            val padX11Window = X11.Window(padWindowId)
            if (readTransientFor(display, padX11Window) != null) {
                X11.INSTANCE.XDeleteProperty(display, padX11Window, X11.XA_WM_TRANSIENT_FOR)
                X11.INSTANCE.XFlush(display)
            }
        } finally {
            X11.INSTANCE.XCloseDisplay(display)
        }
    }

    private fun readAwtWindowId(window: Window): Long {
        if (!window.isDisplayable) return 0L
        return runCatching { Native.getWindowID(window) }.getOrDefault(0L)
    }

    private fun isWaylandSession(): Boolean {
        val sessionType = System.getenv("XDG_SESSION_TYPE")?.lowercase()
        return sessionType == "wayland" || !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
    }

    /**
     * 读取控制条当前在 X11 上实际绑定的 WM_TRANSIENT_FOR 目标窗口 ID。
     */
    private fun readTransientFor(display: X11.Display, padWindow: X11.Window): Long? {
        val actualType = X11.AtomByReference()
        val actualFormat = IntByReference()
        val itemCount = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val property = PointerByReference()
        val status = X11.INSTANCE.XGetWindowProperty(
            display,
            padWindow,
            X11.XA_WM_TRANSIENT_FOR,
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
                pointer.getNativeLong(0).toLong().takeIf { it != 0L }
            }
        } finally {
            X11.INSTANCE.XFree(pointer)
        }
    }

    /**
     * 写入 ICCCM 的 WM_TRANSIENT_FOR 属性，声明控制条依附于投屏窗口。
     * X11 32-bit 属性在 64 位平台上按 NativeLong（8 字节）传递缓冲区。
     */
    private fun setTransientFor(display: X11.Display, padWindow: X11.Window, mirrorClientId: Long) {
        val buffer = Memory(Native.LONG_SIZE.toLong())
        buffer.setNativeLong(0, NativeLong(mirrorClientId))
        X11.INSTANCE.XChangeProperty(
            display,
            padWindow,
            X11.XA_WM_TRANSIENT_FOR,
            X11.XA_WINDOW,
            32,
            X11.PropModeReplace,
            buffer,
            1
        )
    }

    /**
     * 向根窗口发送 EWMH _NET_RESTACK_WINDOW 消息，调整 targetWindow 相对于 siblingWindow 的堆叠位置。
     */
    private fun sendRestackWindow(
        display: X11.Display,
        root: X11.Window,
        targetWindow: X11.Window,
        siblingWindow: X11.Window,
        stackMode: Int
    ) {
        val atom = X11.INSTANCE.XInternAtom(display, "_NET_RESTACK_WINDOW", false) ?: return
        if (atom.toLong() == 0L) return
        sendRootClientMessage(
            display = display,
            root = root,
            targetWindow = targetWindow,
            messageType = atom,
            d0 = EWMH_SOURCE_PAGER,
            d1 = siblingWindow.toLong(),
            d2 = stackMode.toLong(),
            d3 = 0L,
            d4 = 0L
        )
    }

    /**
     * 向根窗口发送 EWMH _NET_WM_STATE 消息，开启或关闭窗口的 _NET_WM_STATE_ABOVE 置顶状态。
     */
    private fun sendNetWmStateAbove(
        display: X11.Display,
        root: X11.Window,
        targetWindow: X11.Window,
        enable: Boolean
    ) {
        val stateAtom = X11.INSTANCE.XInternAtom(display, "_NET_WM_STATE", false) ?: return
        val aboveAtom = X11.INSTANCE.XInternAtom(display, "_NET_WM_STATE_ABOVE", false) ?: return
        if (stateAtom.toLong() == 0L || aboveAtom.toLong() == 0L) return
        sendRootClientMessage(
            display = display,
            root = root,
            targetWindow = targetWindow,
            messageType = stateAtom,
            d0 = if (enable) NET_WM_STATE_ADD else NET_WM_STATE_REMOVE,
            d1 = aboveAtom.toLong(),
            d2 = 0L,
            d3 = EWMH_SOURCE_PAGER,
            d4 = 0L
        )
    }

    private fun sendRootClientMessage(
        display: X11.Display,
        root: X11.Window,
        targetWindow: X11.Window,
        messageType: X11.Atom,
        d0: Long,
        d1: Long,
        d2: Long,
        d3: Long,
        d4: Long
    ) {
        val xEvent = X11.XEvent()
        xEvent.type = X11.ClientMessage
        xEvent.setType(X11.XClientMessageEvent::class.java)
        xEvent.xclient.type = X11.ClientMessage
        xEvent.xclient.window = targetWindow
        xEvent.xclient.message_type = messageType
        xEvent.xclient.format = 32
        xEvent.xclient.data.setType(Array<NativeLong>::class.java)
        xEvent.xclient.data.l[0] = NativeLong(d0)
        xEvent.xclient.data.l[1] = NativeLong(d1)
        xEvent.xclient.data.l[2] = NativeLong(d2)
        xEvent.xclient.data.l[3] = NativeLong(d3)
        xEvent.xclient.data.l[4] = NativeLong(d4)

        val mask = NativeLong((X11.SubstructureRedirectMask or X11.SubstructureNotifyMask).toLong())
        X11.INSTANCE.XSendEvent(display, root, 0, mask, xEvent)
    }

    /**
     * 读取根窗口上的 _NET_CLIENT_LIST_STACKING，返回按 Z 轴从底到顶排序的客户端窗口 ID 列表。
     */
    private fun readClientStackingList(display: X11.Display, root: X11.Window): List<Long> {
        val atom = X11.INSTANCE.XInternAtom(display, "_NET_CLIENT_LIST_STACKING", false) ?: return emptyList()
        if (atom.toLong() == 0L) return emptyList()

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
            NativeLong(512),
            false,
            X11.XA_WINDOW,
            actualType,
            actualFormat,
            itemCount,
            bytesAfter,
            property
        )
        if (status != X11.Success) return emptyList()

        val pointer = property.value ?: return emptyList()
        return try {
            if (actualFormat.value != 32 || itemCount.value.toLong() < 1) {
                emptyList()
            } else {
                val count = itemCount.value.toInt().coerceAtMost(512)
                val stride = Native.LONG_SIZE.toLong()
                List(count) { index ->
                    pointer.getNativeLong(index * stride).toLong()
                }
            }
        } finally {
            X11.INSTANCE.XFree(pointer)
        }
    }

    /**
     * 递归查找窗口树。scrcpy 窗口通常被窗口管理器包在一层 frame 里，
     * 只遍历 root 的直接子窗口会漏掉它。
     *
     * 找到标题匹配的客户窗口后，先判断它是否被最小化或隐藏，再计算外框几何与置顶状态。
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

                val mirrorAlwaysOnTop = hasNetWmState(display, window, "_NET_WM_STATE_ABOVE")
                val absoluteX = IntByReference()
                val absoluteY = IntByReference()
                val childReturn = X11.WindowByReference()
                if (X11.INSTANCE.XTranslateCoordinates(
                        display, window, root, 0, 0,
                        absoluteX, absoluteY, childReturn
                    )
                ) {
                    val outerFrame = findOuterFrame(display, window, root)
                    val handle = ScrcpyWindowHandle(
                        clientId = window.toLong(),
                        frameId = outerFrame?.toLong()
                    )

                    // 优先取窗口管理器包在客户区外层的 frame 几何，
                    // 这样顶部和右侧对齐的是用户看到的整扇窗口，而不只是内容区。
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
                                    bounds = mirrorBounds,
                                    handle = handle,
                                    isAlwaysOnTop = mirrorAlwaysOnTop
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
                        bounds = mirrorBounds,
                        handle = handle,
                        isAlwaysOnTop = mirrorAlwaysOnTop
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
     * 读取 ICCCM 的 WM_STATE 属性。
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
     * 判断 _NET_WM_STATE 列表中是否包含指定状态（例如 _NET_WM_STATE_HIDDEN 或 _NET_WM_STATE_ABOVE）。
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
