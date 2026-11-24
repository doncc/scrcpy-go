package com.doncc.scrcpygo.history

import com.doncc.scrcpygo.utils.PlatformUtils
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * TCP 成功连接历史的本地存储。
 * 使用 Java Properties 文件，避免引入额外的序列化依赖；所有公开方法内部加锁，保证单进程内线程安全。
 */
class TcpConnectionHistoryStore(
    private val historyFile: File = PlatformUtils.tcpHistoryFile
) {
    private val lock = Any()

    /**
     * 读取历史列表，按最近成功连接时间倒序；文件不存在或损坏时返回空列表。
     */
    fun load(): List<TcpConnectionRecord> = synchronized(lock) {
        readRecords()
    }

    /**
     * 记录一次成功连接：同地址合并、次数累加、时间更新并置顶。
     */
    fun recordSuccess(
        address: String,
        model: String?,
        now: Long = System.currentTimeMillis()
    ): HistoryWriteResult = synchronized(lock) {
        val normalized = TcpConnectionHistoryRules.normalizeAddress(address)
            ?: return HistoryWriteResult(readRecords(), false)
        val existing = readRecords()
        val previous = existing.firstOrNull { it.address == normalized }
        val newRecord = TcpConnectionRecord(
            address = normalized,
            model = model?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.model,
            lastConnectedAt = now,
            successCount = (previous?.successCount ?: 0) + 1
        )
        val updated = (listOf(newRecord) + existing.filterNot { it.address == normalized })
            .sortedByDescending { it.lastConnectedAt }
            .take(TcpConnectionHistoryRules.MAX_RECORDS)
        HistoryWriteResult(updated, writeRecords(updated))
    }

    /**
     * 删除一条历史记录
     */
    fun remove(address: String): HistoryWriteResult = synchronized(lock) {
        val normalized = TcpConnectionHistoryRules.normalizeAddress(address)
            ?: return HistoryWriteResult(readRecords(), false)
        val updated = readRecords().filterNot { it.address == normalized }
        HistoryWriteResult(updated, writeRecords(updated))
    }

    /**
     * 清空全部历史记录
     */
    fun clear(): HistoryWriteResult = synchronized(lock) {
        HistoryWriteResult(emptyList(), writeRecords(emptyList()))
    }

    /**
     * 从文件读取记录；任何解析异常都按损坏文件处理，不影响应用启动。
     */
    private fun readRecords(): List<TcpConnectionRecord> {
        val target = historyFile.absoluteFile
        if (!target.exists()) return emptyList()

        val properties = Properties()
        try {
            target.inputStream().use { properties.load(it) }
        } catch (e: Exception) {
            // 文件损坏时移走原文件，避免反复解析失败，同时保留现场供排查
            quarantineCorruptFile(target)
            return emptyList()
        }

        val indices = properties.stringPropertyNames()
            .mapNotNull { name ->
                RECORD_ADDRESS_PATTERN.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
            }
            .toSet()

        val recordsByAddress = LinkedHashMap<String, TcpConnectionRecord>()
        indices.sorted().forEach { index ->
            val address = properties.getProperty("record.$index.address")?.trim().orEmpty()
            if (address.isEmpty()) return@forEach
            val normalized = TcpConnectionHistoryRules.normalizeAddress(address) ?: return@forEach
            val model = properties.getProperty("record.$index.model")?.takeIf { it.isNotBlank() }
            val lastConnectedAt = properties.getProperty("record.$index.lastConnectedAt")?.toLongOrNull() ?: 0L
            val successCount = properties.getProperty("record.$index.successCount")
                ?.toIntOrNull()
                ?.coerceAtLeast(1)
                ?: 1
            val record = TcpConnectionRecord(normalized, model, lastConnectedAt, successCount)
            val existing = recordsByAddress[normalized]
            recordsByAddress[normalized] = if (existing == null) {
                record
            } else {
                TcpConnectionRecord(
                    address = normalized,
                    model = record.model ?: existing.model,
                    lastConnectedAt = maxOf(existing.lastConnectedAt, record.lastConnectedAt),
                    successCount = maxOf(existing.successCount, record.successCount)
                )
            }
        }

        return recordsByAddress.values
            .sortedByDescending { it.lastConnectedAt }
            .take(TcpConnectionHistoryRules.MAX_RECORDS)
    }

    /**
     * 将记录写入 Properties 文件；先写临时文件再原子替换。
     */
    private fun writeRecords(records: List<TcpConnectionRecord>): Boolean {
        val target = historyFile.absoluteFile
        val directory = target.parentFile ?: return false
        if (!directory.exists() && !directory.mkdirs()) return false

        return try {
            val properties = Properties()
            properties.setProperty("format.version", FORMAT_VERSION)
            records.take(TcpConnectionHistoryRules.MAX_RECORDS).forEachIndexed { index, record ->
                properties.setProperty("record.$index.address", record.address)
                record.model?.takeIf { it.isNotBlank() }?.let {
                    properties.setProperty("record.$index.model", it)
                }
                properties.setProperty("record.$index.lastConnectedAt", record.lastConnectedAt.toString())
                properties.setProperty("record.$index.successCount", record.successCount.toString())
            }

            val tempFile = File(directory, "${target.name}.tmp")
            try {
                tempFile.outputStream().use { output ->
                    properties.store(output, "scrcpy-go TCP connection history")
                }
                try {
                    Files.move(
                        tempFile.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
            true
        } catch (e: Exception) {
            // 磁盘不可写等异常不应影响主流程，交给调用方提示
            false
        }
    }

    /**
     * 损坏文件改名备份，保留原始内容
     */
    private fun quarantineCorruptFile(target: File) {
        val backup = File(target.parentFile, "${target.name}.corrupt-${System.currentTimeMillis()}")
        try {
            if (!target.renameTo(backup)) {
                target.copyTo(backup, overwrite = true)
            }
        } catch (e: Exception) {
            // 备份失败时保留原文件，避免进一步破坏
        }
    }

    companion object {
        private const val FORMAT_VERSION = "1"
        private val RECORD_ADDRESS_PATTERN = Regex("^record\\.(\\d+)\\.address$")
    }
}
