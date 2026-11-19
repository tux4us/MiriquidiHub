package io.github.tux4us.miriquidihub.files

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

/**
 * WebDAV-Quelle auf Basis von OkHttp (PROPFIND, MKCOL, MOVE, COPY, PUT, DELETE). Wird auch für
 * Nextcloud verwendet, dort mit der Basis-URL `<server>/remote.php/dav/files/<benutzer>`.
 *
 * Verändernde Anfragen folgen keinen Weiterleitungen: OkHttp würde DELETE/MOVE nach 301/302 als GET
 * wiederholen und damit einen Erfolg vortäuschen.
 *
 * @param baseUrl Wurzel der Verbindung; Pfade der Quelle sind relativ dazu.
 */
class WebDavSource(
    override val id: String,
    override val label: String,
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient
) : FileSource {

    override val isRemote: Boolean get() = true

    private val base: HttpUrl = baseUrl.toHttpUrl()

    /** Dekodierter URL-Pfad der Wurzel, z. B. "/remote.php/dav/files/anna". */
    private val basePath: String = WebDavParser.relativePath(base.encodedPath, PathUtil.ROOT) ?: PathUtil.ROOT

    private val mutClient: OkHttpClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    private val xml = "application/xml; charset=utf-8".toMediaType()

    private fun urlFor(path: String, directory: Boolean = false): HttpUrl {
        val b = HttpUrl.Builder().scheme(base.scheme).host(base.host).port(base.port)
        for (s in PathUtil.segments(basePath)) b.addPathSegment(s)
        val segs = PathUtil.segments(path)
        for (s in segs) b.addPathSegment(s)
        if (directory || segs.isEmpty()) b.addPathSegment("")
        return b.build()
    }

    private fun request(
        url: HttpUrl,
        method: String,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap()
    ): Request {
        val b = Request.Builder().url(url).method(method, body)
        if (username.isNotEmpty()) b.header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
        for ((k, v) in headers) b.header(k, v)
        return b.build()
    }

    private fun emptyBody(): RequestBody = ByteArray(0).toRequestBody(null)

    private fun fail(code: Int, action: String): Nothing {
        val text = when (code) {
            401 -> "Anmeldung abgelehnt"
            403 -> "Zugriff verweigert"
            404 -> "Nicht gefunden"
            405 -> "Vorgang vom Server nicht erlaubt"
            409 -> "Übergeordneter Ordner fehlt"
            412 -> "Ziel existiert bereits"
            423 -> "Datei ist gesperrt"
            507 -> "Speicherplatz auf dem Server erschöpft"
            301, 302, 303, 307, 308 -> "Server leitet um – bitte die vollständige Adresse (ggf. mit „/“ am Ende) eintragen"
            else -> "Serverfehler"
        }
        throw RemoteException("$action: $text (HTTP $code)", code)
    }

    /** Prüft die Verbindung und gibt den Wurzelordner zurück. */
    override fun testConnection(): FileEntry {
        val r = stat(PathUtil.ROOT) ?: throw RemoteException("Wurzelordner nicht gefunden (HTTP 404)", 404)
        if (!r.isDirectory) throw RemoteException("Die Adresse zeigt auf eine Datei, nicht auf einen Ordner", 0)
        return r
    }

    override fun list(path: String): List<FileEntry> {
        val p = PathUtil.normalize(path)
        val req = request(urlFor(p, true), "PROPFIND", WebDavParser.PROPFIND_BODY.toRequestBody(xml), mapOf("Depth" to "1"))
        client.newCall(req).execute().use { r ->
            if (r.code != 207) fail(r.code, "Ordner lesen")
            return WebDavParser.parse(r.body!!.string(), basePath, p)
        }
    }

    override fun stat(path: String): FileEntry? {
        val p = PathUtil.normalize(path)
        val req = request(urlFor(p), "PROPFIND", WebDavParser.PROPFIND_BODY.toRequestBody(xml), mapOf("Depth" to "0"))
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (r.code != 207) fail(r.code, "Eintrag lesen")
            val e = WebDavParser.parseAll(r.body!!.string(), basePath)
                .firstOrNull { PathUtil.normalize(it.path) == p } ?: return null
            return if (p == PathUtil.ROOT) e.copy(name = label) else e
        }
    }

    override fun open(path: String): InputStream {
        val r: Response = client.newCall(request(urlFor(path), "GET")).execute()
        if (!r.isSuccessful) {
            val code = r.code
            r.close()
            fail(code, "Datei lesen")
        }
        return r.body!!.byteStream()
    }

    override fun write(path: String, input: InputStream, length: Long, overwrite: Boolean) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = length
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                }
            }
        }
        val headers = if (overwrite) emptyMap() else mapOf("If-None-Match" to "*")
        mutClient.newCall(request(urlFor(path), "PUT", body, headers)).execute().use { r ->
            when (r.code) {
                200, 201, 204 -> {}
                412 -> throw AlreadyExistsException(path)
                else -> fail(r.code, "Datei schreiben")
            }
        }
    }

    override fun mkdir(path: String) {
        mutClient.newCall(request(urlFor(path, true), "MKCOL", emptyBody())).execute().use { r ->
            when (r.code) {
                201 -> {}
                405 -> throw AlreadyExistsException(path)
                else -> fail(r.code, "Ordner anlegen")
            }
        }
    }

    override fun delete(path: String) {
        if (PathUtil.normalize(path) == PathUtil.ROOT) throw IOException("Die Wurzel kann nicht gelöscht werden")
        val entry = stat(path) ?: return
        mutClient.newCall(request(urlFor(path, entry.isDirectory), "DELETE")).execute().use { r ->
            when (r.code) {
                200, 204, 404 -> {}
                else -> fail(r.code, "Löschen")
            }
        }
    }

    override fun rename(path: String, newName: String): String {
        if (!PathUtil.isValidName(newName)) throw IOException("Ungültiger Name: $newName")
        val parent = PathUtil.parent(path) ?: throw IOException("Die Wurzel kann nicht umbenannt werden")
        val dst = PathUtil.child(parent, newName)
        moveOrCopy("MOVE", path, dst)
        return dst
    }

    override fun tryMove(src: String, dst: String): Boolean = try {
        moveOrCopy("MOVE", src, dst); true
    } catch (e: IOException) {
        false
    }

    override fun tryCopy(src: String, dst: String): Boolean = try {
        moveOrCopy("COPY", src, dst); true
    } catch (e: IOException) {
        false
    }

    private fun moveOrCopy(method: String, src: String, dst: String) {
        val entry = stat(src) ?: throw RemoteException("Nicht gefunden: $src", 404)
        val dir = entry.isDirectory
        val headers = mapOf(
            "Destination" to urlFor(dst, dir).toString(),
            "Overwrite" to "F"
        )
        mutClient.newCall(request(urlFor(src, dir), method, emptyBody(), headers)).execute().use { r ->
            when (r.code) {
                201, 204 -> {}
                412 -> throw AlreadyExistsException(dst)
                else -> fail(r.code, if (method == "MOVE") "Verschieben" else "Kopieren")
            }
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
