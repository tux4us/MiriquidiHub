package io.github.tux4us.miriquidihub.sms

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.tux4us.miriquidihub.SecureStore

/** Gekoppeltes Gerät. [secret] ist mit [SecureStore] verschlüsselt. */
data class SmsContact(
    val pairId: String,
    val name: String,
    val number: String,
    val secret: String,
    val role: String,
    /** Darf dieses Gerät meinen Standort abfragen? */
    val allowLocate: Boolean,
    val lastPosition: ReceivedPosition? = null,
    val pending: List<PendingRequest>? = null,
    val answered: List<SmsLocationRules.Seen>? = null,
    /** Letzter Zustand einer eigenen Anfrage als [RequestState]-Name. */
    val requestState: String? = null,
    val requestStateAt: Long = 0
) {
    fun ownRole(): SmsLocationProtocol.Role = SmsLocationProtocol.Role.valueOf(role)

    fun secretBytes(): ByteArray? {
        val code = SecureStore.decrypt(secret)
        return PairingCode.parseCode(code)
    }
}

data class PendingRequest(val requestId: Int, val sentAtSeconds: Long)

data class ReceivedPosition(
    val lat: Double,
    val lon: Double,
    val fixTimeSeconds: Long,
    val accuracyMeters: Int?,
    val batteryPercent: Int?,
    val status: String,
    val receivedAtSeconds: Long
)

enum class RequestState {
    SENDING, SENT, SEND_FAILED, ANSWER_WAITING_FOR_FIX, ANSWERED, NO_FIX, LOCATION_OFF, NO_PERMISSION
}

data class LogEntry(val timeMillis: Long, val text: String)

/**
 * Ablage der Kopplungen und des Protokolls. Die Datei `sms_location.xml` ist vom Backup
 * ausgenommen. Alle Änderungen laufen synchronisiert, weil Receiver, Dienst und Oberfläche
 * dieselben Daten ändern.
 */
class SmsLocationStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun contacts(): List<SmsContact> = synchronized(LOCK) { readContacts() }

    fun contact(pairId: String): SmsContact? = contacts().firstOrNull { it.pairId == pairId }

    fun add(secret: ByteArray, role: SmsLocationProtocol.Role, name: String, number: String, allowLocate: Boolean): SmsContact {
        val contact = SmsContact(
            pairId = SmsLocationProtocol.pairId(secret),
            name = name,
            number = number,
            secret = SecureStore.encrypt(PairingCode.format(secret)),
            role = role.name,
            allowLocate = allowLocate
        )
        val stored = synchronized(LOCK) {
            val list = readContacts()
            // Wird derselbe Code erneut übernommen, bleiben Verlauf und Wiederholungsschutz erhalten.
            val old = list.firstOrNull { it.pairId == contact.pairId }
            val merged = contact.copy(
                lastPosition = old?.lastPosition, pending = old?.pending, answered = old?.answered
            )
            writeContacts(list.filter { it.pairId != contact.pairId } + merged)
            merged
        }
        notifyChanged()
        return stored
    }

    /** Ändert eine Kopplung atomar. Rückgabe null, wenn sie nicht (mehr) existiert. */
    fun update(pairId: String, change: (SmsContact) -> SmsContact): SmsContact? {
        val result = synchronized(LOCK) {
            val list = readContacts().toMutableList()
            val index = list.indexOfFirst { it.pairId == pairId }
            if (index < 0) return@synchronized null
            list[index] = change(list[index])
            writeContacts(list)
            list[index]
        }
        notifyChanged()
        return result
    }

    fun remove(pairId: String) {
        synchronized(LOCK) { writeContacts(readContacts().filter { it.pairId != pairId }) }
        notifyChanged()
    }

    /**
     * Prüft eine Anfrage und merkt sie bei Annahme in einem Schritt vor, damit eine doppelt
     * zugestellte SMS nicht zweimal beantwortet wird.
     */
    fun acceptRequest(pairId: String, requestId: Int, sentAtSeconds: Long, nowSeconds: Long): SmsLocationRules.Verdict {
        synchronized(LOCK) {
            val list = readContacts().toMutableList()
            val index = list.indexOfFirst { it.pairId == pairId }
            if (index < 0) return SmsLocationRules.Verdict.EXPIRED
            val seen = SmsLocationRules.pruneSeen(list[index].answered.orEmpty(), nowSeconds)
            val verdict = SmsLocationRules.checkRequest(requestId, sentAtSeconds, nowSeconds, seen)
            if (verdict == SmsLocationRules.Verdict.ACCEPT) {
                list[index] = list[index].copy(answered = seen + SmsLocationRules.Seen(requestId, sentAtSeconds, nowSeconds))
                writeContacts(list)
            }
            return verdict
        }
    }

    fun log(text: String) {
        synchronized(LOCK) {
            val entries = (listOf(LogEntry(System.currentTimeMillis(), text)) + logEntries()).take(MAX_LOG)
            prefs.edit().putString(KEY_LOG, gson.toJson(entries)).commit()
        }
        notifyChanged()
    }

    fun logEntries(): List<LogEntry> = synchronized(LOCK) {
        parse<List<LogEntry>>(KEY_LOG, object : TypeToken<List<LogEntry>>() {}.type) ?: emptyList()
    }

    fun clearLog() {
        synchronized(LOCK) { prefs.edit().remove(KEY_LOG).commit() }
        notifyChanged()
    }

    var ownName: String
        get() = prefs.getString(KEY_OWN_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OWN_NAME, value).apply()

    var ownNumber: String
        get() = prefs.getString(KEY_OWN_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OWN_NUMBER, value).apply()

    private fun readContacts(): List<SmsContact> =
        parse<List<SmsContact>>(KEY_CONTACTS, object : TypeToken<List<SmsContact>>() {}.type) ?: emptyList()

    private fun <T> parse(key: String, type: java.lang.reflect.Type): T? {
        val json = prefs.getString(key, null) ?: return null
        return try {
            gson.fromJson<T>(json, type)
        } catch (e: com.google.gson.JsonParseException) {
            null
        }
    }

    // commit() statt apply(): Der Prozess kann direkt nach dem SMS-Empfang beendet werden.
    private fun writeContacts(list: List<SmsContact>) {
        prefs.edit().putString(KEY_CONTACTS, gson.toJson(list)).commit()
    }

    private fun notifyChanged() {
        appContext.sendBroadcast(Intent(ACTION_CHANGED).setPackage(appContext.packageName))
    }

    companion object {
        const val ACTION_CHANGED = "io.github.tux4us.miriquidihub.SMS_LOCATION_CHANGED"
        private const val FILE = "sms_location"
        private const val KEY_CONTACTS = "contacts"
        private const val KEY_LOG = "log"
        private const val KEY_OWN_NAME = "own_name"
        private const val KEY_OWN_NUMBER = "own_number"
        private const val MAX_LOG = 100
        private val LOCK = Any()
        private val gson = Gson()
    }
}
