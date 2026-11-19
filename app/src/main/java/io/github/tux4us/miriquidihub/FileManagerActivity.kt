package io.github.tux4us.miriquidihub

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tux4us.miriquidihub.databinding.ActivityFileManagerBinding
import io.github.tux4us.miriquidihub.databinding.DialogFmInputBinding
import io.github.tux4us.miriquidihub.databinding.DialogFmProgressBinding
import io.github.tux4us.miriquidihub.files.AlreadyExistsException
import io.github.tux4us.miriquidihub.files.CancelledException
import io.github.tux4us.miriquidihub.files.ConflictPolicy
import io.github.tux4us.miriquidihub.files.Connection
import io.github.tux4us.miriquidihub.files.ConnectionStore
import io.github.tux4us.miriquidihub.files.DropRules
import io.github.tux4us.miriquidihub.files.FileClipboard
import io.github.tux4us.miriquidihub.files.FileEntry
import io.github.tux4us.miriquidihub.files.FileSource
import io.github.tux4us.miriquidihub.files.FileTools
import io.github.tux4us.miriquidihub.files.LocalArchive
import io.github.tux4us.miriquidihub.files.LocalSource
import io.github.tux4us.miriquidihub.files.PathUtil
import io.github.tux4us.miriquidihub.files.TransferFailure
import io.github.tux4us.miriquidihub.files.TransferManager
import io.github.tux4us.miriquidihub.files.TransferResult
import io.github.tux4us.miriquidihub.files.UploadItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Dateimanager für den lokalen Speicher sowie WebDAV-, Nextcloud- und SMB-Verbindungen, wahlweise mit
 * einem oder zwei Bereichen (geteilte Ansicht), als Liste oder Raster und mit Drag and Drop.
 * Kopieren, Verschieben, Löschen, Hochladen und Packen laufen im [TransferManager] und damit auch
 * im Hintergrund weiter. Quellen: siehe [FileSource]. Zustand eines Bereichs: siehe [FilePane].
 */
class FileManagerActivity : AppCompatActivity(), FilePane.Host {

    private enum class Sort { NAME, SIZE, DATE, TYPE }

    private class Location(val id: String, val label: String, val local: LocalSource?, val connection: Connection?)

