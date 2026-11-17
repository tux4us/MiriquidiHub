package io.github.tux4us.miriquidihub

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.view.Display
import android.webkit.WebView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Liest erweiterte Geräteinformationen ohne zusätzliche Berechtigungen und ohne Root aus.
 * Jeder Abschnitt wird einzeln abgesichert: Ist ein Wert auf einem Gerät nicht lesbar, fehlt
 * er oder wird als "nicht auslesbar" markiert, der Rest bleibt erhalten.
 * Genutzt vom Geräte-Report, vom Sicherheits-Check und vom Report-Vergleich.
 */
object DeviceInfo {

    fun collect(ctx: Context): List<DiagSection> = listOfNotNull(
        safe { processor(ctx) },
        safe { display(ctx) },
        safe { storage(ctx) },
        safe { battery(ctx) },
        safe { security(ctx) },
        safe { software(ctx) },
        safe { sensors(ctx) }
    )

    /** Textblock im Stil der übrigen Report-Abschnitte ("--- NAME ---", "LABEL: Wert"). */
    fun formatReportBlock(sections: List<DiagSection>): String {
        val locale = Locale.getDefault()
        return sections.joinToString("\n\n") { section ->
            val header = "--- ${section.title.uppercase(locale)} ---"
            header + "\n" + section.rows.joinToString("\n") { "${it.label.uppercase(locale)}: ${it.value}" }
        }
    }

    private fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------
    // Prozessor
    // ------------------------------------------------------------------

