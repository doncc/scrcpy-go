package com.doncc.scrcpygo.update

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateSkipStoreTest {
    private lateinit var tempDir: File
    private lateinit var skipFile: File
    private lateinit var store: UpdateSkipStore

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("scrcpy-go-update-skip-test").toFile()
        skipFile = File(tempDir, "update-skip.properties")
        store = UpdateSkipStore(skipFile)
    }

    @AfterTest
    fun tearDown() {
        tempDir.setWritable(true)
        tempDir.deleteRecursively()
    }

    @Test
    fun loadReturnsNullWhenFileMissing() {
        assertNull(store.load())
    }

    @Test
    fun savesAndLoadsVersion() {
        assertTrue(store.save("1.1.0"))
        assertEquals("1.1.0", store.load())
        assertFalse(File(tempDir, "update-skip.properties.tmp").exists())
    }

    @Test
    fun corruptFileIsTreatedAsNotSkipped() {
        skipFile.writeText("skippedVersion=\\uZZZZ")
        assertNull(store.load())
    }

    @Test
    fun returnsFalseWhenTargetPathCannotBeWritten() {
        // Windows 上给目录设置只读属性并不会阻止在其中创建文件，无法模拟“目录不可写”；
        // 改为让目标路径本身被一个目录占住：临时文件写入成功，最后一步移动必然失败，
        // 这样 Windows / Linux / macOS 都能稳定进入写入失败分支。
        skipFile.mkdirs()

        val blockedStore = UpdateSkipStore(skipFile)

        assertFalse(blockedStore.save("1.1.0"))
        assertFalse(File(tempDir, "update-skip.properties.tmp").exists())
    }
}
