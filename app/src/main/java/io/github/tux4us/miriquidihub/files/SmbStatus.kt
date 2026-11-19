package io.github.tux4us.miriquidihub.files

/**
 * Bildet SMB-Statuscodes (NTSTATUS) auf [RemoteException] ab. Die Codes entsprechen den
 * HTTP-Entsprechungen, damit die vorhandenen Fehlermeldungen der Oberfläche greifen.
 */
object SmbStatus {
    const val NO_SUCH_FILE = 0xC000000FL
    const val ACCESS_DENIED = 0xC0000022L
    const val OBJECT_NAME_NOT_FOUND = 0xC0000034L
    const val OBJECT_NAME_COLLISION = 0xC0000035L
    const val OBJECT_PATH_NOT_FOUND = 0xC000003AL
    const val SHARING_VIOLATION = 0xC0000043L
    const val LOGON_FAILURE = 0xC000006DL
    const val PASSWORD_EXPIRED = 0xC0000071L
    const val ACCOUNT_DISABLED = 0xC0000072L
    const val DISK_FULL = 0xC000007FL
    const val BAD_NETWORK_NAME = 0xC00000CCL
    const val DIRECTORY_NOT_EMPTY = 0xC0000101L
    const val NOT_FOUND = 0xC0000225L

    fun isNotFound(status: Long): Boolean =
        status == OBJECT_NAME_NOT_FOUND || status == OBJECT_PATH_NOT_FOUND ||
            status == NO_SUCH_FILE || status == NOT_FOUND || status == BAD_NETWORK_NAME

    fun isCollision(status: Long): Boolean = status == OBJECT_NAME_COLLISION

    /** Pseudo-HTTP-Code für die Oberfläche; 0 = kein Äquivalent. */
    fun httpCode(status: Long): Int = when (status) {
        LOGON_FAILURE, PASSWORD_EXPIRED, ACCOUNT_DISABLED -> 401
        ACCESS_DENIED -> 403
        OBJECT_NAME_NOT_FOUND, OBJECT_PATH_NOT_FOUND, NO_SUCH_FILE, NOT_FOUND, BAD_NETWORK_NAME -> 404
        OBJECT_NAME_COLLISION -> 412
        SHARING_VIOLATION -> 423
        DISK_FULL -> 507
        else -> 0
    }

    fun toException(status: Long, action: String, detail: String?, cause: Throwable? = null): RemoteException {
        val hex = "0x" + java.lang.Long.toHexString(status).uppercase()
        return when {
            isCollision(status) -> AlreadyExistsException("$action: existiert bereits")
            status == BAD_NETWORK_NAME ->
                RemoteException("$action: Freigabe nicht gefunden (SMB $hex)", 404, cause)
            status == DIRECTORY_NOT_EMPTY ->
                RemoteException("$action: Ordner ist nicht leer (SMB $hex)", 0, cause)
            else -> {
                val code = httpCode(status)
                val msg = "$action: ${detail ?: "SMB-Fehler"} (SMB $hex)"
                RemoteException(msg, code, cause)
            }
        }
    }
}
