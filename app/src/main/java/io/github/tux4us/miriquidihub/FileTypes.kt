package io.github.tux4us.miriquidihub

import android.webkit.MimeTypeMap
import androidx.annotation.DrawableRes
import io.github.tux4us.miriquidihub.files.FileEntry

/** Dateityp-Erkennung für Symbole und "Öffnen mit". */
object FileTypes {

    private val ARCHIVES = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "jar", "apk", "apks")
    private val TEXTS = setOf(
        "txt", "md", "log", "csv", "json", "xml", "html", "htm", "ini", "conf", "yml", "yaml",
        "pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "kt", "java", "py", "sh"
    )

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    @DrawableRes
    fun iconFor(entry: FileEntry): Int {
        if (entry.isDirectory) return R.drawable.ic_fm_folder
        val mime = entry.mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: mimeOf(entry.name)
        val ext = entry.extension
        return when {
            mime.startsWith("image/") -> R.drawable.ic_fm_image
            mime.startsWith("audio/") -> R.drawable.ic_fm_audio
            mime.startsWith("video/") -> R.drawable.ic_fm_video
            ext in ARCHIVES -> R.drawable.ic_fm_archive
            mime.startsWith("text/") || ext in TEXTS -> R.drawable.ic_fm_text
            else -> R.drawable.ic_fm_file
        }
    }

    fun isZip(entry: FileEntry): Boolean = !entry.isDirectory && entry.extension == "zip"
}
