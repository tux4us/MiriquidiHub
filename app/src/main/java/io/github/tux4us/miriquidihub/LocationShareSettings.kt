package io.github.tux4us.miriquidihub

import android.content.Context

/**
 * Nicht geheime Einstellungen der Standortfreigabe: Sendeintervall und optionales Zeitfenster,
 * in dem gesendet wird. Die Passphrase liegt verschlüsselt in [WebDavPreferences].
 */
class LocationShareSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("location_share_settings", Context.MODE_PRIVATE)

    var intervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_S).let { if (it in INTERVALS_S) it else DEFAULT_INTERVAL_S }
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    var windowEnabled: Boolean
        get() = prefs.getBoolean(KEY_WINDOW_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_WINDOW_ON, value).apply()

    /** Beginn des Zeitfensters in Minuten seit Mitternacht. */
    var windowStartMinute: Int
        get() = prefs.getInt(KEY_WINDOW_START, 8 * 60)
        set(value) = prefs.edit().putInt(KEY_WINDOW_START, value.coerceIn(0, 1439)).apply()

    /** Ende des Zeitfensters in Minuten seit Mitternacht. */
    var windowEndMinute: Int
        get() = prefs.getInt(KEY_WINDOW_END, 20 * 60)
        set(value) = prefs.edit().putInt(KEY_WINDOW_END, value.coerceIn(0, 1439)).apply()

    /** True, wenn jetzt gesendet werden darf (Zeitfenster aus oder aktuelle Uhrzeit innerhalb). */
    fun isSendingAllowedNow(nowMinute: Int = currentMinuteOfDay()): Boolean =
        !windowEnabled || isWithinWindow(nowMinute, windowStartMinute, windowEndMinute)

    companion object {
        private const val KEY_INTERVAL = "interval_s"
        private const val KEY_WINDOW_ON = "window_on"
        private const val KEY_WINDOW_START = "window_start"
        private const val KEY_WINDOW_END = "window_end"
        const val DEFAULT_INTERVAL_S = 60
        val INTERVALS_S = listOf(30, 60, 120, 300)

        /**
         * Prüft, ob [now] im Fenster [start, end) liegt. Ein Fenster über Mitternacht
         * (start > end) wird unterstützt; start == end bedeutet ganztägig.
         */
        fun isWithinWindow(now: Int, start: Int, end: Int): Boolean = when {
            start == end -> true
            start < end -> now in start until end
            else -> now >= start || now < end
        }

        fun currentMinuteOfDay(): Int {
            val c = java.util.Calendar.getInstance()
            return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        }

        fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
    }
}
