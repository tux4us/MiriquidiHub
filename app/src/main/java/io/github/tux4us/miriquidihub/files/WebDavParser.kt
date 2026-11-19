package io.github.tux4us.miriquidihub.files

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/** Wertet WebDAV-Multistatus-Antworten (PROPFIND) aus. Reine Logik, ohne Android-Abhängigkeit. */
object WebDavParser {

    private const val DAV = "DAV:"
    private const val OC = "http://owncloud.org/ns"

    /** Body für PROPFIND; fragt neben den Standard-Eigenschaften die Ordnergröße von ownCloud/Nextcloud ab. */
    const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\"><d:prop>" +
            "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/><oc:size/>" +
            "</d:prop></d:propfind>"

    /**
     * Liefert die Einträge einer Multistatus-Antwort. [basePath] ist der dekodierte URL-Pfad der
     * Verbindungswurzel (z. B. "/remote.php/dav/files/anna"), [requestedPath] der abgefragte
     * Quellen-Pfad. Der abgefragte Ordner selbst ist nicht Teil der Liste.
     */
    fun parse(xml: String, basePath: String, requestedPath: String): List<FileEntry> =
        parseAll(xml, basePath).filter { PathUtil.normalize(it.path) != PathUtil.normalize(requestedPath) }

    /** Wie [parse], enthält aber auch den abgefragten Eintrag selbst (für Depth: 0). */
    fun parseAll(xml: String, basePath: String): List<FileEntry> {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            throw IOException("Ungültige Serverantwort (DTD nicht erlaubt)")
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw IOException("Ungültige Serverantwort: ${e.message}", e)
        }
        val base = PathUtil.normalize(basePath)
        val result = ArrayList<FileEntry>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val resp = responses.item(i) as? Element ?: continue
            val href = text(resp, DAV, "href") ?: continue
            val path = relativePath(href, base) ?: continue
            val props = okProp(resp)
            val isDir = props?.let { hasCollection(it) } ?: href.endsWith("/")
            val len = props?.let { text(it, DAV, "getcontentlength")?.trim()?.toLongOrNull() }
            val ocSize = props?.let { text(it, OC, "size")?.trim()?.toLongOrNull() }
            val quota = props?.let { text(it, DAV, "quota-used-bytes")?.trim()?.toLongOrNull() }
            val size = if (isDir) (ocSize ?: quota ?: -1L) else (len ?: ocSize ?: -1L)
            val modified = props?.let { text(it, DAV, "getlastmodified") }?.let { parseDate(it) } ?: 0L
            val mime = props?.let { text(it, DAV, "getcontenttype") }?.trim()?.takeIf { it.isNotEmpty() }
            val name = PathUtil.name(path).ifEmpty { "/" }
            if (name == "." || name == "..") continue
            result.add(FileEntry(name, path, isDir, size, modified, if (isDir) null else mime))
        }
        return result
    }

    /**
     * Wandelt ein href (absoluter URL oder URL-Pfad, kodiert) in einen Quellen-Pfad um.
     * null, wenn das href außerhalb von [basePath] liegt.
     */
    fun relativePath(href: String, basePath: String): String? {
        val rawPath = try {
            if (href.contains("://")) URI(href).rawPath ?: "" else href.substringBefore('?').substringBefore('#')
        } catch (e: Exception) {
            return null
        }
        val decoded = try {
            // "+" ist in URL-Pfaden ein Pluszeichen, kein Leerzeichen.
            URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            return null
        }
        val full = PathUtil.normalize(decoded)
        val base = PathUtil.normalize(basePath)
        if (base == PathUtil.ROOT) return full
        if (full == base) return PathUtil.ROOT
        if (!full.startsWith("$base/")) return null
        return PathUtil.normalize(full.removePrefix(base))
    }

    fun parseDate(value: String): Long {
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (f in formats) {
            try {
                val fmt = SimpleDateFormat(f, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("GMT")
                fmt.isLenient = false
                return fmt.parse(value.trim())?.time ?: continue
            } catch (e: Exception) {
                // nächstes Format
            }
        }
        return 0L
    }

    /** Erste prop-Gruppe, deren Status 2xx ist (oder fehlt). */
    private fun okProp(resp: Element): Element? {
        val stats = resp.getElementsByTagNameNS(DAV, "propstat")
        for (i in 0 until stats.length) {
            val ps = stats.item(i) as? Element ?: continue
            val status = text(ps, DAV, "status")
            if (status == null || status.contains(" 2")) {
                val p = ps.getElementsByTagNameNS(DAV, "prop")
                if (p.length > 0) return p.item(0) as? Element
            }
        }
        return null
    }

    private fun hasCollection(prop: Element): Boolean {
        val rt = prop.getElementsByTagNameNS(DAV, "resourcetype")
        if (rt.length == 0) return false
        val children = rt.item(0).childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.localName == "collection") return true
        }
        return false
    }

    private fun text(parent: Element, ns: String, local: String): String? {
        val nodes = parent.getElementsByTagNameNS(ns, local)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent
    }
}
