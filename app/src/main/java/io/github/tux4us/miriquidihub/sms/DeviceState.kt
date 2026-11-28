package io.github.tux4us.miriquidihub.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Abfragen zu Berechtigungen, Standort und Akku. */
object DeviceState {

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasSmsPermissions(context: Context) =
        granted(context, Manifest.permission.SEND_SMS) && granted(context, Manifest.permission.RECEIVE_SMS)

    fun hasFineLocation(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun notificationsEnabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun ignoresBatteryOptimizations(context: Context) =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun locationEnabled(context: Context) = LocationManagerCompat.isLocationEnabled(
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    )

    fun hasTelephony(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    fun batteryPercent(context: Context): Int? {
        val intent = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    fun ageMillis(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    /**
     * Jüngste bekannte Position aller Anbieter. Bei annähernd gleichem Alter (innerhalb von
     * zwei Minuten) gewinnt die genauere.
     */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(context: Context): Location? {
        if (!hasFineLocation(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val all = manager.getProviders(false).mapNotNull {
            try {
                manager.getLastKnownLocation(it)
            } catch (e: SecurityException) {
                null
            }
        }
        val newest = all.minOfOrNull { ageMillis(it) } ?: return null
        return all.filter { ageMillis(it) - newest <= 2 * 60 * 1000L }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
    }

    fun toPosition(location: Location) = SmsLocationProtocol.Position(
        lat = location.latitude,
        lon = location.longitude,
        fixTimeSeconds = location.time / 1000,
        accuracyMeters = if (location.hasAccuracy()) Math.round(location.accuracy) else null
    )
}
