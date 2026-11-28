package io.github.tux4us.miriquidihub.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.tux4us.miriquidihub.R

/**
 * Empfängt SMS und verarbeitet ausschließlich Nachrichten mit dem Präfix [SmsLocationProtocol.PREFIX],
 * die sich mit dem Schlüssel einer Kopplung entschlüsseln lassen. Alle anderen SMS werden
 * weder ausgewertet noch gespeichert.
 *
 * Antworten gehen immer an die bei der Kopplung gespeicherte Rufnummer, nie an die Absenderkennung
 * der SMS. Eine gefälschte oder erneut abgespielte SMS kann den Standort so nicht umleiten.
 */
class SmsLocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bodies = parts.filterNotNull()
            .groupBy { it.originatingAddress.orEmpty() }
            .values
            .map { group -> group.joinToString("") { it.messageBody.orEmpty() } }
        val subscriptionId = subscriptionId(intent)
        bodies.filter { SmsLocationProtocol.looksLikeMessage(it) }
            .forEach { handle(context.applicationContext, it, subscriptionId) }
    }

    private fun subscriptionId(intent: Intent): Int {
        val id = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", SmsSender.NO_SUBSCRIPTION)
        return if (id != SmsSender.NO_SUBSCRIPTION) id
        else intent.getIntExtra("subscription", SmsSender.NO_SUBSCRIPTION)
    }

    private fun handle(context: Context, body: String, subscriptionId: Int) {
        val pairId = SmsLocationProtocol.peekPairId(body) ?: return
        val store = SmsLocationStore(context)
        val contact = store.contact(pairId) ?: return
        val secret = contact.secretBytes() ?: return
        when (val message = SmsLocationProtocol.decode(body, secret, contact.ownRole().peer)) {
            is SmsLocationProtocol.Message.Request -> handleRequest(context, store, contact, message, subscriptionId)
            is SmsLocationProtocol.Message.Response -> handleResponse(context, store, contact, message)
            null -> Log.w(TAG, "SMS mit bekannter Kennung nicht entschlüsselbar")
        }
    }

    private fun handleRequest(
        context: Context,
        store: SmsLocationStore,
        contact: SmsContact,
        request: SmsLocationProtocol.Message.Request,
        subscriptionId: Int
    ) {
        if (!contact.allowLocate) {
            store.log(context.getString(R.string.smsloc_log_denied, contact.name))
            SmsLocationNotifications.event(context, contact, context.getString(R.string.smsloc_notify_denied, contact.name))
            return
        }
        val now = System.currentTimeMillis() / 1000
        when (store.acceptRequest(contact.pairId, request.requestId, request.sentAtSeconds, now)) {
            SmsLocationRules.Verdict.EXPIRED -> {
                store.log(context.getString(R.string.smsloc_log_expired, contact.name))
                return
            }
            SmsLocationRules.Verdict.REPLAY -> {
                store.log(context.getString(R.string.smsloc_log_replay, contact.name))
                return
            }
            SmsLocationRules.Verdict.RATE_LIMITED -> {
                store.log(context.getString(R.string.smsloc_log_rate_limit, contact.name))
                return
            }
            SmsLocationRules.Verdict.ACCEPT -> Unit
        }
        store.log(context.getString(R.string.smsloc_log_request_received, contact.name))

        // Ohne „Immer zulassen“ liefert Android im Hintergrund keinen Standort.
        if (!DeviceState.hasFineLocation(context) || !DeviceState.hasBackgroundLocation(context)) {
            replyDirectly(context, store, contact, request.requestId, SmsLocationProtocol.Status.NO_PERMISSION, null, subscriptionId)
            return
        }
        try {
            ContextCompat.startForegroundService(
                context, SmsLocationService.intent(context, contact.pairId, request.requestId, subscriptionId)
            )
        } catch (e: RuntimeException) {
            // Das System kann den Dienststart aus dem Hintergrund ablehnen
            // (ForegroundServiceStartNotAllowedException, SecurityException).
            Log.e(TAG, "Standortdienst nicht startbar: ${e.javaClass.simpleName}")
            replyWithLastKnown(context, store, contact, request.requestId, subscriptionId)
        }
    }

    private fun replyWithLastKnown(
        context: Context, store: SmsLocationStore, contact: SmsContact, requestId: Int, subscriptionId: Int
    ) {
        val last = DeviceState.lastKnownLocation(context)
        val status = if (last != null) SmsLocationProtocol.Status.LAST_KNOWN_FINAL else SmsLocationProtocol.Status.NO_FIX
        replyDirectly(context, store, contact, requestId, status, last?.let { DeviceState.toPosition(it) }, subscriptionId)
    }

    private fun replyDirectly(
        context: Context,
        store: SmsLocationStore,
        contact: SmsContact,
        requestId: Int,
        status: SmsLocationProtocol.Status,
        position: SmsLocationProtocol.Position?,
        subscriptionId: Int
    ) {
        val sms = SmsReply.build(context, contact, requestId, status, position) ?: return
        val ok = SmsSender.sendAndForget(context, contact.number, sms, subscriptionId)
        val text = context.getString(
            if (ok) R.string.smsloc_log_reply_handed_over else R.string.smsloc_log_reply_failed, contact.name
        )
        store.log(text)
        SmsLocationNotifications.event(context, contact, text)
    }

    private fun handleResponse(
        context: Context,
        store: SmsLocationStore,
        contact: SmsContact,
        response: SmsLocationProtocol.Message.Response
    ) {
        // Nur Antworten auf eigene, noch offene Anfragen werden angenommen.
        val now = System.currentTimeMillis() / 1000
        val isOpen = contact.pending.orEmpty().any {
            it.requestId == response.requestId && now - it.sentAtSeconds <= SmsLocationRules.PENDING_RETENTION_S
        }
        if (!isOpen) return
        var positionUpdated = false
        val updated = store.update(contact.pairId) { current ->
            val position = response.position
            positionUpdated = position != null && SmsLocationRules.shouldReplacePosition(
                current.lastPosition?.fixTimeSeconds, position.fixTimeSeconds
            )
            val stored = if (position != null && positionUpdated) {
                ReceivedPosition(
                    position.lat, position.lon, position.fixTimeSeconds, position.accuracyMeters,
                    response.batteryPercent, response.status.name, now
                )
            } else {
                current.lastPosition
            }
            // Zustand nur für die jüngste Anfrage fortschreiben. Eine verspätete Zwischenmeldung
            // darf das Endergebnis nicht überschreiben.
            val newest = current.pending.orEmpty().maxByOrNull { it.sentAtSeconds }?.requestId
            val lateInterim = response.status == SmsLocationProtocol.Status.LAST_KNOWN_MORE &&
                current.requestState == RequestState.ANSWERED.name
            val state = when (response.status) {
                SmsLocationProtocol.Status.LAST_KNOWN_MORE -> RequestState.ANSWER_WAITING_FOR_FIX
                SmsLocationProtocol.Status.FRESH,
                SmsLocationProtocol.Status.LAST_KNOWN_FINAL -> RequestState.ANSWERED
                SmsLocationProtocol.Status.NO_FIX -> RequestState.NO_FIX
                SmsLocationProtocol.Status.LOCATION_OFF -> RequestState.LOCATION_OFF
                SmsLocationProtocol.Status.NO_PERMISSION -> RequestState.NO_PERMISSION
            }
            val applyState = newest == response.requestId && !lateInterim
            current.copy(
                lastPosition = stored,
                requestState = if (applyState) state.name else current.requestState,
                requestStateAt = if (applyState) now else current.requestStateAt
            )
        } ?: return
        val text = SmsLocationTexts.responseSummary(context, updated.name, response)
        store.log(text)
        SmsLocationNotifications.response(context, updated, text, if (positionUpdated) updated.lastPosition else null)
    }

    private companion object {
        const val TAG = "SmsLocationReceiver"
    }
}
