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
    fun returnsFalseWhenDirectoryIsNotWritable() {
        val readOnlyDir = File(tempDir, "read-only").apply { mkdirs() }
        readOnlyDir.setWritable(false)
        try {
            val readOnlyStore = UpdateSkipStore(File(readOnlyDir, "update-skip.properties"))
            assertFalse(readOnlyStore.save("1.1.0"))
        } finally {
            readOnlyDir.setWritable(true)
        }
    }
}
