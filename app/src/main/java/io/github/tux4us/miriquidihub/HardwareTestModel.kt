package io.github.tux4us.miriquidihub

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.DateFormat
import java.util.Date

enum class HwStatus { PENDING, PASSED, FAILED, SKIPPED }

data class HwResult(val status: HwStatus, val detail: String = "")

/**
 * Alle Einzeltests des Hardware-Selbsttests. Reihenfolge = Anzeigereihenfolge:
 * zuerst die automatischen Tests, danach die Tests mit Nutzerinteraktion.
 */
enum class HwTestId(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val automatic: Boolean
) {
    FEATURES(R.string.hw_features_title, R.string.hw_features_desc, true),
    SENSORS(R.string.hw_sensors_title, R.string.hw_sensors_desc, true),
    BATTERY(R.string.hw_battery_title, R.string.hw_battery_desc, true),
    TOUCH(R.string.hw_touch_title, R.string.hw_touch_desc, false),
    DISPLAY(R.string.hw_display_title, R.string.hw_display_desc, false),
    SPEAKER(R.string.hw_speaker_title, R.string.hw_speaker_desc, false),
    MICROPHONE(R.string.hw_mic_title, R.string.hw_mic_desc, false),
    VIBRATION(R.string.hw_vibration_title, R.string.hw_vibration_desc, false),
    VOLUME_KEYS(R.string.hw_keys_title, R.string.hw_keys_desc, false),
    CAMERA(R.string.hw_camera_title, R.string.hw_camera_desc, false),
    CHARGER(R.string.hw_charger_title, R.string.hw_charger_desc, false),
    GPS(R.string.hw_gps_title, R.string.hw_gps_desc, false),
    DISPLAY_ADVANCED(R.string.hw_dispadv_title, R.string.hw_dispadv_desc, false),
    COMPASS(R.string.hw_compass_title, R.string.hw_compass_desc, false),
    LIGHT(R.string.hw_light_title, R.string.hw_light_desc, false),
    PROXIMITY(R.string.hw_prox_title, R.string.hw_prox_desc, false),
    WIFI_SCAN(R.string.hw_wifiscan_title, R.string.hw_wifiscan_desc, false),
    BLUETOOTH_SCAN(R.string.hw_btscan_title, R.string.hw_btscan_desc, false),
    AUDIO_JACK(R.string.hw_jack_title, R.string.hw_jack_desc, false),
    BIOMETRIC(R.string.hw_bio_title, R.string.hw_bio_desc, false)
}

/** Archivierter Testlauf (Zusammenfassung, ohne Detailtexte). */
data class HwRunSnapshot(
    val timestamp: Long,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val total: Int,
    val results: Map<String, String>
)

/**
 * Speichert die zuletzt ermittelten Ergebnisse lokal (SharedPreferences), damit der
 * Geräte-Report sie als Abschnitt übernehmen kann, sowie einen Verlauf archivierter Läufe.
 * Es werden keine Standortdaten, Aufnahmen oder Kennungen gespeichert, nur Status,
 * Zeitpunkt und ein kurzer Detailtext.
 */
class HardwareTestStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("hardware_tests", Context.MODE_PRIVATE)
    private val gson = Gson()

    private fun key(id: HwTestId, field: String) = "${id.name}_$field"

    fun get(id: HwTestId): HwResult {
        val name = prefs.getString(key(id, "status"), null) ?: return HwResult(HwStatus.PENDING)
        val status = HwStatus.values().firstOrNull { it.name == name } ?: HwStatus.PENDING
        return HwResult(status, prefs.getString(key(id, "detail"), "") ?: "")
    }

    /** Zeitpunkt des letzten Laufs dieses Tests (0 = nie). */
    fun getTime(id: HwTestId): Long = prefs.getLong(key(id, "time"), 0L)

    fun put(id: HwTestId, result: HwResult) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(key(id, "status"), result.status.name)
            .putString(key(id, "detail"), result.detail)
            .putLong(key(id, "time"), now)
            .putLong("last_run", now)
            .apply()
    }

    /** Setzt die aktuellen Ergebnisse zurück. Der archivierte Verlauf bleibt erhalten. */
    fun clear() {
        val editor = prefs.edit()
        for (id in HwTestId.values()) {
            editor.remove(key(id, "status")).remove(key(id, "detail")).remove(key(id, "time"))
        }
        editor.remove("last_run").apply()
    }

    private fun lastRun(): Long = prefs.getLong("last_run", 0L)

    fun counts(): Counts {
        var passed = 0
        var failed = 0
        var skipped = 0
        var pending = 0
        for (id in HwTestId.values()) {
            when (get(id).status) {
                HwStatus.PASSED -> passed++
                HwStatus.FAILED -> failed++
                HwStatus.SKIPPED -> skipped++
                HwStatus.PENDING -> pending++
            }
        }
        return Counts(passed, failed, skipped, pending)
    }

    data class Counts(val passed: Int, val failed: Int, val skipped: Int, val pending: Int) {
        val total: Int get() = passed + failed + skipped + pending
        val executed: Int get() = passed + failed + skipped
    }

    // ------------------------------------------------------------------
    // Verlauf
    // ------------------------------------------------------------------

    fun history(): List<HwRunSnapshot> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            gson.fromJson<List<HwRunSnapshot>>(json, object : TypeToken<List<HwRunSnapshot>>() {}.type)
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Legt die aktuellen Ergebnisse als Eintrag im Verlauf ab. Gibt false zurück, wenn nichts zu archivieren ist. */
    fun archiveCurrent(): Boolean {
        val counts = counts()
        if (counts.executed == 0) return false
        val stamp = lastRun()
        val existing = history()
        if (existing.firstOrNull()?.timestamp == stamp) return false
        val results = HwTestId.values()
            .filter { get(it).status != HwStatus.PENDING }
            .associate { it.name to get(it).status.name }
        val snapshot = HwRunSnapshot(stamp, counts.passed, counts.failed, counts.skipped, counts.total, results)
        val updated = (listOf(snapshot) + existing).take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, gson.toJson(updated)).apply()
        return true
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /** Tests, deren Status sich zwischen [previous] und den aktuellen Ergebnissen unterscheidet. */
    fun changesSince(previous: HwRunSnapshot): List<Triple<HwTestId, String, HwStatus>> {
        val changes = mutableListOf<Triple<HwTestId, String, HwStatus>>()
        for (id in HwTestId.values()) {
            val now = get(id).status
            val before = previous.results[id.name] ?: HwStatus.PENDING.name
            if (now != HwStatus.PENDING && now.name != before) changes += Triple(id, before, now)
        }
        return changes
    }

    // ------------------------------------------------------------------
    // Textausgabe
    // ------------------------------------------------------------------

    private fun tag(status: HwStatus) = when (status) {
        HwStatus.PASSED -> "[OK]"
        HwStatus.FAILED -> "[FAIL]"
        HwStatus.SKIPPED -> "[SKIP]"
        HwStatus.PENDING -> "[-]"
    }

    /**
     * Textblock für den Geräte-Report im Stil der übrigen Abschnitte ("--- NAME ---").
     * Liefert null, wenn noch kein Test ausgeführt wurde. Nicht ausgeführte Tests
     * werden weggelassen.
     */
    fun formatReportBlock(context: Context): String? {
        val results = HwTestId.values()
            .map { it to get(it) }
            .filter { it.second.status != HwStatus.PENDING }
        if (results.isEmpty()) return null

        val counts = counts()
        val timestamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(lastRun()))
        val sb = StringBuilder()
        sb.append(context.getString(R.string.hw_report_section)).append('\n')
        sb.append(context.getString(R.string.hw_report_timestamp, timestamp)).append('\n')
        sb.append(context.getString(R.string.hw_report_summary, counts.passed, counts.executed, counts.failed)).append('\n')
        for ((id, result) in results) {
            sb.append(tag(result.status)).append(' ').append(context.getString(id.titleRes))
            if (result.detail.isNotBlank()) {
                sb.append(": ").append(result.detail.replace("\n", "; "))
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** Eigenständiges Markdown-Dokument mit Tabelle der Ergebnisse und dem Verlauf. */
    fun formatDocument(context: Context, appVersion: String): String {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val counts = counts()
        val sb = StringBuilder()
        sb.append("# ").append(context.getString(R.string.hw_doc_title)).append("\n\n")
        sb.append(context.getString(R.string.hw_doc_meta, appVersion, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
            .append("\n\n")
        if (lastRun() > 0) sb.append(context.getString(R.string.hw_report_timestamp, df.format(Date(lastRun())))).append("\n\n")
        sb.append(context.getString(R.string.hw_report_summary, counts.passed, counts.executed, counts.failed)).append("\n\n")
        sb.append("| ").append(context.getString(R.string.hw_doc_col_test)).append(" | ")
            .append(context.getString(R.string.hw_doc_col_status)).append(" | ")
            .append(context.getString(R.string.hw_doc_col_time)).append(" | ")
            .append(context.getString(R.string.hw_doc_col_detail)).append(" |\n")
        sb.append("|---|---|---|---|\n")
        for (id in HwTestId.values()) {
            val r = get(id)
            if (r.status == HwStatus.PENDING) continue
            val time = getTime(id).takeIf { it > 0 }?.let { df.format(Date(it)) } ?: "–"
            sb.append("| ").append(context.getString(id.titleRes)).append(" | ").append(tag(r.status)).append(" | ")
                .append(time).append(" | ").append(r.detail.replace("\n", "; ").replace("|", "/")).append(" |\n")
        }
        val history = history()
        if (history.isNotEmpty()) {
            sb.append("\n## ").append(context.getString(R.string.hw_doc_history)).append("\n\n")
            for (h in history) {
                sb.append("- ").append(df.format(Date(h.timestamp))).append(": ")
                    .append(context.getString(R.string.hw_history_line, h.passed, h.failed, h.skipped)).append('\n')
            }
        }
        return sb.toString()
    }

    companion object {
        private const val KEY_HISTORY = "history_json"
        private const val MAX_HISTORY = 20
    }
}
