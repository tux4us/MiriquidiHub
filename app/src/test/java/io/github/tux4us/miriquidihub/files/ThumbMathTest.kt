package io.github.tux4us.miriquidihub.files

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbMathTest {

    @Test fun sampleSizeKeepsTargetResolution() {
        assertEquals(1, ThumbMath.sampleSize(200, 200, 200))
        assertEquals(1, ThumbMath.sampleSize(300, 300, 200))
        assertEquals(2, ThumbMath.sampleSize(400, 400, 200))
        assertEquals(8, ThumbMath.sampleSize(4000, 3000, 300))
        assertEquals(16, ThumbMath.sampleSize(8000, 6000, 300))
    }

    @Test fun sampleSizeUsesShortSide() {
        assertEquals(1, ThumbMath.sampleSize(4000, 250, 200))
    }

    @Test fun sampleSizeInvalidInput() {
        assertEquals(1, ThumbMath.sampleSize(0, 100, 50))
        assertEquals(1, ThumbMath.sampleSize(100, 100, 0))
    }

    @Test fun spanCount() {
        assertEquals(2, ThumbMath.spanCount(0, 100))
        assertEquals(2, ThumbMath.spanCount(150, 100))
        assertEquals(3, ThumbMath.spanCount(330, 110))
        assertEquals(9, ThumbMath.spanCount(1000, 110))
        assertEquals(2, ThumbMath.spanCount(500, 0))
    }
}
