package io.github.tux4us.miriquidihub.sms

import android.content.Context
import io.github.tux4us.miriquidihub.R
import java.security.SecureRandom

/** Sendet eine Standortanfrage an eine Kopplung. */
object SmsLocationRequester {

    fun request(context: Context, pairId: String) {
        val app = context.applicationContext
        val store = SmsLocationStore(app)
        val contact = store.contact(pairId) ?: return
        val secret = contact.secretBytes()
        if (secret == null) {
            store.log(app.getString(R.string.smsloc_log_key_unreadable, contact.name))
            return
        }
        val now = System.currentTimeMillis() / 1000
        val requestId = SecureRandom().nextInt()
        val sms = SmsLocationProtocol.encode(
            SmsLocationProtocol.Message.Request(requestId, now), secret, contact.ownRole()
        )
        // Vor dem Versand speichern, damit eine sehr schnelle Antwort zugeordnet werden kann.
        store.update(pairId) { current ->
            val open = current.pending.orEmpty()
                .filter { now - it.sentAtSeconds <= SmsLocationRules.PENDING_RETENTION_S }
            current.copy(
                pending = open + PendingRequest(requestId, now),
                requestState = RequestState.SENDING.name,
                requestStateAt = now
            )
        }
        SmsSender.send(app, contact.number, sms, SmsSender.NO_SUBSCRIPTION) { result ->
            store.update(pairId) { current ->
                // Eine bereits eingetroffene Antwort nicht überschreiben.
                if (current.requestState != RequestState.SENDING.name) current
                else current.copy(
                    requestState = (if (result == false) RequestState.SEND_FAILED else RequestState.SENT).name,
                    requestStateAt = System.currentTimeMillis() / 1000
                )
            }
            val res = if (result == false) R.string.smsloc_log_request_failed else R.string.smsloc_log_request_sent
            store.log(app.getString(res, contact.name))
        }
    }
}
