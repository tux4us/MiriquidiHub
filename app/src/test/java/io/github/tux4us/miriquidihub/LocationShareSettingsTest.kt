package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationShareSettingsTest {

    @Test
    fun window_sameDay() {
        assertTrue(LocationShareSettings.isWithinWindow(8 * 60, 8 * 60, 20 * 60))
        assertTrue(LocationShareSettings.isWithinWindow(19 * 60 + 59, 8 * 60, 20 * 60))
        assertFalse(LocationShareSettings.isWithinWindow(20 * 60, 8 * 60, 20 * 60))
        assertFalse(LocationShareSettings.isWithinWindow(7 * 60, 8 * 60, 20 * 60))
    }

    @Test
    fun window_overMidnight() {
        assertTrue(LocationShareSettings.isWithinWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(LocationShareSettings.isWithinWindow(5 * 60, 22 * 60, 6 * 60))
        assertFalse(LocationShareSettings.isWithinWindow(12 * 60, 22 * 60, 6 * 60))
    }

    @Test
    fun window_equalBoundsMeansAllDay() {
        assertTrue(LocationShareSettings.isWithinWindow(3 * 60, 8 * 60, 8 * 60))
    }

    @Test
    fun formatMinute_padsZeros() {
        assertEquals("08:05", LocationShareSettings.formatMinute(8 * 60 + 5))
        assertEquals("00:00", LocationShareSettings.formatMinute(0))
    }
}
