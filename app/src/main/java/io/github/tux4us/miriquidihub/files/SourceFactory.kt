package io.github.tux4us.miriquidihub.files

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Baut aus einer [Connection] die passende [FileSource] und wendet dabei die [HttpPolicy] an. */
object SourceFactory {

    /** Liefert die WebDAV-Basisadresse einer Verbindung. */
    fun davUrl(connection: Connection): String = when (connection.type) {
        ConnectionType.WEBDAV -> connection.url.trim()
        ConnectionType.NEXTCLOUD -> NextcloudApi.davRoot(
            connection.url,
            connection.nextcloudUserId ?: connection.username
        ) ?: throw IOException("Ungültige Serveradresse")
        ConnectionType.SMB -> throw IOException("SMB-Verbindungen haben keine WebDAV-Adresse")
    }

    /** Wie [davUrl], liefert aber null statt einer Ausnahme bei ungültiger Adresse. */
    fun davUrlOrNull(connection: Connection): String? = try { davUrl(connection) } catch (e: IOException) { null }

    fun create(connection: Connection, password: String): FileSource =
        if (connection.type == ConnectionType.SMB) createSmb(connection, password) else createWebDav(connection, password)

    private fun createSmb(connection: Connection, password: String): SmbSource {
        val target = SmbTarget.parse(connection.url) ?: throw IOException("Ungültige Serveradresse")
        val share = connection.share?.trim().takeUnless { it.isNullOrEmpty() } ?: target.share
        if (share == null || !SmbTarget.isValidShare(share)) throw IOException("Freigabename fehlt oder ist ungültig")
        // SMB ist nicht für das Internet gedacht (Port 445); nur Adressen im lokalen Netz sind zulässig.
        if (!HttpPolicy.isPrivateHost(target.host)) throw IOException("SMB ist nur im lokalen Netz erlaubt")
        return SmbSource(
            connection.id, connection.name, target.host, target.port, share,
            connection.username, password, connection.domain?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun createWebDav(connection: Connection, password: String): WebDavSource {
        val url = davUrl(connection)
        val parsed = url.toHttpUrlOrNull() ?: throw IOException("Ungültige Adresse: $url")
        if (HttpPolicy.check(parsed.scheme, parsed.host, connection.allowInsecureHttp) != HttpPolicy.Verdict.OK) {
            throw IOException("Unverschlüsseltes HTTP ist für diese Adresse nicht freigegeben")
        }
        val client = RemoteClients.build(connection.pinnedCertSha256)
        return WebDavSource(connection.id, connection.name, url, connection.username, password, client)
    }
}
