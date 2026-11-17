package io.github.tux4us.miriquidihub

import android.content.Context

/** Zuletzt gewählte Verzögerung der Screenshot-Aufnahme. */
class ScreenshotSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("screenshot_settings", Context.MODE_PRIVATE)

    var delaySeconds: Int
        get() = prefs.getInt(KEY_DELAY, 0).let { if (it in DELAYS_S) it else 0 }
        set(value) = prefs.edit().putInt(KEY_DELAY, value).apply()

    companion object {
        private const val KEY_DELAY = "delay_s"
        val DELAYS_S = listOf(0, 3, 5, 10)
    }
}
