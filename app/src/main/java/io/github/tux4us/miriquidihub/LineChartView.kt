package io.github.tux4us.miriquidihub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Einfaches Liniendiagramm für eine Messreihe (Akku-Verlauf, Live-Monitor, Speicherverlauf).
 * Die Y-Achse wird aus [setData] bestimmt (oder fest vorgegeben), die X-Achse sind
 * gleichmäßig verteilte Messpunkte. Beschriftungen kommen vom Aufrufer.
 */
class LineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var values: List<Float> = emptyList()
    private var fixedMin: Float? = null
    private var fixedMax: Float? = null
    private var yFormatter: (Float) -> String = { String.format("%.0f", it) }
    private var leftLabel: String = ""
    private var rightLabel: String = ""

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 1f
        alpha = 80
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density }

    init {
        minimumHeight = (160 * density).toInt()
    }

    /**
     * @param min/max feste Achsengrenzen; null = aus den Daten bestimmen (mit etwas Rand).
     * @param left/right Beschriftungen unter der X-Achse (z. B. "vor 5 Min" / "jetzt").
     */
    fun setData(
        data: List<Float>,
        min: Float? = null,
        max: Float? = null,
        left: String = "",
        right: String = "",
        format: (Float) -> String = { String.format("%.0f", it) }
    ) {
        values = data
        fixedMin = min
        fixedMax = max
        leftLabel = left
        rightLabel = right
        yFormatter = format
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((180 * density).toInt(), heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val typed = TypedValue()
        context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typed, true)
        val accent = typed.data
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, typed, true)
        textPaint.color = typed.data
        linePaint.color = accent
        fillPaint.color = accent
        fillPaint.alpha = 50

        val labelWidth = 52f * density
        val bottomPad = 20f * density
        val topPad = 8f * density
        val chartW = width - labelWidth
        val chartH = height - bottomPad - topPad

        var lo = fixedMin ?: (values.minOrNull() ?: 0f)
        var hi = fixedMax ?: (values.maxOrNull() ?: 1f)
        if (fixedMin == null || fixedMax == null) {
            val pad = ((hi - lo) * 0.1f).coerceAtLeast(0.5f)
            if (fixedMin == null) lo -= pad
            if (fixedMax == null) hi += pad
        }
        if (hi - lo < 0.0001f) hi = lo + 1f

        for (i in 0..4) {
            val y = topPad + chartH * i / 4
            canvas.drawLine(0f, y, chartW, y, gridPaint)
            val v = hi - (hi - lo) * i / 4
            canvas.drawText(yFormatter(v), chartW + 6f * density, y + 4f * density, textPaint)
        }
        canvas.drawText(leftLabel, 0f, height - 4f * density, textPaint)
        val rw = textPaint.measureText(rightLabel)
        canvas.drawText(rightLabel, chartW - rw, height - 4f * density, textPaint)

        if (values.size < 2) return
        val path = Path()
        val fill = Path()
        val step = chartW / (values.size - 1)
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = topPad + chartH - ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * chartH
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, topPad + chartH)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(chartW, topPad + chartH)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
