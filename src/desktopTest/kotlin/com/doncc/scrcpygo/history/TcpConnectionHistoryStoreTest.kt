package com.doncc.scrcpygo.history

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TcpConnectionHistoryStoreTest {
    private lateinit var tempDir: File
    private lateinit var historyFile: File
    private lateinit var store: TcpConnectionHistoryStore

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("scrcpy-go-history-test").toFile()
        historyFile = File(tempDir, "tcp-history.properties")
        store = TcpConnectionHistoryStore(historyFile)
    }

    @AfterTest
    fun tearDown() {
        tempDir.setWritable(true)
        tempDir.deleteRecursively()
    }

    @Test
    fun normalizeAddressAddsDefaultPortAndLowercasesHost() {
        assertEquals("192.168.1.5:5555", TcpConnectionHistoryRules.normalizeAddress(" 192.168.1.5 "))
        assertEquals("192.168.1.5:5556", TcpConnectionHistoryRules.normalizeAddress("192.168.1.5:5556"))
        assertEquals("myphone.local:5555", TcpConnectionHistoryRules.normalizeAddress("MyPhone.local"))
        assertNull(TcpConnectionHistoryRules.normalizeAddress("   "))
    }

    @Test
    fun connectSuccessParsesAdbOutput() {
        assertTrue(TcpConnectionHistoryRules.isConnectSuccess("connected to 192.168.1.5:5555"))
        assertTrue(TcpConnectionHistoryRules.isConnectSuccess("already connected to 192.168.1.5:5555"))
        assertFalse(TcpConnectionHistoryRules.isConnectSuccess("failed to connect to 192.168.1.5:5555: Connection refused"))
        assertFalse(TcpConnectionHistoryRules.isConnectSuccess("cannot connect to 192.168.1.5:5555: No route to host"))
        assertFalse(TcpConnectionHistoryRules.isConnectSuccess(""))
    }

    @Test
    fun recordSuccessCreatesAndMergesRecords() {
        val first = store.recordSuccess("192.168.1.5", "Pixel 8", now = 1_000L)
        assertTrue(first.persisted)
        assertEquals(1, first.records.size)
        assertEquals("192.168.1.5:5555", first.records[0].address)
        assertEquals("Pixel 8", first.records[0].model)
        assertEquals(1, first.records[0].successCount)

        val second = store.recordSuccess("192.168.1.5:5555", "Pixel 8 Pro", now = 2_000L)
        assertTrue(second.persisted)
        assertEquals(1, second.records.size)
        assertEquals(2, second.records[0].successCount)
        assertEquals("Pixel 8 Pro", second.records[0].model)
        assertEquals(2_000L, second.records[0].lastConnectedAt)
    }

    @Test
    fun recordSuccessKeepsPreviousModelWhenNewModelIsBlank() {
        store.recordSuccess("10.0.0.1", "小米 14", now = 1_000L)
        val updated = store.recordSuccess("10.0.0.1", null, now = 2_000L)
        assertEquals("小米 14", updated.records[0].model)
    }

    @Test
    fun recordsAreSortedByLastConnectedTime() {
        store.recordSuccess("10.0.0.1", null, now = 1_000L)
        store.recordSuccess("10.0.0.2", null, now = 2_000L)
        assertEquals("10.0.0.2:5555", store.load().first().address)

        store.recordSuccess("10.0.0.1", null, now = 3_000L)
        val records = store.load()
        assertEquals("10.0.0.1:5555", records[0].address)
        assertEquals("10.0.0.2:5555", records[1].address)
    }

    @Test
    fun recordsAreLimitedToTwenty() {
        repeat(25) { index ->
            store.recordSuccess("10.0.0.${index + 1}", null, now = (index + 1) * 1_000L)
        }
        val records = store.load()
        assertEquals(TcpConnectionHistoryRules.MAX_RECORDS, records.size)
        assertEquals("10.0.0.25:5555", records.first().address)
        assertFalse(records.any { it.address == "10.0.0.5:5555" })
    }

    @Test
    fun recordsSurviveReloadWithChineseModel() {
        store.recordSuccess("192.168.1.5", "小米 14", now = 1_000L)
        val reloaded = TcpConnectionHistoryStore(historyFile).load()
        assertEquals(1, reloaded.size)
        assertEquals("小米 14", reloaded[0].model)
        assertEquals(1, reloaded[0].successCount)
    }

    @Test
    fun missingFileReturnsEmptyList() {
        val missingStore = TcpConnectionHistoryStore(File(tempDir, "missing.properties"))
        assertTrue(missingStore.load().isEmpty())
    }

    @Test
    fun corruptFileIsQuarantinedAndReturnsEmptyList() {
        // 非法 Unicode 转义会让 Properties.load 抛出异常，用于模拟损坏文件
        historyFile.writeText("record.0.address=\\uZZZZ")
        assertTrue(store.load().isEmpty())
        assertFalse(historyFile.exists())
        assertTrue(
            tempDir.listFiles()?.any { it.name.startsWith("tcp-history.properties.corrupt-") } == true
        )
    }

    @Test
    fun deleteAndClearArePersisted() {
        store.recordSuccess("10.0.0.1", null, now = 1_000L)
        store.recordSuccess("10.0.0.2", null, now = 2_000L)

        val removed = store.remove("10.0.0.1")
        assertTrue(removed.persisted)
        assertEquals(1, removed.records.size)
        assertEquals(1, store.load().size)

        val cleared = store.clear()
        assertTrue(cleared.persisted)
        assertTrue(cleared.records.isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun writeFailureKeepsRecordsInMemory() {
        tempDir.setWritable(false)
        try {
            if (tempDir.canWrite()) return // root 等特殊环境下无法模拟只读目录
            val result = store.recordSuccess("10.0.0.1", null, now = 1_000L)
            assertFalse(result.persisted)
            assertEquals(1, result.records.size)
        } finally {
            tempDir.setWritable(true)
        }
    }
}
