package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLogicTest {

    @Test
    fun bytesPerSecond_handlesResetAndUnknown() {
        assertEquals(1000L, RateCalculator.bytesPerSecond(0, 2000, 2000))
        assertEquals(0L, RateCalculator.bytesPerSecond(5000, 100, 1000))
        assertEquals(0L, RateCalculator.bytesPerSecond(-1, 100, 1000))
        assertEquals(0L, RateCalculator.bytesPerSecond(0, 100, 0))
    }

    @Test
    fun recommend24_choosesLeastLoadedOf1_6_11() {
        val aps = listOf(
            ChannelAnalysis.Ap(1, -50), ChannelAnalysis.Ap(1, -60), ChannelAnalysis.Ap(6, -80), ChannelAnalysis.Ap(3, -60)
        )
        assertEquals(11, ChannelAnalysis.recommend24(aps))
        assertEquals(1, ChannelAnalysis.recommend24(emptyList()))
    }

    @Test
    fun load24_weightsStrongNetworksAndOverlap() {
        val aps = listOf(ChannelAnalysis.Ap(4, -50), ChannelAnalysis.Ap(9, -80), ChannelAnalysis.Ap(11, -80))
        assertEquals(2, ChannelAnalysis.load24(aps, 1)) // Kanal 4 überlappt (Abstand 3), Kanal 9 nicht
        assertEquals(2, ChannelAnalysis.load24(aps, 10)) // 9 und 11 mit je 1
    }

    @Test
    fun countPerChannel_isSorted() {
        val counts = ChannelAnalysis.countPerChannel(listOf(ChannelAnalysis.Ap(6, -1), ChannelAnalysis.Ap(1, -1), ChannelAnalysis.Ap(6, -1)))
        assertEquals(listOf(1, 6), counts.keys.toList())
        assertEquals(2, counts[6])
    }

    @Test
    fun securityRules_mapStates() {
        assertEquals(DiagStatus.OK, SecurityRules.patchStatus(30))
        assertEquals(DiagStatus.WARN, SecurityRules.patchStatus(200))
        assertEquals(DiagStatus.ERROR, SecurityRules.patchStatus(400))
        assertEquals(DiagStatus.INFO, SecurityRules.patchStatus(null))
        assertEquals(DiagStatus.OK, SecurityRules.verifiedBootStatus("green"))
        assertEquals(DiagStatus.WARN, SecurityRules.verifiedBootStatus("Orange"))
        assertEquals(DiagStatus.ERROR, SecurityRules.verifiedBootStatus("red"))
        assertEquals(DiagStatus.OK, SecurityRules.bootloaderStatus("locked"))
        assertEquals(DiagStatus.WARN, SecurityRules.bootloaderStatus("unlocked"))
    }

    @Test
    fun logFilter_keepsContinuationLinesOfKeptEntries() {
        val lines = listOf(
            "11-17 10:00:00.123  1000  1001 D Tag: debug",
            "11-17 10:00:01.123  1000  1001 E Tag: error",
            "    at stack line",
            "11-17 10:00:02.123  1000  1001 I Tag: info"
        )
        val result = LogFilter.filter(lines, 'W')
        assertEquals(listOf(lines[1], lines[2]), result)
        assertEquals('E', LogFilter.levelOf(lines[1]))
        assertEquals(null, LogFilter.levelOf(lines[2]))
        assertEquals(4, LogFilter.filter(lines, 'V').size)
    }

    @Test
    fun hashing_fingerprint() {
        val hex = Hashing.sha256Hex("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
        assertEquals("ba78 16bf 8f01 cfea", Hashing.fingerprint(hex))
        assertTrue(Hashing.fingerprint("ab").isNotEmpty())
    }
}
