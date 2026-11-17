package io.github.tux4us.miriquidihub

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inventar der installierten Apps: Version, Zielversion (targetSdk), Installationsquelle und
 * Signatur-Fingerabdruck (SHA-256 des Zertifikats). Benötigt QUERY_ALL_PACKAGES (F-Droid-Variante).
 */
class AppInventoryActivity : ToolActivity() {

    override val titleRes: Int = R.string.ai_title
    override val footnoteRes: Int = R.string.ai_footnote

    private class Entry(
        val label: String,
        val packageName: String,
        val version: String,
        val targetSdk: Int,
        val installer: String,
        val fingerprint: String
    )

    private var includeSystem = false
    private var entries: List<Entry> = emptyList()

    override fun onToolCreated() {
        setPrimaryAction(R.string.po_show_system) { toggleSystem() }
        load()
    }

    override fun onRefresh() {
        load()
    }

    private fun toggleSystem() {
        includeSystem = !includeSystem
        binding.btnPrimary.setText(if (includeSystem) R.string.po_hide_system else R.string.po_show_system)
        load()
    }

    private fun load() {
        setBusy(true)
        setSummary(null, getString(R.string.insights_working))
        lifecycleScope.launch {
            entries = withContext(Dispatchers.IO) { collect() }
            val rows = entries.map { entry ->
                DiagRow(
                    "${entry.label} (${entry.packageName})",
                    getString(R.string.ai_row, entry.version, entry.targetSdk, entry.installer, entry.fingerprint),
                    if (entry.targetSdk in 1 until OLD_TARGET_SDK) DiagStatus.WARN else DiagStatus.INFO
                ) { AppSettings.open(this@AppInventoryActivity, entry.packageName) }
            }
            showSections(listOf(DiagSection(getString(R.string.ai_section, entries.size), rows)))
            val old = entries.count { it.targetSdk in 1 until OLD_TARGET_SDK }
            setSummary(
                if (old > 0) DiagStatus.WARN else DiagStatus.INFO,
                getString(R.string.ai_summary, entries.size, old, OLD_TARGET_SDK)
            )
            setBusy(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun collect(): List<Entry> {
        val pm = packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        val unknownInstaller = getString(R.string.ai_installer_unknown)
        return AppQueries.installedPackages(pm, flags)
            .filter { includeSystem || !AppQueries.isSystemApp(it.applicationInfo) }
            .map { info ->
                Entry(
                    label = AppQueries.label(pm, info.packageName, info.applicationInfo),
                    packageName = info.packageName,
                    version = "${info.versionName ?: "?"} (${versionCodeOf(info)})",
                    targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
                    installer = installerOf(pm, info.packageName)?.let { InstallerNames.known(it) ?: it } ?: unknownInstaller,
                    fingerprint = fingerprintOf(info)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun installerOf(pm: PackageManager, packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(packageName).installingPackageName
        else pm.getInstallerPackageName(packageName)
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun fingerprintOf(info: PackageInfo): String {
        val signature = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                info.signatures?.firstOrNull()
            }
        } catch (e: Exception) {
            null
        } ?: return "–"
        return Hashing.fingerprint(Hashing.sha256Hex(signature.toByteArray()))
    }

    companion object {
        /** Apps mit älterer Zielversion unterliegen weniger neuen Einschränkungen (Android 9 = 28). */
        private const val OLD_TARGET_SDK = 28
    }
}
