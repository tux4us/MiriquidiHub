package io.github.tux4us.miriquidihub.sms

import android.content.Context

/** Erzeugt verschlüsselte Antwort-SMS an eine Kopplung. */
object SmsReply {

    /** null, wenn der Kopplungsschlüssel nicht mehr lesbar ist. */
    fun build(
        context: Context,
        contact: SmsContact,
        requestId: Int,
        status: SmsLocationProtocol.Status,
        position: SmsLocationProtocol.Position?
    ): String? {
        val secret = contact.secretBytes() ?: return null
        val message = SmsLocationProtocol.Message.Response(
            requestId = requestId,
            sentAtSeconds = System.currentTimeMillis() / 1000,
            status = status,
            position = position,
            batteryPercent = DeviceState.batteryPercent(context)
        )
        return SmsLocationProtocol.encode(message, secret, contact.ownRole())
    }
}
