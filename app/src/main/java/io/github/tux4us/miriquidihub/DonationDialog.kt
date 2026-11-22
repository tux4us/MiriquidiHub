package io.github.tux4us.miriquidihub

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Freiwillige Unterstützung des Entwicklers. Dieser Dialog wird auf Nutzeraktion geöffnet
 * (Startseite oder Appinfo); einen einmaligen Hinweis nach einem Update zeigt [UpdateNotice].
 * Die Spende läuft über die Webseite von Buy Me a Coffee im externen Browser; die App enthält
 * dafür weder SDK noch Tracking.
 */
object DonationDialog {
    const val DONATION_URL = "https://buymeacoffee.com/tux4us"

    fun show(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.donate_title)
            .setMessage(R.string.donate_message)
            .setPositiveButton(R.string.donate_open) { _, _ -> openDonationPage(context) }
            .setNeutralButton(R.string.donate_copy_link) { _, _ -> copyLink(context) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    internal fun openDonationPage(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.donate_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyLink(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.donate_title), DONATION_URL))
        Toast.makeText(context, R.string.donate_link_copied, Toast.LENGTH_SHORT).show()
    }
}
