package io.github.tux4us.miriquidihub.files

/** Reine Pfad-Hilfen für Quellen-Pfade ("/"-getrennt, mit führendem "/"). Ohne Android-Abhängigkeit. */
object PathUtil {

    const val ROOT = "/"

    /**
     * Normalisiert einen Pfad: führender "/", doppelte Trenner und "." entfernt, ".." aufgelöst
     * (nie über die Wurzel hinaus), kein abschließender "/".
     */
    fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (seg in path.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return if (out.isEmpty()) ROOT else "/" + out.joinToString("/")
    }

    fun segments(path: String): List<String> {
        val n = normalize(path)
        return if (n == ROOT) emptyList() else n.substring(1).split('/')
    }

    /** Übergeordneter Pfad, null für die Wurzel. */
    fun parent(path: String): String? {
        val segs = segments(path)
        if (segs.isEmpty()) return null
        return if (segs.size == 1) ROOT else "/" + segs.dropLast(1).joinToString("/")
    }

    fun name(path: String): String = segments(path).lastOrNull() ?: ""

    fun child(dir: String, name: String): String = normalize(normalize(dir) + "/" + name)

    /** true, wenn [path] gleich [ancestor] oder darunter liegt. */
    fun isInside(path: String, ancestor: String): Boolean {
        val p = normalize(path)
        val a = normalize(ancestor)
        if (a == ROOT) return true
        return p == a || p.startsWith("$a/")
    }

    /** Ein einzelner Dateiname ist gültig: nicht leer, kein "/", nicht "." oder "..", keine Steuerzeichen. */
    fun isValidName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 0x20 } && name.length <= 255

    /** "a.txt" → "a (1).txt"; Ordner ohne Erweiterung → "a (1)". */
    fun numberedName(name: String, counter: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($counter)${name.substring(dot)}" else "$name ($counter)"
    }

    /** Liefert [name] oder die erste freie nummerierte Variante, wenn [exists] für den Namen true meldet. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        var i = 1
        while (exists(numberedName(name, i))) i++
        return numberedName(name, i)
    }
}
