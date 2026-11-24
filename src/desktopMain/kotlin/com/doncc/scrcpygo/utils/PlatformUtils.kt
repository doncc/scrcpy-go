package com.doncc.scrcpygo.utils

import java.awt.Desktop
import java.io.File
import java.io.FileWriter
import java.net.URI

/**
 * 平台辅助工具类
 * 用于检测操作系统、定位 adb/scrcpy 二进制路径以及生成桌面快捷方式
 */
object PlatformUtils {
    val osName: String = System.getProperty("os.name").lowercase()
    val osArch: String = System.getProperty("os.arch").lowercase()

    val isWindows: Boolean = osName.contains("win")
    val isMac: Boolean = osName.contains("mac")
    val isLinux: Boolean = osName.contains("nux") || osName.contains("nix")

    /**
     * 获取当前进程启动命令的绝对路径 (打包后即为原生可执行文件路径，如 .exe 或 launcher 脚本)
     */
    val currentExecutablePath: String by lazy {
        ProcessHandle.current().info().command().orElse("")
    }

    /**
     * 判断当前是否处于打包发布环境 (而非通过 java.exe / java 直接运行的开发环境)
     */
    val isPackaged: Boolean by lazy {
        val path = currentExecutablePath.lowercase()
        path.isNotEmpty() &&
                !path.endsWith("java") &&
                !path.endsWith("java.exe") &&
                !path.endsWith("javaw") &&
                !path.endsWith("javaw.exe")
    }

    /**
     * 应用本地数据目录，三端统一放在用户主目录下
     */
    val appDataDir: File by lazy {
        File(System.getProperty("user.home"), ".scrcpy-go")
    }

    /**
     * TCP 成功连接的历史记录文件
     */
    val tcpHistoryFile: File by lazy {
        File(appDataDir, "tcp-history.properties")
    }

    /**
     * “跳过此版本”的更新提示记录文件
     */
    val updateSkipFile: File by lazy {
        File(appDataDir, "update-skip.properties")
    }

    /**
     * 获取 adb 或 scrcpy 可执行程序的绝对路径
     * 支持开发环境调试与打包后的生产环境运行
     */
    fun getBinaryPath(binaryName: String): String {
        val exeName = if (isWindows) "$binaryName.exe" else binaryName
        
        // 1. 优先读取 compose 桌面打包注入的资源路径
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (!resourcesDir.isNullOrBlank()) {
            val binFile = File(resourcesDir, "bin/$exeName")
            if (binFile.exists()) {
                ensureExecutable(binFile)
                return binFile.absolutePath
            }
        }
        
        // 2. 开发环境下，检测 IDE 工程中的 external-resources 资源路径
        val projectDir = File(System.getProperty("user.dir"))
        val composePlatformName = when {
            isWindows -> "windows"
            isMac -> "macOS"
            isLinux -> "linux"
            else -> "common"
        }
        val devBinFile = File(projectDir, "src/desktopMain/external-resources/$composePlatformName/bin/$exeName")
        if (devBinFile.exists()) {
            ensureExecutable(devBinFile)
            return devBinFile.absolutePath
        }

        // 3. 备选回退：检测项目根目录 libs 下平台专属的未打包文件夹
        val platformFolder = when {
            isWindows -> if (osArch.contains("64")) "scrcpy-win64-v4.1" else "scrcpy-win32-v4.1"
            isMac -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "scrcpy-macos-aarch64-v4.1" else "scrcpy-macos-x86_64-v4.1"
            isLinux -> "scrcpy-linux-x86_64-v4.1"
            else -> null
        }
        if (platformFolder != null) {
            val backupBinFile = File(projectDir, "libs/$platformFolder/$exeName")
            if (backupBinFile.exists()) {
                ensureExecutable(backupBinFile)
                return backupBinFile.absolutePath
            }
        }
        
        // 4. 最后回退：直接使用系统 PATH 环境中的程序名称
        return binaryName
    }

    /**
     * 确保二进制文件具有可执行权限 (针对 Linux/Mac)
     */
    private fun ensureExecutable(file: File) {
        if (!isWindows && file.exists() && !file.canExecute()) {
            try {
                file.setExecutable(true)
            } catch (e: Exception) {
                // 忽略权限设置失败，可能是因为已经是可执行状态
            }
        }
    }

    /**
     * 获取 scrcpy-server 的路径
     */
    fun getScrcpyServerPath(): String? {
        val serverName = "scrcpy-server"
        
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (!resourcesDir.isNullOrBlank()) {
            val serverFile = File(resourcesDir, "bin/$serverName")
            if (serverFile.exists()) return serverFile.absolutePath
        }
        
        val projectDir = File(System.getProperty("user.dir"))
        val composePlatformName = when {
            isWindows -> "windows"
            isMac -> "macOS"
            isLinux -> "linux"
            else -> "common"
        }
        val devServerFile = File(projectDir, "src/desktopMain/external-resources/$composePlatformName/bin/$serverName")
        if (devServerFile.exists()) return devServerFile.absolutePath

        val platformFolder = when {
            isWindows -> if (osArch.contains("64")) "scrcpy-win64-v4.1" else "scrcpy-win32-v4.1"
            isMac -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "scrcpy-macos-aarch64-v4.1" else "scrcpy-macos-x86_64-v4.1"
            isLinux -> "scrcpy-linux-x86_64-v4.1"
            else -> null
        }
        if (platformFolder != null) {
            val backupServerFile = File(projectDir, "libs/$platformFolder/$serverName")
            if (backupServerFile.exists()) return backupServerFile.absolutePath
        }

        return null
    }

