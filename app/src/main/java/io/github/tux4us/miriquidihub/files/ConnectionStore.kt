package io.github.tux4us.miriquidihub.files

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.tux4us.miriquidihub.SecureStore
import java.util.UUID

/**
 * Speichert die Verbindungen des Dateimanagers. Die Liste liegt als JSON in den SharedPreferences,
 * Passwörter einzeln und mit [SecureStore] verschlüsselt. Die Datei ist vom Backup ausgenommen
 * (siehe backup_rules.xml und data_extraction_rules.xml).
 */
class ConnectionStore(private val context: Context) {

    private val gson = Gson()

    private fun prefs() = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun list(): List<Connection> {
        val json = prefs().getString(KEY_LIST, null) ?: return emptyList()
        return try {
            gson.fromJson<List<Connection>>(json, object : TypeToken<List<Connection>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(id: String): Connection? = list().firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString()

    /** Speichert oder ersetzt eine Verbindung. [password] = null lässt ein vorhandenes Passwort unverändert. */
    fun save(connection: Connection, password: String?) {
        val updated = list().filterNot { it.id == connection.id } + connection
        val editor = prefs().edit().putString(KEY_LIST, gson.toJson(updated.sortedBy { it.name.lowercase() }))
        if (password != null) editor.putString(KEY_PASSWORD_PREFIX + connection.id, SecureStore.encrypt(password))
        editor.apply()
    }

    fun delete(id: String) {
        val remaining = list().filterNot { it.id == id }
        prefs().edit()
            .putString(KEY_LIST, gson.toJson(remaining))
            .remove(KEY_PASSWORD_PREFIX + id)
            .apply()
    }

    fun password(id: String): String =
        SecureStore.decrypt(prefs().getString(KEY_PASSWORD_PREFIX + id, "") ?: "")

    /** Baut die Quelle für eine Verbindung. Wirft [java.io.IOException] bei ungültiger Adresse. */
    fun openSource(connection: Connection): FileSource = SourceFactory.create(connection, password(connection.id))

    companion object {
        private const val PREFS_FILE = "fm_connections"
        private const val KEY_LIST = "connections"
        private const val KEY_PASSWORD_PREFIX = "pw_"
    }
}
