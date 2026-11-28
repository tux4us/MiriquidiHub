package io.github.tux4us.miriquidihub.sms

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import io.github.tux4us.miriquidihub.R

/**
 * Kurzlebiger Vordergrunddienst, der eine Standortanfrage beantwortet:
 * 1. Ist der letzte Standort aktuell und genau, wird genau eine SMS gesendet.
 * 2. Sonst geht der letzte bekannte Standort sofort raus, dann folgt ein frischer Fix
 *    in einer zweiten SMS (höchstens [FIX_TIMEOUT_MS] Wartezeit).
 * Nicht angenommene SMS (z. B. Funkloch) werden mit wachsendem Abstand erneut gesendet.
 */
class SmsLocationService : Service() {

    private data class Job(val pairId: String, val requestId: Int, val subscriptionId: Int)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SmsLocationStore
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    /** Anfragen, die auf den frischen Fix warten. */
    private val waiting = mutableListOf<Job>()
    private var openSends = 0
    private var best: Location? = null
    private var listening = false
    private var settling = false
    private var lastStartId = 0
    private var destroyed = false

    private val fixTimeout = Runnable { finishFix() }
    private val settle = Runnable { finishFix() }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Zwischengespeicherte alte Positionen zählen nicht als frischer Fix.
            if (DeviceState.ageMillis(location) > MAX_FRESH_AGE_MS) return
            val current = best
            if (current == null || accuracyOf(location) <= accuracyOf(current)) best = location
            // Ein grober Funkzellen-Standort darf das Warten auf GPS nicht verkürzen.
            if (!settling && accuracyOf(location) <= SETTLE_ACCURACY_M) {
                settling = true
                handler.postDelayed(settle, SETTLE_MS)
            }
            if (location.hasAccuracy() && location.accuracy <= SmsLocationRules.GOOD_ACCURACY_M) finishFix()
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        store = SmsLocationStore(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!enterForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val pairId = intent?.getStringExtra(EXTRA_PAIR_ID)
        val contact = pairId?.let { store.contact(it) }
        if (intent == null || pairId == null || contact == null || !contact.allowLocate) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        process(
            Job(
                pairId,
                intent.getIntExtra(EXTRA_REQUEST_ID, 0),
                intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, SmsSender.NO_SUBSCRIPTION)
            )
        )
        // Wird der Prozess beendet, stellt das System die offene Anfrage erneut zu.
        return START_REDELIVER_INTENT
    }

    private fun enterForeground(): Boolean = try {
        val notification = SmsLocationNotifications.service(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, SmsLocationNotifications.SERVICE_ID, notification, type)
        true
    } catch (e: RuntimeException) {
        Log.e(TAG, "Vordergrunddienst nicht möglich: ${e.javaClass.simpleName}")
        false
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiriquidiHub:SmsLocation")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        // Erneutes acquire verlängert die Frist.
        lock.acquire(WAKE_LOCK_MS)
    }

    private fun process(job: Job) {
        val last = DeviceState.lastKnownLocation(this)
        if (!DeviceState.locationEnabled(this)) {
            reply(job, SmsLocationProtocol.Status.LOCATION_OFF, last)
            return
        }
        if (last != null && SmsLocationRules.isCurrentFix(
                DeviceState.ageMillis(last), if (last.hasAccuracy()) last.accuracy else null
            )
        ) {
            reply(job, SmsLocationProtocol.Status.FRESH, last)
            return
        }
        waiting.add(job)
        if (!startListening()) {
            waiting.remove(job)
            val status = if (last != null) SmsLocationProtocol.Status.LAST_KNOWN_FINAL else SmsLocationProtocol.Status.NO_FIX
            reply(job, status, last)
            return
        }
        if (last != null) reply(job, SmsLocationProtocol.Status.LAST_KNOWN_MORE, last)
    }

    @SuppressLint("MissingPermission")
    private fun startListening(): Boolean {
        if (listening) return true
        if (!DeviceState.hasFineLocation(this)) return false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.allProviders.contains(provider) && locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                    listening = true
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Standortzugriff verweigert")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Anbieter $provider nicht verfügbar")
            }
        }
        if (listening) handler.postDelayed(fixTimeout, FIX_TIMEOUT_MS)
        return listening
    }

    private fun stopListening() {
        handler.removeCallbacks(fixTimeout)
        handler.removeCallbacks(settle)
        if (listening) locationManager.removeUpdates(listener)
        listening = false
        settling = false
    }

    private fun finishFix() {
        if (!listening) return
        stopListening()
        val fresh = best
        best = null
        val jobs = waiting.toList()
        waiting.clear()
        val fallback = if (fresh == null) DeviceState.lastKnownLocation(this) else null
        for (job in jobs) {
            when {
                fresh != null -> reply(job, SmsLocationProtocol.Status.FRESH, fresh)
                fallback != null -> reply(job, SmsLocationProtocol.Status.LAST_KNOWN_FINAL, fallback)
                else -> reply(job, SmsLocationProtocol.Status.NO_FIX, null)
            }
        }
        stopIfIdle()
    }

    private fun reply(job: Job, status: SmsLocationProtocol.Status, location: Location?) {
        // Kopplung erneut lesen: Sie kann inzwischen gelöscht oder gesperrt worden sein.
        val contact = store.contact(job.pairId)
        val sms = if (contact != null && contact.allowLocate) {
            SmsReply.build(this, contact, job.requestId, status, location?.let { DeviceState.toPosition(it) })
        } else {
            null
        }
        if (contact == null || sms == null) {
            handler.post { stopIfIdle() }
            return
        }
        openSends++
        sendWithRetry(job, sms, 0)
    }

    private fun sendWithRetry(job: Job, sms: String, attempt: Int) {
        val contact = store.contact(job.pairId)
        if (destroyed || contact == null || !contact.allowLocate) {
            openSends--
            stopIfIdle()
            return
        }
        acquireWakeLock()
        SmsSender.send(this, contact.number, sms, job.subscriptionId) { result ->
            if (result == false && attempt < RETRY_DELAYS_MS.size && !destroyed) {
                handler.postDelayed({ sendWithRetry(job, sms, attempt + 1) }, RETRY_DELAYS_MS[attempt])
            } else {
                val textRes = when (result) {
                    true -> R.string.smsloc_log_reply_sent
                    false -> R.string.smsloc_log_reply_failed
                    null -> R.string.smsloc_log_reply_handed_over
                }
                val text = getString(textRes, contact.name)
                store.log(text)
                SmsLocationNotifications.event(this, contact, text)
                openSends--
                stopIfIdle()
            }
        }
    }

    private fun stopIfIdle() {
        if (waiting.isEmpty() && openSends <= 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    override fun onDestroy() {
        destroyed = true
        stopListening()
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun accuracyOf(location: Location) = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE

    companion object {
        private const val TAG = "SmsLocationService"
        private const val EXTRA_PAIR_ID = "pair_id"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
        private const val FIX_TIMEOUT_MS = 5 * 60 * 1000L
        /** Nach dem ersten ungenauen Fix wird noch so lange auf einen besseren gewartet. */
        private const val SETTLE_MS = 60 * 1000L
        private const val SETTLE_ACCURACY_M = 200f
        private const val MAX_FRESH_AGE_MS = 30 * 1000L
        private const val WAKE_LOCK_MS = 20 * 60 * 1000L
        private val RETRY_DELAYS_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

        fun intent(context: Context, pairId: String, requestId: Int, subscriptionId: Int): Intent =
            Intent(context, SmsLocationService::class.java)
                .putExtra(EXTRA_PAIR_ID, pairId)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
    }
}
