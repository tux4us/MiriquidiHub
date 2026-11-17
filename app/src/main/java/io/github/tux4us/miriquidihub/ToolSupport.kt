package io.github.tux4us.miriquidihub

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Kleine Hilfen für die Werkzeug-Bildschirme. */
object AppSettings {
    /** Öffnet die System-App-Info-Seite; Fehler (exotische OEM-Systeme) werden ignoriert. */
    fun open(context: Context, packageName: String) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Seite nicht erreichbar – ignorieren
        }
    }
}

/** Bekannte Installationsquellen (Eigennamen, nicht übersetzt). */
object InstallerNames {
    private val KNOWN = mapOf(
        "com.android.vending" to "Google Play",
        "org.fdroid.fdroid" to "F-Droid",
        "org.fdroid.basic" to "F-Droid Basic",
        "com.aurora.store" to "Aurora Store",
        "com.looker.droidify" to "Droid-ify",
        "com.machiav3lli.fdroid" to "Neo Store",
        "com.amazon.venezia" to "Amazon Appstore",
        "com.sec.android.app.samsungapps" to "Galaxy Store",
        "com.huawei.appmarket" to "AppGallery",
        "com.android.packageinstaller" to "Paketinstaller",
        "com.google.android.packageinstaller" to "Paketinstaller"
    )

    fun known(packageName: String?): String? = packageName?.let { KNOWN[it] }
}

object AppQueries {

    @Suppress("DEPRECATION")
    fun installedPackages(pm: PackageManager, flags: Int = 0) = pm.getInstalledPackages(flags)

    fun isSystemApp(info: ApplicationInfo?): Boolean =
        info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    fun label(pm: PackageManager, packageName: String, info: ApplicationInfo?): String = try {
        if (info != null) pm.getApplicationLabel(info).toString() else packageName
    } catch (e: Exception) {
        packageName
    }
}
