package com.doncc.scrcpygo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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

private val PAD_WIDTH = 76.dp
private val PAD_HEIGHT = 620.dp
private val SCREEN_MARGIN = ControlPadRules.RIGHT_MARGIN_DP.dp
private val PAD_GAP = ControlPadRules.PAD_GAP_DP.dp

/**
 * 单台设备的纵向控制条窗口。
 * 窗口贴住投屏窗口右上角：顶部平齐、左侧紧贴右边沿；右侧空间不足时自动翻到左侧。
 */
@Composable
fun ControlPadWindow(
    session: ControlPadSession,
    onDisconnect: () -> Unit
) {
    val density = LocalDensity.current
    val workArea = rememberPrimaryWorkArea()
    val locatorSupported = remember { ScrcpyWindowLocator.isSupported }
    var visible by remember { mutableStateOf(!locatorSupported) }
    val position = remember(session.serial, session.slotIndex, density, workArea) {
        val point = ControlPadRules.calculatePosition(
            workAreaX = workArea.x,
            workAreaY = workArea.y,
            workAreaWidth = workArea.width,
            workAreaHeight = workArea.height,
            padWidthPx = with(density) { PAD_WIDTH.roundToPx() },
            padHeightPx = with(density) { PAD_HEIGHT.roundToPx() },
            marginPx = with(density) { SCREEN_MARGIN.roundToPx() },
            gapPx = with(density) { PAD_GAP.roundToPx() },
            slotIndex = session.slotIndex
        )
        WindowPosition(
            with(density) { point.x.toDp() },
            with(density) { point.y.toDp() }
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
        alwaysOnTop = true
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
                        onReady = { visible = true },
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
    onReady: () -> Unit,
    onWindowVisibilityChanged: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val actionMutex = remember { Mutex() }
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

    val density = LocalDensity.current
    val padWidthPx = with(density) { PAD_WIDTH.roundToPx() }
    val padHeightPx = with(density) { PAD_HEIGHT.roundToPx() }

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

    LaunchedEffect(session.serial, session.process, locatorSupported) {
        if (!locatorSupported) {
            onReady()
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
                    if (state.bounds != lastBounds) {
                        applyDock(state.bounds)
                        lastBounds = state.bounds
                    }
                    first = false
                    // 投屏窗口被前台窗口盖住时一起隐藏；只是失去焦点但仍露着时保持显示
                    onWindowVisibilityChanged(!state.coveredByForeground)
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
                    if (statusText == "未找到投屏窗口") {
                        statusText = null
                        statusIsError = false
                        statusVersion++
                    }
                    onWindowVisibilityChanged(false)
                }
                ScrcpyWindowState.NotFound -> {
                    if (first && waitedMs >= 3000) {
                        // 窗口一直没出现时先展示默认位置，避免用户完全失去控制入口
                        first = false
                        onReady()
                        statusText = "未找到投屏窗口"
                        statusIsError = true
                        statusVersion++
                    }
                }
            }

            delay(250)
            waitedMs += 250
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
