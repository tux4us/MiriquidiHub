package io.github.tux4us.miriquidihub

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Hinweis nach einem App-Update mit Bitte um freiwillige Unterstützung (Buy Me a Coffee).
 * Erscheint einmal pro Versionssprung nach oben, nicht bei Neuinstallation, Downgrade, Drehen
 * oder erneutem Öffnen derselben Version. Die zuletzt gesehene Version wird vor dem Anzeigen
 * gespeichert, der Dialog erscheint also auch nach Abbruch nicht erneut. Er blockiert nichts
 * und lässt sich über „Später“ oder Zurück schließen.
 */
object UpdateNotice {
    private const val PREFS = "update_notice"
    private const val KEY_LAST_VERSION_CODE = "last_seen_version_code"

    fun showIfUpgraded(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val info = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }
        val current = PackageInfoCompat.getLongVersionCode(info)
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_VERSION_CODE, -1L)
        prefs.edit().putLong(KEY_LAST_VERSION_CODE, current).apply()

        // Ohne gespeicherten Stand (Update von einer Version vor diesem Hinweis): Update liegt vor,
        // wenn Installations- und Aktualisierungszeitpunkt auseinanderliegen.
        val upgraded = if (last >= 0) current > last else info.firstInstallTime != info.lastUpdateTime
        if (!upgraded) return

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_title)
            .setMessage(activity.getString(R.string.update_message, info.versionName.orEmpty()))
            .setPositiveButton(R.string.update_donate) { _, _ -> DonationDialog.openDonationPage(activity) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }
}