    private lateinit var binding: ActivityFileManagerBinding
    private val store by lazy { ConnectionStore(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private lateinit var panes: List<FilePane>
    private var activeIndex = 0
    private val active: FilePane get() = panes[activeIndex]
    private var splitEnabled = false
    private var gridView = false

    private var locations: List<Location> = emptyList()
    private val openRemote = HashMap<String, FileSource>()

    /** Quelle und Pfad des aktiven Bereichs. */
    private val current: FileSource? get() = active.source
    private val currentPath: String get() = active.path

    private var actionMode: ActionMode? = null
    private var searchItem: MenuItem? = null

    private var showHiddenFiles = false
    private var sort = Sort.NAME
    private var ascending = true

    override val scope: CoroutineScope get() = lifecycleScope

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) startUpload(uris)
    }
    private val legacyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        panes.forEach { if (it.source is LocalSource) it.navigate(it.path) }
        invalidateOptionsMenu()
    }

    // ------------------------------------------------------------------
    // Lebenszyklus
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.fm_title)

        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot) { view, insets ->
            view.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        showHiddenFiles = prefs.getBoolean(KEY_HIDDEN, false)
        sort = runCatching { Sort.valueOf(prefs.getString(KEY_SORT, Sort.NAME.name) ?: Sort.NAME.name) }
            .getOrDefault(Sort.NAME)
        ascending = prefs.getBoolean(KEY_ASC, true)
        gridView = prefs.getBoolean(KEY_GRID, false)
        splitEnabled = prefs.getBoolean(KEY_SPLIT, false)

        panes = listOf(FilePane(0, binding.pane1, this), FilePane(1, binding.pane2, this))
        panes.forEach { it.setGrid(gridView) }
        panes[0].setActive(true)
        applyPaneLayout()

        binding.btnPaste.setOnClickListener { paste() }
        binding.btnClipClear.setOnClickListener {
            FileClipboard.clear()
            updatePasteBar()
        }
        binding.btnTransferCancel.setOnClickListener { TransferManager.cancel() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    actionMode != null -> actionMode?.finish()
                    searchItem?.isActionViewExpanded == true -> searchItem?.collapseActionView()
                    active.path != PathUtil.ROOT -> active.navigate(PathUtil.parent(active.path) ?: PathUtil.ROOT)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TransferManager.state.collect { renderTransfer(it) }
            }
        }

        cleanOpenCache()
        refreshLocations()
        val first = locations.firstOrNull { it.id == prefs.getString(KEY_LOCATION, null) } ?: locations.first()
        selectLocation(panes[0], first, rememberedPath(0, first.id), force = true)
        if (splitEnabled) initSecondPane()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPaneLayout()
    }

    override fun onResume() {
        super.onResume()
        panes.forEach { if (it.source is LocalSource && it.needsPermission && hasAllFilesAccess()) it.navigate(it.path) }
        updatePasteBar()
        invalidateOptionsMenu()
    }

    override fun onDestroy() {
        panes.forEach { it.cancelJobs() }
        // Quellen, die noch in der Zwischenablage liegen oder von einem laufenden Vorgang genutzt werden, bleiben offen.
        if (!TransferManager.isBusy) {
            val keep = FileClipboard.source
            val toClose = openRemote.values.filter { it !== keep }
            Thread { toClose.forEach { runCatching { it.close() } } }.start()
        }
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ------------------------------------------------------------------
    // Bereiche: Anordnung, Aktivierung, geteilte Ansicht
    // ------------------------------------------------------------------

    /** Querformat: nebeneinander, Hochformat: übereinander. */
    private fun applyPaneLayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.panesContainer.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (p in panes) {
            val lp = p.binding.root.layoutParams as LinearLayout.LayoutParams
            lp.width = if (land) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = if (land) ViewGroup.LayoutParams.MATCH_PARENT else 0
            lp.weight = 1f
            p.binding.root.layoutParams = lp
        }
        panes[1].binding.root.visibility = if (splitEnabled) View.VISIBLE else View.GONE
    }

    private fun setSplit(on: Boolean) {
        if (on == splitEnabled) return
        splitEnabled = on
        prefs.edit().putBoolean(KEY_SPLIT, on).apply()
        if (!on && activeIndex == 1) setActive(0)
        applyPaneLayout()
        if (on) {
            if (panes[1].source == null) initSecondPane() else panes[1].load()
        }
        invalidateOptionsMenu()
    }

    /** Startort des zweiten Bereichs: zuletzt verwendeter, sonst der Ort des ersten Bereichs. */
    private fun initSecondPane() {
        val first = panes[0]
        val loc = locations.firstOrNull { it.id == prefs.getString(KEY_LOCATION_2, null) }
            ?: locations.firstOrNull { it.id == first.locationId }
            ?: locations.first()
        val remembered = prefs.getString(KEY_PATH2_PREFIX + loc.id, null)
            ?: if (loc.id == first.locationId) first.path else PathUtil.ROOT
        selectLocation(panes[1], loc, remembered, force = true)
    }

    private fun setGridView(on: Boolean) {
        gridView = on
        prefs.edit().putBoolean(KEY_GRID, on).apply()
        panes.forEach { it.setGrid(on) }
        invalidateOptionsMenu()
    }

    private fun setActive(index: Int) {
        if (index == activeIndex) return
        // Suche und Auswahl gehören zum bisherigen Bereich.
        searchItem?.collapseActionView()
        actionMode?.finish()
        activeIndex = index
        panes.forEach { it.setActive(it.index == index) }
        updateChipChecked()
        updateSubtitle()
        updatePasteBar()
        invalidateOptionsMenu()
    }

    /** Der jeweils andere sichtbare Bereich mit geöffnetem Speicherort, sonst null. */
    private fun otherPane(): FilePane? {
        if (!splitEnabled) return null
        val p = panes[1 - activeIndex]
        return if (p.visible && p.source != null && !p.needsPermission) p else null
    }

    private fun updateSubtitle() {
        supportActionBar?.subtitle = locationLabel(active.locationId)
    }

    private fun rememberedPath(paneIndex: Int, locationId: String): String =
        prefs.getString((if (paneIndex == 0) KEY_PATH_PREFIX else KEY_PATH2_PREFIX) + locationId, PathUtil.ROOT) ?: PathUtil.ROOT

    // ------------------------------------------------------------------
    // FilePane.Host
    // ------------------------------------------------------------------

    override fun locationLabel(id: String?): String = locations.firstOrNull { it.id == id }?.label.orEmpty()

    override fun showHidden(): Boolean = showHiddenFiles

    override fun comparator(): Comparator<FileEntry> {
        val byName = Comparator<FileEntry> { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        val base: Comparator<FileEntry> = when (sort) {
            Sort.NAME -> byName
            Sort.SIZE -> Comparator { a, b -> a.size.compareTo(b.size) }
            Sort.DATE -> Comparator { a, b -> a.modified.compareTo(b.modified) }
            Sort.TYPE -> Comparator { a, b -> a.extension.compareTo(b.extension, ignoreCase = true) }
        }
        val directed = if (ascending) base else base.reversed()
        val foldersFirst = Comparator<FileEntry> { a, b -> (if (a.isDirectory) 0 else 1) - (if (b.isDirectory) 0 else 1) }
        return foldersFirst.then(directed).then(byName)
    }

    override fun onPaneTouched(pane: FilePane) = setActive(pane.index)

    override fun onUserNavigate(pane: FilePane) {
        searchItem?.collapseActionView()
    }

    override fun onPathChanged(pane: FilePane) {
        val id = pane.locationId ?: return
        prefs.edit().putString((if (pane.index == 0) KEY_PATH_PREFIX else KEY_PATH2_PREFIX) + id, pane.path).apply()
    }

    override fun onGrantPermission() = requestAllFilesAccess()

    /** Lädt alle sichtbaren Bereiche neu (z. B. nach einem Vorgang, der beide betreffen kann). */
    private fun load() {
        panes.filter { it.visible }.forEach { it.load() }
    }

    // ------------------------------------------------------------------
    // Speicherorte
    // ------------------------------------------------------------------

    private fun refreshLocations() {
        val list = ArrayList<Location>()
        val internalLabel = getString(R.string.fm_internal_storage)
        list.add(
            Location(
                "local:internal", internalLabel,
                LocalSource("local:internal", internalLabel, Environment.getExternalStorageDirectory()), null
            )
        )
        // Wechseldatenträger: Wurzel aus dem app-spezifischen Pfad ableiten.
        val seen = HashSet<String>()
        for (dir in ContextCompat.getExternalFilesDirs(this, null).drop(1)) {
            val path = dir?.absolutePath ?: continue
            val root = path.substringBefore("/Android/data")
            if (root == path || !seen.add(root)) continue
            val f = File(root)
            if (!f.isDirectory) continue
            val id = "local:" + f.name
            val label = getString(R.string.fm_removable_storage, f.name)
            list.add(Location(id, label, LocalSource(id, label, f), null))
        }
        store.list().forEach { c -> list.add(Location("conn:" + c.id, c.name, null, c)) }
        locations = list
        rebuildChips()
    }

    private fun rebuildChips() {
        binding.chipGroup.removeAllViews()
        for (loc in locations) {
            val chip = Chip(this).apply {
                text = loc.label
                tag = loc.id
                isCheckable = true
                isCheckedIconVisible = false
                chipIcon = ContextCompat.getDrawable(
                    this@FileManagerActivity,
                    when {
                        loc.connection != null -> R.drawable.ic_fm_cloud
                        loc.id == "local:internal" -> R.drawable.ic_fm_phone
                        else -> R.drawable.ic_fm_sd
                    }
                )
                isChipIconVisible = true
                setOnClickListener { selectLocation(active, loc, null, force = false) }
                if (loc.connection != null) {
                    setOnLongClickListener {
                        showConnectionMenu(this, loc)
                        true
                    }
                }
            }
            binding.chipGroup.addView(chip)
        }
        val add = Chip(this).apply {
            text = getString(R.string.fm_conn_add_chip)
            isCheckable = false
            setOnClickListener { showConnectionDialog(null) }
        }
        binding.chipGroup.addView(add)
        updateChipChecked()
    }

    /** Die Chips zeigen den Ort des aktiven Bereichs. */
    private fun updateChipChecked() {
        val id = panes.getOrNull(activeIndex)?.locationId
        for (i in 0 until binding.chipGroup.childCount) {
            val chip = binding.chipGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isCheckable) chip.isChecked = chip.tag == id
        }
    }

    private fun selectLocation(pane: FilePane, loc: Location, startPath: String?, force: Boolean) {
        if (!force && loc.id == pane.locationId) {
            updateChipChecked()
            return
        }
        if (pane === active) {
            searchItem?.collapseActionView()
            actionMode?.finish()
        }
        pane.cancelJobs()
        pane.locationId = loc.id
        prefs.edit().putString(if (pane.index == 0) KEY_LOCATION else KEY_LOCATION_2, loc.id).apply()
        if (pane === active) updateSubtitle()
        updateChipChecked()

        var error: String? = null
        val source: FileSource? = loc.local ?: try {
            openRemote.getOrPut(loc.id) { store.openSource(loc.connection!!) }
        } catch (e: IOException) {
            error = FileErrors.describe(this, e)
            null
        }
        pane.source = source
        if (source == null) {
            pane.rawEntries = emptyList()
            pane.listError = error
            pane.updateBreadcrumb()
            pane.applyFilter()
            return
        }
        pane.listError = null
        pane.navigate(startPath ?: rememberedPath(pane.index, loc.id))
        invalidateOptionsMenu()
    }

    // ----- Verbindungen verwalten -----

    private fun showConnectionDialog(existing: Connection?) {
        FileConnectionDialog(this, store, existing) { saved ->
            closeRemote(saved.id)
            refreshLocations()
            locations.firstOrNull { it.id == "conn:" + saved.id }?.let { loc ->
                // Der aktive Bereich wechselt zur Verbindung; ein anderer Bereich mit alter Quelle öffnet sie neu.
                panes.forEach { if (it === active || it.locationId == loc.id) selectLocation(it, loc, null, force = true) }
            }
        }.show()
    }

    private fun showConnectionMenu(anchor: View, loc: Location) {
        val conn = loc.connection ?: return
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, R.string.fm_conn_edit)
        pm.menu.add(0, 2, 1, R.string.fm_conn_remove)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showConnectionDialog(conn)
                2 -> confirmRemoveConnection(conn)
            }
            true
        }
        pm.show()
    }

    private fun confirmRemoveConnection(conn: Connection) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fm_conn_remove)
            .setMessage(getString(R.string.fm_conn_remove_msg, conn.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                store.delete(conn.id)
                closeRemote(conn.id)
                val affected = panes.filter { it.locationId == "conn:" + conn.id }
                refreshLocations()
                affected.forEach { selectLocation(it, locations.first(), PathUtil.ROOT, force = true) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun closeRemote(connectionId: String) {
        val s = openRemote.remove("conn:$connectionId") ?: return
        if (FileClipboard.source?.id == s.id) {
            FileClipboard.clear()
            updatePasteBar()
        }
        if (!TransferManager.isBusy) Thread { runCatching { s.close() } }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Einträge: Klicks, Auswahl, Ziehen
    // ------------------------------------------------------------------

    override fun onEntryClick(pane: FilePane, entry: FileEntry) {
        setActive(pane.index)
        if (actionMode != null) {
            pane.toggleSelect(entry)
            return
        }
        if (entry.isDirectory) {
            searchItem?.collapseActionView()
            pane.navigate(entry.path)
        } else {
            openFile(entry)
        }
    }

    /** Langer Druck wählt den Eintrag aus und beginnt das Ziehen der Auswahl. */
    override fun onEntryLongClick(pane: FilePane, entry: FileEntry, view: View) {
        setActive(pane.index)
        if (actionMode == null) {
            actionMode = startSupportActionMode(actionCallback)
            pane.setSelectionMode(true)
        }
        if (entry.path !in pane.selection) pane.toggleSelect(entry)
        startDrag(pane, view)
    }

    override fun onSelectionChanged(pane: FilePane) {
        if (pane !== active) return
        if (pane.hasSelection) actionMode?.invalidate() else actionMode?.finish()
    }

    private fun startDrag(pane: FilePane, anchor: View) {
        val src = pane.source ?: return
        val entries = pane.selectedEntries()
        if (entries.isEmpty()) return
        val label = if (entries.size == 1) entries[0].name else getString(R.string.fm_drag_count, entries.size)
        val shadow = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            maxWidth = dp(240)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.bg_fm_drag_shadow)
        }
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        shadow.measure(unspecified, unspecified)
        shadow.layout(0, 0, shadow.measuredWidth, shadow.measuredHeight)
        val clip = ClipData.newPlainText("fm", label)
        anchor.startDragAndDrop(clip, View.DragShadowBuilder(shadow), DragPayload(pane.index, src, entries), 0)
    }

    /** Ablage: fragt nach Kopieren oder Verschieben, sofern etwas zu tun ist. */
    override fun onDrop(pane: FilePane, payload: DragPayload, target: DropTarget) {
        val to = pane.source ?: return
        val dir = PathUtil.normalize(target.dir)
        // Ablage auf einen der gezogenen Ordner selbst (langer Druck ohne Ziehen) ist keine Aktion.
        if (payload.source.id == to.id && payload.entries.any { it.path == dir }) return
        when (DropRules.check(payload.source.id, payload.entries, to.id, dir, move = true)) {
            DropRules.Verdict.NOTHING_TO_DO -> return
            DropRules.Verdict.INTO_ITSELF -> {
                toast(R.string.fm_err_into_itself)
                return
            }
            DropRules.Verdict.OK -> {}
        }
        val name = if (dir == PathUtil.ROOT) locationLabel(pane.locationId) else PathUtil.name(dir)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.fm_drop_title, payload.entries.size, name))
            .setPositiveButton(R.string.fm_move) { _, _ ->
                actionMode?.finish()
                startTransfer(payload.source, payload.entries, to, dir, move = true, fromClipboard = false)
            }
            .setNeutralButton(R.string.fm_copy) { _, _ ->
                actionMode?.finish()
                startTransfer(payload.source, payload.entries, to, dir, move = false, fromClipboard = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val actionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menuInflater.inflate(R.menu.menu_file_selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val sel = active.selectedEntries()
            val local = current is LocalSource
            val split = otherPane() != null
            mode.title = getString(R.string.fm_selected_count, active.selection.size)
            menu.findItem(R.id.action_sel_copy_other)?.isVisible = split
            menu.findItem(R.id.action_sel_move_other)?.isVisible = split
            menu.findItem(R.id.action_sel_rename)?.isVisible = sel.size == 1
            menu.findItem(R.id.action_sel_properties)?.isVisible = sel.size == 1
            menu.findItem(R.id.action_sel_share)?.isVisible = local && sel.isNotEmpty() && sel.none { it.isDirectory }
            menu.findItem(R.id.action_sel_download)?.isVisible = !local
            menu.findItem(R.id.action_sel_zip)?.isVisible = local
            menu.findItem(R.id.action_sel_unzip)?.isVisible = local && sel.size == 1 && FileTypes.isZip(sel[0])
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val sel = active.selectedEntries()
            when (item.itemId) {
                R.id.action_sel_copy -> putOnClipboard(sel, move = false)
                R.id.action_sel_cut -> putOnClipboard(sel, move = true)
                R.id.action_sel_copy_other -> transferToOtherPane(sel, move = false)
                R.id.action_sel_move_other -> transferToOtherPane(sel, move = true)
                R.id.action_sel_delete -> confirmDelete(sel)
                R.id.action_sel_rename -> sel.firstOrNull()?.let { promptRename(it) }
                R.id.action_sel_share -> shareSelected(sel)
                R.id.action_sel_download -> downloadSelected(sel)
                R.id.action_sel_zip -> promptZip(sel)
                R.id.action_sel_unzip -> sel.firstOrNull()?.let { unzip(it) }
                R.id.action_sel_properties -> sel.firstOrNull()?.let { showProperties(it) }
                R.id.action_sel_all -> active.selectAll()
                else -> return false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            panes.forEach { it.resetSelectionSilently() }
        }
    }

    // ------------------------------------------------------------------
    // Menü der Toolbar
    // ------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_file_manager, menu)
        searchItem = menu.findItem(R.id.action_fm_search)
        val sv = searchItem?.actionView as? SearchView
        sv?.queryHint = getString(R.string.fm_search_hint)
        sv?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                active.startRecursiveSearch(q.orEmpty())
                sv?.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Neue Eingabe verwirft Suchergebnisse und filtert wieder nur den aktuellen Ordner.
                active.setQuery(newText.orEmpty())
                return true
            }
        })
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                active.setQuery("")
                return true
            }
        })
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val src = current
        val usable = src != null && !(src is LocalSource && !hasAllFilesAccess())
        menu.findItem(R.id.action_fm_new_folder)?.isVisible = usable
        menu.findItem(R.id.action_fm_new_file)?.isVisible = usable
        menu.findItem(R.id.action_fm_upload)?.isVisible = usable && src?.isRemote == true
        menu.findItem(R.id.action_fm_hidden)?.isChecked = showHiddenFiles
        menu.findItem(R.id.action_fm_view)?.apply {
            setIcon(if (gridView) R.drawable.ic_fm_view_list else R.drawable.ic_fm_view_grid)
            setTitle(if (gridView) R.string.fm_view_list else R.string.fm_view_grid)
        }
        menu.findItem(R.id.action_fm_split)?.icon?.mutate()?.alpha = if (splitEnabled) 255 else 130
        menu.findItem(
            when (sort) {
                Sort.NAME -> R.id.sort_name
                Sort.SIZE -> R.id.sort_size
                Sort.DATE -> R.id.sort_date
                Sort.TYPE -> R.id.sort_type
            }
        )?.isChecked = true
        menu.findItem(R.id.sort_descending)?.isChecked = !ascending
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_fm_refresh -> load()
            R.id.action_fm_view -> setGridView(!gridView)
            R.id.action_fm_split -> setSplit(!splitEnabled)
            R.id.action_fm_new_folder -> promptNewFolder()
            R.id.action_fm_new_file -> promptNewFile()
            R.id.action_fm_upload -> pickFiles.launch(arrayOf("*/*"))
            R.id.action_fm_hidden -> {
                showHiddenFiles = !item.isChecked
                item.isChecked = showHiddenFiles
                prefs.edit().putBoolean(KEY_HIDDEN, showHiddenFiles).apply()
                panes.forEach { it.applyFilter() }
            }
            R.id.sort_name, R.id.sort_size, R.id.sort_date, R.id.sort_type -> {
                sort = when (item.itemId) {
                    R.id.sort_size -> Sort.SIZE
                    R.id.sort_date -> Sort.DATE
                    R.id.sort_type -> Sort.TYPE
                    else -> Sort.NAME
                }
                item.isChecked = true
                prefs.edit().putString(KEY_SORT, sort.name).apply()
                panes.forEach { it.applyFilter() }
            }
            R.id.sort_descending -> {
                ascending = item.isChecked // war "absteigend" an → jetzt aufsteigend
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean(KEY_ASC, ascending).apply()
                panes.forEach { it.applyFilter() }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------
    // Zwischenablage, Einfügen, Übertragen, Konflikte
    // ------------------------------------------------------------------

    private fun putOnClipboard(entries: List<FileEntry>, move: Boolean) {
        val src = current ?: return
        if (entries.isEmpty()) return
        FileClipboard.set(src, entries, move)
        actionMode?.finish()
        updatePasteBar()
    }

    private fun updatePasteBar() {
        if (!::binding.isInitialized || !::panes.isInitialized) return
        val empty = FileClipboard.isEmpty
        binding.pasteBar.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) {
            binding.tvClipboard.text = getString(
                if (FileClipboard.move) R.string.fm_clip_move else R.string.fm_clip_copy,
                FileClipboard.entries.size
            )
        }
        val src = current
        binding.btnPaste.isEnabled = src != null && !(src is LocalSource && !hasAllFilesAccess())
    }

    private fun paste() {
        val to = current ?: return
        val from = FileClipboard.source ?: return
        startTransfer(from, FileClipboard.entries, to, currentPath, FileClipboard.move, fromClipboard = true)
    }

    /** Auswahl direkt in den Ordner des anderen Bereichs kopieren oder verschieben. */
    private fun transferToOtherPane(entries: List<FileEntry>, move: Boolean) {
        val from = current ?: return
        val other = otherPane() ?: return
        val to = other.source ?: return
        if (entries.isEmpty()) return
        actionMode?.finish()
        startTransfer(from, entries, to, other.path, move, fromClipboard = false)
    }

    /** Gemeinsamer Weg für Einfügen, Ablegen und "in anderen Bereich": prüft, fragt bei Konflikten, startet. */
    private fun startTransfer(
        from: FileSource,
        entries: List<FileEntry>,
        to: FileSource,
        dir: String,
        move: Boolean,
        fromClipboard: Boolean
    ) {
        if (TransferManager.isBusy) {
            toast(R.string.fm_busy)
            return
        }
        when (DropRules.check(from.id, entries, to.id, dir, move)) {
            DropRules.Verdict.NOTHING_TO_DO -> {
                if (fromClipboard) {
                    FileClipboard.clear()
                    updatePasteBar()
                }
                return
            }
            DropRules.Verdict.INTO_ITSELF -> {
                toast(R.string.fm_err_into_itself)
                return
            }
            DropRules.Verdict.OK -> {}
        }
        val normalized = PathUtil.normalize(dir)
        val sameFolder = from.id == to.id && entries.all { PathUtil.parent(it.path) == normalized }
        val title = getString(if (move) R.string.fm_moving else R.string.fm_copying)
        withConflictPolicy(entries.map { it.name }, to, normalized, skipCheck = sameFolder) { policy ->
            val started = TransferManager.start(this, title) { engine ->
                engine.transfer(from, entries, to, normalized, move, policy)
            }
            if (!started) {
                toast(R.string.fm_busy)
            } else if (move && fromClipboard) {
                FileClipboard.clear()
                updatePasteBar()
            }
        }
    }

    /** Fragt bei vorhandenen Zielnamen einmal nach. [skipCheck]: Kopie in denselben Ordner, es wird nummeriert. */
    private fun withConflictPolicy(
        names: List<String>,
        to: FileSource,
        dir: String,
        skipCheck: Boolean,
        run: (ConflictPolicy) -> Unit
    ) {
        if (skipCheck) {
            run(ConflictPolicy.RENAME)
            return
        }
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    names.count { to.exists(PathUtil.child(dir, it)) }
                } catch (e: Exception) {
                    0
                }
            }
            if (count == 0) {
                run(ConflictPolicy.RENAME)
                return@launch
            }
            MaterialAlertDialogBuilder(this@FileManagerActivity)
                .setTitle(R.string.fm_conflict_title)
                .setMessage(getString(R.string.fm_conflict_msg, count))
                .setPositiveButton(R.string.fm_conflict_overwrite) { _, _ -> run(ConflictPolicy.OVERWRITE) }
                .setNeutralButton(R.string.fm_conflict_keep_both) { _, _ -> run(ConflictPolicy.RENAME) }
                .setNegativeButton(R.string.fm_conflict_skip) { _, _ -> run(ConflictPolicy.SKIP) }
                .show()
        }
    }

    // ------------------------------------------------------------------
    // Löschen, Umbenennen, Anlegen
    // ------------------------------------------------------------------

    private fun confirmDelete(entries: List<FileEntry>) {
        val src = current ?: return
        if (entries.isEmpty()) return
        val what = if (entries.size == 1) getString(R.string.fm_delete_one, entries[0].name)
        else getString(R.string.fm_delete_many, entries.size)
        val b = MaterialAlertDialogBuilder(this).setTitle(R.string.delete)
        if (src is LocalSource) {
            b.setMessage(what + "\n\n" + getString(R.string.fm_delete_local_hint))
                .setPositiveButton(R.string.fm_to_trash) { _, _ -> runDelete(src, entries, toTrash = true) }
                .setNeutralButton(R.string.fm_delete_permanent) { _, _ -> runDelete(src, entries, toTrash = false) }
        } else {
            b.setMessage(what + "\n\n" + getString(R.string.fm_delete_remote_hint))
                .setPositiveButton(R.string.delete) { _, _ -> runDelete(src, entries, toTrash = false) }
        }
        b.setNegativeButton(R.string.cancel, null).show()
    }

    private fun runDelete(src: FileSource, entries: List<FileEntry>, toTrash: Boolean) {
        actionMode?.finish()
        val started = TransferManager.start(this, getString(R.string.fm_deleting)) { engine ->
            if (toTrash && src is LocalSource) trashLocal(src, entries) else engine.delete(src, entries)
        }
        if (!started) toast(R.string.fm_busy)
    }

    private fun promptRename(e: FileEntry) {
        actionMode?.finish()
        promptText(R.string.fm_rename, e.name, selectBaseName = !e.isDirectory) { newName ->
            val src = current ?: return@promptText
            when {
                newName == e.name -> {}
                !PathUtil.isValidName(newName) -> toast(R.string.fm_err_invalid_name)
                else -> runIo({ src.rename(e.path, newName) }) { load() }
            }
        }
    }

    private fun promptNewFolder() {
        promptText(R.string.fm_new_folder, "", selectBaseName = false) { name ->
            val src = current ?: return@promptText
            if (!PathUtil.isValidName(name)) {
                toast(R.string.fm_err_invalid_name)
                return@promptText
            }
            val path = PathUtil.child(currentPath, name)
            runIo({
                if (src.exists(path)) throw AlreadyExistsException(path)
                src.mkdir(path)
            }) { load() }
        }
    }

    private fun promptNewFile() {
        promptText(R.string.fm_new_file, "", selectBaseName = true) { name ->
            val src = current ?: return@promptText
            if (!PathUtil.isValidName(name)) {
                toast(R.string.fm_err_invalid_name)
                return@promptText
            }
            val path = PathUtil.child(currentPath, name)
            runIo({ src.write(path, ByteArrayInputStream(ByteArray(0)), 0, overwrite = false) }) { load() }
        }
    }

    private fun promptText(titleRes: Int, initial: String, selectBaseName: Boolean, onOk: (String) -> Unit) {
        val b = DialogFmInputBinding.inflate(layoutInflater)
        b.tilInput.hint = getString(R.string.fm_name_label)
        b.etInput.setText(initial)
        val dot = initial.lastIndexOf('.')
        b.etInput.setSelection(0, if (selectBaseName && dot > 0) dot else initial.length)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(b.root)
            .setPositiveButton(R.string.save) { _, _ -> onOk(b.etInput.text?.toString()?.trim().orEmpty()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        b.etInput.requestFocus()
    }

    /** Führt [block] im Hintergrund aus und meldet Fehler als Meldung. */
    private fun <T> runIo(block: () -> T, onSuccess: (T) -> Unit) {
        val pane = active
        pane.setRefreshing(true)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            pane.setRefreshing(false)
            r.onSuccess(onSuccess).onFailure { toastError(it) }
        }
    }

    // ------------------------------------------------------------------
    // Öffnen, Teilen, Herunterladen, Hochladen
    // ------------------------------------------------------------------

    private fun openFile(e: FileEntry) {
        val src = current ?: return
        if (src is LocalSource) viewFile(src.toFile(e.path), e.name) else downloadAndOpen(src, e)
    }

    private fun viewFile(file: File, name: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, FileTypes.mimeOf(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.fm_err_no_app)
        } catch (e: IllegalArgumentException) {
            toast(R.string.fm_err_no_app)
        }
    }

    private fun downloadAndOpen(src: FileSource, e: FileEntry) {
        val dir = File(cacheDir, OPEN_CACHE).apply { mkdirs() }
        val target = File(dir, Integer.toHexString((src.id + e.path).hashCode()) + "_" + e.name)
        val pb = DialogFmProgressBinding.inflate(layoutInflater)
        pb.tvProgressText.text = e.name
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fm_downloading)
            .setView(pb.root)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener { job?.cancel() }
        job = lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        src.open(e.path).use { input ->
                            FileOutputStream(target).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                }
                r.onSuccess { viewFile(target, e.name) }.onFailure {
                    target.delete()
                    toastError(it)
                }
            } catch (c: CancellationException) {
                target.delete()
                throw c
            } finally {
                dialog.dismiss()
            }
        }
    }

    private fun shareSelected(entries: List<FileEntry>) {
        val src = current as? LocalSource ?: return
        val files = entries.filter { !it.isDirectory }
        if (files.isEmpty()) return
        val uris = try {
            files.map { FileProvider.getUriForFile(this, "$packageName.files", src.toFile(it.path)) }
        } catch (e: IllegalArgumentException) {
            toast(R.string.fm_err_no_app)
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(FileTypes.mimeOf(files[0].name)).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData.newRawUri(null, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        actionMode?.finish()
        startActivity(Intent.createChooser(intent, getString(R.string.fm_share)))
    }

    private fun downloadSelected(entries: List<FileEntry>) {
        val from = current ?: return
        if (entries.isEmpty()) return
        if (!hasAllFilesAccess()) {
            toast(R.string.fm_permission_hint)
            requestAllFilesAccess()
            return
        }
        val downloads = File(Environment.getExternalStorageDirectory(), "Download").apply { mkdirs() }
        val to = LocalSource("local:download", getString(R.string.fm_downloads), downloads)
        actionMode?.finish()
        val started = TransferManager.start(this, getString(R.string.fm_downloading)) { engine ->
            engine.transfer(from, entries, to, PathUtil.ROOT, false, ConflictPolicy.RENAME)
        }
        if (!started) toast(R.string.fm_busy) else toast(R.string.fm_download_target)
    }

    private fun startUpload(uris: List<Uri>) {
        val to = current ?: return
        val dir = currentPath
        val resolver = contentResolver
        val items = uris.map { uri ->
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val safeName = (name ?: uri.lastPathSegment ?: "Datei").replace('/', '_')
            UploadItem(safeName, size) { resolver.openInputStream(uri) ?: throw IOException("Datei nicht lesbar: $safeName") }
        }
        withConflictPolicy(items.map { it.name }, to, dir, skipCheck = false) { policy ->
            val started = TransferManager.start(this, getString(R.string.fm_uploading)) { engine ->
                engine.upload(items, to, dir, policy)
            }
            if (!started) toast(R.string.fm_busy)
        }
    }

    // ------------------------------------------------------------------
    // ZIP und Eigenschaften (lokal)
    // ------------------------------------------------------------------

    private fun promptZip(entries: List<FileEntry>) {
        val src = current as? LocalSource ?: return
        if (entries.isEmpty()) return
        val base = if (entries.size == 1) entries[0].name
        else PathUtil.name(currentPath).ifEmpty { getString(R.string.fm_archive_default) }
        actionMode?.finish()
        promptText(R.string.fm_zip, "$base.zip", selectBaseName = true) { typed ->
            val fileName = if (typed.lowercase().endsWith(".zip")) typed else "$typed.zip"
            if (!PathUtil.isValidName(fileName)) {
                toast(R.string.fm_err_invalid_name)
                return@promptText
            }
            val out = File(src.toFile(currentPath), fileName)
            if (out.exists()) {
                toast(R.string.fm_err_exists)
                return@promptText
            }
            val files = entries.map { src.toFile(it.path) }
            val started = TransferManager.start(this, getString(R.string.fm_zipping)) { engine ->
                runLocalJob(out.name) { LocalArchive.zip(files, out, engine::isCancelRequested, engine::step) }
            }
            if (!started) toast(R.string.fm_busy)
        }
    }

    private fun unzip(entry: FileEntry) {
        val src = current as? LocalSource ?: return
        val zip = src.toFile(entry.path)
        val parent = zip.parentFile ?: return
        val folder = PathUtil.uniqueName(LocalArchive.folderNameFor(entry.name)) { File(parent, it).exists() }
        val dest = File(parent, folder)
        actionMode?.finish()
        val started = TransferManager.start(this, getString(R.string.fm_unzipping)) { engine ->
            runLocalJob(entry.name) { LocalArchive.unzip(zip, dest, engine::isCancelRequested, engine::step) }
        }
        if (!started) toast(R.string.fm_busy)
    }

    private fun showProperties(e: FileEntry) {
        val src = current ?: return
        actionMode?.finish()
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_SHOW_YEAR
        val lines = ArrayList<String>()
        lines.add(getString(R.string.fm_prop_name, e.name))
        lines.add(getString(R.string.fm_prop_path, e.path))
        lines.add(
            getString(
                R.string.fm_prop_type,
                if (e.isDirectory) getString(R.string.fm_type_folder) else (e.mime ?: FileTypes.mimeOf(e.name))
            )
        )
        if (e.modified > 0) lines.add(getString(R.string.fm_prop_modified, DateUtils.formatDateTime(this, e.modified, flags)))
        if (!e.isDirectory && e.size >= 0) lines.add(getString(R.string.fm_prop_size, "${formatBytes(e.size)} (${e.size} B)"))
        val base = lines.joinToString("\n")
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fm_properties)
            .setMessage(base)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog.show()
        if (e.isDirectory) {
            val job = lifecycleScope.launch {
                val (files, bytes) = withContext(Dispatchers.IO) { FileTools.measure(src, e) { !isActive } }
                dialog.setMessage(base + "\n" + getString(R.string.fm_prop_content, files, formatBytes(bytes)))
            }
            dialog.setOnDismissListener { job.cancel() }
        }
    }

    // ------------------------------------------------------------------
    // Fortschritt und Ergebnis
    // ------------------------------------------------------------------

    private fun renderTransfer(st: TransferManager.State) {
        when (st) {
            is TransferManager.State.Idle -> binding.transferCard.visibility = View.GONE
            is TransferManager.State.Running -> {
                binding.transferCard.visibility = View.VISIBLE
                binding.tvTransferTitle.text = st.title
                val p = st.progress
                if (p == null) {
                    binding.tvTransferDetail.text = ""
                    binding.transferProgress.isIndeterminate = true
                } else {
                    binding.tvTransferDetail.text = if (p.currentSize > 0) {
                        "${p.currentName} · ${formatBytes(p.currentDone)} / ${formatBytes(p.currentSize)}"
                    } else {
                        p.currentName
                    }
                    if (p.currentSize > 0) {
                        binding.transferProgress.isIndeterminate = false
                        binding.transferProgress.setProgressCompat((p.currentDone * 100 / p.currentSize).toInt().coerceIn(0, 100), false)
                    } else {
                        binding.transferProgress.isIndeterminate = true
                    }
                }
            }
            is TransferManager.State.Finished -> {
                binding.transferCard.visibility = View.GONE
                showResult(st)
                TransferManager.acknowledge()
                load()
            }
        }
    }

    private fun showResult(st: TransferManager.State.Finished) {
        val r = st.result
        val error = st.error
        if (error != null) {
            MaterialAlertDialogBuilder(this).setTitle(st.title).setMessage(error)
                .setPositiveButton(R.string.close, null).show()
            return
        }
        if (r == null) return
        if (!r.hasProblems) {
            Toast.makeText(this, getString(R.string.fm_done, r.done), Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder(getString(R.string.fm_result_summary, r.done, r.skipped, r.failures.size))
        if (r.cancelled) sb.append('\n').append(getString(R.string.fm_err_cancelled))
        r.failures.take(8).forEach { sb.append("\n• ").append(it.path).append(": ").append(it.message) }
        if (r.failures.size > 8) sb.append("\n…")
        MaterialAlertDialogBuilder(this).setTitle(st.title).setMessage(sb.toString())
            .setPositiveButton(R.string.close, null).show()
    }

    // ------------------------------------------------------------------
    // Berechtigung und Hilfen
    // ------------------------------------------------------------------

    override fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun cleanOpenCache() {
        val dir = File(cacheDir, OPEN_CACHE)
        Thread {
            val limit = System.currentTimeMillis() - DAY_MS
            dir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
        }.start()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toastError(t: Throwable) =
        Toast.makeText(this, FileErrors.describe(this, t), Toast.LENGTH_LONG).show()

    companion object {
        private const val PREFS = "file_manager"
        private const val KEY_LOCATION = "last_location"
        private const val KEY_LOCATION_2 = "last_location_2"
        private const val KEY_PATH_PREFIX = "last_path_"
        private const val KEY_PATH2_PREFIX = "last_path2_"
        private const val KEY_GRID = "grid_view"
        private const val KEY_SPLIT = "split_view"
        private const val KEY_HIDDEN = "show_hidden"
        private const val KEY_SORT = "sort"
        private const val KEY_ASC = "ascending"
        private const val OPEN_CACHE = "fm_open"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Verschiebt lokale Elemente in den Papierkorb der Speicheranalyse (wiederherstellbar). */
        private fun trashLocal(src: LocalSource, entries: List<FileEntry>): TransferResult {
            val trash = TrashManager(File(Environment.getExternalStorageDirectory(), TrashManager.FOLDER_NAME))
            var done = 0
            val failures = ArrayList<TransferFailure>()
            for (e in entries) {
                if (trash.moveToTrash(src.toFile(e.path)) != null) done++
                else failures.add(TransferFailure(e.path, "Papierkorb nicht möglich, bitte endgültig löschen"))
            }
            return TransferResult(done, 0, failures, false)
        }

        /** Führt einen lokalen Packvorgang aus und übersetzt Ausnahmen in ein [TransferResult]. */
        private fun runLocalJob(name: String, block: () -> Int): TransferResult = try {
            TransferResult(block(), 0, emptyList(), false)
        } catch (e: CancelledException) {
            TransferResult(0, 0, emptyList(), true)
        } catch (e: IOException) {
            TransferResult(0, 0, listOf(TransferFailure(name, e.message ?: e.javaClass.simpleName)), false)
        }
    }
}
