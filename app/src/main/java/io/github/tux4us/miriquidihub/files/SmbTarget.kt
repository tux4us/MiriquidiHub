package io.github.tux4us.miriquidihub.files

/**
 * Verbindungsziel einer SMB-Freigabe, abgeleitet aus einer Benutzereingabe.
 *
 * Akzeptierte Schreibweisen: `nas`, `nas.local`, `192.168.1.10:445`, `smb://nas/Freigabe`,
 * `\\nas\Freigabe`. Enthält die Eingabe einen Freigabenamen, wird er in [share] geliefert.
 */
data class SmbTarget(val host: String, val port: Int, val share: String?) {

    /** Kanonische Schreibweise für die Speicherung: `host` oder `host:port`. */
    fun hostSpec(): String = if (port == DEFAULT_PORT) hostForUrl(host) else "${hostForUrl(host)}:$port"

    companion object {
        const val DEFAULT_PORT = 445

        private fun hostForUrl(host: String) = if (host.contains(':')) "[$host]" else host

        /** null, wenn die Eingabe keinen gültigen Host enthält. */
        fun parse(raw: String): SmbTarget? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            s = s.replace('\\', '/')
            s = Regex("^smb://", RegexOption.IGNORE_CASE).replace(s, "")
            s = s.trimStart('/')
            val slash = s.indexOf('/')
            val authority = if (slash >= 0) s.substring(0, slash) else s
            val rest = if (slash >= 0) s.substring(slash + 1) else ""
            val auth = authority.substringAfterLast('@')
            if (auth.isEmpty()) return null

            val host: String
            var port = DEFAULT_PORT
            if (auth.startsWith("[")) {
                val end = auth.indexOf(']')
                if (end < 0) return null
                host = auth.substring(1, end)
                val tail = auth.substring(end + 1)
                if (tail.isNotEmpty()) {
                    if (!tail.startsWith(":")) return null
                    port = tail.substring(1).toIntOrNull() ?: return null
                }
            } else if (auth.count { it == ':' } == 1) {
                host = auth.substringBefore(':')
                port = auth.substringAfter(':').toIntOrNull() ?: return null
            } else {
                host = auth
            }
            if (host.isEmpty() || port !in 1..65535) return null
            if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' }) return null

            val share = rest.substringBefore('/').trim().takeIf { it.isNotEmpty() }
            if (share != null && !isValidShare(share)) return null
            return SmbTarget(host, port, share)
        }

        /** Freigabename ohne Trennzeichen und Steuerzeichen, höchstens 80 Zeichen. */
        fun isValidShare(name: String): Boolean =
            name.isNotBlank() && name.length <= 80 &&
                name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 }
    }
}
