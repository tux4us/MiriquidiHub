package io.github.tux4us.miriquidihub

import java.security.MessageDigest
import kotlin.math.abs

/** Geschwindigkeit aus zwei Zählerständen (z. B. TrafficStats). */
object RateCalculator {
    /** Bytes pro Sekunde; 0 bei zurückgesetztem Zähler, unbekanntem Wert (-1) oder Zeitdifferenz ≤ 0. */
    fun bytesPerSecond(previous: Long, current: Long, deltaMillis: Long): Long {
        if (previous < 0 || current < 0 || deltaMillis <= 0 || current < previous) return 0L
        return (current - previous) * 1000L / deltaMillis
    }
}

/** Auswertung der WLAN-Kanalbelegung. */
object ChannelAnalysis {

    data class Ap(val channel: Int, val rssi: Int)

    /** Belegung eines 2,4-GHz-Kanals: benachbarte Kanäle (≤ 3 Kanäle Abstand) überlappen; starke Netze zählen doppelt. */
    fun load24(aps: List<Ap>, channel: Int): Int =
        aps.filter { abs(it.channel - channel) <= 3 }.sumOf { if (it.rssi >= -70) 2 else 1 }

    /** Empfohlener Kanal aus 1, 6 und 11 (bei Gleichstand der niedrigste). */
    fun recommend24(aps: List<Ap>): Int = listOf(1, 6, 11).minByOrNull { load24(aps, it) } ?: 1

    fun countPerChannel(aps: List<Ap>): Map<Int, Int> =
        aps.groupingBy { it.channel }.eachCount().toSortedMap()
}

/** Regeln der Sicherheitsprüfung (Schwellen und Bewertung). */
object SecurityRules {

    fun patchStatus(ageDays: Long?): DiagStatus = when {
        ageDays == null -> DiagStatus.INFO
        ageDays <= 120 -> DiagStatus.OK
        ageDays <= 365 -> DiagStatus.WARN
        else -> DiagStatus.ERROR
    }

    fun verifiedBootStatus(state: String?): DiagStatus = when (state?.lowercase()) {
        "green" -> DiagStatus.OK
        "yellow", "orange" -> DiagStatus.WARN
        "red" -> DiagStatus.ERROR
        else -> DiagStatus.INFO
    }

    fun bootloaderStatus(state: String?): DiagStatus = when (state?.lowercase()) {
        "locked" -> DiagStatus.OK
        "unlocked" -> DiagStatus.WARN
        else -> DiagStatus.INFO
    }
}

/** Filter für `logcat -v threadtime`-Zeilen. */
object LogFilter {

    private val LEVEL_ORDER = "VDIWEF"
    private val LINE = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+([VDIWEF])\s""")

    fun levelOf(line: String): Char? = LINE.find(line)?.groupValues?.get(1)?.first()

    /** Behält Zeilen ab [minLevel]; Zeilen ohne Pegel (Fortsetzungen) folgen der vorherigen Entscheidung. */
    fun filter(lines: List<String>, minLevel: Char): List<String> {
        val min = LEVEL_ORDER.indexOf(minLevel).coerceAtLeast(0)
        val out = ArrayList<String>()
        var keep = false
        for (line in lines) {
            val level = levelOf(line)
            if (level != null) keep = LEVEL_ORDER.indexOf(level) >= min
            if (keep) out += line
        }
        return out
    }
}

object Hashing {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Die ersten 16 Hexzeichen in Vierergruppen, z. B. "1a2b 3c4d 5e6f 7a8b". */
    fun fingerprint(hex: String): String = hex.take(16).chunked(4).joinToString(" ")
}
