package io.github.tux4us.miriquidihub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditGeometryTest {

    @Test
    fun fit_centersAndScales() {
        val f = EditGeometry.fit(1000f, 1000f, 500f, 1000f)!!
        assertEquals(1f, f.scale, 0.0001f)
        assertEquals(250f, f.offsetX, 0.0001f)
        assertEquals(0f, f.offsetY, 0.0001f)
        assertEquals(10f, f.toImageX(260f), 0.0001f)
        assertEquals(260f, f.toViewX(10f), 0.0001f)
        assertNull(EditGeometry.fit(0f, 10f, 5f, 5f))
        assertNotNull(EditGeometry.fit(10f, 10f, 5f, 5f))
    }

    @Test
    fun normalize_ordersAndClamps() {
        val b = EditGeometry.normalize(120f, -5f, 20f, 80f, 100f, 60f)
        assertEquals(Box(20f, 0f, 100f, 60f), b)
    }

    @Test
    fun isUsable_requiresMinimumSize() {
        assertFalse(EditGeometry.isUsable(Box(0f, 0f, 7f, 100f)))
        assertTrue(EditGeometry.isUsable(Box(0f, 0f, 8f, 8f)))
    }

    @Test
    fun cropRect_staysInsideImage() {
        val r = EditGeometry.cropRect(Box(10.7f, 20.2f, 500f, 500f), 100, 200)
        assertEquals(listOf(10, 20, 90, 180), r.toList())
        val tiny = EditGeometry.cropRect(Box(99.9f, 199.9f, 100f, 200f), 100, 200)
        assertEquals(listOf(99, 199, 1, 1), tiny.toList())
    }
}
