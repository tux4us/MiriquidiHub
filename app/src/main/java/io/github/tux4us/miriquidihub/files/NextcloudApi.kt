package io.github.tux4us.miriquidihub.files

import com.google.gson.JsonParser
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Nextcloud-spezifische Hilfen: Adressen, Benutzerkennung und die Anmeldung per Login Flow v2. */
object NextcloudApi {

    /**
     * Bringt Nutzereingaben auf die Serveradresse: ergänzt https://, entfernt Abschnitte ab
     * "/remote.php", "/index.php" und "/apps" sowie abschließende Schrägstriche.
     * Eine eingefügte WebDAV-Adresse (…/remote.php/dav/files/anna) wird so auf die Serveradresse reduziert.
     */
    fun normalizeServerUrl(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return s
        if (!s.contains("://")) s = "https://$s"
        for (marker in listOf("/remote.php", "/index.php", "/apps/", "/ocs/")) {
            val i = s.indexOf(marker, startIndex = s.indexOf("://") + 3)
            if (i >= 0) s = s.substring(0, i)
        }
        return s.trimEnd('/')
    }

    /** Benutzerkennung aus einer eingefügten WebDAV-Adresse (…/remote.php/dav/files/<kennung>), sonst null. */
    fun userIdFromDavUrl(input: String): String? {
        val marker = "/remote.php/dav/files/"
        val i = input.indexOf(marker)
        if (i < 0) return null
        val rest = input.substring(i + marker.length).substringBefore('/').substringBefore('?')
        return rest.takeIf { it.isNotBlank() }?.let { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
    }

    /** WebDAV-Wurzel eines Nutzers: <server>/remote.php/dav/files/<kennung>. */
    fun davRoot(serverUrl: String, userId: String): String? {
        val base = serverUrl.trim().toHttpUrlOrNull() ?: return null
        val basePath = WebDavParser.relativePath(base.encodedPath, PathUtil.ROOT) ?: PathUtil.ROOT
        val b = HttpUrl.Builder().scheme(base.scheme).host(base.host).port(base.port)
        for (s in PathUtil.segments(basePath)) b.addPathSegment(s)
        for (s in listOf("remote.php", "dav", "files", userId)) b.addPathSegment(s)
        return b.build().toString()
    }

    /** Fragt die interne Benutzerkennung ab. null, wenn der Server sie nicht liefert. */
    fun resolveUserId(client: OkHttpClient, serverUrl: String, username: String, password: String): String? {
        val base = serverUrl.trim().toHttpUrlOrNull() ?: return null
        val url = base.newBuilder()
            .addPathSegments("ocs/v1.php/cloud/user")
            .addQueryParameter("format", "json")
            .build()
        val req = Request.Builder().url(url)
            .header("OCS-APIRequest", "true")
            .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw RemoteException("Anmeldung abgelehnt (HTTP ${r.code})", r.code)
                if (!r.isSuccessful) return null
                JsonParser.parseString(r.body!!.string()).asJsonObject
                    .getAsJsonObject("ocs").getAsJsonObject("data").get("id")?.asString
            }
        } catch (e: RemoteException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ----- Login Flow v2 -----

    class LoginSession(val loginUrl: String, val pollEndpoint: String, val pollToken: String)

    class LoginResult(val server: String, val loginName: String, val appPassword: String)

    /** Startet den Login Flow v2. Die [LoginSession.loginUrl] öffnet der Nutzer im Browser. */
    fun startLoginFlow(client: OkHttpClient, serverUrl: String): LoginSession {
        val base = serverUrl.trim().toHttpUrlOrNull() ?: throw IOException("Ungültige Serveradresse")
        val url = base.newBuilder().addPathSegments("index.php/login/v2").build()
        val req = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RemoteException("Der Server unterstützt die Browser-Anmeldung nicht (HTTP ${r.code})", r.code)
            val json = try {
                JsonParser.parseString(r.body!!.string()).asJsonObject
            } catch (e: Exception) {
                throw IOException("Unerwartete Antwort des Servers", e)
            }
            val login = json.get("login")?.asString ?: throw IOException("Unerwartete Antwort des Servers")
            val poll = json.getAsJsonObject("poll")
            val token = poll?.get("token")?.asString ?: throw IOException("Unerwartete Antwort des Servers")
            val endpoint = poll.get("endpoint")?.asString ?: throw IOException("Unerwartete Antwort des Servers")
            val scheme = login.toHttpUrlOrNull()?.scheme
            if (scheme != "https" && scheme != "http") throw IOException("Ungültige Anmelde-Adresse")
            return LoginSession(login, endpoint, token)
        }
    }

    /** Fragt einmal ab. null = Nutzer hat die Anmeldung im Browser noch nicht bestätigt. */
    fun pollLoginFlow(client: OkHttpClient, session: LoginSession): LoginResult? {
        val endpoint = session.pollEndpoint.toHttpUrlOrNull() ?: throw IOException("Ungültige Abfrage-Adresse")
        val body = FormBody.Builder().add("token", session.pollToken).build()
        client.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw RemoteException("Anmeldung fehlgeschlagen (HTTP ${r.code})", r.code)
            val json = try {
                JsonParser.parseString(r.body!!.string()).asJsonObject
            } catch (e: Exception) {
                throw IOException("Unerwartete Antwort des Servers", e)
            }
            val server = json.get("server")?.asString
            val login = json.get("loginName")?.asString
            val pass = json.get("appPassword")?.asString
            if (server == null || login == null || pass == null) throw IOException("Unerwartete Antwort des Servers")
            return LoginResult(server, login, pass)
        }
    }
}
