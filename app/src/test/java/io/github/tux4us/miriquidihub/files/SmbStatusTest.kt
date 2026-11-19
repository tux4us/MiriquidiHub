package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbStatusTest {

    @Test fun httpCodes() {
        assertEquals(401, SmbStatus.httpCode(SmbStatus.LOGON_FAILURE))
        assertEquals(401, SmbStatus.httpCode(SmbStatus.PASSWORD_EXPIRED))
        assertEquals(401, SmbStatus.httpCode(SmbStatus.ACCOUNT_DISABLED))
        assertEquals(403, SmbStatus.httpCode(SmbStatus.ACCESS_DENIED))
        assertEquals(404, SmbStatus.httpCode(SmbStatus.OBJECT_NAME_NOT_FOUND))
        assertEquals(404, SmbStatus.httpCode(SmbStatus.OBJECT_PATH_NOT_FOUND))
        assertEquals(404, SmbStatus.httpCode(SmbStatus.BAD_NETWORK_NAME))
        assertEquals(423, SmbStatus.httpCode(SmbStatus.SHARING_VIOLATION))
        assertEquals(507, SmbStatus.httpCode(SmbStatus.DISK_FULL))
        assertEquals(0, SmbStatus.httpCode(0xC0000001L))
    }

    @Test fun notFoundAndCollision() {
        assertTrue(SmbStatus.isNotFound(SmbStatus.OBJECT_NAME_NOT_FOUND))
        assertTrue(SmbStatus.isNotFound(SmbStatus.NO_SUCH_FILE))
        assertFalse(SmbStatus.isNotFound(SmbStatus.ACCESS_DENIED))
        assertTrue(SmbStatus.isCollision(SmbStatus.OBJECT_NAME_COLLISION))
        assertFalse(SmbStatus.isCollision(SmbStatus.ACCESS_DENIED))
    }

    @Test fun exceptionMapping() {
        val exists = SmbStatus.toException(SmbStatus.OBJECT_NAME_COLLISION, "Ordner anlegen", null)
        assertTrue(exists is AlreadyExistsException)

        val auth = SmbStatus.toException(SmbStatus.LOGON_FAILURE, "Verbinden", "Anmeldung fehlgeschlagen")
        assertEquals(401, auth.code)
        assertTrue(auth.isAuth)
        assertTrue(auth.message!!.contains("0xC000006D"))

        val share = SmbStatus.toException(SmbStatus.BAD_NETWORK_NAME, "Verbinden", null)
        assertEquals(404, share.code)

        val unknown = SmbStatus.toException(0xC0000001L, "Löschen", null)
        assertEquals(0, unknown.code)
        assertTrue(unknown.message!!.startsWith("Löschen: SMB-Fehler"))
    }

    @Test fun constantsMatchSpecification() {
        // Werte laut [MS-ERREF]; gleich mit com.hierynomus.mserref.NtStatus.
        assertEquals(0xC000006DL, SmbStatus.LOGON_FAILURE)
        assertEquals(0xC0000022L, SmbStatus.ACCESS_DENIED)
        assertEquals(0xC0000034L, SmbStatus.OBJECT_NAME_NOT_FOUND)
        assertEquals(0xC0000035L, SmbStatus.OBJECT_NAME_COLLISION)
        assertEquals(0xC00000CCL, SmbStatus.BAD_NETWORK_NAME)
        assertEquals(0xC000007FL, SmbStatus.DISK_FULL)
    }
}
