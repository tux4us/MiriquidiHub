package io.github.tux4us.miriquidihub.files

import java.io.IOException

/** Fehler beim Zugriff auf eine Quelle. [code] ist der HTTP-Statuscode, 0 wenn nicht zutreffend. */
open class RemoteException(message: String, val code: Int = 0, cause: Throwable? = null) :
    IOException(message, cause) {
    val isAuth: Boolean get() = code == 401 || code == 403
    val isNotFound: Boolean get() = code == 404
}

/** Ziel existiert bereits (HTTP 412/405 bei MKCOL, lokal vorhandene Datei). */
class AlreadyExistsException(message: String) : RemoteException(message, 412)
