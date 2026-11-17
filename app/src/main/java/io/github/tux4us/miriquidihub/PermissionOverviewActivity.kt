package io.github.tux4us.miriquidihub

import android.Manifest
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zeigt, welche installierten Apps sensible Berechtigungen erteilt bekommen haben. Die Übersicht
 * zählt je Berechtigung; Antippen listet die Apps, Antippen einer App öffnet deren App-Info.
 * Benötigt QUERY_ALL_PACKAGES (nur für die F-Droid-Variante vorgesehen).
 */
class PermissionOverviewActivity : ToolActivity() {

    override val titleRes: Int = R.string.po_title
    override val footnoteRes: Int = R.string.po_footnote

    private class Grant(val label: String, val packageName: String, val system: Boolean)

    private val catalog: List<Pair<String, Int>> = buildList {
        add(Manifest.permission.CAMERA to R.string.po_camera)
        add(Manifest.permission.RECORD_AUDIO to R.string.po_microphone)
        add(Manifest.permission.ACCESS_FINE_LOCATION to R.string.po_location)
        add(Manifest.permission.ACCESS_BACKGROUND_LOCATION to R.string.po_location_background)
        add(Manifest.permission.READ_CONTACTS to R.string.po_contacts)
        add(Manifest.permission.READ_CALENDAR to R.string.po_calendar)
        add(Manifest.permission.READ_SMS to R.string.po_sms)
        add(Manifest.permission.READ_CALL_LOG to R.string.po_call_log)
        add(Manifest.permission.READ_PHONE_STATE to R.string.po_phone)
        add(Manifest.permission.BODY_SENSORS to R.string.po_body_sensors)
        add(Manifest.permission.READ_EXTERNAL_STORAGE to R.string.po_storage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES to R.string.po_media_images)
            add(Manifest.permission.POST_NOTIFICATIONS to R.string.po_notifications)
        }
    }

    private var grants: Map<String, List<Grant>> = emptyMap()
    private var selected: String? = null
    private var includeSystem = false

    override fun onToolCreated() {
        load()
    }

    override fun onRefresh() {
        load()
    }

    private fun load() {
        setBusy(true)
        lifecycleScope.launch {
            grants = withContext(Dispatchers.IO) { collectGrants() }
            setBusy(false)
            render()
        }
    }

    @Suppress("DEPRECATION")
    private fun collectGrants(): Map<String, List<Grant>> {
        val pm = packageManager
        val wanted = catalog.map { it.first }.toSet()
        val result = HashMap<String, MutableList<Grant>>()
        for (info: PackageInfo in AppQueries.installedPackages(pm, PackageManager.GET_PERMISSIONS)) {
            if (info.packageName == packageName) continue
            val requested = info.requestedPermissions ?: continue
            val flags = info.requestedPermissionsFlags ?: continue
            val app = info.applicationInfo
            val grant = Grant(AppQueries.label(pm, info.packageName, app), info.packageName, AppQueries.isSystemApp(app))
            for (i in requested.indices) {
                val permission = requested[i]
                if (permission in wanted && i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                    result.getOrPut(permission) { mutableListOf() } += grant
                }
            }
        }
        return result
    }

    private fun visible(permission: String): List<Grant> =
        grants[permission].orEmpty().filter { includeSystem || !it.system }.sortedBy { it.label.lowercase() }

    private fun render() {
        val current = selected
        if (current == null) renderOverview() else renderDetail(current)
    }

    private fun renderOverview() {
        val rows = catalog.map { (permission, labelRes) ->
            val count = visible(permission).size
            DiagRow(
                getString(labelRes), getString(R.string.po_apps_count, count),
                if (count > 0) DiagStatus.INFO else DiagStatus.OK
            ) {
                selected = permission
                render()
            }
        }
        showSections(listOf(DiagSection(getString(R.string.po_section_overview), rows)))
        setSummary(DiagStatus.INFO, getString(R.string.po_summary_overview))
        setPrimaryAction(if (includeSystem) R.string.po_hide_system else R.string.po_show_system) {
            includeSystem = !includeSystem
            render()
        }
        binding.btnSecondary.visibility = android.view.View.GONE
    }

    private fun renderDetail(permission: String) {
        val labelRes = catalog.first { it.first == permission }.second
        val list = visible(permission)
        val rows = list.map { grant ->
            DiagRow(grant.label, grant.packageName, DiagStatus.INFO) { AppSettings.open(this, grant.packageName) }
        }
        showSections(listOf(DiagSection(getString(R.string.po_section_apps, getString(labelRes), list.size), rows)))
        setSummary(DiagStatus.INFO, getString(R.string.po_summary_detail))
        setPrimaryAction(R.string.po_back) {
            selected = null
            render()
        }
        setSecondaryAction(if (includeSystem) R.string.po_hide_system else R.string.po_show_system) {
            includeSystem = !includeSystem
            render()
        }
    }
}
