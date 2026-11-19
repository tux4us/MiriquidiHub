package io.github.tux4us.miriquidihub.files

/**
 * Ein Eintrag in einem [FileSource]. [path] ist absolut innerhalb der Quelle, getrennt mit "/",
 * ohne abschließenden Schrägstrich (Ausnahme: Wurzel "/"). Pfade sind nie URL-kodiert.
 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    /** Größe in Byte, -1 wenn unbekannt (z. B. Ordner auf WebDAV-Servern ohne Größenangabe). */
    val size: Long,
    /** Änderungszeit in ms seit 1970, 0 wenn unbekannt. */
    val modified: Long,
    val mime: String? = null
) {
    val isHidden: Boolean get() = name.startsWith(".")
    val extension: String get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
}
