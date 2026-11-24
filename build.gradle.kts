import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.0.0"
    id("org.jetbrains.compose") version "1.6.11"
    kotlin("plugin.compose") version "2.0.0"
}

val appVersion = project.property("app.version") as String

// 编译和资源打包前自动生成 version.properties 存入 resources
val generateVersionProperties = tasks.register("generateVersionProperties") {
    group = "compose"
    val outputDir = file("src/desktopMain/resources")
    inputs.property("version", appVersion)
    outputs.dir(outputDir)
    
    doLast {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val propertiesFile = file("src/desktopMain/resources/version.properties")
        propertiesFile.writeText("version=$appVersion\n")
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    // 强制声明使用完整且含有 jlink/jpackage 编译器的标准 JDK 21 工具链。
    // 这能防范部分 IDE 自带的精简版 Runtime (如 JBR) 缺少打包依赖导致的 :checkRuntime 构建失败。
    jvmToolchain(21)

    jvm("desktop") {
        compilations.all {
            kotlinOptions.jvmTarget = "21"
        }
    }
    
    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.ui)
                // 更新提示条的滑入滑出动画
                implementation(compose.animation)
                
                // 协程支持
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// 动态检测当前平台与架构，配置对应的二进制拷贝
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val platformFolder = when {
    osName.contains("win") -> if (osArch.contains("64")) "scrcpy-win64-v4.1" else "scrcpy-win32-v4.1"
    osName.contains("mac") -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "scrcpy-macos-aarch64-v4.1" else "scrcpy-macos-x86_64-v4.1"
    osName.contains("nux") || osName.contains("nix") -> "scrcpy-linux-x86_64-v4.1"
    else -> null
}

// 映射为 Compose Desktop 标准外部资源子目录名称
val composePlatformName = when {
    osName.contains("win") -> "windows"
    osName.contains("mac") -> "macOS"
    osName.contains("nux") || osName.contains("nix") -> "linux"
    else -> null
}

// 注册拷贝特定平台 adb/scrcpy 二进制至 external-resources 的任务
val copyPlatformBinaries = tasks.register<Copy>("copyPlatformBinaries") {
    group = "compose"
    description = "Copy current platform native scrcpy/adb binaries to external-resources directory."
    
    if (platformFolder != null && composePlatformName != null) {
        val libsDir = file("libs/$platformFolder")
        if (libsDir.exists()) {
            from(libsDir) {
                // 过滤不需要的脚本或说明文件
                exclude("open_a_terminal_here.bat")
                exclude("scrcpy-noconsole.vbs")
                exclude("LICENSE")
                exclude("LICENSE.txt")
                exclude("scrcpy.1")
            }
            into(file("src/desktopMain/external-resources/$composePlatformName/bin"))
        }
    }
}

// 保证在每次运行、打包或资源收集任务执行前，完成二进制文件拷贝及版本文件生成
tasks.matching { 
    it.name.startsWith("run") || 
    it.name.contains("package") || 
    it.name.contains("createDistributable") || 
    it.name.contains("prepareAppResources") ||
    it.name.contains("processResources", ignoreCase = true)
}.configureEach {
    dependsOn(copyPlatformBinaries)
    dependsOn(generateVersionProperties)
}

compose.desktop {
    application {
        mainClass = "com.doncc.scrcpygo.MainKt"
        
        nativeDistributions {
            // 更新检测使用 JDK 内置 HttpClient，jlink 打包默认不含该模块，必须显式声明
            modules("java.net.http")

            // 配置外部资源根目录，打包时会自动将此目录下的所有内容加入 App 根目录下的 resources 中，不打进 JAR
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/desktopMain/external-resources"))
            
            // 支持生成 dmg，msi 以及 deb 包
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "scrcpy-go"
            packageVersion = appVersion
            
            description = "Android Screen Mirroring Wrapped in Compose Desktop"
            copyright = "© 2026 doncc. All rights reserved."
            
            // 各个平台打包配置的微调
            macOS {
                bundleID = "com.doncc.scrcpygo"
                iconFile.set(project.file("src/desktopMain/resources/icons/icon.icns"))
            }
            windows {
                menu = true
                shortcut = true
                iconFile.set(project.file("src/desktopMain/resources/icons/icon.ico"))
            }
            linux {
                shortcut = true
                iconFile.set(project.file("src/desktopMain/resources/icons/icon.png"))
            }
        }
    }
}

// 注册跨平台绿色免安装压缩包打包任务
val packageZip = tasks.register<Zip>("packageZip") {
    group = "compose"
    description = "Package the distributable application into a zip archive."
    // 依赖 Compose 默认的绿色版可执行程序生成任务
    dependsOn("createDistributable")
    
    // 动态判断当前打包平台类型与系统架构
    val platformName = composePlatformName ?: "unknown"
    val archName = if (osArch.contains("aarch64") || osArch.contains("arm64")) "aarch64" else "x64"
    
    // 设置压缩包的基础名称、分类标识和版本号
    archiveBaseName.set("scrcpy-go")
    archiveClassifier.set("$platformName-$archName")
    archiveVersion.set(appVersion)
    
    // macOS 环境下生成的绿色版是 .app 文件夹，需要完整打包 .app 目录
    if (platformName.equals("macOS", ignoreCase = true)) {
        from("build/compose/binaries/main/app") {
            include("scrcpy-go.app/**")
        }
    } else {
        // Windows/Linux 环境下打包 scrcpy-go 整个可执行程序目录，并放入解压后的 scrcpy-go 根目录下
        from("build/compose/binaries/main/app/scrcpy-go") {
            into("scrcpy-go")
        }
    }
    
    // 设置输出目录为 build/distributions
    destinationDirectory.set(file("build/distributions"))
}

