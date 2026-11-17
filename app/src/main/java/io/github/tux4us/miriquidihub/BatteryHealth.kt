package io.github.tux4us.miriquidihub

import android.content.Context
import android.os.BatteryManager

/** Einheitliche Bezeichnung aller `BATTERY_HEALTH_*`-Werte für Report, Test und Monitor. */
object BatteryHealth {
    fun label(ctx: Context, health: Int): String = ctx.getString(
        when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> R.string.battery_health_good
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.battery_health_overheat
            BatteryManager.BATTERY_HEALTH_DEAD -> R.string.battery_health_dead
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.battery_health_over_voltage
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> R.string.battery_health_failure
            BatteryManager.BATTERY_HEALTH_COLD -> R.string.battery_health_cold
            else -> R.string.battery_health_unknown
        }
    )

    /** true bei allen Zuständen, die auf ein Problem hinweisen (nicht GOOD und nicht UNKNOWN). */
    fun isCritical(health: Int): Boolean =
        health != BatteryManager.BATTERY_HEALTH_GOOD && health != BatteryManager.BATTERY_HEALTH_UNKNOWN
}
