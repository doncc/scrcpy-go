package com.doncc.scrcpygo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.doncc.scrcpygo.control.ControlPadRules
import com.doncc.scrcpygo.control.ControlPadSession
import com.doncc.scrcpygo.control.DeviceAction
import com.doncc.scrcpygo.control.DeviceActionRunner
import com.doncc.scrcpygo.control.ScrcpyWindowBounds
import com.doncc.scrcpygo.control.ScrcpyWindowHandle
import com.doncc.scrcpygo.control.ScrcpyWindowLocator
import com.doncc.scrcpygo.control.ScrcpyWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private val PAD_WIDTH = 76.dp
private val PAD_HEIGHT = 620.dp
private val SCREEN_MARGIN = ControlPadRules.RIGHT_MARGIN_DP.dp
private val PAD_GAP = ControlPadRules.PAD_GAP_DP.dp

/** 轮询投屏窗口状态的间隔（毫秒） */
private const val POLL_INTERVAL_MS = 100L

/** 控制条被激活后，这段时间内不允许轮询重排覆盖窗口激活触发的重排（毫秒） */
private const val RAISE_GUARD_WINDOW_MS = 250L

/**
 * 单台设备的纵向控制条窗口。
 * 窗口贴住投屏窗口右上角：顶部平齐、左侧紧贴右边沿；右侧空间不足时自动翻到左侧。
 * 同时通过平台适配器维持控制条紧贴在投屏窗口正上方的 Z 轴层级。
 */