    /**
     * 使用系统默认浏览器打开链接。
     * 无桌面环境、无默认浏览器或调用异常时返回 false，由调用方决定如何提示。
     */
    fun openUrl(url: String): Boolean {
        return try {
            if (!Desktop.isDesktopSupported()) {
                false
            } else {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI(url))
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 一键生成桌面快捷方式
     */
    fun createDesktopShortcut(appJarOrExePath: String, appName: String): Result<String> {
        return try {
            if (isLinux) {
                createLinuxShortcut(appJarOrExePath, appName)
            } else if (isWindows) {
                createWindowsShortcut(appJarOrExePath, appName)
            } else {
                Result.failure(UnsupportedOperationException("当前操作系统不支持应用内快捷方式注册"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 在 Linux 下创建 .desktop 文件并注册至应用菜单
     */
    private fun createLinuxShortcut(execPath: String, appName: String): Result<String> {
        val desktopDir = File(System.getProperty("user.home"), ".local/share/applications")
        if (!desktopDir.exists()) {
            desktopDir.mkdirs()
        }
        val desktopFile = File(desktopDir, "${appName.lowercase()}.desktop")
        
        // 寻找应用图标路径
        // 1. 优先定位打包后的原生图标路径 (相对于可执行程序 bin/scrcpy-go，图标通常在 lib/scrcpy-go.png)
        val exeFile = File(execPath)
        val appDir = exeFile.parentFile?.parentFile
        var iconFile = File(appDir, "lib/${appName.lowercase()}.png")
        
        // 2. 备选：如果不存在，则尝试在开发环境的 user.dir 寻找 scrcpy.png
        if (!iconFile.exists()) {
            val projectDir = File(System.getProperty("user.dir"))
            iconFile = File(projectDir, "scrcpy.png")
        }
        
        val iconPath = if (iconFile.exists()) {
            iconFile.absolutePath
        } else {
            "phone" // 系统默认手机类图标回退
        }
        
        val content = """
            [Desktop Entry]
            Version=1.0
            Type=Application
            Name=$appName
            Comment=Scrcpy 跨平台快捷连接器
            Exec=$execPath
            Icon=$iconPath
            Terminal=false
            Categories=Utility;Development;
            StartupNotify=false
            StartupWMClass=${appName.lowercase()}
        """.trimIndent()
        
        FileWriter(desktopFile).use { writer ->
            writer.write(content)
        }
        desktopFile.setExecutable(true)
        return Result.success("已成功将快捷工具注册至应用菜单！\n保存位置: ${desktopFile.absolutePath}")
    }

    /**
     * 在 Windows 下通过动态生成并调用 VBScript 创建桌面快捷方式
     */
    private fun createWindowsShortcut(execPath: String, appName: String): Result<String> {
        val desktopPath = File(System.getProperty("user.home"), "Desktop").absolutePath
        val vbsFile = File(System.getProperty("java.io.tmpdir"), "create_shortcut.vbs")
        
        val lnkPath = File(desktopPath, "$appName.lnk").absolutePath
        val projectDir = File(System.getProperty("user.dir"))
        var iconPath = File(projectDir, "scrcpy.png").absolutePath
        if (!File(iconPath).exists()) {
            iconPath = ""
        }

        val vbsContent = """
            Set oWS = WScript.CreateObject("WScript.Shell")
            sLinkFile = "$lnkPath"
            Set oLink = oWS.CreateShortcut(sLinkFile)
            oLink.TargetPath = "$execPath"
            oLink.WorkingDirectory = "${File(execPath).parent}"
            oLink.Description = "Scrcpy 跨平台快捷连接器"
            ${if (iconPath.isNotEmpty()) "oLink.IconLocation = \"$iconPath\"" else ""}
            oLink.Save
        """.trimIndent()

        FileWriter(vbsFile).use { writer ->
            writer.write(vbsContent)
        }

        // 执行 VBScript 脚本以生成快捷方式
        val process = ProcessBuilder("wscript.exe", vbsFile.absolutePath).start()
        val exitCode = process.waitFor()
        vbsFile.delete()
        
        return if (exitCode == 0) {
            Result.success("已成功在 Windows 桌面创建快捷方式！")
        } else {
            Result.failure(RuntimeException("Windows 快捷方式创建失败，进程返回码: $exitCode"))
        }
    }
}
