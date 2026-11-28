package io.github.tux4us.miriquidihub.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/** Versendet eine SMS und meldet, ob das Mobilfunknetz sie angenommen hat. */
object SmsSender {

    private const val TAG = "SmsSender"
    private const val ACTION_SENT = "io.github.tux4us.miriquidihub.SMS_SENT"
    private const val RESULT_TIMEOUT_MS = 90_000L
    const val NO_SUBSCRIPTION = -1

    /**
     * [onResult] wird genau einmal im Haupt-Thread aufgerufen. Bleibt die Rückmeldung aus,
     * gilt der Versand nach [RESULT_TIMEOUT_MS] als ungeklärt (null), damit nichts doppelt
     * gesendet wird.
     */
    fun send(context: Context, number: String, text: String, subscriptionId: Int, onResult: (Boolean?) -> Unit) {
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val action = "$ACTION_SENT.${UUID.randomUUID()}"
        var done = false
        lateinit var receiver: BroadcastReceiver
        lateinit var timeout: Runnable

        fun finish(result: Boolean?) {
            if (done) return
            done = true
            handler.removeCallbacks(timeout)
            try {
                app.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver war nicht registriert")
            }
            onResult(result)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                finish(resultCode == Activity.RESULT_OK)
            }
        }
        timeout = Runnable { finish(null) }

        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val sentIntent = PendingIntent.getBroadcast(
            app, 0, Intent(action).setPackage(app.packageName),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        handler.postDelayed(timeout, RESULT_TIMEOUT_MS)
        try {
            smsManager(app, subscriptionId).sendTextMessage(number, null, text, sentIntent, null)
        } catch (e: Exception) {
            // SecurityException (Recht fehlt), IllegalArgumentException (Nummer) u. a.
            Log.e(TAG, "SMS-Versand fehlgeschlagen: ${e.javaClass.simpleName}")
            handler.post { finish(false) }
        }
    }

    /** Versand ohne Warten auf das Ergebnis, für Kurzmeldungen direkt aus dem Receiver. */
    fun sendAndForget(context: Context, number: String, text: String, subscriptionId: Int): Boolean = try {
        smsManager(context, subscriptionId).sendTextMessage(number, null, text, null, null)
        true
    } catch (e: Exception) {
        Log.e(TAG, "SMS-Versand fehlgeschlagen: ${e.javaClass.simpleName}")
        false
    }

    /** Antworten sollen über die SIM gehen, die die Anfrage empfangen hat (Dual-SIM). */
    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, subscriptionId: Int): SmsManager {
        val id = if (subscriptionId != NO_SUBSCRIPTION) {
            subscriptionId
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } else {
            NO_SUBSCRIPTION
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
            if (id >= 0) manager.createForSubscriptionId(id) else manager
        } else if (id >= 0) {
            SmsManager.getSmsManagerForSubscriptionId(id)
        } else {
            SmsManager.getDefault()
        }
    }
}
