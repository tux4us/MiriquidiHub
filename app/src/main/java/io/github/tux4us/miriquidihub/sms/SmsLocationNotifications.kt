package io.github.tux4us.miriquidihub.sms

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.tux4us.miriquidihub.R

object SmsLocationNotifications {

    const val SERVICE_ID = 2101
    private const val CHANNEL_SERVICE = "sms_location_service"
    private const val CHANNEL_EVENTS = "sms_location_events"

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.smsloc_channel_service), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, context.getString(R.string.smsloc_channel_events), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, SmsLocationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun service(context: Context): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.smsloc_title))
            .setContentText(context.getString(R.string.smsloc_service_running))
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .build()
    }

    /** Sichtbarer Hinweis auf dem georteten Gerät. */
    fun event(context: Context, contact: SmsContact, text: String) {
        createChannels(context)
        post(context, "event_${contact.pairId}", builder(context, text).build())
    }

    /** Antwort auf eine eigene Anfrage, mit Schaltfläche zur Navigations-App. */
    fun response(context: Context, contact: SmsContact, text: String, position: ReceivedPosition?) {
        createChannels(context)
        val builder = builder(context, text)
        if (position != null) {
            val navigate = PendingIntent.getActivity(
                context, contact.pairId.hashCode(),
                SmsLocationTexts.navigationIntent(contact.name, position),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_directions, context.getString(R.string.smsloc_navigate), navigate)
        }
        post(context, "response_${contact.pairId}", builder.build())
    }

    private fun builder(context: Context, text: String) =
        NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.smsloc_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

    @SuppressLint("MissingPermission")
    private fun post(context: Context, tag: String, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) manager.notify(tag, 1, notification)
    }
}
