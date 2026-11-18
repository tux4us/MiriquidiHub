package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AppLogFilterTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long {
        val c = Calendar.getInstance(utc)
        c.clear()
        c.set(y, mo - 1, d, h, mi, s)
        return c.timeInMillis
    }

    private val now = millis(2026, 11, 18, 12, 0, 0)

    private val log = listOf(
        "11-18 11:50:00.000 u0_a123  1000  1000 I MyApp: früh",
        "11-18 11:56:00.000 u0_a123  1000  1000 E MyApp: Fehler",
        "11-18 11:56:00.100 u0_a123  1000  1000 E AndroidRuntime: FATAL EXCEPTION: main",
        "11-18 11:56:00.100 u0_a123  1000  1000 E AndroidRuntime: Process: com.example.app, PID: 1000",
        "\tat com.example.app.Main.run(Main.kt:1)",
        "11-18 11:56:01.000 u0_a999  2000  2000 E Other: fremd",
        "11-18 11:57:00.000 system   500   500 I ActivityManager: Start proc 3000:com.example.app/u0a123 for activity",
        "11-18 11:57:01.000 u0_a123  3000  3000 D MyApp: neuer Prozess",
        "11-18 11:58:00.000 system   500   500 I WindowManager: com.example.app visible",
        "11-18 11:58:30.000 system   500   500 I WindowManager: unrelated"
    )

    private fun run(start: Long = now - 15 * 60_000, level: Char = 'V') =
        AppLogFilter.filter(log, "com.example.app", 10123, start, now, level, now, utc)

    @Test fun uidAliasesContainNumberAndName() {
        assertEquals(setOf("10123", "u0_a123"), AppLogFilter.uidAliases(10123))
        assertEquals(setOf("1000"), AppLogFilter.uidAliases(1000))
        assertEquals(setOf("1010123", "u10_a123"), AppLogFilter.uidAliases(1010123))
    }

    @Test fun keepsOnlyAppEntriesAndContinuations() {
        val r = run()
        assertTrue(r.any { it.contains("Fehler") })
        assertTrue(r.any { it.startsWith("\tat com.example.app") })
        assertTrue(r.any { it.contains("neuer Prozess") })
        assertTrue(r.any { it.contains("Start proc 3000") })
        assertTrue(r.any { it.contains("visible") })
        assertTrue(r.none { it.contains("fremd") })
        assertTrue(r.none { it.contains("unrelated") })
    }

    @Test fun timeWindowCutsOldEntries() {
        val r = run(start = now - 5 * 60_000)
        assertTrue(r.none { it.contains("früh") })
        assertTrue(r.any { it.contains("Fehler") })
        val tooLate = AppLogFilter.filter(log, "com.example.app", 10123, now - 60_000, now, 'V', now, utc)
        assertTrue(tooLate.isEmpty())
    }

    @Test fun levelFilterAppliesToWholeEntry() {
        val r = run(level = 'E')
        assertTrue(r.none { it.contains("früh") })
        assertTrue(r.none { it.contains("neuer Prozess") })
        assertTrue(r.any { it.startsWith("\tat com.example.app") })
    }

    @Test fun pidsCollectedFromUidStartAndCrash() {
        val pids = AppLogFilter.pidsOf(AppLogFilter.parse(log), "com.example.app", 10123)
        assertEquals(setOf(1000, 3000), pids)
    }

    @Test fun linesWithoutUidColumnUseProcessIds() {
        val noUid = listOf(
            "11-18 11:57:00.000   500   500 I ActivityManager: Start proc 3000:com.example.app/u0a123 for activity",
            "11-18 11:57:01.000  3000  3000 D MyApp: ohne UID-Spalte",
            "11-18 11:57:02.000  4000  4000 D Other: fremd"
        )
        val r = AppLogFilter.filter(noUid, "com.example.app", 10123, now - 900_000, now, 'V', now, utc)
        assertEquals(2, r.size)
        assertTrue(r.none { it.contains("fremd") })
    }

    @Test fun timestampAcrossNewYearUsesPreviousYear() {
        val jan = millis(2027, 1, 1, 0, 10, 0)
        val t = AppLogFilter.parseTimestamp("12-31 23:55:00.000", jan, utc)
        assertEquals(millis(2026, 12, 31, 23, 55, 0), t)
    }

    @Test fun timestampMillisecondsParsed() {
        val t = AppLogFilter.parseTimestamp("11-18 11:56:00.250", now, utc)
        assertEquals(millis(2026, 11, 18, 11, 56, 0) + 250, t)
    }
}
