package io.github.tux4us.miriquidihub

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.tux4us.miriquidihub.databinding.ItemFileEntryBinding
import io.github.tux4us.miriquidihub.databinding.ItemFileGridBinding
import io.github.tux4us.miriquidihub.files.FileEntry
import io.github.tux4us.miriquidihub.files.FileSource
import io.github.tux4us.miriquidihub.files.LocalSource
import io.github.tux4us.miriquidihub.files.PathUtil
import java.io.File

/**
 * Liste der Dateien und Ordner im Dateimanager, wahlweise als Liste oder Raster, mit Mehrfachauswahl.
 * Ordner nehmen abgelegte Elemente (Drag and Drop) entgegen.
 */
class FileEntryAdapter(
    private val onClick: (FileEntry) -> Unit,
    private val onLongClick: (FileEntry, View) -> Unit,
    private val onFolderDrop: (FileEntry, DragPayload) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<FileEntry> = emptyList()
    private var selected: Set<String> = emptySet()
    private var selectionMode = false
    private var showPath = false
    private var source: FileSource? = null

    var grid: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submit(list: List<FileEntry>, showPath: Boolean, source: FileSource?) {
        items = list
        this.showPath = showPath
        this.source = source
        notifyDataSetChanged()
    }

    fun setSelection(paths: Set<String>, selectionMode: Boolean) {
        selected = paths
        this.selectionMode = selectionMode
        notifyDataSetChanged()
    }

    private class ListHolder(val b: ItemFileEntryBinding) : RecyclerView.ViewHolder(b.root)
    private class GridHolder(val b: ItemFileGridBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int): Int = if (grid) TYPE_GRID else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GRID) GridHolder(ItemFileGridBinding.inflate(inflater, parent, false))
        else ListHolder(ItemFileEntryBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val e = items[position]
        when (holder) {
            is ListHolder -> {
                bindList(holder.b, e)
                bindCommon(holder.b.root, holder.b.cbSelect, e)
            }
            is GridHolder -> {
                bindGrid(holder.b, e)
                bindCommon(holder.b.root, holder.b.cbSelect, e)
            }
        }
    }

    private fun bindList(b: ItemFileEntryBinding, e: FileEntry) {
        val ctx = b.root.context
        b.ivIcon.setImageResource(FileTypes.iconFor(e))
        b.tvName.text = e.name
        b.tvDetails.text = if (showPath) {
            PathUtil.parent(e.path) ?: "/"
        } else {
            val parts = ArrayList<String>(2)
            if (e.size >= 0 && (!e.isDirectory || e.size > 0)) parts.add(formatBytes(e.size))
            if (e.modified > 0) {
                parts.add(
                    DateUtils.formatDateTime(
                        ctx, e.modified,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_SHOW_YEAR
                    )
                )
            }
            parts.joinToString(" · ")
        }
    }

    private fun bindGrid(b: ItemFileGridBinding, e: FileEntry) {
        val iv = b.ivIcon
        b.tvName.text = e.name
        showIcon(iv, e)
        iv.tag = null
        val file = localFileOf(e) ?: return
        val mime = (e.mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: FileTypes.mimeOf(e.name))
        if (e.isDirectory || !ThumbnailLoader.supports(mime)) return
        val key = "${file.path}|${e.modified}|${e.size}"
        iv.tag = key
        val hit = ThumbnailLoader.cached(key)
        if (hit != null) {
            showThumb(iv, hit)
        } else {
            val px = (THUMB_DP * iv.resources.displayMetrics.density).toInt()
            ThumbnailLoader.load(iv, key, file, mime, px) { bmp -> showThumb(iv, bmp) }
        }
    }

    private fun localFileOf(e: FileEntry): File? = try {
        (source as? LocalSource)?.toFile(e.path)
    } catch (ex: Exception) {
        null
    }

    private fun showIcon(iv: ImageView, e: FileEntry) {
        val pad = (22 * iv.resources.displayMetrics.density).toInt()
        iv.setPadding(pad, pad, pad, pad)
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        ImageViewCompat.setImageTintList(
            iv, ColorStateList.valueOf(ContextCompat.getColor(iv.context, R.color.rd_overlay_accent))
        )
        iv.setImageResource(FileTypes.iconFor(e))
    }

    private fun showThumb(iv: ImageView, bmp: android.graphics.Bitmap) {
        iv.setPadding(0, 0, 0, 0)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        ImageViewCompat.setImageTintList(iv, null)
        iv.setImageBitmap(bmp)
    }

    private fun bindCommon(root: View, check: MaterialCheckBox, e: FileEntry) {
        val isSelected = e.path in selected
        root.isActivated = isSelected
        check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        check.isChecked = isSelected
        root.setOnClickListener { onClick(e) }
        root.setOnLongClickListener { onLongClick(e, root); true }
        if (e.isDirectory) {
            root.setOnDragListener { v, ev -> folderDrag(v, ev, e) }
        } else {
            root.setOnDragListener(null)
        }
    }

    /** Ordner als Ablageziel. Positionsereignisse gibt er weiter, damit der Bereich am Rand scrollen kann. */
    private fun folderDrag(v: View, ev: DragEvent, folder: FileEntry): Boolean {
        val payload = ev.localState as? DragPayload ?: return false
        val restore = { v.isActivated = folder.path in selected }
        return when (ev.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                v.isActivated = true
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> false
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                restore()
                true
            }
            DragEvent.ACTION_DROP -> {
                restore()
                onFolderDrop(folder, payload)
                true
            }
            else -> false
        }
    }

    private companion object {
        const val TYPE_LIST = 0
        const val TYPE_GRID = 1
        const val THUMB_DP = 96
    }
}
