package io.github.tux4us.miriquidihub.files

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext

/** Erzeugt OkHttp-Clients für Dateiverbindungen. */
object RemoteClients {

    const val USER_AGENT = "MiriquidiHub-Files"

    /**
     * @param pinnedSha256 Fingerabdruck eines vom Nutzer bestätigten Zertifikats, null für die
     * normale Prüfung über die System-Zertifikate.
     */
    fun build(pinnedSha256: String?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
        if (!pinnedSha256.isNullOrBlank()) {
            val tm = PinnedTrustManager(pinnedSha256)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, tm)
            // Das Zertifikat selbst ist die Identität; Hostnamen im Heimnetz passen selten zum Zertifikat.
            b.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
        return b.build()
    }
}
