package io.github.tux4us.miriquidihub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Testmuster für den erweiterten Display-Test: Verläufe (Banding), Gamma-Vergleich,
 * gleichmäßige Grautöne (Flecken, Einbrennen bei OLED).
 */
class DisplayPatternView(context: Context) : View(context) {

    companion object {
        const val PAGE_COUNT = 5
    }

    var page: Int = 0
        set(value) {
            field = value.coerceIn(0, PAGE_COUNT - 1)
            invalidate()
        }

    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (page) {
            0 -> drawRamps(canvas, w, h)
            1 -> drawGamma(canvas, w, h)
            2 -> canvas.drawColor(Color.rgb(32, 32, 32))
            3 -> canvas.drawColor(Color.rgb(128, 128, 128))
            else -> canvas.drawColor(Color.rgb(224, 224, 224))
        }
    }

    private fun drawRamps(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(Color.BLACK)
        val ends = intArrayOf(Color.WHITE, Color.RED, Color.GREEN, Color.BLUE)
        val bandHeight = h / ends.size
        ends.forEachIndexed { i, end ->
            paint.shader = LinearGradient(0f, 0f, w, 0f, Color.BLACK, end, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, i * bandHeight, w, (i + 1) * bandHeight, paint)
        }
        paint.shader = null
    }

    /** Linke Hälfte: Grau 50 %. Rechte Hälfte: Zeilenraster Schwarz/Weiß. Bei korrektem Gamma wirken beide ähnlich hell. */
    private fun drawGamma(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(Color.BLACK)
        paint.color = Color.rgb(188, 188, 188)
        canvas.drawRect(0f, 0f, w / 2, h, paint)
        paint.color = Color.WHITE
        var y = 0f
        while (y < h) {
            canvas.drawRect(w / 2, y, w, y + 1f, paint)
            y += 2f
        }
    }
}
