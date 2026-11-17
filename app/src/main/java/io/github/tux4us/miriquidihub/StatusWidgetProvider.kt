package io.github.tux4us.miriquidihub

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.widget.RemoteViews

/**
 * Homescreen-Widget mit Akkustand, freiem Speicher und Arbeitsspeicher-Belegung. Aktualisiert
 * sich über das System (alle 30 Minuten, ohne eigenen Hintergrunddienst); Antippen öffnet die App.
 */
class StatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, buildViews(context))
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_status)

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        views.setTextViewText(
            R.id.widgetBattery,
            if (batteryPercent >= 0) context.getString(R.string.widget_battery, batteryPercent) else context.getString(R.string.widget_unknown)
        )

        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        views.setTextViewText(R.id.widgetStorage, context.getString(R.string.widget_storage, formatBytes(stat.availableBytes)))

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramPercent = if (info.totalMem > 0) ((info.totalMem - info.availMem) * 100 / info.totalMem).toInt() else 0
        views.setTextViewText(R.id.widgetRam, context.getString(R.string.widget_ram, ramPercent))

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, StartActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widgetRoot, open)
        return views
    }
}
