package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageHistoryTest {

    @Test
    fun merge_replacesSameDayAndSorts() {
        val list = listOf(StorageSnapshot("2026-01-02", 5, 10), StorageSnapshot("2026-01-01", 4, 10))
        val merged = StorageHistory.merge(list, StorageSnapshot("2026-01-02", 6, 10))
        assertEquals(listOf("2026-01-01", "2026-01-02"), merged.map { it.day })
        assertEquals(6L, merged.last().usedBytes)
    }

    @Test
    fun merge_limitsToMaxDays() {
        var list = emptyList<StorageSnapshot>()
        for (i in 1..(StorageHistory.MAX_DAYS + 5)) {
            list = StorageHistory.merge(list, StorageSnapshot("2026-%04d".format(i), i.toLong(), 1000))
        }
        assertEquals(StorageHistory.MAX_DAYS, list.size)
        assertEquals(6L, list.first().usedBytes)
    }
}
