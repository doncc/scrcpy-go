package com.doncc.scrcpygo

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.doncc.scrcpygo.process.LogEntry
import com.doncc.scrcpygo.process.LogcatController
import com.doncc.scrcpygo.process.ProcessManager
import com.doncc.scrcpygo.ui.*
import com.doncc.scrcpygo.utils.PlatformUtils
import com.doncc.scrcpygo.utils.VersionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File
import java.net.URI

/**
 * 设备数据类
 */
data class Device(
    val serial: String,
    val model: String,
    val type: String, // USB 或 TCP
    val status: String // device, unauthorized, offline 等
)

/**
 * Logcat 会话持有者
 */
class LogcatSession(
    val serial: String,
    val model: String,
    val controller: LogcatController,
    val logList: MutableList<LogEntry> = mutableStateListOf()
)

fun main() = application {
    val coroutineScope = rememberCoroutineScope()
    
    // UI 全局状态
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var adbExists by remember { mutableStateOf(false) }
    var scrcpyExists by remember { mutableStateOf(false) }
    
    // 跟踪正在运行的 scrcpy 投屏设备列表，驱动指示灯实时更新
    val runningDevices = remember { mutableStateListOf<String>() }
    
    var ipAddress by remember { mutableStateOf("192.168.100.254") }
    var scrcpyArgs by remember { mutableStateOf("--no-audio") }
    
    var alertTitle by remember { mutableStateOf("") }
    var alertMessage by remember { mutableStateOf("") }
    var showAlert by remember { mutableStateOf(false) }
    
    // 保存已开启的 Logcat 独立窗口会话 (Key: 设备序列号)
    val activeLogcatSessions = remember { mutableStateMapOf<String, LogcatSession>() }

    // 触发全局提示框的方法
    fun showToast(title: String, message: String) {
        alertTitle = title
        alertMessage = message
        showAlert = true
    }

    // 刷新设备列表管道
    fun refreshDeviceList() {
        coroutineScope.launch {
            // 环境检测
            val adbPath = PlatformUtils.getBinaryPath("adb")
            val scrcpyPath = PlatformUtils.getBinaryPath("scrcpy")
            adbExists = File(adbPath).exists() || adbPath == "adb"
            scrcpyExists = File(scrcpyPath).exists() || scrcpyPath == "scrcpy"
            
            if (!adbExists) {
                devices = emptyList()
                return@launch
            }

            // 获取 ADB 挂载设备
            val (stdout, _) = ProcessManager.runAdbCommand("devices", "-l")
            val parsedDevices = mutableListOf<Device>()
            val lines = stdout.split("\n")
            if (lines.size > 1) {
                for (i in 1 until lines.size) {
                    val line = lines[i].trim()
                    if (line.isEmpty()) continue
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val serial = parts[0]
                        val status = parts[1]
                        var model = "未知设备"
                        for (part in parts) {
                            if (part.startsWith("model:")) {
                                model = part.substringAfter("model:")
                                break
                            }
                        }
                        val isTcp = serial.contains(":")
                        parsedDevices.add(Device(serial, model, if (isTcp) "TCP" else "USB", status))
                    }
                }
            }
            devices = parsedDevices
            
            // 增量同步运行状态到 runningDevices，防止界面闪烁
            val currentRunning = parsedDevices.filter { ProcessManager.isDeviceRunning(it.serial) }.map { it.serial }.toSet()
            runningDevices.removeAll { it !in currentRunning }
            currentRunning.forEach { serial ->
                if (!runningDevices.contains(serial)) {
                    runningDevices.add(serial)
                }
            }
            
            // 修正选中状态
            if (selectedDevice != null && !devices.any { it.serial == selectedDevice?.serial }) {
                selectedDevice = null
            }
        }
    }

    // 启动投屏
    fun startMirroring(device: Device) {
        if (!scrcpyExists) {
            showToast("错误", "找不到 scrcpy 投屏服务，无法启动连接！")
            return
        }
        val argsList = scrcpyArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = ProcessManager.launchScrcpy(device.serial, argsList)
        if (result.isFailure) {
            showToast("错误", "启动投屏失败:\n${result.exceptionOrNull()?.message}")
        } else {
            val process = result.getOrNull()
            if (process != null) {
                // 启动成功，立即点亮指示灯
                if (!runningDevices.contains(device.serial)) {
                    runningDevices.add(device.serial)
                }
                // 开启后台协程，等待进程退出后更新状态，熄灭指示灯
                coroutineScope.launch(Dispatchers.IO) {
                    process.waitFor()
                    withContext(Dispatchers.Main) {
                        runningDevices.remove(device.serial)
                    }
                }
            }
        }
    }

    // 初始化加载
    LaunchedEffect(Unit) {
        refreshDeviceList()
    }

    // 独立二级 Logcat 窗口渲染循环
    activeLogcatSessions.forEach { (serial, session) ->
        Window(
            onCloseRequest = {
                session.controller.stop()
                activeLogcatSessions.remove(serial)
            },
            title = "Logcat 控制台 - ${session.model} ($serial)",
            state = rememberWindowState(width = 900.dp, height = 550.dp),
            icon = painterResource("icons/icon.png")
        ) {
            ScrcpyGoTheme {
                LogcatWindowScreen(session, this.window) {
                    session.controller.stop()
                    activeLogcatSessions.remove(serial)
                }
            }
        }
    }

    // 主窗口
    Window(
        onCloseRequest = ::exitApplication,
        title = "Scrcpy-Go 快捷连接器 v${VersionUtils.version}",
        state = rememberWindowState(width = 680.dp, height = 700.dp),
        resizable = false,
        icon = painterResource("icons/icon.png")
    ) {
        ScrcpyGoTheme {
            Surface(color = MaterialTheme.colors.background) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 主体布局
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 12.dp, start = 12.dp, end = 12.dp)
                    ) {
                        // 左栏：设备卡片列表
                        Column(
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxHeight()
                                .padding(end = 6.dp)
                        ) {
                            Text(
                                text = "已连接设备列表 (双击直接启动)",
                                style = MaterialTheme.typography.h2,
                                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                            )
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(1.dp, LightBorder, AppShapes.medium)
                                    .background(LightSurface, AppShapes.medium)
                                    .padding(4.dp)
                            ) {
                                if (devices.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (!adbExists) "未检测到 ADB，无法获取设备列表" else "未发现已连接的设备\n请插入 USB 或使用 TCP 连接",
                                            style = MaterialTheme.typography.body2,
                                            color = LightTextSub,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                } else {
                                    val listState = rememberLazyListState()
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        items(devices, key = { it.serial }) { device ->
                                            val isSelected = selectedDevice?.serial == device.serial
                                            val isRunning = runningDevices.contains(device.serial)
                                            
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                                                    .clip(AppShapes.medium)
                                                    .background(
                                                        if (isSelected) Color(0xFFE3F2FD) else Color.Transparent
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (isSelected) LightPrimary else Color.Transparent,
                                                        AppShapes.medium
                                                    )
                                                    .pointerInput(device) {
                                                        awaitPointerEventScope {
                                                            while (true) {
                                                                val event = awaitPointerEvent()
                                                                // 仅在鼠标按压时处理点击事件
                                                                if (event.type == PointerEventType.Press) {
                                                                    val mouseEvent = event.awtEventOrNull as? java.awt.event.MouseEvent
                                                                    if (mouseEvent != null) {
                                                                        if (mouseEvent.clickCount == 1) {
                                                                            // 单击：立即更新选中状态，排除 detectTapGestures 的等待延迟
                                                                            selectedDevice = device
                                                                        } else if (mouseEvent.clickCount == 2) {
                                                                            // 双击：立即启动投屏服务
                                                                            startMirroring(device)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // 连接类型 Badge 标签
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            if (device.type == "TCP") Color(0xFFE0F7FA) else Color(0xFFECEFF1),
                                                            RoundedCornerShape(4.dp)
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = device.type,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (device.type == "TCP") Color(0xFF006064) else Color(0xFF37474F)
                                                    )
                                                }
                                                
                                                Spacer(modifier = Modifier.width(10.dp))
                                                
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = device.model,
                                                        style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.SemiBold),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "${device.serial} (${device.status})",
                                                        style = MaterialTheme.typography.body2,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                
                                                Spacer(modifier = Modifier.width(4.dp))
                                                
                                                // 投屏状态指示灯
                                                if (isRunning) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .background(ColorSuccess, CircleShape)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    
                                    // 垂直滚动条
                                    VerticalScrollbar(
                                        adapter = rememberScrollbarAdapter(listState),
                                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                                    )
                                }
                            }
                        }

                        // 右栏：控制操作面板
                        Column(
                            modifier = Modifier
                                .weight(0.9f)
                                .fillMaxHeight()
                                .padding(start = 6.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            // 1. 运行环境检测
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                border = BorderStroke(1.dp, LightBorder),
                                shape = AppShapes.medium
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("运行环境检测", style = MaterialTheme.typography.h2)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(8.dp).background(if (adbExists) ColorSuccess else ColorError, CircleShape))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("ADB: ${if (adbExists) "就绪" else "未找到"}", style = MaterialTheme.typography.body1)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(8.dp).background(if (scrcpyExists) ColorSuccess else ColorError, CircleShape))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Scrcpy: ${if (scrcpyExists) "就绪" else "未找到"}", style = MaterialTheme.typography.body1)
                                    }
                                }
                            }

                            // 2. 设备操作按钮
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                border = BorderStroke(1.dp, LightBorder),
                                shape = AppShapes.medium
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("设备操作", style = MaterialTheme.typography.h2)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Button(
                                        onClick = { selectedDevice?.let { startMirroring(it) } },
                                        enabled = selectedDevice != null,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = LightPrimary)
                                    ) {
                                        Text("连接选中设备", color = Color.White)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(
                                        onClick = { 
                                            selectedDevice?.let { 
                                                ProcessManager.terminateProcessForDevice(it.serial)
                                                refreshDeviceList()
                                            }
                                        },
                                        enabled = selectedDevice != null,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = ColorError)
                                    ) {
                                        Text("断开选中设备", color = Color.White)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Button(
                                        onClick = {
                                            selectedDevice?.let { device ->
                                                if (activeLogcatSessions.containsKey(device.serial)) {
                                                    showToast("提示", "该设备的 Logcat 窗口已经开启！")
                                                } else {
                                                    val controller = LogcatController(device.serial, coroutineScope)
                                                    val session = LogcatSession(device.serial, device.model, controller)
                                                    activeLogcatSessions[device.serial] = session
                                                }
                                            }
                                        },
                                        enabled = selectedDevice != null,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = LightSecondary)
                                    ) {
                                        Text("查看 Logcat 日志", color = Color.White)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedButton(
                                        onClick = { refreshDeviceList() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("刷新设备列表")
                                    }
                                }
                            }

                            // 3. 手动 TCP 连接
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                border = BorderStroke(1.dp, LightBorder),
                                shape = AppShapes.medium
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("手动 TCP 连接", style = MaterialTheme.typography.h2)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = ipAddress,
                                        onValueChange = { ipAddress = it },
                                        label = { Text("设备 IP (默认端口 5555)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val ipPort = if (ipAddress.contains(":")) ipAddress else "$ipAddress:5555"
                                                    val (stdout, stderr) = ProcessManager.runAdbCommand("connect", ipPort)
                                                    refreshDeviceList()
                                                    if (stdout.contains("connected to")) {
                                                        showToast("TCP 连接成功", "已成功连接到设备 $ipPort。\n您可以在设备列表中选中并投屏。")
                                                    } else {
                                                        val err = if (stderr.isNotEmpty()) stderr else stdout
                                                        showToast("TCP 连接失败", "连接 $ipPort 失败:\n$err")
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(backgroundColor = LightPrimary)
                                        ) {
                                            Text("TCP 连接", color = Color.White)
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        OutlinedButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val ipPort = if (ipAddress.contains(":")) ipAddress else "$ipAddress:5555"
                                                    val (stdout, stderr) = ProcessManager.runAdbCommand("disconnect", ipPort)
                                                    refreshDeviceList()
                                                    if (stdout.contains("disconnected") || stderr.isEmpty()) {
                                                        showToast("TCP 断开成功", "已断开与设备 $ipPort 的连接。")
                                                    } else {
                                                        showToast("TCP 断开失败", "断开 $ipPort 失败:\n$stdout")
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("TCP 断开")
                                        }
                                    }
                                }
                            }

                            // 4. 参数配置与快捷方式
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                border = BorderStroke(1.dp, LightBorder),
                                shape = AppShapes.medium
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("参数与配置", style = MaterialTheme.typography.h2)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = scrcpyArgs,
                                        onValueChange = { scrcpyArgs = it },
                                        label = { Text("Scrcpy 参数") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    // 仅在 Windows 和 Linux 下，并且已经打包分发的环境下展示创建快捷方式按钮
                                    if ((PlatformUtils.isWindows || PlatformUtils.isLinux) && PlatformUtils.isPackaged) {
                                        Button(
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    // 直接获取当前运行的原生可执行文件路径
                                                    val execPath = PlatformUtils.currentExecutablePath
                                                    val result = PlatformUtils.createDesktopShortcut(execPath, "scrcpy-go")
                                                    withContext(Dispatchers.Main) {
                                                        if (result.isSuccess) {
                                                            showToast("快捷方式创建成功", result.getOrNull() ?: "")
                                                        } else {
                                                            showToast("快捷方式创建失败", result.exceptionOrNull()?.message ?: "")
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(backgroundColor = LightSecondary)
                                        ) {
                                            Text("创建桌面快捷方式", color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 底部 Star 开源信息栏
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LightBorder.copy(alpha = 0.3f))
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    ) {
                        val githubUrl = "https://github.com/doncc/scrcpy-go"
                        Text(
                            text = "⭐ 觉得好用请给个 Star: $githubUrl",
                            color = LightPrimary,
                            style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier
                                .clickable {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            java.awt.Desktop.getDesktop().browse(URI(githubUrl))
                                        } catch (e: Exception) {
                                            // 忽略错误
                                        }
                                    }
                                }
                                .align(Alignment.CenterStart)
                        )
                    }
                }

                // 弹出对话框提示
                if (showAlert) {
                    AlertDialog(
                        onDismissRequest = { showAlert = false },
                        title = { Text(alertTitle, style = MaterialTheme.typography.h2) },
                        text = { Text(alertMessage, style = MaterialTheme.typography.body1) },
                        confirmButton = {
                            TextButton(onClick = { showAlert = false }) {
                                Text("确定")
                            }
                        },
                        shape = AppShapes.medium
                    )
                }
            }
        }
    }
}

