package io.github.tux4us.miriquidihub.files

import java.io.Closeable
import java.io.InputStream

/**
 * Gemeinsame Schnittstelle für Speicherorte (lokal, WebDAV, Nextcloud; SMB).
 * Alle Methoden blockieren und gehören auf einen IO-Thread. Pfade siehe [FileEntry.path].
 */
interface FileSource : Closeable {
    val id: String
    val label: String
    val isRemote: Boolean

    /** Listet den Inhalt eines Ordners (ohne den Ordner selbst). */
    fun list(path: String): List<FileEntry>

    /** null, wenn der Pfad nicht existiert. */
    fun stat(path: String): FileEntry?

    /** Öffnet eine Datei zum Lesen. Der Aufrufer schließt den Strom. */
    fun open(path: String): InputStream

    /**
     * Schreibt [input] nach [path]. [length] ist die Länge in Byte oder -1 wenn unbekannt.
     * Bei [overwrite] = false schlägt das Schreiben mit [AlreadyExistsException] fehl, wenn das Ziel existiert.
     */
    fun write(path: String, input: InputStream, length: Long, overwrite: Boolean)

    /** Legt genau einen Ordner an; der übergeordnete Ordner muss existieren. */
    fun mkdir(path: String)

    /** Löscht Datei oder Ordner rekursiv. Ein nicht vorhandener Pfad ist kein Fehler. */
    fun delete(path: String)

    /** Benennt im selben Ordner um und liefert den neuen Pfad. */
    fun rename(path: String, newName: String): String

    fun exists(path: String): Boolean = stat(path) != null

    /** Prüft die Erreichbarkeit und liefert den Wurzelordner. Wirft [RemoteException] bei Problemen. */
    fun testConnection(): FileEntry =
        stat(PathUtil.ROOT) ?: throw RemoteException("Wurzelordner nicht gefunden", 404)

    /** Legt einen Ordner an, falls er noch nicht existiert. */
    fun ensureDir(path: String) {
        val s = stat(path)
        if (s != null) {
            if (!s.isDirectory) throw AlreadyExistsException(path)
            return
        }
        mkdir(path)
    }

    /** Verschiebt innerhalb dieser Quelle ohne Datenübertragung. false = nicht möglich, Aufrufer kopiert und löscht. */
    fun tryMove(src: String, dst: String): Boolean = false

    /** Kopiert innerhalb dieser Quelle ohne Datenübertragung. false = nicht möglich, Aufrufer überträgt Daten. */
    fun tryCopy(src: String, dst: String): Boolean = false

    override fun close() {}
}
