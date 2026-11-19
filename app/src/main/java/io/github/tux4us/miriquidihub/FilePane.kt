package io.github.tux4us.miriquidihub

import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tux4us.miriquidihub.databinding.PaneFileListBinding
import io.github.tux4us.miriquidihub.files.FileEntry
import io.github.tux4us.miriquidihub.files.FileSource
import io.github.tux4us.miriquidihub.files.FileTools
import io.github.tux4us.miriquidihub.files.LocalSource
import io.github.tux4us.miriquidihub.files.PathUtil
import io.github.tux4us.miriquidihub.files.ThumbMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Inhalt eines Drag-and-Drop-Vorgangs: Quelle und Elemente des Bereichs, aus dem gezogen wird. */
class DragPayload(val paneIndex: Int, val source: FileSource, val entries: List<FileEntry>)

/**
 * Ablageziel. [viaFolder] ist true, wenn auf einen Ordner oder ein Pfadsegment abgelegt wurde, false bei
 * Ablage auf dem freien Bereich (dann gilt der aktuelle Ordner des Bereichs).
 */
class DropTarget(val dir: String, val viaFolder: Boolean)

/**
 * Ein Bereich des Dateimanagers: eigener Speicherort, Pfad, Liste, Auswahl und Pfadleiste. Zwei Bereiche
 * bilden die geteilte Ansicht. Die Activity ([Host]) verwaltet Menü, Zwischenablage und Übertragungen.
 */
