package io.github.tux4us.miriquidihub

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tux4us.miriquidihub.databinding.DialogFmConnectionBinding
import io.github.tux4us.miriquidihub.files.CertTools
import io.github.tux4us.miriquidihub.files.Connection
import io.github.tux4us.miriquidihub.files.ConnectionStore
import io.github.tux4us.miriquidihub.files.ConnectionType
import io.github.tux4us.miriquidihub.files.HttpPolicy
import io.github.tux4us.miriquidihub.files.NextcloudApi
import io.github.tux4us.miriquidihub.files.RemoteClients
import io.github.tux4us.miriquidihub.files.RemoteException
import io.github.tux4us.miriquidihub.files.SmbTarget
import io.github.tux4us.miriquidihub.files.SourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.cert.X509Certificate
import java.text.DateFormat

/**
 * Dialog zum Anlegen und Bearbeiten einer WebDAV- oder Nextcloud-Verbindung. Vor dem Speichern wird
 * die Verbindung getestet. Selbstsignierte Zertifikate werden erst nach Bestätigung des
 * Fingerabdrucks akzeptiert; unverschlüsseltes HTTP außerhalb des Heimnetzes nur nach Rückfrage.
 */
class FileConnectionDialog(
    private val activity: AppCompatActivity,
    private val store: ConnectionStore,
    private val existing: Connection?,
    private val onSaved: (Connection) -> Unit
) {
    private lateinit var b: DialogFmConnectionBinding
    private var dialog: AlertDialog? = null
    private var pinned: String? = existing?.pinnedCertSha256
    private var allowInsecure: Boolean = existing?.allowInsecureHttp ?: false
    private var loginJob: Job? = null

    private sealed class Outcome {
        class Ok(val connection: Connection) : Outcome()
        class CertProblem(val host: String, val port: Int) : Outcome()
        class Failed(val message: String) : Outcome()
    }

    fun show() {
        b = DialogFmConnectionBinding.inflate(activity.layoutInflater)
        existing?.let {
            b.etName.setText(it.name)
            b.etUrl.setText(it.url)
            b.etUser.setText(it.username)
            b.etShare.setText(it.share.orEmpty())
            b.etDomain.setText(it.domain.orEmpty())
            b.typeToggle.check(
                when (it.type) {
                    ConnectionType.NEXTCLOUD -> R.id.btnTypeNextcloud
                    ConnectionType.SMB -> R.id.btnTypeSmb
                    ConnectionType.WEBDAV -> R.id.btnTypeWebdav
                }
            )
        } ?: b.typeToggle.check(R.id.btnTypeWebdav)
        b.typeToggle.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) updateTypeUi() }
        b.etUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateCleartextHint()
        })
        b.btnBrowserLogin.setOnClickListener { startBrowserLogin() }
        updateTypeUi()
        updatePinnedUi()

        val d = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.fm_conn_add else R.string.fm_conn_edit)
            .setView(b.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onSave() } }
        d.setOnDismissListener { loginJob?.cancel() }
        dialog = d
        d.show()
    }

    private fun currentType(): ConnectionType = when (b.typeToggle.checkedButtonId) {
        R.id.btnTypeNextcloud -> ConnectionType.NEXTCLOUD
        R.id.btnTypeSmb -> ConnectionType.SMB
        else -> ConnectionType.WEBDAV
    }

    private fun updateTypeUi() {
        val type = currentType()
        val nc = type == ConnectionType.NEXTCLOUD
        val smb = type == ConnectionType.SMB
        b.tilUrl.hint = activity.getString(
            when (type) {
                ConnectionType.NEXTCLOUD -> R.string.fm_url_hint_nextcloud
                ConnectionType.SMB -> R.string.fm_url_hint_smb
                ConnectionType.WEBDAV -> R.string.fm_url_hint
            }
        )
        b.tvTypeHint.setText(
            when (type) {
                ConnectionType.NEXTCLOUD -> R.string.fm_type_hint_nextcloud
                ConnectionType.SMB -> R.string.fm_type_hint_smb
                ConnectionType.WEBDAV -> R.string.fm_type_hint_webdav
            }
        )
        b.btnBrowserLogin.visibility = if (nc) View.VISIBLE else View.GONE
        b.tilShare.visibility = if (smb) View.VISIBLE else View.GONE
        b.tilDomain.visibility = if (smb) View.VISIBLE else View.GONE
        updateCleartextHint()
    }

    private fun updateCleartextHint() {
        val t = b.etUrl.text?.toString()?.trim()?.lowercase().orEmpty()
        b.tvCleartext.visibility =
            if (currentType() != ConnectionType.SMB && t.startsWith("http://")) View.VISIBLE else View.GONE
    }

    private fun updatePinnedUi() {
        val p = pinned
        if (p.isNullOrBlank()) {
            b.tvPinned.visibility = View.GONE
        } else {
            b.tvPinned.visibility = View.VISIBLE
            b.tvPinned.text = activity.getString(R.string.fm_pinned_cert, CertTools.pretty(p).take(23) + "…")
        }
    }

    private fun setBusy(busy: Boolean) {
        b.connProgress.visibility = if (busy) View.VISIBLE else View.GONE
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !busy
        b.btnBrowserLogin.isEnabled = !busy
    }

    private fun showError(message: String?) {
        b.tvConnError.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        b.tvConnError.text = message.orEmpty()
    }

    /** Normalisiert die Adresse und prüft sie gegen die HTTP-Regel. null = abgebrochen oder ungültig. */
    private fun resolveUrl(type: ConnectionType, raw: String, then: (url: String, nextcloudUser: String?) -> Unit) {
        var nextcloudUser: String? = null
        val url = if (type == ConnectionType.NEXTCLOUD) {
            nextcloudUser = NextcloudApi.userIdFromDavUrl(raw)
            NextcloudApi.normalizeServerUrl(raw)
        } else {
            if (raw.contains("://")) raw else "https://$raw"
        }
        val parsed: HttpUrl? = url.toHttpUrlOrNull()
        if (parsed == null) {
            b.tilUrl.error = activity.getString(R.string.fm_err_invalid_url)
            return
        }
        b.tilUrl.error = null
        when (HttpPolicy.check(parsed.scheme, parsed.host, allowInsecure)) {
            HttpPolicy.Verdict.OK -> then(url, nextcloudUser)
            HttpPolicy.Verdict.INVALID -> b.tilUrl.error = activity.getString(R.string.fm_err_invalid_url)
            HttpPolicy.Verdict.NEEDS_CONFIRMATION -> MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.fm_http_confirm_title)
                .setMessage(activity.getString(R.string.fm_http_confirm_msg, parsed.host))
                .setPositiveButton(R.string.fm_http_confirm_ok) { _, _ ->
                    allowInsecure = true
                    then(url, nextcloudUser)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun onSave() {
        showError(null)
        val type = currentType()
        val rawUrl = b.etUrl.text?.toString()?.trim().orEmpty()
        val user = b.etUser.text?.toString()?.trim().orEmpty()
        val typedPass = b.etPass.text?.toString().orEmpty()
        if (rawUrl.isEmpty()) {
            b.tilUrl.error = activity.getString(R.string.fm_err_invalid_url)
            return
        }
        if (type != ConnectionType.WEBDAV && user.isEmpty()) {
            b.tilUser.error = activity.getString(R.string.fm_err_user_required)
            return
        }
        b.tilUser.error = null
        if (type == ConnectionType.SMB) {
            saveSmb(rawUrl, user, typedPass)
            return
        }
        resolveUrl(type, rawUrl) { url, ncUser ->
            val host = url.toHttpUrlOrNull()?.host.orEmpty()
            val name = b.etName.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: host
            val id = existing?.id ?: store.newId()
            // Bei geänderter Adresse oder Kennung muss die Nextcloud-Benutzerkennung neu ermittelt werden.
            val keepNcUser = existing != null && existing.url == url && existing.username == user
            val conn = Connection(
                id = id,
                type = type,
                name = name,
                url = url,
                username = user,
                nextcloudUserId = ncUser ?: if (keepNcUser) existing?.nextcloudUserId else null,
                pinnedCertSha256 = pinned,
                allowInsecureHttp = allowInsecure
            )
            val password = if (typedPass.isEmpty() && existing != null) store.password(existing.id) else typedPass
            testAndSave(conn, password, typedPass.isNotEmpty() || existing == null)
        }
    }

    /** SMB: Adresse, Freigabe und Netzbereich prüfen, dann wie die anderen Typen testen und speichern. */
    private fun saveSmb(rawUrl: String, user: String, typedPass: String) {
        b.tilUrl.error = null
        b.tilShare.error = null
        val target = SmbTarget.parse(rawUrl)
        if (target == null) {
            b.tilUrl.error = activity.getString(R.string.fm_err_invalid_url)
            return
        }
        if (!HttpPolicy.isPrivateHost(target.host)) {
            b.tilUrl.error = activity.getString(R.string.fm_err_smb_private)
            return
        }
        val share = b.etShare.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: target.share
        if (share == null || !SmbTarget.isValidShare(share)) {
            b.tilShare.error = activity.getString(R.string.fm_err_smb_share)
            return
        }
        val domain = b.etDomain.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
        val name = b.etName.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "${target.host}/$share"
        val conn = Connection(
            id = existing?.id ?: store.newId(),
            type = ConnectionType.SMB,
            name = name,
            url = target.hostSpec(),
            username = user,
            share = share,
            domain = domain
        )
        val password = if (typedPass.isEmpty() && existing != null) store.password(existing.id) else typedPass
        testAndSave(conn, password, typedPass.isNotEmpty() || existing == null)
    }

    private fun testAndSave(conn: Connection, password: String, passwordChanged: Boolean) {
        setBusy(true)
        activity.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runTest(conn, password) }
            setBusy(false)
            when (outcome) {
                is Outcome.Ok -> {
                    store.save(outcome.connection, if (passwordChanged) password else null)
                    dialog?.dismiss()
                    onSaved(outcome.connection)
                }
                is Outcome.CertProblem -> askToTrustCertificate(outcome.host, outcome.port) { onSave() }
                is Outcome.Failed -> showError(outcome.message)
            }
        }
    }

    private fun runTest(conn: Connection, password: String): Outcome {
        return try {
            var c = conn
            if (conn.type == ConnectionType.NEXTCLOUD && conn.nextcloudUserId == null) {
                val client = RemoteClients.build(conn.pinnedCertSha256)
                try {
                    val resolved = NextcloudApi.resolveUserId(client, conn.url, conn.username, password)
                    c = conn.copy(nextcloudUserId = resolved)
                } finally {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
            SourceFactory.create(c, password).use { it.testConnection() }
            Outcome.Ok(c)
        } catch (e: Exception) {
            if (FileErrors.isCertificateProblem(e)) {
                val parsed = SourceFactory.davUrlOrNull(conn)?.toHttpUrlOrNull()
                if (conn.pinnedCertSha256 != null) {
                    Outcome.Failed(activity.getString(R.string.fm_err_cert_changed))
                } else if (parsed != null) {
                    Outcome.CertProblem(parsed.host, parsed.port)
                } else {
                    Outcome.Failed(FileErrors.describe(activity, e))
                }
            } else {
                Outcome.Failed(FileErrors.describe(activity, e))
            }
        }
    }

    /** Zeigt den Fingerabdruck des Serverzertifikats; erst nach Bestätigung wird er hinterlegt. */
    private fun askToTrustCertificate(host: String, port: Int, retry: () -> Unit) {
        setBusy(true)
        activity.lifecycleScope.launch {
            val cert: X509Certificate? = withContext(Dispatchers.IO) {
                try { CertTools.fetchLeaf(host, port) } catch (e: Exception) { null }
            }
            setBusy(false)
            if (cert == null) {
                showError(activity.getString(R.string.fm_err_cert_fetch))
                return@launch
            }
            val fp = CertTools.pretty(CertTools.sha256(cert))
            val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
            val msg = activity.getString(
                R.string.fm_cert_message,
                cert.subjectX500Principal.name,
                cert.issuerX500Principal.name,
                df.format(cert.notBefore), df.format(cert.notAfter),
                fp
            )
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.fm_cert_title)
                .setMessage(msg)
                .setPositiveButton(R.string.fm_cert_trust) { _, _ ->
                    pinned = CertTools.normalize(fp)
                    updatePinnedUi()
                    retry()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ----- Nextcloud: Anmeldung im Browser (Login Flow v2) -----

    private fun startBrowserLogin() {
        showError(null)
        val raw = b.etUrl.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            b.tilUrl.error = activity.getString(R.string.fm_err_invalid_url)
            return
        }
        resolveUrl(ConnectionType.NEXTCLOUD, raw) { server, _ -> beginLoginFlow(server) }
    }

    private fun beginLoginFlow(server: String) {
        setBusy(true)
        activity.lifecycleScope.launch {
            val client = RemoteClients.build(pinned)
            val result = withContext(Dispatchers.IO) {
                try { Result.success(NextcloudApi.startLoginFlow(client, server)) } catch (e: Exception) { Result.failure<NextcloudApi.LoginSession>(e) }
            }
            setBusy(false)
            val session = result.getOrNull()
            if (session == null) {
                val e = result.exceptionOrNull() ?: IOException()
                val parsed = server.toHttpUrlOrNull()
                if (FileErrors.isCertificateProblem(e) && pinned == null && parsed != null) {
                    askToTrustCertificate(parsed.host, parsed.port) { beginLoginFlow(server) }
                } else {
                    showError(FileErrors.describe(activity, e))
                }
                shutdown(client)
                return@launch
            }
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.loginUrl)))
            } catch (e: ActivityNotFoundException) {
                showError(activity.getString(R.string.fm_err_no_browser))
                shutdown(client)
                return@launch
            }
            awaitLogin(client, session, server)
        }
    }

    private fun awaitLogin(client: okhttp3.OkHttpClient, session: NextcloudApi.LoginSession, server: String) {
        val waiting = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.fm_login_waiting_title)
            .setMessage(R.string.fm_login_waiting_msg)
            .setNegativeButton(R.string.cancel) { _, _ -> loginJob?.cancel() }
            .setCancelable(false)
            .show()
        loginJob = activity.lifecycleScope.launch {
            try {
                val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(POLL_INTERVAL_MS)
                    val res = withContext(Dispatchers.IO) {
                        try {
                            Result.success<NextcloudApi.LoginResult?>(NextcloudApi.pollLoginFlow(client, session))
                        } catch (e: RemoteException) {
                            Result.failure<NextcloudApi.LoginResult?>(e)
                        } catch (e: IOException) {
                            Result.success<NextcloudApi.LoginResult?>(null) // kurzer Netzfehler: weiter abfragen
                        }
                    }
                    val failure = res.exceptionOrNull()
                    if (failure != null) {
                        showError(FileErrors.describe(activity, failure))
                        return@launch
                    }
                    val login = res.getOrNull() ?: continue
                    b.etUrl.setText(NextcloudApi.normalizeServerUrl(login.server.ifBlank { server }))
                    b.etUser.setText(login.loginName)
                    b.etPass.setText(login.appPassword)
                    Toast.makeText(activity, R.string.fm_login_done, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (isActive) showError(activity.getString(R.string.fm_login_timeout))
            } finally {
                waiting.dismiss()
                shutdown(client)
            }
        }
    }

    private fun shutdown(client: okhttp3.OkHttpClient) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val LOGIN_TIMEOUT_MS = 10L * 60 * 1000
    }
}