@Composable
fun ControlPadWindow(
    session: ControlPadSession,
    onDisconnect: () -> Unit
) {
    val workArea = rememberPrimaryWorkArea()
    val locatorSupported = remember { ScrcpyWindowLocator.isSupported }
    var visible by remember { mutableStateOf(!locatorSupported) }
    var fallbackMode by remember { mutableStateOf(!locatorSupported) }
    var mirrorAlwaysOnTop by remember { mutableStateOf(false) }
    val padAlwaysOnTop = ControlPadRules.shouldPadBeAlwaysOnTop(
        locatorSupported = locatorSupported,
        fallbackMode = fallbackMode,
        mirrorAlwaysOnTop = mirrorAlwaysOnTop
    )

    // 控制条规则统一按逻辑像素计算：WindowPosition 的 Dp 数值在 Compose 桌面端会原样
    // 传给 AWT setLocation（AWT 使用的是逻辑像素），所以这里直接用 dp 数值承载逻辑像素。
    val position = remember(session.serial, session.slotIndex, workArea) {
        val point = ControlPadRules.calculatePosition(
            workAreaX = workArea.x,
            workAreaY = workArea.y,
            workAreaWidth = workArea.width,
            workAreaHeight = workArea.height,
            padWidthPx = PAD_WIDTH.value.roundToInt(),
            padHeightPx = PAD_HEIGHT.value.roundToInt(),
            marginPx = SCREEN_MARGIN.value.roundToInt(),
            gapPx = PAD_GAP.value.roundToInt(),
            slotIndex = session.slotIndex
        )
        WindowPosition(
            point.x.dp,
            point.y.dp
        )
    }

    Window(
        onCloseRequest = onDisconnect,
        visible = visible,
        title = "控制条 - ${session.model} (${session.serial})",
        icon = painterResource("icons/icon.png"),
        state = rememberWindowState(
            width = PAD_WIDTH,
            height = PAD_HEIGHT,
            position = position
        ),
        undecorated = true,
        resizable = false,
        alwaysOnTop = padAlwaysOnTop
    ) {
        ScrcpyGoTheme {
            Surface(color = LightSurface, modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                    ControlPadHeader(session)
                    Divider(color = LightBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(4.dp))
                    ControlPadBody(
                        session = session,
                        onDisconnect = onDisconnect,
                        locatorSupported = locatorSupported,
                        onFallbackModeChanged = { fallbackMode = it },
                        onMirrorAlwaysOnTopChanged = { mirrorAlwaysOnTop = it },
                        onWindowVisibilityChanged = { visible = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlPadHeader(session: ControlPadSession) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = session.model,
            style = MaterialTheme.typography.body2.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = LightTextMain
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = session.serial.takeLast(8),
            style = MaterialTheme.typography.body2.copy(fontSize = 9.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FrameWindowScope.ControlPadBody(
    session: ControlPadSession,
    onDisconnect: () -> Unit,
    locatorSupported: Boolean,
    onFallbackModeChanged: (Boolean) -> Unit,
    onMirrorAlwaysOnTopChanged: (Boolean) -> Unit,
    onWindowVisibilityChanged: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val actionMutex = remember { Mutex() }
    // 轮询重排与窗口激活重排共用同一把锁，避免两次 SetWindowPos 交错
    val zOrderMutex = remember { Mutex() }
    val latestMirrorHandle = remember { AtomicReference<ScrcpyWindowHandle?>(null) }
    val raiseGuardUntilMs = remember { AtomicLong(0L) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var statusVersion by remember { mutableStateOf(0) }

    LaunchedEffect(statusVersion) {
        // 成功提示短暂显示后自动消失；错误提示保留到下一次操作，方便排查
        val shown = statusText
        if (shown != null && shown != "执行中..." && !statusIsError) {
            delay(2500)
            if (statusText == shown) {
                statusText = null
            }
        }
    }

    val runAction: (DeviceAction) -> Unit = { action ->
        scope.launch {
            actionMutex.withLock {
                statusText = "执行中..."
                statusIsError = false
                statusVersion++
                val outcome = DeviceActionRunner.execute(session.serial, action)
                statusText = outcome.message ?: "${action.label} 已执行"
                statusIsError = !outcome.success
                statusVersion++
            }
        }
    }

    // 窗口宽高和位置都使用逻辑像素（dp 数值），与 ScrcpyWindowLocator 返回的坐标同一单位。
    val padWidthPx = PAD_WIDTH.value.roundToInt()
    val padHeightPx = PAD_HEIGHT.value.roundToInt()

    fun applyDock(bounds: ScrcpyWindowBounds) {
        val workArea = workAreaForWindow(bounds)
        val target = ControlPadRules.calculateDockedPosition(
            windowX = bounds.x,
            windowY = bounds.y,
            windowWidth = bounds.width,
            padWidthPx = padWidthPx,
            padHeightPx = padHeightPx,
            workAreaX = workArea.x,
            workAreaY = workArea.y,
            workAreaWidth = workArea.width,
            workAreaHeight = workArea.height
        )
        window.setLocation(target.x, target.y)
    }

    // 控制条自身被激活（点击/拖动）时保证投屏窗口紧贴在它正下方，
    // 否则置顶的控制条会把投屏窗口完全盖住，点击按钮后看不到画面。
    DisposableEffect(locatorSupported) {
        if (!locatorSupported) return@DisposableEffect onDispose { }

        val listener = object : WindowAdapter() {
            override fun windowActivated(e: WindowEvent?) {
                val mirrorHandle = latestMirrorHandle.get() ?: return
                raiseGuardUntilMs.set(System.currentTimeMillis() + RAISE_GUARD_WINDOW_MS)
                scope.launch {
                    zOrderMutex.withLock {
                        if (window.isDisplayable && window.isVisible) {
                            withContext(Dispatchers.IO) {
                                ScrcpyWindowLocator.raiseMirrorBelowPad(window, mirrorHandle)
                            }
                        }
                    }
                }
            }
        }
        window.addWindowListener(listener)
        onDispose { window.removeWindowListener(listener) }
    }

    LaunchedEffect(session.serial, session.process, locatorSupported) {
        if (!locatorSupported) {
            onFallbackModeChanged(true)
            onWindowVisibilityChanged(true)
            return@LaunchedEffect
        }

        var first = true
        var lastBounds: ScrcpyWindowBounds? = null
        var waitedMs = 0L

        while (isActive) {
            val state = withContext(Dispatchers.IO) {
                ScrcpyWindowLocator.findWindowState(session.serial)
            }
            when (state) {
                is ScrcpyWindowState.Visible -> {
                    latestMirrorHandle.set(state.handle)
                    if (state.bounds != lastBounds) {
                        applyDock(state.bounds)
                        lastBounds = state.bounds
                    }
                    first = false
                    onFallbackModeChanged(false)
                    onMirrorAlwaysOnTopChanged(state.isAlwaysOnTop)
                    onWindowVisibilityChanged(true)

                    // 控制条刚被激活时跳过轮询重排，让激活触发的重排先完成
                    if (System.currentTimeMillis() >= raiseGuardUntilMs.get() &&
                        window.isDisplayable && window.isVisible
                    ) {
                        zOrderMutex.withLock {
                            if (System.currentTimeMillis() >= raiseGuardUntilMs.get()) {
                                withContext(Dispatchers.IO) {
                                    ScrcpyWindowLocator.syncPadZOrder(
                                        window,
                                        state.handle,
                                        state.isAlwaysOnTop
                                    )
                                }
                            }
                        }
                    }

                    if (statusText == "未找到投屏窗口") {
                        statusText = null
                        statusIsError = false
                        statusVersion++
                    }
                }
                ScrcpyWindowState.Hidden -> {
                    // 投屏窗口最小化或隐藏时控制条一起隐藏，还原后重新贴靠再显示。
                    // 清空 lastBounds，保证还原时即使窗口位置没变也会重新对齐。
                    first = false
                    lastBounds = null
                    latestMirrorHandle.set(null)
                    onMirrorAlwaysOnTopChanged(false)
                    zOrderMutex.withLock {
                        withContext(Dispatchers.IO) {
                            ScrcpyWindowLocator.clearPadBinding(window)
                        }
                    }
                    if (statusText == "未找到投屏窗口") {
                        statusText = null
                        statusIsError = false
                        statusVersion++
                    }
                    onWindowVisibilityChanged(false)
                }
                ScrcpyWindowState.NotFound -> {
                    latestMirrorHandle.set(null)
                    if (first && waitedMs >= 3000) {
                        // 窗口一直没出现时先展示默认位置，避免用户完全失去控制入口
                        first = false
                        onFallbackModeChanged(true)
                        onWindowVisibilityChanged(true)
                        statusText = "未找到投屏窗口"
                        statusIsError = true
                        statusVersion++
                    }
                }
            }

            delay(POLL_INTERVAL_MS)
            waitedMs += POLL_INTERVAL_MS
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DeviceAction.entries.forEachIndexed { index, action ->
                ControlPadActionButton(action, runAction)
                // 导航、系统、音量三组之间留出更明显的间距
                if (index == 2 || index == 5 || index == 7) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = statusText ?: " ",
            style = MaterialTheme.typography.body2.copy(fontSize = 9.sp),
            color = if (statusIsError) ColorError else LightTextSub,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onDisconnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            shape = AppShapes.small,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = ColorError,
                contentColor = Color.White
            )
        ) {
            Text("断开投屏", fontSize = 11.sp)
        }
    }
}

@Composable
private fun ControlPadActionButton(
    action: DeviceAction,
    onClick: (DeviceAction) -> Unit
) {
    val backgroundColor = when (action) {
        DeviceAction.BACK, DeviceAction.HOME, DeviceAction.RECENTS -> LightPrimary
        DeviceAction.NOTIFICATIONS, DeviceAction.QUICK_SETTINGS, DeviceAction.COLLAPSE_PANELS -> LightSecondary
        DeviceAction.VOLUME_UP, DeviceAction.VOLUME_DOWN -> LightPrimaryHover
        DeviceAction.ROTATE -> LightSecondary
        DeviceAction.SCREEN_POWER -> LightTextSub
    }

    Button(
        onClick = { onClick(action) },
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        shape = AppShapes.small,
        contentPadding = PaddingValues(horizontal = 2.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = backgroundColor,
            contentColor = Color.White
        )
    ) {
        Text(
            text = action.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * 取指定投屏窗口所在屏幕的工作区；多显示器时跟随窗口所在屏幕。
 */
private fun workAreaForWindow(bounds: ScrcpyWindowBounds): Rectangle {
    val centerX = bounds.x + bounds.width / 2
    val centerY = bounds.y + bounds.height / 2
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val device = environment.screenDevices.firstOrNull {
        it.defaultConfiguration.bounds.contains(centerX, centerY)
    } ?: environment.defaultScreenDevice
    val screenBounds = device.defaultConfiguration.bounds
    val insets = runCatching {
        Toolkit.getDefaultToolkit().getScreenInsets(device.defaultConfiguration)
    }.getOrDefault(Insets(0, 0, 0, 0))
    return Rectangle(
        screenBounds.x + insets.left,
        screenBounds.y + insets.top,
        (screenBounds.width - insets.left - insets.right).coerceAtLeast(1),
        (screenBounds.height - insets.top - insets.bottom).coerceAtLeast(1)
    )
}

/**
 * 取主屏工作区（会尽量避开任务栏），取不到时回退到整个屏幕。
 */
@Composable
private fun rememberPrimaryWorkArea(): Rectangle {
    return remember {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        if (bounds.width > 0 && bounds.height > 0) {
            bounds
        } else {
            val screen = Toolkit.getDefaultToolkit().screenSize
            Rectangle(0, 0, screen.width, screen.height)
        }
    }
}
