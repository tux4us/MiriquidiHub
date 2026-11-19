package io.github.tux4us.miriquidihub.files

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.connection.Connection as SmbConnection

/**
 * SMB-Quelle (SMB 2/3, ohne SMB 1) auf Basis von smbj. Pfade der Quelle sind relativ zur Freigabe;
 * intern werden sie mit "\" getrennt an smbj übergeben.
 *
 * Die Verbindung wird beim ersten Zugriff aufgebaut und bei Verbindungsverlust beim nächsten Zugriff
 * neu hergestellt. Auf Android nutzt smbj ausschließlich NTLM (kein Kerberos).
 */
class SmbSource(
    override val id: String,
    override val label: String,
    private val host: String,
    private val port: Int,
    private val shareName: String,
    private val username: String,
    private val password: String,
    private val domain: String?
) : FileSource {

    override val isRemote: Boolean get() = true

    private val lock = Any()
    private var client: SMBClient? = null
    private var connection: SmbConnection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var closed = false

    // ---- Verbindung ---------------------------------------------------------------------------

    private fun share(): DiskShare = synchronized(lock) {
        if (closed) throw IOException("Verbindung geschlossen")
        val cur = share
        if (cur != null && connection?.isConnected == true) return cur
        disconnectLocked()

        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .build()
        val c = SMBClient(config)
        try {
            val conn = c.connect(host, port)
            val sess = conn.authenticate(AuthenticationContext(username, password.toCharArray(), domain.orEmpty()))
            val sh = sess.connectShare(shareName) as? DiskShare
                ?: throw RemoteException("„$shareName“ ist keine Dateifreigabe", 0)
            client = c
            connection = conn
            session = sess
            share = sh
            sh
        } catch (e: Throwable) {
            try { c.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun disconnectLocked() {
        try { share?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        share = null; session = null; connection = null; client = null
    }

    private fun reset() = synchronized(lock) { disconnectLocked() }

    /** Führt [block] aus und übersetzt smbj-Fehler in [RemoteException]. */
    private inline fun <T> call(action: String, block: (DiskShare) -> T): T {
        try {
            return block(share())
        } catch (e: CancelledException) {
            throw e
        } catch (e: RemoteException) {
            throw e
        } catch (e: SMBApiException) {
            throw SmbStatus.toException(e.statusCode, action, e.message, e)
        } catch (e: SMBRuntimeException) {
            reset()
            throw RemoteException("$action: ${e.message ?: "SMB-Fehler"}", 0, e)
        } catch (e: IOException) {
            reset()
            throw e
        }
    }

    // ---- Hilfen -------------------------------------------------------------------------------

    private fun smbPath(path: String): String = PathUtil.segments(path).joinToString("\\")

    private class Info(val dir: Boolean, val reparse: Boolean, val size: Long, val modified: Long)

    private fun infoOrNull(sh: DiskShare, path: String): Info? {
        return try {
            val i = sh.getFileInformation(smbPath(path))
            val attrs = i.basicInformation.fileAttributes
            Info(
                dir = i.standardInformation.isDirectory,
                reparse = attrs and REPARSE != 0L,
                size = i.standardInformation.endOfFile,
                modified = i.basicInformation.lastWriteTime.toEpochMillis()
            )
        } catch (e: SMBApiException) {
            if (SmbStatus.isNotFound(e.statusCode)) null else throw e
        }
    }

    /** Prüft die Verbindung (Anmeldung, Freigabe, Leserecht auf der Wurzel). */
    override fun testConnection(): FileEntry {
        call("Verbinden") { sh -> sh.list("") }
        return FileEntry(label, PathUtil.ROOT, true, -1L, 0L)
    }

    // ---- FileSource ---------------------------------------------------------------------------

    override fun list(path: String): List<FileEntry> {
        val p = PathUtil.normalize(path)
        return call("Ordner lesen") { sh ->
            sh.list(smbPath(p)).mapNotNull { e ->
                val n = e.fileName
                if (n == "." || n == "..") return@mapNotNull null
                val dir = e.fileAttributes and DIRECTORY != 0L
                FileEntry(
                    name = n,
                    path = PathUtil.child(p, n),
                    isDirectory = dir,
                    size = if (dir) -1L else e.endOfFile,
                    modified = e.lastWriteTime.toEpochMillis()
                )
            }
        }
    }

    override fun stat(path: String): FileEntry? {
        val p = PathUtil.normalize(path)
        if (p == PathUtil.ROOT) return FileEntry(label, PathUtil.ROOT, true, -1L, 0L)
        return call("Status") { sh ->
            infoOrNull(sh, p)?.let {
                FileEntry(
                    name = PathUtil.name(p),
                    path = p,
                    isDirectory = it.dir,
                    size = if (it.dir) -1L else it.size,
                    modified = it.modified
                )
            }
        }
    }

    override fun open(path: String): InputStream {
        val smb = smbPath(path)
        val file = call("Datei öffnen") { sh ->
            sh.openFile(
                smb,
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SHARE_ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java)
            )
        }
        return object : FilterInputStream(file.inputStream) {
            override fun close() {
                try { super.close() } finally { try { file.close() } catch (_: Exception) {} }
            }
        }
    }

    override fun write(path: String, input: InputStream, length: Long, overwrite: Boolean) {
        val p = PathUtil.normalize(path)
        if (p == PathUtil.ROOT) throw RemoteException("Ungültiges Ziel", 0)
        if (!overwrite && stat(p) != null) throw AlreadyExistsException("Schreiben: existiert bereits")
        val smbTmp = smbPath(p + TEMP_SUFFIX)
        val smbDst = smbPath(p)
        call("Schreiben") { sh ->
            var completed = false
            try {
                sh.openFile(
                    smbTmp,
                    EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.noneOf(SMB2ShareAccess::class.java),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.noneOf(SMB2CreateOptions::class.java)
                ).use { f ->
                    val out = f.outputStream
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                    // Erst nach vollständiger Übertragung unter dem Zielnamen sichtbar machen.
                    f.rename(smbDst, overwrite)
                }
                completed = true
            } finally {
                if (!completed) {
                    try { sh.rm(smbTmp) } catch (_: Exception) {}
                }
            }
        }
    }

    override fun mkdir(path: String) {
        val smb = smbPath(path)
        if (smb.isEmpty()) throw AlreadyExistsException("Ordner anlegen: existiert bereits")
        call("Ordner anlegen") { sh -> sh.mkdir(smb) }
    }

    override fun delete(path: String) {
        val p = PathUtil.normalize(path)
        if (p == PathUtil.ROOT) throw RemoteException("Die Freigabe selbst kann nicht gelöscht werden", 403)
        call("Löschen") { sh ->
            val info = infoOrNull(sh, p) ?: return@call
            deleteRecursive(sh, p, info.dir, info.reparse)
        }
    }

    /** Verknüpfungen (Reparse-Points) werden nicht betreten, damit nie Inhalte außerhalb gelöscht werden. */
    private fun deleteRecursive(sh: DiskShare, path: String, dir: Boolean, reparse: Boolean) {
        if (dir && !reparse) {
            for (e in sh.list(smbPath(path))) {
                val n = e.fileName
                if (n == "." || n == "..") continue
                val a = e.fileAttributes
                deleteRecursive(sh, PathUtil.child(path, n), a and DIRECTORY != 0L, a and REPARSE != 0L)
            }
        }
        val options = EnumSet.noneOf(SMB2CreateOptions::class.java)
        options.add(if (dir) SMB2CreateOptions.FILE_DIRECTORY_FILE else SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        if (reparse) options.add(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)
        try {
            sh.open(
                smbPath(path),
                EnumSet.of(AccessMask.DELETE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SHARE_ALL,
                SMB2CreateDisposition.FILE_OPEN,
                options
            ).use { it.deleteOnClose() }
        } catch (e: SMBApiException) {
            if (!SmbStatus.isNotFound(e.statusCode)) throw e
        }
    }

    private fun renameEntry(sh: DiskShare, from: String, to: String) {
        val entry: DiskEntry = sh.open(
            smbPath(from),
            EnumSet.of(AccessMask.DELETE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SHARE_ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java)
        )
        entry.use { it.rename(smbPath(to), false) }
    }

    override fun rename(path: String, newName: String): String {
        if (!PathUtil.isValidName(newName)) throw RemoteException("Ungültiger Name", 0)
        val p = PathUtil.normalize(path)
        val parent = PathUtil.parent(p) ?: throw RemoteException("Die Freigabe selbst kann nicht umbenannt werden", 403)
        val target = PathUtil.child(parent, newName)
        call("Umbenennen") { sh -> renameEntry(sh, p, target) }
        return target
    }

    /** Verschieben innerhalb der Freigabe ist auf dem Server eine Umbenennung; sonst kopiert der Aufrufer. */
    override fun tryMove(src: String, dst: String): Boolean = try {
        call("Verschieben") { sh -> renameEntry(sh, PathUtil.normalize(src), PathUtil.normalize(dst)) }
        true
    } catch (e: CancelledException) {
        throw e
    } catch (e: IOException) {
        false
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            disconnectLocked()
        }
    }

    private companion object {
        const val TEMP_SUFFIX = ".fm-part"
        const val BUFFER = 256 * 1024
        val DIRECTORY = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
        val REPARSE = FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value
        val SHARE_ALL: Set<SMB2ShareAccess> = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ,
            SMB2ShareAccess.FILE_SHARE_WRITE,
            SMB2ShareAccess.FILE_SHARE_DELETE
        )
    }
}
