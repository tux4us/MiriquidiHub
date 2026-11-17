package io.github.tux4us.miriquidihub

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import io.github.tux4us.miriquidihub.databinding.ItemDiagRowBinding
import io.github.tux4us.miriquidihub.databinding.ItemDiagSectionBinding

/** Gemeinsame Darstellung und Textexport von [DiagSection]-Listen (wie in der Netzwerk-Diagnose). */
object DiagRenderer {

    fun render(context: Context, container: LinearLayout, sections: List<DiagSection>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (section in sections) {
            val sectionBinding = ItemDiagSectionBinding.inflate(inflater, container, false)
            sectionBinding.tvSectionTitle.text = section.title
            for (row in section.rows) {
                val rowBinding = ItemDiagRowBinding.inflate(inflater, sectionBinding.rowsContainer, false)
                rowBinding.tvStatus.text = symbolFor(row.status)
                rowBinding.tvStatus.setTextColor(colorFor(context, row.status, rowBinding.tvStatus))
                rowBinding.tvLabel.text = row.label
                rowBinding.tvValue.text = row.value
                row.onClick?.let { action ->
                    rowBinding.root.setOnClickListener { action() }
                    rowBinding.root.isClickable = true
                }
                sectionBinding.rowsContainer.addView(rowBinding.root)
            }
            container.addView(sectionBinding.root)
        }
    }

    fun symbolFor(status: DiagStatus): String = when (status) {
        DiagStatus.OK -> "✔"
        DiagStatus.WARN -> "▲"
        DiagStatus.ERROR -> "✖"
        DiagStatus.INFO -> "•"
    }

    fun colorFor(context: Context, status: DiagStatus, anchor: View): Int = when (status) {
        DiagStatus.OK -> ContextCompat.getColor(context, R.color.diag_ok)
        DiagStatus.WARN -> ContextCompat.getColor(context, R.color.diag_warn)
        DiagStatus.ERROR -> ContextCompat.getColor(context, R.color.diag_error)
        DiagStatus.INFO -> MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOutline)
    }

    /** Klartext mit Kopfzeile, geeignet für Zwischenablage und Teilen. */
    fun toPlainText(title: String, sections: List<DiagSection>): String {
        val sb = StringBuilder(title).append("\n\n")
        for (section in sections) {
            sb.append("== ").append(section.title).append(" ==\n")
            for (row in section.rows) {
                val tag = when (row.status) {
                    DiagStatus.OK -> "[OK]"
                    DiagStatus.WARN -> "[WARN]"
                    DiagStatus.ERROR -> "[ERR]"
                    DiagStatus.INFO -> "[i]"
                }
                sb.append(tag).append(' ').append(row.label).append(": ")
                    .append(row.value.replace("\n", ", ")).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