class FilePane(
    val index: Int,
    val binding: PaneFileListBinding,
    private val host: Host
) {
    interface Host {
        val scope: CoroutineScope
        fun hasAllFilesAccess(): Boolean
        fun locationLabel(id: String?): String
        fun showHidden(): Boolean
        fun comparator(): Comparator<FileEntry>

        /** Der Nutzer hat diesen Bereich berührt. */
        fun onPaneTouched(pane: FilePane)

        /** Der Nutzer wechselt den Ordner (Pfadleiste oder Ordner-Klick). */
        fun onUserNavigate(pane: FilePane)

        /** Pfad oder Ort wurde geändert (zum Merken). */
        fun onPathChanged(pane: FilePane)

        fun onEntryClick(pane: FilePane, entry: FileEntry)
        fun onEntryLongClick(pane: FilePane, entry: FileEntry, view: View)
        fun onSelectionChanged(pane: FilePane)
        fun onDrop(pane: FilePane, payload: DragPayload, target: DropTarget)
        fun onGrantPermission()
    }

    var source: FileSource? = null
    var locationId: String? = null
    var path: String = PathUtil.ROOT
        private set

    var rawEntries: List<FileEntry> = emptyList()
    var shown: List<FileEntry> = emptyList()
        private set
    var listError: String? = null
    private var query = ""
    private var searchResults: List<FileEntry>? = null
    private var searchJob: Job? = null
    private var loadSeq = 0

    val selection = LinkedHashSet<String>()
    private var selectionMode = false

    private val ctx get() = binding.root.context
    private val rv get() = binding.rvFiles

    private val adapter = FileEntryAdapter(
        onClick = { host.onEntryClick(this, it) },
        onLongClick = { e, v -> host.onEntryLongClick(this, e, v) },
        onFolderDrop = { folder, payload -> host.onDrop(this, payload, DropTarget(folder.path, true)) }
    )
    private val listManager = LinearLayoutManager(ctx)
    private val gridManager = GridLayoutManager(ctx, 3)
    var grid = false
        private set

    val visible: Boolean get() = binding.root.visibility == View.VISIBLE
    val hasSelection: Boolean get() = selection.isNotEmpty()

    init {
        rv.layoutManager = listManager
        rv.adapter = adapter
        binding.swipe.setOnRefreshListener { load() }
        binding.btnGrantPermission.setOnClickListener { host.onGrantPermission() }
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) host.onPaneTouched(this@FilePane)
                return false
            }
        })
        binding.breadcrumbScroll.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) host.onPaneTouched(this)
            false
        }
        rv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (grid) updateSpan() }
        binding.root.setOnDragListener { _, ev -> onPaneDrag(ev) }
    }

    fun setActive(active: Boolean) {
        binding.root.isActivated = active
    }

    fun setRefreshing(on: Boolean) {
        binding.swipe.isRefreshing = on
    }

    // ---- Ansicht ------------------------------------------------------------------------------

    fun setGrid(on: Boolean) {
        grid = on
        rv.layoutManager = if (on) gridManager else listManager
        adapter.grid = on
        if (on) updateSpan()
    }

    private fun updateSpan() {
        val width = rv.width - rv.paddingStart - rv.paddingEnd
        val n = ThumbMath.spanCount(width, dp(104))
        if (gridManager.spanCount != n) gridManager.spanCount = n
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    // ---- Navigation und Liste -----------------------------------------------------------------

    fun navigate(newPath: String) {
        val src = source ?: return
        path = PathUtil.normalize(newPath)
        host.onPathChanged(this)
        clearSelection()
        updateBreadcrumb()
        if (src is LocalSource && !host.hasAllFilesAccess()) {
            binding.permissionLayout.visibility = View.VISIBLE
            rawEntries = emptyList()
            listError = null
            applyFilter()
            return
        }
        binding.permissionLayout.visibility = View.GONE
        load()
    }

    /** true, wenn die Berechtigungsabfrage angezeigt wird. */
    val needsPermission: Boolean get() = binding.permissionLayout.visibility == View.VISIBLE

    fun load() {
        val src = source
        if (src == null) {
            binding.swipe.isRefreshing = false
            applyFilter()
            return
        }
        if (src is LocalSource && !host.hasAllFilesAccess()) {
            binding.swipe.isRefreshing = false
            return
        }
        val p = path
        val seq = ++loadSeq
        binding.swipe.isRefreshing = true
        host.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { src.list(p) } }
            if (seq != loadSeq) return@launch
            binding.swipe.isRefreshing = false
            result.onSuccess {
                rawEntries = it
                listError = null
            }.onFailure {
                rawEntries = emptyList()
                listError = FileErrors.describe(ctx, it)
            }
            applyFilter()
        }
    }

    fun applyFilter() {
        val base = searchResults ?: rawEntries
        var list = if (host.showHidden()) base else base.filter { !it.isHidden }
        if (searchResults == null && query.isNotBlank()) list = list.filter { it.name.contains(query, ignoreCase = true) }
        shown = list.sortedWith(host.comparator())
        adapter.submit(shown, showPath = searchResults != null, source = source)
        adapter.setSelection(selection, selectionMode)

        val text = when {
            needsPermission -> null
            listError != null -> listError
            shown.isEmpty() && (searchResults != null || query.isNotBlank()) -> ctx.getString(R.string.fm_no_results)
            shown.isEmpty() -> ctx.getString(R.string.fm_empty_folder)
            else -> null
        }
        binding.tvEmpty.visibility = if (text == null) View.GONE else View.VISIBLE
        binding.tvEmpty.text = text.orEmpty()
    }

    // ---- Suche --------------------------------------------------------------------------------

    /** Filtert den aktuellen Ordner nach Name und verwirft laufende oder vorhandene Suchergebnisse. */
    fun setQuery(q: String) {
        searchJob?.cancel()
        binding.swipe.isRefreshing = false
        searchResults = null
        query = q
        applyFilter()
    }

    fun startRecursiveSearch(q: String) {
        val src = source ?: return
        if (q.isBlank()) return
        val root = path
        searchJob?.cancel()
        query = q
        binding.swipe.isRefreshing = true
        searchJob = host.scope.launch {
            val hits = ArrayList<FileEntry>()
            withContext(Dispatchers.IO) {
                FileTools.search(src, root, q, { !isActive }) { hit -> synchronized(hits) { hits.add(hit) } }
            }
            binding.swipe.isRefreshing = false
            searchResults = synchronized(hits) { hits.toList() }
            applyFilter()
        }
    }

    fun cancelJobs() {
        searchJob?.cancel()
    }

    // ---- Auswahl ------------------------------------------------------------------------------

    fun setSelectionMode(on: Boolean) {
        selectionMode = on
        adapter.setSelection(selection, on)
    }

    fun toggleSelect(e: FileEntry) {
        if (!selection.add(e.path)) selection.remove(e.path)
        adapter.setSelection(selection, selectionMode)
        host.onSelectionChanged(this)
    }

    fun selectAll() {
        selection.addAll(shown.map { it.path })
        adapter.setSelection(selection, selectionMode)
        host.onSelectionChanged(this)
    }

    /** Hebt die Auswahl auf und meldet es dem Host, damit er den Auswahlmodus beendet. */
    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        adapter.setSelection(selection, selectionMode)
        host.onSelectionChanged(this)
    }

    /** Wie [clearSelection], aber ohne Rückmeldung (der Host beendet den Modus selbst). */
    fun resetSelectionSilently() {
        selection.clear()
        selectionMode = false
        adapter.setSelection(selection, false)
    }

    fun selectedEntries(): List<FileEntry> = shown.filter { it.path in selection }

    // ---- Pfadleiste ---------------------------------------------------------------------------

    fun updateBreadcrumb() {
        val container = binding.breadcrumb
        container.removeAllViews()
        if (source == null) return
        fun segment(label: String, target: String, last: Boolean) {
            val tv = TextView(ctx).apply {
                text = label
                textSize = 14f
                setBackgroundResource(R.drawable.bg_fm_row)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setTextColor(ContextCompat.getColor(context, if (last) R.color.rd_text_primary else R.color.rd_overlay_accent))
                if (last) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                } else {
                    setOnClickListener {
                        host.onPaneTouched(this@FilePane)
                        host.onUserNavigate(this@FilePane)
                        navigate(target)
                    }
                }
                setOnDragListener { v, ev -> segmentDrag(v, ev, target) }
            }
            container.addView(tv)
            if (!last) {
                container.addView(TextView(ctx).apply {
                    text = "›"
                    setTextColor(ContextCompat.getColor(context, R.color.rd_text_secondary))
                    setPadding(dp(2), 0, dp(2), 0)
                })
            }
        }
        val segs = PathUtil.segments(path)
        segment(host.locationLabel(locationId), PathUtil.ROOT, segs.isEmpty())
        var acc = ""
        segs.forEachIndexed { i, s ->
            acc += "/$s"
            segment(s, acc, i == segs.lastIndex)
        }
        binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ---- Drag and Drop ------------------------------------------------------------------------

    private fun segmentDrag(v: View, ev: DragEvent, target: String): Boolean {
        val payload = ev.localState as? DragPayload ?: return false
        return when (ev.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                v.isActivated = true
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> false
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                v.isActivated = false
                true
            }
            DragEvent.ACTION_DROP -> {
                v.isActivated = false
                host.onDrop(this, payload, DropTarget(target, true))
                true
            }
            else -> false
        }
    }

    /** Freier Bereich als Ablageziel; hebt sich nur hervor, wenn aus dem anderen Bereich gezogen wird. */
    private fun onPaneDrag(ev: DragEvent): Boolean {
        val payload = ev.localState as? DragPayload ?: return false
        val foreign = payload.paneIndex != index
        return when (ev.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                binding.root.isSelected = foreign
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                autoScroll(ev.y)
                true
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                binding.root.isSelected = false
                true
            }
            DragEvent.ACTION_DROP -> {
                binding.root.isSelected = false
                // Ablage auf freier Fläche im selben Bereich ist keine Aktion (gleicher Ordner).
                if (foreign) host.onDrop(this, payload, DropTarget(path, false))
                true
            }
            else -> false
        }
    }

    private fun autoScroll(y: Float) {
        val areaTop = (binding.swipe.parent as View).top
        val areaBottom = areaTop + rv.height
        val edge = dp(56)
        val step = dp(18)
        when {
            y < areaTop + edge -> rv.scrollBy(0, -step)
            y > areaBottom - edge -> rv.scrollBy(0, step)
        }
    }
}
