package io.github.tux4us.miriquidihub

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Ergebnis der Bluetooth-Suche: Zahl der gefundenen Geräte, ohne Namen oder Adressen zu speichern. */
sealed class BluetoothScanOutcome {
    object NoAdapter : BluetoothScanOutcome()
    object Disabled : BluetoothScanOutcome()
    object StartFailed : BluetoothScanOutcome()
    data class Done(val devicesFound: Int) : BluetoothScanOutcome()
}

/**
 * Zusätzliche, nicht-visuelle Testlogik (Audioanschluss, Bluetooth-Suche, WLAN-Suche).
 * Dialoge und Berechtigungsabfragen liegen in [HardwareTestActivity].
 */
class HardwareTesterExtra(private val ctx: Context) {

    // ------------------------------------------------------------------
    // Audioanschluss (Klinke / USB-Audio)
    // ------------------------------------------------------------------

    private val wiredTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_ANALOG
    )

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> ctx.getString(R.string.hw_jack_type_headset)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> ctx.getString(R.string.hw_jack_type_headphones)
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> ctx.getString(R.string.hw_jack_type_usb)
        else -> ctx.getString(R.string.hw_jack_type_line)
    }

    private fun connectedWired(audio: AudioManager): List<AudioDeviceInfo> =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in wiredTypes }

    /** Wartet, bis ein kabelgebundenes Audiogerät angeschlossen ist (oder bereits ist). */
    suspend fun waitForAudioJack(timeoutMs: Long): HwResult {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        connectedWired(audio).firstOrNull()?.let {
            return HwResult(HwStatus.PASSED, ctx.getString(R.string.hw_jack_ok, typeLabel(it.type)))
        }
        var callback: AudioDeviceCallback? = null
        val found = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<AudioDeviceInfo?> { cont ->
                    val cb = object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                            val match = addedDevices?.firstOrNull { it.isSink && it.type in wiredTypes }
                            if (match != null && cont.isActive) cont.resume(match)
                        }
                    }
                    callback = cb
                    audio.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
                }
            }
        } finally {
            callback?.let { audio.unregisterAudioDeviceCallback(it) }
        }
        return if (found != null) {
            HwResult(HwStatus.PASSED, ctx.getString(R.string.hw_jack_ok, typeLabel(found.type)))
        } else {
            HwResult(HwStatus.FAILED, ctx.getString(R.string.hw_jack_timeout, (timeoutMs / 1000).toInt()))
        }
    }

    // ------------------------------------------------------------------
    // Bluetooth-Suche
    // ------------------------------------------------------------------

    @Suppress("MissingPermission")
    suspend fun bluetoothScan(durationMs: Long): BluetoothScanOutcome {
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = manager?.adapter ?: return BluetoothScanOutcome.NoAdapter
        if (!adapter.isEnabled) return BluetoothScanOutcome.Disabled

        var count = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothDevice.ACTION_FOUND) count++
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        return try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (!adapter.startDiscovery()) {
                BluetoothScanOutcome.StartFailed
            } else {
                delay(durationMs)
                BluetoothScanOutcome.Done(count)
            }
        } catch (e: SecurityException) {
            BluetoothScanOutcome.StartFailed
        } finally {
            try { adapter.cancelDiscovery() } catch (e: SecurityException) { /* Berechtigung entzogen */ }
            try { ctx.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) { /* bereits abgemeldet */ }
        }
    }

    // ------------------------------------------------------------------
    // WLAN-Suche
    // ------------------------------------------------------------------

    suspend fun wifiScan(): HwResult {
        if (!WifiScanHelper.isWifiEnabled(ctx)) return HwResult(HwStatus.SKIPPED, ctx.getString(R.string.hw_wifiscan_off))
        val results = WifiScanHelper.scan(ctx)
        if (results.isEmpty()) return HwResult(HwStatus.FAILED, ctx.getString(R.string.hw_wifiscan_none))
        val bands = results.map { WifiScanHelper.bandOf(it.frequency) }.distinct().sorted().joinToString(", ")
        val strongest = results.maxOf { it.level }
        return HwResult(HwStatus.PASSED, ctx.getString(R.string.hw_wifiscan_ok, results.size, bands, strongest))
    }
}
