package io.github.tux4us.miriquidihub

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Einfache Bearbeitung eines Screenshots: schwarze Schwärzungsrechtecke (vollständig deckend,
 * nicht umkehrbar) und ein Zuschnitt. Die Eingaben werden in Bildkoordinaten gespeichert;
 * erst [renderResult] erzeugt das bearbeitete Bild.
 */
class ScreenshotEditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { REDACT, CROP }

    var mode: Mode = Mode.REDACT
    var onChanged: (() -> Unit)? = null

    private class Op(val redaction: Box?, val previousCrop: Box?)

    private var bitmap: Bitmap? = null
    private val redactions = mutableListOf<Box>()
    private var crop: Box? = null
    private val ops = mutableListOf<Op>()

    private var fit: FitTransform? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blackPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val dimPaint = Paint().apply { color = 0xAA000000.toInt(); style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5252.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density
    }

    fun setBitmap(value: Bitmap) {
        bitmap = value
        redactions.clear()
        ops.clear()
        crop = null
        requestLayout()
        invalidate()
        onChanged?.invoke()
    }

    fun hasEdits(): Boolean = ops.isNotEmpty()

    fun undo() {
        val op = ops.removeLastOrNull() ?: return
        if (op.redaction != null) redactions.remove(op.redaction) else crop = op.previousCrop
        invalidate()
        onChanged?.invoke()
    }

    /** Wendet Schwärzungen und Zuschnitt an. Das Original-Bitmap bleibt unverändert. */
    fun renderResult(): Bitmap? {
        val src = bitmap ?: return null
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(out)
        for (r in redactions) canvas.drawRect(r.left, r.top, r.right, r.bottom, blackPaint)
        val c = crop ?: return out
        val rect = EditGeometry.cropRect(c, out.width, out.height)
        val cropped = Bitmap.createBitmap(out, rect[0], rect[1], rect[2], rect[3])
        if (cropped !== out) out.recycle()
        return cropped
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val f = EditGeometry.fit(width.toFloat(), height.toFloat(), bmp.width.toFloat(), bmp.height.toFloat()) ?: return
        fit = f
        val dest = RectF(f.toViewX(0f), f.toViewY(0f), f.toViewX(bmp.width.toFloat()), f.toViewY(bmp.height.toFloat()))
        canvas.drawBitmap(bmp, null, dest, bitmapPaint)
        for (r in redactions) canvas.drawRect(viewRect(f, r), blackPaint)

        crop?.let { c ->
            val v = viewRect(f, c)
            canvas.drawRect(dest.left, dest.top, dest.right, v.top, dimPaint)
            canvas.drawRect(dest.left, v.bottom, dest.right, dest.bottom, dimPaint)
            canvas.drawRect(dest.left, v.top, v.left, v.bottom, dimPaint)
            canvas.drawRect(v.right, v.top, dest.right, v.bottom, dimPaint)
            canvas.drawRect(v, borderPaint)
        }
        if (dragging) {
            canvas.drawRect(
                minOf(dragStartX, dragX), minOf(dragStartY, dragY),
                maxOf(dragStartX, dragX), maxOf(dragStartY, dragY), previewPaint
            )
        }
    }

    private fun viewRect(f: FitTransform, b: Box) =
        RectF(f.toViewX(b.left), f.toViewY(b.top), f.toViewX(b.right), f.toViewY(b.bottom))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || fit == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x; dragStartY = event.y
                dragX = event.x; dragY = event.y
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                dragX = event.x; dragY = event.y
            }
            MotionEvent.ACTION_UP -> {
                dragX = event.x; dragY = event.y
                commitDrag()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        invalidate()
        return true
    }

    private fun commitDrag() {
        val f = fit ?: return
        val bmp = bitmap ?: return
        val box = EditGeometry.normalize(
            f.toImageX(dragStartX), f.toImageY(dragStartY), f.toImageX(dragX), f.toImageY(dragY),
            bmp.width.toFloat(), bmp.height.toFloat()
        )
        if (!EditGeometry.isUsable(box)) return
        if (mode == Mode.REDACT) {
            redactions += box
            ops += Op(box, null)
        } else {
            ops += Op(null, crop)
            crop = box
        }
        onChanged?.invoke()
    }
}