    private fun processor(ctx: Context): DiagSection {
        val rows = mutableListOf<DiagRow>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" ")
            if (soc.isNotBlank()) rows += DiagRow(ctx.getString(R.string.di_soc), soc)
        }
        rows += DiagRow(ctx.getString(R.string.di_abis), Build.SUPPORTED_ABIS.joinToString(", "))
        val cores = Runtime.getRuntime().availableProcessors()
        rows += DiagRow(ctx.getString(R.string.di_cores), cores.toString())

        var maxKhz = 0L
        for (i in 0 until cores) {
            val value = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (value != null && value > maxKhz) maxKhz = value
        }
        if (maxKhz > 0) rows += DiagRow(ctx.getString(R.string.di_cpu_max), ctx.getString(R.string.di_mhz, maxKhz / 1000))

        System.getProperty("os.version")?.takeIf { it.isNotBlank() }?.let {
            rows += DiagRow(ctx.getString(R.string.di_kernel), it)
        }
        return DiagSection(ctx.getString(R.string.di_sec_processor), rows)
    }

    private fun readLong(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------
    // Display
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun display(ctx: Context): DiagSection {
        val rows = mutableListOf<DiagRow>()
        val manager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = ctx.resources.displayMetrics

        val size = Point()
        display?.getRealSize(size)
        val width = if (size.x > 0) size.x else metrics.widthPixels
        val height = if (size.y > 0) size.y else metrics.heightPixels
        rows += DiagRow(ctx.getString(R.string.di_resolution), "$width × $height px")
        rows += DiagRow(ctx.getString(R.string.di_density), ctx.getString(R.string.di_dpi, metrics.densityDpi))

        if (display != null) {
            rows += DiagRow(ctx.getString(R.string.di_refresh), String.format(Locale.US, "%.0f Hz", display.refreshRate))
            val modes = display.supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted()
            if (modes.size > 1) {
                rows += DiagRow(ctx.getString(R.string.di_refresh_modes), modes.joinToString(", ") { "$it Hz" })
            }
            val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            val hdrNames = hdrTypes.map {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                    else -> null
                }
            }.filterNotNull()
            rows += DiagRow(
                ctx.getString(R.string.di_hdr),
                if (hdrNames.isEmpty()) ctx.getString(R.string.di_none) else hdrNames.joinToString(", ")
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                rows += DiagRow(
                    ctx.getString(R.string.di_wide_gamut),
                    ctx.getString(if (display.isWideColorGamut) R.string.yes else R.string.no)
                )
            }
        }
        return DiagSection(ctx.getString(R.string.di_sec_display), rows)
    }

    // ------------------------------------------------------------------
    // Speicher
    // ------------------------------------------------------------------

    private fun storage(ctx: Context): DiagSection {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val percent = if (total > 0) ((total - free) * 100 / total).toInt() else 0
        return DiagSection(
            ctx.getString(R.string.di_sec_storage),
            listOf(
                DiagRow(ctx.getString(R.string.di_storage_total), formatBytes(total)),
                DiagRow(ctx.getString(R.string.di_storage_free), "${formatBytes(free)} ($percent % ${ctx.getString(R.string.di_used)})")
            )
        )
    }

    // ------------------------------------------------------------------
    // Akku
    // ------------------------------------------------------------------

    private fun battery(ctx: Context): DiagSection? {
        val intent: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val rows = mutableListOf<DiagRow>()

        intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }?.let {
            rows += DiagRow(ctx.getString(R.string.di_bat_tech), it)
        }
        val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (rawTemp != Int.MIN_VALUE) {
            rows += DiagRow(ctx.getString(R.string.di_bat_temp), String.format(Locale.US, "%.1f °C", rawTemp / 10.0))
        }
        val millivolt = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (millivolt > 0) rows += DiagRow(ctx.getString(R.string.di_bat_voltage), "$millivolt mV")

        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        rows += DiagRow(
            ctx.getString(R.string.di_bat_health),
            BatteryHealth.label(ctx, health),
            if (BatteryHealth.isCritical(health)) DiagStatus.WARN else DiagStatus.INFO
        )

        val manager = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val counterUah = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (percent in 5..100 && counterUah > 0) {
            val estimatedMah = counterUah / 1000.0 * 100.0 / percent
            rows += DiagRow(
                ctx.getString(R.string.di_bat_capacity_est),
                ctx.getString(R.string.di_bat_capacity_est_value, estimatedMah.toInt())
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cycles = intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
            if (cycles >= 0) rows += DiagRow(ctx.getString(R.string.di_bat_cycles), cycles.toString())
            val soh = manager.getIntProperty(10 /* BATTERY_PROPERTY_STATE_OF_HEALTH */)
            if (soh in 1..100) rows += DiagRow(ctx.getString(R.string.di_bat_soh), "$soh %")
        }
        return DiagSection(ctx.getString(R.string.di_sec_battery), rows)
    }

    // ------------------------------------------------------------------
    // Sicherheit
    // ------------------------------------------------------------------

    /** Alter des Sicherheits-Patchlevels in Tagen oder null, wenn nicht ermittelbar. */
    fun patchAgeDays(): Long? = try {
        val patch = Build.VERSION.SECURITY_PATCH
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)
        if (parsed == null) null else (System.currentTimeMillis() - parsed.time) / 86_400_000L
    } catch (e: Exception) {
        null
    }

    fun storageEncryptionStatus(ctx: Context): Int {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.storageEncryptionStatus
    }

    fun encryptionLabel(ctx: Context, status: Int): String = ctx.getString(
        when (status) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> R.string.di_enc_active
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> R.string.di_enc_default_key
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVATING -> R.string.di_enc_activating
            DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> R.string.di_enc_inactive
            else -> R.string.di_enc_unsupported
        }
    )

    /** Liest eine Systemeigenschaft über das mitgelieferte `getprop`. Kein Root nötig; null, wenn nicht lesbar. */
    fun getProp(name: String): String? = try {
        val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        text.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** true = SELinux erzwingt, false = permissiv, null = nicht lesbar. */
    fun selinuxEnforcing(): Boolean? = try {
        when (File("/sys/fs/selinux/enforce").readText().trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun security(ctx: Context): DiagSection {
        val rows = mutableListOf<DiagRow>()
        val age = patchAgeDays()
        rows += DiagRow(ctx.getString(R.string.di_patch), Build.VERSION.SECURITY_PATCH)
        if (age != null) rows += DiagRow(ctx.getString(R.string.di_patch_age), ctx.getString(R.string.di_days, age))

        rows += DiagRow(
            ctx.getString(R.string.di_encryption),
            encryptionLabel(ctx, storageEncryptionStatus(ctx))
        )

        val bootState = getProp("ro.boot.verifiedbootstate")
        rows += DiagRow(ctx.getString(R.string.di_verified_boot), bootState ?: ctx.getString(R.string.di_unreadable))

        val deviceState = getProp("ro.boot.vbmeta.device_state") ?: getProp("ro.boot.flash.locked")?.let {
            if (it == "1") "locked" else "unlocked"
        }
        rows += DiagRow(ctx.getString(R.string.di_bootloader_state), deviceState ?: ctx.getString(R.string.di_unreadable))

        val enforcing = selinuxEnforcing()
        rows += DiagRow(
            ctx.getString(R.string.di_selinux),
            when (enforcing) {
                true -> ctx.getString(R.string.di_sel_enforcing)
                false -> ctx.getString(R.string.di_sel_permissive)
                null -> ctx.getString(R.string.di_unreadable)
            }
        )
        return DiagSection(ctx.getString(R.string.di_sec_security), rows)
    }

    // ------------------------------------------------------------------
    // Software
    // ------------------------------------------------------------------

    private fun software(ctx: Context): DiagSection {
        val rows = mutableListOf<DiagRow>()
        rows += DiagRow(ctx.getString(R.string.di_build_type), "${Build.TYPE} / ${Build.TAGS}")
        rows += DiagRow(
            ctx.getString(R.string.di_build_time),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Build.TIME)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val webView = WebView.getCurrentWebViewPackage()
            if (webView != null) {
                rows += DiagRow(ctx.getString(R.string.di_webview), "${webView.packageName} ${webView.versionName}")
            }
        }
        rows += DiagRow(ctx.getString(R.string.di_locale), Locale.getDefault().toLanguageTag())
        rows += DiagRow(ctx.getString(R.string.di_timezone), TimeZone.getDefault().id)
        rows += DiagRow(ctx.getString(R.string.di_uptime), formatUptime(ctx, SystemClock.elapsedRealtime()))
        return DiagSection(ctx.getString(R.string.di_sec_software), rows)
    }

    fun formatUptime(ctx: Context, millis: Long): String {
        val minutes = millis / 60_000
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return ctx.getString(R.string.di_uptime_value, days, hours, mins)
    }

    // ------------------------------------------------------------------
    // Sensoren
    // ------------------------------------------------------------------

    private fun sensors(ctx: Context): DiagSection {
        val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val all = manager.getSensorList(Sensor.TYPE_ALL)
        val names = all.map { it.name }.distinct()
        val shown = names.take(MAX_SENSOR_NAMES).joinToString(", ") +
            if (names.size > MAX_SENSOR_NAMES) " …" else ""
        return DiagSection(
            ctx.getString(R.string.di_sec_sensors),
            listOf(
                DiagRow(ctx.getString(R.string.di_sensor_count), all.size.toString()),
                DiagRow(ctx.getString(R.string.di_sensor_names), shown.ifEmpty { ctx.getString(R.string.di_none) })
            )
        )
    }

    private const val MAX_SENSOR_NAMES = 25
}
