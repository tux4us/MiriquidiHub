package io.github.tux4us.miriquidihub

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Gemeinsame WLAN-Suche für den Hardware-Selbsttest und die WLAN-Analyse.
 * Ab Android 13 genügt `NEARBY_WIFI_DEVICES` (mit `neverForLocation`), davor wird der
 * Standortzugriff vom System verlangt. Es werden keine BSSIDs weitergegeben.
 */
object WifiScanHelper {

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    fun isWifiEnabled(ctx: Context): Boolean {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.isWifiEnabled
    }

    /**
     * Startet eine Suche und wartet bis zu [timeoutMs] auf das Ergebnis. Android drosselt
     * Suchen für Apps; dann werden die zuletzt zwischengespeicherten Ergebnisse geliefert.
     */
    suspend fun scan(ctx: Context, timeoutMs: Long = 8_000L): List<ScanResult> {
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        var receiver: BroadcastReceiver? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val r = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                    receiver = r
                    app.registerReceiver(r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
                    @Suppress("DEPRECATION")
                    val started = try { wm.startScan() } catch (e: SecurityException) { false }
                    if (!started && cont.isActive) cont.resume(false)
                }
            }
        } finally {
            receiver?.let {
                try { app.unregisterReceiver(it) } catch (e: IllegalArgumentException) { /* bereits abgemeldet */ }
            }
        }
        return try { wm.scanResults ?: emptyList() } catch (e: SecurityException) { emptyList() }
    }

    fun channelOf(frequencyMhz: Int): Int = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5160..5885 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5
        else -> -1
    }

    fun bandOf(frequencyMhz: Int): String = when {
        frequencyMhz < 3000 -> "2,4 GHz"
        frequencyMhz < 5900 -> "5 GHz"
        else -> "6 GHz"
    }

    /** Grobe Güte aus dem Pegel in dBm (1 = schlecht … 4 = sehr gut). */
    fun qualityLevel(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
}
