package io.github.tux4us.miriquidihub

import android.content.Intent
import android.net.wifi.ScanResult
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * WLAN-Analyse: Kanalbelegung je Frequenzband, Empfehlung für 2,4 GHz und Liste der sichtbaren
 * Netze. Netzwerknamen erscheinen nur in der Anzeige; der exportierte Text enthält ausschließlich
 * Kanalstatistik (keine SSIDs, keine BSSIDs).
 */
class WifiAnalysisActivity : ToolActivity() {

    override val titleRes: Int = R.string.wa_title
    override val footnoteRes: Int = R.string.wa_footnote

    private var exportSections: List<DiagSection> = emptyList()

    /** True, solange die Suche wegen ausgeschalteter Standortdienste wartet; nach der Rückkehr wird neu gesucht. */
    private var waitingForLocation = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[WifiScanHelper.requiredPermission()] == true) scan()
            else setSummary(DiagStatus.ERROR, getString(R.string.wa_permission_denied))
        }

    override fun onToolCreated() {
        setPrimaryAction(R.string.wa_scan) { startScan() }
        startScan()
    }

    override fun onRefresh() {
        startScan()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForLocation && WifiScanHelper.isLocationEnabled(this)) {
            waitingForLocation = false
            startScan()
        }
    }

    override fun exportText(): String =
        DiagRenderer.toPlainText(getString(R.string.wa_title) + " – MiriquidiHub", exportSections)

    private fun startScan() {
        if (!WifiScanHelper.hasPermission(this)) {
            permissionLauncher.launch(WifiScanHelper.requiredPermissions())
            return
        }
        scan()
    }

    private fun scan() {
        if (!WifiScanHelper.isWifiEnabled(this)) {
            showSections(emptyList())
            setSummary(DiagStatus.WARN, getString(R.string.wa_wifi_off))
            return
        }
        if (!WifiScanHelper.isLocationEnabled(this)) {
            showSections(emptyList())
            setSummary(DiagStatus.WARN, getString(R.string.wa_location_off))
            setSecondaryAction(R.string.wa_open_location_settings) {
                waitingForLocation = true
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            waitingForLocation = true
            return
        }
        waitingForLocation = false
        binding.btnSecondary.visibility = View.GONE
        setBusy(true)
        setSummary(null, getString(R.string.wa_scanning))
        lifecycleScope.launch {
            val results = WifiScanHelper.scan(this@WifiAnalysisActivity)
            setBusy(false)
            show(results)
        }
    }

    @Suppress("DEPRECATION")
    private fun show(results: List<ScanResult>) {
        if (results.isEmpty()) {
            showSections(emptyList())
            exportSections = emptyList()
            setSummary(DiagStatus.INFO, getString(R.string.wa_none))
            return
        }
        val aps = results.map { WifiAp(WifiScanHelper.channelOf(it.frequency), it.frequency, it.level, it.SSID.orEmpty(), it.capabilities.orEmpty()) }
        val stats = mutableListOf<DiagSection>()

        for (band in listOf("2,4 GHz", "5 GHz", "6 GHz")) {
            val inBand = aps.filter { WifiScanHelper.bandOf(it.frequency) == band && it.channel > 0 }
            if (inBand.isEmpty()) continue
            val counts = ChannelAnalysis.countPerChannel(inBand.map { ChannelAnalysis.Ap(it.channel, it.rssi) })
            val rows = counts.map { (channel, count) ->
                val strongest = inBand.filter { it.channel == channel }.maxOf { it.rssi }
                DiagRow(
                    getString(R.string.wa_channel, channel),
                    getString(R.string.wa_channel_value, count, strongest),
                    if (count >= CROWDED) DiagStatus.WARN else DiagStatus.INFO
                )
            }.toMutableList()
            if (band == "2,4 GHz") {
                val recommended = ChannelAnalysis.recommend24(inBand.map { ChannelAnalysis.Ap(it.channel, it.rssi) })
                rows += DiagRow(getString(R.string.wa_recommended), getString(R.string.wa_channel, recommended), DiagStatus.OK)
            }
            stats += DiagSection(getString(R.string.wa_section_band, band), rows)
        }
        exportSections = stats

        val networkRows = aps.sortedByDescending { it.rssi }.map { ap ->
            val security = securityOf(ap.capabilities)
            DiagRow(
                ap.ssid.ifEmpty { getString(R.string.wa_hidden) },
                getString(R.string.wa_network_value, ap.channel, ap.rssi, security.first),
                if (security.second) DiagStatus.WARN else DiagStatus.INFO
            )
        }
        showSections(stats + DiagSection(getString(R.string.wa_section_networks, aps.size), networkRows))
        setSummary(DiagStatus.INFO, getString(R.string.wa_summary, aps.size))
    }

    private class WifiAp(val channel: Int, val frequency: Int, val rssi: Int, val ssid: String, val capabilities: String)

    /** Sicherheitsart (Anzeige) und ob sie als schwach/offen zu markieren ist. */
    private fun securityOf(capabilities: String): Pair<String, Boolean> = when {
        capabilities.contains("SAE") || capabilities.contains("WPA3") -> "WPA3" to false
        capabilities.contains("WPA2") -> "WPA2" to false
        capabilities.contains("WPA") -> "WPA" to true
        capabilities.contains("WEP") -> "WEP" to true
        else -> getString(R.string.wa_open) to true
    }

    companion object {
        private const val CROWDED = 4
    }
}
