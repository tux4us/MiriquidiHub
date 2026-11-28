package io.github.tux4us.miriquidihub.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.tux4us.miriquidihub.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Texte und Intents zur Anzeige, Navigation und Weitergabe empfangener Standorte. */
object SmsLocationTexts {

    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(millis))

    fun age(context: Context, fromSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
        val diff = (nowSeconds - fromSeconds).coerceAtLeast(0)
        return when {
            diff < 60 -> context.getString(R.string.time_diff_seconds, diff)
            diff < 3600 -> context.getString(R.string.time_diff_minutes, diff / 60)
            else -> context.getString(R.string.time_diff_hours, diff / 3600)
        }
    }

    fun coordinates(lat: Double, lon: Double): String = String.format(Locale.US, "%.6f, %.6f", lat, lon)

    fun positionDetails(context: Context, p: ReceivedPosition): String {
        val kind = context.getString(
            if (p.status == SmsLocationProtocol.Status.FRESH.name) R.string.smsloc_kind_fresh
            else R.string.smsloc_kind_last_known
        )
        val lines = mutableListOf(
            kind,
            context.getString(R.string.smsloc_measured, dateTime(p.fixTimeSeconds * 1000), age(context, p.fixTimeSeconds)),
            coordinates(p.lat, p.lon)
        )
        p.accuracyMeters?.let { lines.add(context.getString(R.string.smsloc_accuracy, it)) }
        p.batteryPercent?.let { lines.add(context.getString(R.string.battery_format, it)) }
        return lines.joinToString("\n")
    }

    fun responseSummary(context: Context, name: String, response: SmsLocationProtocol.Message.Response): String {
        val res = when (response.status) {
            SmsLocationProtocol.Status.FRESH -> R.string.smsloc_response_fresh
            SmsLocationProtocol.Status.LAST_KNOWN_MORE -> R.string.smsloc_response_last_known_more
            SmsLocationProtocol.Status.LAST_KNOWN_FINAL -> R.string.smsloc_response_last_known_final
            SmsLocationProtocol.Status.NO_FIX -> R.string.smsloc_response_no_fix
            SmsLocationProtocol.Status.LOCATION_OFF -> R.string.smsloc_response_location_off
            SmsLocationProtocol.Status.NO_PERMISSION -> R.string.smsloc_response_no_permission
        }
        return context.getString(res, name)
    }

    private fun geoUri(name: String, p: ReceivedPosition): Uri {
        val point = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon)
        return Uri.parse("geo:$point?q=$point(${Uri.encode(name)})")
    }

    /** Öffnet die Position direkt in einer Karten- oder Navigations-App. */
    fun navigationIntent(name: String, p: ReceivedPosition): Intent =
        Intent(Intent.ACTION_VIEW, geoUri(name, p))

    fun shareIntent(context: Context, name: String, p: ReceivedPosition): Intent {
        val lat = String.format(Locale.US, "%.6f", p.lat)
        val lon = String.format(Locale.US, "%.6f", p.lon)
        val text = context.getString(
            R.string.smsloc_share_text, name, positionDetails(context, p),
            geoUri(name, p).toString(), "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
        )
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, context.getString(R.string.share_location_via))
    }
}
