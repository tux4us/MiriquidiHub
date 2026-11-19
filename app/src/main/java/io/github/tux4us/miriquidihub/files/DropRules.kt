package io.github.tux4us.miriquidihub.files

/**
 * Regeln für Ablegen (Drag and Drop) und Einfügen: erkennt Ziele, bei denen nichts zu tun ist oder
 * ein Ordner in sich selbst kopiert würde.
 */
object DropRules {

    enum class Verdict {
        /** Vorgang kann ausgeführt werden. */
        OK,

        /** Verschieben in den Ordner, in dem die Elemente bereits liegen. */
        NOTHING_TO_DO,

        /** Ein Ordner würde in sich selbst oder einen seiner Unterordner kopiert oder verschoben. */
        INTO_ITSELF
    }

    fun check(
        fromSourceId: String,
        entries: List<FileEntry>,
        toSourceId: String,
        targetDir: String,
        move: Boolean
    ): Verdict {
        if (entries.isEmpty()) return Verdict.NOTHING_TO_DO
        val sameSource = fromSourceId == toSourceId
        if (sameSource && entries.any { it.isDirectory && PathUtil.isInside(targetDir, it.path) }) {
            return Verdict.INTO_ITSELF
        }
        val dir = PathUtil.normalize(targetDir)
        if (move && sameSource && entries.all { PathUtil.parent(it.path) == dir }) return Verdict.NOTHING_TO_DO
        return Verdict.OK
    }
}
