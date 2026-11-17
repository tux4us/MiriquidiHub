package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputTestTest {

    @Test
    fun onlyHttpsWithHostIsAllowed() {
        assertTrue(ThroughputTest.isAllowedUrl("https://example.org/file.bin"))
        assertFalse(ThroughputTest.isAllowedUrl("http://example.org/file.bin"))
        assertFalse(ThroughputTest.isAllowedUrl("ftp://example.org/file"))
        assertFalse(ThroughputTest.isAllowedUrl("https:///file"))
        assertFalse(ThroughputTest.isAllowedUrl("not a url"))
        assertFalse(ThroughputTest.isAllowedUrl(""))
    }

    @Test
    fun megabitPerSecond_isComputedFromBytesAndMillis() {
        val r = ThroughputResult(bytes = 12_500_000, millis = 1000, firstByteMillis = 10, httpCode = 200, completed = true)
        assertEquals(100.0, r.megabitPerSecond, 0.001)
        assertEquals(0.0, r.copy(millis = 0).megabitPerSecond, 0.0)
    }
}
