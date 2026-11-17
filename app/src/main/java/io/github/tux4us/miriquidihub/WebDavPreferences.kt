package io.github.tux4us.miriquidihub

import android.content.Context

/**
 * Speichert WebDAV-Zugangsdaten, den OpenRouteService-Schlüssel und die Passphrase der
 * Ende-zu-Ende-Verschlüsselung. Passwort, API-Schlüssel und Passphrase liegen verschlüsselt
 * ([SecureStore]) in den SharedPreferences; Klartextwerte aus älteren Versionen werden beim
 * ersten Lesen automatisch migriert. Die Datei ist vom Backup ausgenommen.
 *
 * Nicht geheime Einstellungen der Ortung (Intervall, Zeitfenster) liegen getrennt in
 * [LocationShareSettings].
 */
class WebDavPreferences(private val context: Context) {

    companion object {
        private const val PREFS_FILE = "webdav_credentials"
        private const val KEY_SERVER_URL = "webdav_server_url"
        private const val KEY_USERNAME = "webdav_username"
        private const val KEY_PASSWORD = "webdav_password"
        private const val KEY_ORS_KEY = "ors_api_key"
        private const val KEY_E2E_PASSPHRASE = "e2e_passphrase"
    }

    data class WebDavConfig(
        val serverUrl: String,
        val username: String,
        val password: String
    )

    private fun prefs() = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Liest ein verschlüsseltes Feld und migriert Klartext aus früheren Versionen. */
    private fun readSecret(key: String): String {
        val p = prefs()
        val raw = p.getString(key, "") ?: ""
        if (raw.isNotEmpty() && !SecureStore.isEncrypted(raw)) {
            val encrypted = SecureStore.encrypt(raw)
            if (encrypted != raw) p.edit().putString(key, encrypted).apply()
            return raw
        }
        return SecureStore.decrypt(raw)
    }

    private fun writeSecret(key: String, value: String) {
        prefs().edit().putString(key, SecureStore.encrypt(value)).apply()
    }

    fun saveCredentials(serverUrl: String, username: String, password: String) {
        prefs().edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_USERNAME, username)
            .apply()
        writeSecret(KEY_PASSWORD, password)
    }

    fun saveOrsKey(key: String) = writeSecret(KEY_ORS_KEY, key)

    fun getOrsKey(): String = readSecret(KEY_ORS_KEY)

    /** Passphrase der Ende-zu-Ende-Verschlüsselung der Standortfreigabe. Leer = Verschlüsselung aus. */
    fun saveE2ePassphrase(passphrase: String) = writeSecret(KEY_E2E_PASSPHRASE, passphrase)

    fun getE2ePassphrase(): String = readSecret(KEY_E2E_PASSPHRASE)

    fun loadConfig(): WebDavConfig? {
        val p = prefs()
        val url = p.getString(KEY_SERVER_URL, null) ?: return null
        val user = p.getString(KEY_USERNAME, null) ?: return null
        val pass = readSecret(KEY_PASSWORD)
        if (url.isBlank() || user.isBlank()) return null
        return WebDavConfig(url, user, pass)
    }

    fun clearCredentials() = prefs().edit().clear().apply()

    fun hasCredentials(): Boolean = loadConfig() != null
}
