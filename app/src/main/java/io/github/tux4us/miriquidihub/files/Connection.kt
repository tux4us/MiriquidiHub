package io.github.tux4us.miriquidihub.files

enum class ConnectionType { WEBDAV, NEXTCLOUD, SMB }

/**
 * Gespeicherte Verbindung zu einem entfernten Speicher. Das Passwort liegt nicht hier, sondern
 * verschlüsselt im [ConnectionStore].
 *
 * @property url WEBDAV: vollständige Adresse des WebDAV-Ordners. NEXTCLOUD: Serveradresse
 * (z. B. https://cloud.example.org), die WebDAV-Adresse wird daraus abgeleitet. SMB: Host mit
 * optionalem Port (`nas.local` oder `nas.local:4445`), siehe [SmbTarget.hostSpec].
 * @property nextcloudUserId interne Benutzerkennung, falls sie vom Anmeldenamen abweicht.
 * @property pinnedCertSha256 bestätigter Fingerabdruck eines selbstsignierten Zertifikats.
 * @property allowInsecureHttp ausdrückliche Bestätigung für HTTP außerhalb des Heimnetzes.
 * @property share nur SMB: Name der Freigabe.
 * @property domain nur SMB: Anmeldedomäne oder Arbeitsgruppe, optional.
 */
data class Connection(
    val id: String,
    val type: ConnectionType,
    val name: String,
    val url: String,
    val username: String,
    val nextcloudUserId: String? = null,
    val pinnedCertSha256: String? = null,
    val allowInsecureHttp: Boolean = false,
    val share: String? = null,
    val domain: String? = null
)
