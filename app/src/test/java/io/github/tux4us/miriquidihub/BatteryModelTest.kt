package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatteryModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hour = 3_600_000L

    private fun s(t: Long, level: Int, charging: Boolean = false, temp: Int = 300) =
        BatterySample(t, level, temp, 4000, charging)

    @Test
    fun drainPerHour_usesOnlyDischargePairs() {
        val samples = listOf(s(0, 100), s(hour, 95), s(2 * hour, 90), s(3 * hour, 95, charging = true))
        assertEquals(5.0, BatteryStats.drainPerHour(samples)!!, 0.001)
    }

    @Test
    fun chargePerHour_usesOnlyChargePairs() {
        val samples = listOf(s(0, 20, true), s(hour / 2, 50, true), s(hour, 80, true))
        assertEquals(60.0, BatteryStats.chargePerHour(samples)!!, 0.001)
    }

    @Test
    fun rate_isNullForTooLittleData() {
        assertNull(BatteryStats.drainPerHour(emptyList()))
        assertNull(BatteryStats.drainPerHour(listOf(s(0, 90), s(60_000, 89))))
    }

    @Test
    fun rate_ignoresLargeGaps() {
        assertNull(BatteryStats.drainPerHour(listOf(s(0, 100), s(7 * hour, 50))))
    }

    @Test
    fun temperatureRange_returnsMinMax() {
        val range = BatteryStats.temperatureRange(listOf(s(0, 90, temp = 280), s(1, 90, temp = 395)))!!
        assertEquals(28.0f, range.first, 0.001f)
        assertEquals(39.5f, range.second, 0.001f)
        assertNull(BatteryStats.temperatureRange(emptyList()))
    }

    @Test
    fun store_addsDeduplicatesAndLimits() {
        val store = BatterySampleStore(File(tmp.root, "b.json"), maxSamples = 3)
        assertTrue(store.add(s(0, 90)))
        assertFalse(store.add(s(10_000, 90))) // gleicher Stand, zu früh
        assertTrue(store.add(s(20_000, 89))) // Stand geändert
        assertTrue(store.add(s(200_000, 89)))
        assertTrue(store.add(s(400_000, 88)))
        assertEquals(3, store.list().size)
        assertEquals(89, store.list().first().level)
        store.clear()
        assertTrue(store.list().isEmpty())
        assertNotNull(store)
    }
}
