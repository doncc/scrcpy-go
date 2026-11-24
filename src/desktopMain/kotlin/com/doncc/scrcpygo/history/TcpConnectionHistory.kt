package com.doncc.scrcpygo.history

import java.util.Locale

/**
 * 一条 TCP 成功连接的历史记录
 */
data class TcpConnectionRecord(
    val address: String,
    val model: String?,
    val lastConnectedAt: Long,
    val successCount: Int
)

/**
 * 历史记录写入结果：records 为写入后的内存列表，persisted 表示是否成功落盘
 */
data class HistoryWriteResult(
    val records: List<TcpConnectionRecord>,
    val persisted: Boolean
)

/**
 * TCP 历史记录的纯规则集合，便于单元测试
 */
object TcpConnectionHistoryRules {
    const val DEFAULT_PORT = 5555
    const val MAX_RECORDS = 20

    /**
     * 规范化地址：去空白、补默认端口、主机名统一小写。
     * 输入为空时返回 null；IPv6 属于现有手动连接逻辑未覆盖的场景，这里不做特殊处理。
     */
    fun normalizeAddress(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withPort = if (trimmed.contains(':')) trimmed else "$trimmed:$DEFAULT_PORT"
        return withPort.lowercase(Locale.ROOT)
    }

    /**
     * 解析 adb connect 的输出判断是否连接成功。
     * 兼容 already connected to 的成功场景，同时排除 failed/cannot 等失败文本。
     */
    fun isConnectSuccess(stdout: String): Boolean {
        val lower = stdout.lowercase(Locale.ROOT)
        return lower.contains("connected to") &&
            !lower.contains("failed") &&
            !lower.contains("cannot")
    }
}