/**
 * 独立的 Logcat 日志渲染界面
 */
@Composable
fun LogcatWindowScreen(session: LogcatSession, parentWindow: java.awt.Window, onClose: () -> Unit) {
    var packageFilter by remember { mutableStateOf("") }
    var isLoggingActive by remember { mutableStateOf(false) }
    var isScrollLocked by remember { mutableStateOf(true) } // 滚动锁定 (默认开启，自动滚动到底部)
    
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var saveAlertMessage by remember { mutableStateOf("") }
    var showSaveAlert by remember { mutableStateOf(false) }

    // 绑定控制器缓冲日志派发
    DisposableEffect(session) {
        session.controller.onLogsBuffered = { logs ->
            session.logList.addAll(logs)
            // 自动裁剪日志行数上限为 3000 行
            if (session.logList.size > 3000) {
                session.logList.subList(0, session.logList.size - 3000).clear()
            }
        }
        
        onDispose {
            // 不在此处停止以防意外生命周期变动，交给 Window onCloseRequest
        }
    }

    // 滚动到底部特效
    LaunchedEffect(session.logList.size, isScrollLocked) {
        if (isScrollLocked && session.logList.isNotEmpty()) {
            lazyListState.scrollToItem(session.logList.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightBackground)
            .padding(10.dp)
    ) {
        // 顶部过滤器与控制工具栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("过滤包名:", style = MaterialTheme.typography.body1, modifier = Modifier.padding(end = 6.dp))
            OutlinedTextField(
                value = packageFilter,
                onValueChange = { packageFilter = it },
                modifier = Modifier.width(220.dp).height(50.dp),
                textStyle = MaterialTheme.typography.body1,
                placeholder = { Text("如 com.android.settings", style = MaterialTheme.typography.body2) },
                singleLine = true
            )
            Spacer(modifier = Modifier.width(6.dp))
            Button(
                onClick = {
                    session.logList.clear()
                    session.controller.filterPackage = packageFilter
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = LightPrimary)
            ) {
                Text("应用过滤", color = Color.White)
            }
            
            Spacer(modifier = Modifier.weight(1f))

            // 开始/暂停按钮
            Button(
                onClick = {
                    if (isLoggingActive) {
                        session.controller.stop()
                        isLoggingActive = false
                    } else {
                        session.controller.start()
                        isLoggingActive = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isLoggingActive) ColorError else ColorSuccess
                )
            ) {
                Text(if (isLoggingActive) "暂停" else "开始", color = Color.White)
            }
            Spacer(modifier = Modifier.width(6.dp))

            // 滚动锁定开关
            Button(
                onClick = { isScrollLocked = !isScrollLocked },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isScrollLocked) LightPrimary else Color.LightGray
                ),
                shape = AppShapes.small,
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = if (isScrollLocked) "已锁滚底" else "自由滚动",
                    color = if (isScrollLocked) Color.White else LightTextMain,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.width(4.dp))

            // 保存日志文件 (利用 AWT FileDialog 原生对话框)
            Button(
                onClick = {
                    val frame = parentWindow as? java.awt.Frame
                    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    val timeStamp = java.time.LocalDateTime.now().format(formatter)
                    val dialog = FileDialog(frame, "保存日志文件", FileDialog.SAVE).apply {
                        file = "logcat_${session.serial.replace(":", "_")}_$timeStamp.log"
                        isVisible = true
                    }
                    val directory = dialog.directory
                    val filename = dialog.file
                    if (!directory.isNullOrEmpty() && !filename.isNullOrEmpty()) {
                        val targetFile = File(directory, filename)
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val textContent = session.logList.joinToString("") { it.rawText }
                                targetFile.writeText(textContent)
                                withContext(Dispatchers.Main) {
                                    saveAlertMessage = "日志已成功导出至:\n${targetFile.absolutePath}"
                                    showSaveAlert = true
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    saveAlertMessage = "保存日志失败，错误信息:\n${e.message}"
                                    showSaveAlert = true
                                }
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = LightSecondary),
                shape = AppShapes.small,
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = "保存日志",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.width(4.dp))

            // 清空屏幕
            IconButton(
                onClick = { session.logList.clear() }
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = ColorError)
            }
        }

        // 中部日志输出文本域 (使用 LazyColumn)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, LightBorder, AppShapes.medium)
                .background(Color.White, AppShapes.medium)
                .padding(6.dp)
        ) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(session.logList) { log ->
                    val color = when (log.level) {
                        "V" -> Color(0xFF7F8C8D) // 雅灰
                        "D" -> Color(0xFF2980B9) // 亮蓝
                        "I" -> Color(0xFF27AE60) // 绿
                        "W" -> Color(0xFFD35400) // 橙黄
                        "E", "F" -> Color(0xFFC0392B) // 深红
                        else -> LightTextMain
                    }
                    
                    Text(
                        text = log.rawText,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = color
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    )
                }
            }

            // 垂直滚动条
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(lazyListState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 底部状态栏
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val statusColor = if (isLoggingActive) ColorSuccess else LightTextSub
            Text(
                text = if (isLoggingActive) {
                    if (packageFilter.isNotEmpty()) "正在抓取包名: $packageFilter 的日志" else "正在全局抓取日志"
                } else "日志已暂停",
                color = statusColor,
                style = MaterialTheme.typography.body2
            )
        }

        // 保存文件后的提示框
        if (showSaveAlert) {
            AlertDialog(
                onDismissRequest = { showSaveAlert = false },
                title = { Text("保存结果", style = MaterialTheme.typography.h2) },
                text = { Text(saveAlertMessage, style = MaterialTheme.typography.body1) },
                confirmButton = {
                    TextButton(onClick = { showSaveAlert = false }) {
                        Text("确定")
                    }
                },
                shape = AppShapes.medium
            )
        }
    }
}
