package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class WebDavParserTest {

    private val base = "/remote.php/dav/files/anna"

    private val sample = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
 <d:response>
  <d:href>/remote.php/dav/files/anna/Fotos/</d:href>
  <d:propstat><d:prop>
    <d:getlastmodified>Mon, 02 Mar 2026 10:00:00 GMT</d:getlastmodified>
    <d:resourcetype><d:collection/></d:resourcetype>
    <oc:size>4096</oc:size>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/anna/Fotos/Urlaub%202025/</d:href>
  <d:propstat><d:prop>
    <d:resourcetype><d:collection/></d:resourcetype>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/anna/Fotos/B%C3%A4r+Katze.jpg</d:href>
  <d:propstat><d:prop>
    <d:getlastmodified>Tue, 03 Mar 2026 12:30:00 GMT</d:getlastmodified>
    <d:getcontentlength>12345</d:getcontentlength>
    <d:getcontenttype>image/jpeg</d:getcontenttype>
    <d:resourcetype/>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
</d:multistatus>"""

    @Test
    fun parse_listsChildrenAndSkipsRequestedFolder() {
        val list = WebDavParser.parse(sample, base, "/Fotos")
        assertEquals(2, list.size)
        val dir = list.first { it.isDirectory }
        assertEquals("Urlaub 2025", dir.name)
        assertEquals("/Fotos/Urlaub 2025", dir.path)
        val file = list.first { !it.isDirectory }
        assertEquals("Bär+Katze.jpg", file.name)
        assertEquals(12345L, file.size)
        assertEquals("image/jpeg", file.mime)
        assertEquals("jpg", file.extension)
        assertTrue(file.modified > 0)
    }

    @Test
    fun parseAll_includesRequestedFolderWithOcSize() {
        val all = WebDavParser.parseAll(sample, base)
        val self = all.first { it.path == "/Fotos" }
        assertTrue(self.isDirectory)
        assertEquals(4096L, self.size)
        assertEquals(-1L, all.first { it.name == "Urlaub 2025" }.size)
    }

    @Test
    fun parseDate_rfc1123() {
        assertEquals(1772445600000L, WebDavParser.parseDate("Mon, 02 Mar 2026 10:00:00 GMT"))
        assertEquals(0L, WebDavParser.parseDate("kein Datum"))
    }

    @Test
    fun relativePath_handlesAbsoluteUrlsAndForeignPaths() {
        assertEquals("/a/b", WebDavParser.relativePath("https://h.example/remote.php/dav/files/anna/a/b", base))
        assertEquals("/", WebDavParser.relativePath("/remote.php/dav/files/anna/", base))
        assertNull(WebDavParser.relativePath("/remote.php/dav/files/bob/x", base))
        assertNull(WebDavParser.relativePath("/remote.php/dav/files/annabelle/x", base))
        assertEquals("/x y", WebDavParser.relativePath("/x%20y", "/"))
    }

    @Test
    fun relativePath_doesNotEscapeViaDotDot() {
        // Ein bösartiges href darf nicht aus der Basis ausbrechen.
        assertNull(WebDavParser.relativePath("/remote.php/dav/files/anna/../bob/x", base))
    }

    @Test
    fun parse_rejectsDoctype() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"b\">]><d:multistatus xmlns:d=\"DAV:\"/>"
        try {
            WebDavParser.parse(evil, "/", "/")
            fail("DTD hätte abgelehnt werden müssen")
        } catch (e: IOException) {
            assertFalse(e.message.isNullOrBlank())
        }
    }

    @Test
    fun parse_rejectsBrokenXml() {
        try {
            WebDavParser.parse("<d:multistatus", "/", "/")
            fail()
        } catch (e: IOException) {
            // erwartet
        }
    }

    @Test
    fun parse_fullUrlHrefsAndMissingStatus() {
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response>
            <d:href>http://nas.local:8080/dav/Doku.pdf</d:href>
            <d:propstat><d:prop><d:getcontentlength>10</d:getcontentlength><d:resourcetype/></d:prop></d:propstat>
            </d:response></d:multistatus>"""
        val list = WebDavParser.parse(xml, "/dav", "/")
        assertEquals(1, list.size)
        assertEquals("/Doku.pdf", list[0].path)
        assertEquals(10L, list[0].size)
    }
}
