package io.github.tux4us.miriquidihub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

data class ThroughputResult(
    val bytes: Long,
    val millis: Long,
    val firstByteMillis: Long,
    val httpCode: Int,
    /** true = Datei vollständig gelesen, false = durch Zeit- oder Volumenlimit beendet. */
    val completed: Boolean
) {
    val megabitPerSecond: Double
        get() = if (millis > 0) bytes * 8.0 / 1_000_000.0 / (millis / 1000.0) else 0.0
}

/**
 * Misst die Download-Geschwindigkeit gegen eine vom Nutzer eingegebene HTTPS-Adresse.
 * Es gibt bewusst keine voreingestellte Adresse: Die App kontaktiert keinen Server, den der
 * Nutzer nicht selbst gewählt hat. Das Volumen ist auf [MAX_BYTES], die Dauer auf [MAX_MILLIS] begrenzt.
 */
object ThroughputTest {
    const val MAX_BYTES = 50L * 1024 * 1024
    const val MAX_MILLIS = 10_000L

    /** Nur HTTPS mit Hostname ist erlaubt (die App sperrt Klartextverkehr). */
    fun isAllowedUrl(url: String): Boolean = try {
        val uri = URI(url.trim())
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    } catch (e: Exception) {
        false
    }

    suspend fun run(url: String): ThroughputResult = withContext(Dispatchers.IO) {
        require(isAllowedUrl(url)) { "Nur HTTPS-Adressen sind erlaubt" }
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder()
            .url(url.trim())
            .header("User-Agent", "MiriquidiHub-Throughput")
            .build()
        val call = client.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        val start = System.nanoTime()
        call.execute().use { response ->
            val firstByte = (System.nanoTime() - start) / 1_000_000
            val body = response.body
            var total = 0L
            var completed = false
            val buffer = ByteArray(64 * 1024)
            val source = body.byteStream()
            val readStart = System.nanoTime()
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) {
                    completed = true
                    break
                }
                total += read
                val elapsed = (System.nanoTime() - readStart) / 1_000_000
                if (total >= MAX_BYTES || elapsed >= MAX_MILLIS) break
            }
            val readMillis = (System.nanoTime() - readStart) / 1_000_000
            ThroughputResult(total, readMillis, firstByte, response.code, completed)
        }
    }
}
