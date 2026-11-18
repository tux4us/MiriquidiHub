package io.github.tux4us.miriquidihub

import java.util.Calendar
import java.util.TimeZone

/**
 * Filtert eine Logcat-Ausgabe im Format `-v threadtime -v uid` auf eine App und ein Zeitfenster.
 * Reine JVM-Logik ohne Android-Abhängigkeit.
 *
 * Zeilenformat: `MM-dd HH:mm:ss.SSS [UID] PID TID L TAG: Text`. Die UID-Spalte ist optional und
 * erscheint als Zahl oder als Name (`u0_a123`). Zeilen ohne diesen Aufbau (z. B. Fortsetzungen
 * von Stacktraces) gehören zum vorherigen Eintrag.
 */
object AppLogFilter {

    private val ENTRY = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(?:(\S+)\s+)?(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*)$""")
    private const val LEVELS = "VDIWEF"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Ein Logeintrag samt Fortsetzungszeilen. */
    class Entry(
        val timestamp: String,
        val uidToken: String?,
        val pid: Int,
        val level: Char,
        val message: String,
        val lines: MutableList<String>
    )

    /** Zahl und Alias-Name der UID, wie Logcat sie ausgeben kann. */
    fun uidAliases(uid: Int): Set<String> {
        val result = mutableSetOf(uid.toString())
        if (uid >= 10000) {
            val user = uid / 100000
            val app = uid % 100000 - 10000
            if (app >= 0) result += "u${user}_a$app"
        }
        return result
    }

    fun parse(lines: List<String>): List<Entry> {
        val entries = ArrayList<Entry>()
        for (line in lines) {
            val m = ENTRY.find(line)
            if (m != null) {
                val pid = m.groupValues[3].toIntOrNull() ?: -1
                entries += Entry(
                    timestamp = m.groupValues[1],
                    uidToken = m.groupValues[2].ifEmpty { null },
                    pid = pid,
                    level = m.groupValues[5].first(),
                    message = m.groupValues[6],
                    lines = mutableListOf(line)
                )
            } else if (entries.isNotEmpty()) {
                entries.last().lines += line
            }
        }
        return entries
    }

    /**
     * Wandelt `MM-dd HH:mm:ss.SSS` (ohne Jahr) in Epochen-Millisekunden um. Liegt das Ergebnis mehr
     * als einen Tag in der Zukunft, wird das Vorjahr angenommen (Jahreswechsel).
     */
    fun parseTimestamp(ts: String, nowMillis: Long, tz: TimeZone): Long {
        val month = ts.substring(0, 2).toInt()
        val day = ts.substring(3, 5).toInt()
        val hour = ts.substring(6, 8).toInt()
        val minute = ts.substring(9, 11).toInt()
        val second = ts.substring(12, 14).toInt()
        val millis = ts.substring(15, 18).toInt()
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = nowMillis
        val year = cal.get(Calendar.YEAR)
        fun at(y: Int): Long {
            cal.clear()
            cal.set(y, month - 1, day, hour, minute, second)
            cal.set(Calendar.MILLISECOND, millis)
            return cal.timeInMillis
        }
        val candidate = at(year)
        return if (candidate > nowMillis + DAY_MS) at(year - 1) else candidate
    }

    /** Prozess-IDs der App: aus der UID-Spalte sowie aus ActivityManager- und Absturzmeldungen. */
    fun pidsOf(entries: List<Entry>, packageName: String, uid: Int): Set<Int> {
        val aliases = uidAliases(uid)
        val pkg = Regex.escape(packageName)
        val start = Regex("""Start proc (\d+):$pkg[/:]""")
        val crash = Regex("""Process: $pkg, PID: (\d+)""")
        val pids = HashSet<Int>()
        for (e in entries) {
            if (e.uidToken != null && e.uidToken in aliases) pids += e.pid
            start.find(e.message)?.groupValues?.get(1)?.toIntOrNull()?.let { pids += it }
            crash.find(e.message)?.groupValues?.get(1)?.toIntOrNull()?.let { pids += it }
        }
        return pids
    }

    /**
     * Liefert die Zeilen der App im Zeitfenster [startMillis, endMillis] ab [minLevel].
     * Ein Eintrag gehört zur App, wenn seine UID passt, er zu einem Prozess der App gehört (nur wenn
     * die Zeile keine UID-Spalte hat) oder der Paketname in der Meldung steht.
     */
    fun filter(
        lines: List<String>,
        packageName: String,
        uid: Int,
        startMillis: Long,
        endMillis: Long,
        minLevel: Char,
        nowMillis: Long,
        tz: TimeZone
    ): List<String> {
        val entries = parse(lines)
        val aliases = uidAliases(uid)
        val pids = pidsOf(entries, packageName, uid)
        val min = LEVELS.indexOf(minLevel).coerceAtLeast(0)
        val out = ArrayList<String>()
        for (e in entries) {
            val belongs = when {
                e.uidToken != null && e.uidToken in aliases -> true
                e.uidToken == null && e.pid in pids -> true
                e.message.contains(packageName) -> true
                else -> false
            }
            if (!belongs) continue
            if (LEVELS.indexOf(e.level) < min) continue
            val t = parseTimestamp(e.timestamp, nowMillis, tz)
            if (t < startMillis || t > endMillis) continue
            out += e.lines
        }
        return out
    }
}
