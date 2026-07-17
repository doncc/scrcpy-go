package com.doncc.scrcpygo.utils

import java.util.Properties

/**
 * 软件版本管理工具类
 * 负责从 classpath 资源包中读取编译期动态注入的 version.properties 版本文件
 */
object VersionUtils {
    val version: String by lazy {
        try {
            val properties = Properties()
            // 利用 ClassLoader 载入运行时属性文件
            val stream = VersionUtils::class.java.classLoader.getResourceAsStream("version.properties")
            if (stream != null) {
                properties.load(stream)
                properties.getProperty("version", "1.0.0").trim()
            } else {
                "1.0.0"
            }
        } catch (e: Exception) {
            "1.0.0"
        }
    }
}
