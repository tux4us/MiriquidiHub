package io.github.tux4us.miriquidihub

import android.content.Context
import io.github.tux4us.miriquidihub.files.AlreadyExistsException
import io.github.tux4us.miriquidihub.files.CancelledException
import io.github.tux4us.miriquidihub.files.RemoteException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Verständliche, übersetzte Fehlermeldungen für den Dateimanager. */
object FileErrors {

    fun describe(context: Context, e: Throwable): String = when {
        e is AlreadyExistsException -> context.getString(R.string.fm_err_exists)
        e is CancelledException -> context.getString(R.string.fm_err_cancelled)
        e is RemoteException && e.code == 401 -> context.getString(R.string.fm_err_auth)
        e is RemoteException && e.code == 403 -> context.getString(R.string.fm_err_forbidden)
        e is RemoteException && e.code == 404 -> context.getString(R.string.fm_err_not_found)
        e is RemoteException && e.code == 423 -> context.getString(R.string.fm_err_locked)
        e is RemoteException && e.code == 507 -> context.getString(R.string.fm_err_full)
        e is RemoteException && e.code in 300..399 -> context.getString(R.string.fm_err_redirect)
        e is RemoteException && e.code >= 400 -> context.getString(R.string.fm_err_http, e.code)
        e is UnknownHostException -> context.getString(R.string.fm_err_host)
        e is SSLException -> context.getString(R.string.fm_err_tls)
        e is SocketTimeoutException -> context.getString(R.string.fm_err_timeout)
        e is ConnectException || e is NoRouteToHostException -> context.getString(R.string.fm_err_connect)
        else -> e.message ?: e.javaClass.simpleName
    }

    /** true, wenn ein Zertifikatsproblem vorliegt (selbstsigniert, unbekannte Stelle, Hostname passt nicht). */
    fun isCertificateProblem(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is SSLException) return true
            t = t.cause
        }
        return false
    }
}
