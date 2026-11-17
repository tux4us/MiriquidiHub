package io.github.tux4us.miriquidihub

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Sicherheitsprüfung des Geräts. Liest nur Einstellungen und Systemeigenschaften, die ohne Root
 * zugänglich sind. Nicht lesbare Werte werden als „nicht lesbar“ ausgewiesen und nicht bewertet.
 */
object SecurityCheck {

    private const val SCREEN_TIMEOUT_WARN_MS = 10 * 60 * 1000

    fun collect(ctx: Context): List<DiagSection> = listOf(system(ctx), lockAndEncryption(ctx), access(ctx))

    private fun system(ctx: Context): DiagSection {
        val rows = mutableListOf<DiagRow>()
        val age = DeviceInfo.patchAgeDays()
        rows += DiagRow(
            ctx.getString(R.string.sc_patch),
            if (age != null) ctx.getString(R.string.sc_patch_value, Build.VERSION.SECURITY_PATCH, age) else Build.VERSION.SECURITY_PATCH,
            SecurityRules.patchStatus(age)
        )
        val bootState = DeviceInfo.getProp("ro.boot.verifiedbootstate")
        rows += DiagRow(
            ctx.getString(R.string.sc_verified_boot),
            bootState ?: ctx.getString(R.string.di_unreadable),
            SecurityRules.verifiedBootStatus(bootState)
        )
        val deviceState = DeviceInfo.getProp("ro.boot.vbmeta.device_state")
            ?: DeviceInfo.getProp("ro.boot.flash.locked")?.let { if (it == "1") "locked" else "unlocked" }
        rows += DiagRow(
            ctx.getString(R.string.sc_bootloader),
            deviceState ?: ctx.getString(R.string.di_unreadable),
            SecurityRules.bootloaderStatus(deviceState)
        )
        val enforcing = DeviceInfo.selinuxEnforcing()
        rows += DiagRow(
            ctx.getString(R.string.sc_selinux),
            when (enforcing) {
                true -> ctx.getString(R.string.di_sel_enforcing)
                false -> ctx.getString(R.string.di_sel_permissive)
                null -> ctx.getString(R.string.di_unreadable)
            },
            when (enforcing) {
                true -> DiagStatus.OK
                false -> DiagStatus.ERROR
                null -> DiagStatus.INFO
            }
        )
        return DiagSection(ctx.getString(R.string.sc_section_system), rows)
    }

    private fun lockAndEncryption(ctx: Context): DiagSection {
        val rows = mutableListOf<DiagRow>()
        val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val secure = keyguard.isDeviceSecure
        rows += DiagRow(
            ctx.getString(R.string.sc_screen_lock),
            ctx.getString(if (secure) R.string.sc_lock_set else R.string.sc_lock_none),
            if (secure) DiagStatus.OK else DiagStatus.ERROR
        )
        val encryption = DeviceInfo.storageEncryptionStatus(ctx)
        rows += DiagRow(
            ctx.getString(R.string.di_encryption),
            DeviceInfo.encryptionLabel(ctx, encryption),
            when (encryption) {
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> DiagStatus.OK
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> DiagStatus.WARN
                DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> DiagStatus.ERROR
                else -> DiagStatus.INFO
            }
        )
        val timeout = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        if (timeout > 0) {
            rows += DiagRow(
                ctx.getString(R.string.sc_screen_timeout),
                ctx.getString(R.string.sc_seconds, timeout / 1000),
                if (timeout > SCREEN_TIMEOUT_WARN_MS) DiagStatus.WARN else DiagStatus.INFO
            )
        }
        return DiagSection(ctx.getString(R.string.sc_section_lock), rows)
    }

    private fun access(ctx: Context): DiagSection {
        val rows = mutableListOf<DiagRow>()
        val adb = Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
        rows += DiagRow(
            ctx.getString(R.string.sc_usb_debugging),
            ctx.getString(if (adb) R.string.netdiag_state_on else R.string.netdiag_state_off),
            if (adb) DiagStatus.WARN else DiagStatus.OK
        )
        val dev = Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
        rows += DiagRow(
            ctx.getString(R.string.sc_developer_options),
            ctx.getString(if (dev) R.string.netdiag_state_on else R.string.netdiag_state_off),
            if (dev) DiagStatus.WARN else DiagStatus.OK
        )
        val services = accessibilityServiceLabels(ctx)
        rows += DiagRow(
            ctx.getString(R.string.sc_accessibility),
            if (services.isEmpty()) ctx.getString(R.string.netdiag_none) else services.joinToString("\n"),
            DiagStatus.INFO
        )
        return DiagSection(ctx.getString(R.string.sc_section_access), rows)
    }

    private fun accessibilityServiceLabels(ctx: Context): List<String> {
        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (raw.isNullOrBlank()) return emptyList()
        val pm = ctx.packageManager
        return raw.split(':').mapNotNull { component ->
            val pkg = component.substringBefore('/').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            try {
                AppQueries.label(pm, pkg, pm.getApplicationInfo(pkg, 0))
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
        }.distinct()
    }
}
