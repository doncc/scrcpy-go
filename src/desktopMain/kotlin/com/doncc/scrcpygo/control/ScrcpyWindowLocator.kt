package com.doncc.scrcpygo.control

import com.doncc.scrcpygo.utils.PlatformUtils
import java.awt.Window

/**
 * scrcpy 投屏窗口外框的屏幕位置和大小，坐标是 AWT/Compose 使用的逻辑像素。
 * 外框包含窗口管理器添加的标题栏和边框，便于控制条与窗口视觉边沿对齐。
 *
 * Windows 下先取 DWM 的可见外框（排除窗口四周用于拖拽缩放的透明边框），
 * 再按显示器缩放比例换算成逻辑像素，保证 125%/150% 缩放时也能贴紧窗口。
 */
data class ScrcpyWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * 投屏窗口的原生窗口句柄。
 *
 * Windows 下 `clientId` 是顶层 HWND；X11 下 `clientId` 是客户窗口、`frameId` 是
 * 窗口管理器装饰的 frame（没有 frame 时为 null）。控制条据此精确调整与投屏窗口
 * 的 Z 轴相邻关系，而不依赖窗口标题二次查找。
 */
data class ScrcpyWindowHandle(
    val clientId: Long,
    val frameId: Long? = null
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
     * [handle] 用于同步控制条与投屏窗口的 Z 轴层级；
     * [isAlwaysOnTop] 表示投屏窗口本身是否处于置顶层级。
     */
    data class Visible(
        val bounds: ScrcpyWindowBounds,
        val handle: ScrcpyWindowHandle,
        val isAlwaysOnTop: Boolean = false
    ) : ScrcpyWindowState

    object Hidden : ScrcpyWindowState
    object NotFound : ScrcpyWindowState
}

/**
 * 平台窗口适配器：查找投屏窗口并同步控制条与投屏窗口的 Z 轴层级。
 *
 * 不同平台的实现分别位于 [WindowsWindowAdapter] 和 [X11WindowAdapter]，
 * 不支持窗口读取的平台使用 [UnsupportedWindowAdapter]。
 */
internal interface PlatformWindowAdapter {
    fun isAvailable(): Boolean

    fun findWindowState(serial: String): ScrcpyWindowState

    fun syncPadZOrder(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle,
        mirrorAlwaysOnTop: Boolean
    )

    fun raiseMirrorBelowPad(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle
    )

    /** 断开投屏时清理控制条与投屏窗口之间的瞬态绑定，默认无需处理。 */
    fun clearPadBinding(padWindow: Window) {}
}

/**
 * Wayland、macOS 等无法读取其他应用窗口位置的平台上的空实现。
 */
internal object UnsupportedWindowAdapter : PlatformWindowAdapter {
    override fun isAvailable(): Boolean = false

    override fun findWindowState(serial: String): ScrcpyWindowState = ScrcpyWindowState.NotFound

    override fun syncPadZOrder(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle,
        mirrorAlwaysOnTop: Boolean
    ) = Unit

    override fun raiseMirrorBelowPad(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle
    ) = Unit
}

/**
 * 查找指定设备的 scrcpy 窗口，并同步控制条与投屏窗口的 Z 轴层级。
 *
 * 当前支持 Linux X11 和 Windows；Wayland 下无法读取其他应用窗口位置，
 * macOS 需要额外授权，这两种情况返回不支持，控制条继续固定在屏幕右缘。
 */
object ScrcpyWindowLocator {
    @Volatile
    private var cachedSupport: Boolean? = null
    private val supportLock = Any()

    private val adapter: PlatformWindowAdapter by lazy {
        when {
            PlatformUtils.isLinux -> X11WindowAdapter
            PlatformUtils.isWindows -> WindowsWindowAdapter
            else -> UnsupportedWindowAdapter
        }
    }

    val isSupported: Boolean
        get() {
            cachedSupport?.let { return it }
            return synchronized(supportLock) {
                cachedSupport ?: runCatching { adapter.isAvailable() }
                    .getOrDefault(false)
                    .also { cachedSupport = it }
            }
        }

    fun findWindowState(serial: String): ScrcpyWindowState {
        if (!isSupported || serial.isBlank()) return ScrcpyWindowState.NotFound
        return runCatching { adapter.findWindowState(serial) }
            .getOrElse { ScrcpyWindowState.NotFound }
    }

    /**
     * 把控制条摆到投屏窗口正上方（同一层级带内），必要时调整置顶层。
     * 调用方通过互斥锁串行化，避免与窗口激活时的重排互相覆盖。
     */
    fun syncPadZOrder(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle,
        mirrorAlwaysOnTop: Boolean
    ) {
        if (!isSupported || mirrorHandle.clientId == 0L) return
        runCatching { adapter.syncPadZOrder(padWindow, mirrorHandle, mirrorAlwaysOnTop) }
    }

    /**
     * 控制条获得焦点后，确保投屏窗口仍紧贴在控制条正下方，避免被控制条完全盖住。
     */
    fun raiseMirrorBelowPad(
        padWindow: Window,
        mirrorHandle: ScrcpyWindowHandle
    ) {
        if (!isSupported || mirrorHandle.clientId == 0L) return
        runCatching { adapter.raiseMirrorBelowPad(padWindow, mirrorHandle) }
    }

    /**
     * 断开投屏 / 控制条关闭时解除与投屏窗口的层级绑定。
     */
    fun clearPadBinding(padWindow: Window) {
        if (!isSupported) return
        runCatching { adapter.clearPadBinding(padWindow) }
    }
}
