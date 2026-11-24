package com.doncc.scrcpygo.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionComparatorTest {
    @Test
    fun extractsNumericVersionFromTag() {
        assertEquals("1.0.1", VersionComparator.extract("v1.0.1"))
        assertEquals("1.2.3", VersionComparator.extract("release-1.2.3"))
        assertEquals("1.0.0", VersionComparator.extract(" 1.0.0 "))
        assertNull(VersionComparator.extract("latest"))
    }

    @Test
    fun comparesSegmentsNumerically() {
        assertTrue(VersionComparator.compare("1.0.2", "1.0.1")!! > 0)
        assertTrue(VersionComparator.compare("1.0.1", "1.0.2")!! < 0)
        assertEquals(0, VersionComparator.compare("1.0", "1.0.0"))
        assertTrue(VersionComparator.compare("1.0.10", "1.0.9")!! > 0)
        assertTrue(VersionComparator.compare("2.0.0", "1.99.99")!! > 0)
    }

    @Test
    fun ignoresPrereleaseSuffix() {
        assertEquals(0, VersionComparator.compare("1.0.1-beta", "1.0.1"))
    }

    @Test
    fun returnsNullForUnparseableVersion() {
        assertNull(VersionComparator.compare("abc", "1.0.0"))
        assertNull(VersionComparator.isNewer("1.0.0", "abc"))
    }

    @Test
    fun isNewerIsStrict() {
        assertEquals(true, VersionComparator.isNewer("1.1.0", "1.0.2"))
        assertFalse(VersionComparator.isNewer("1.0.2", "1.0.2")!!)
        assertFalse(VersionComparator.isNewer("1.0.1", "1.0.2")!!)
    }
}
