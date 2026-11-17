package io.github.tux4us.miriquidihub

import kotlin.math.max
import kotlin.math.min

/** Rechteck in Bildkoordinaten (Pixel des Originalbilds). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Abbildung zwischen Bild- und Ansichtskoordinaten bei zentrierter, proportionaler Einpassung. */
data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toImageX(viewX: Float) = (viewX - offsetX) / scale
    fun toImageY(viewY: Float) = (viewY - offsetY) / scale
    fun toViewX(imageX: Float) = imageX * scale + offsetX
    fun toViewY(imageY: Float) = imageY * scale + offsetY
}

/** Reine Geometrie für den Screenshot-Editor (ohne Android-Klassen, testbar). */
object EditGeometry {

    const val MIN_SIZE_PX = 8f

    fun fit(viewW: Float, viewH: Float, imageW: Float, imageH: Float): FitTransform? {
        if (viewW <= 0f || viewH <= 0f || imageW <= 0f || imageH <= 0f) return null
        val scale = min(viewW / imageW, viewH / imageH)
        return FitTransform(scale, (viewW - imageW * scale) / 2f, (viewH - imageH * scale) / 2f)
    }

    /** Ordnet die Ecken, begrenzt sie auf [0, maxW] × [0, maxH] und liefert das Rechteck. */
    fun normalize(x1: Float, y1: Float, x2: Float, y2: Float, maxW: Float, maxH: Float): Box = Box(
        left = min(x1, x2).coerceIn(0f, maxW),
        top = min(y1, y2).coerceIn(0f, maxH),
        right = max(x1, x2).coerceIn(0f, maxW),
        bottom = max(y1, y2).coerceIn(0f, maxH)
    )

    fun isUsable(box: Box): Boolean = box.width >= MIN_SIZE_PX && box.height >= MIN_SIZE_PX

    /** Ganzzahliger Ausschnitt (x, y, Breite, Höhe), der sicher innerhalb des Bilds liegt. */
    fun cropRect(box: Box, imageW: Int, imageH: Int): IntArray {
        val x = box.left.toInt().coerceIn(0, imageW - 1)
        val y = box.top.toInt().coerceIn(0, imageH - 1)
        val w = (box.right.toInt() - x).coerceIn(1, imageW - x)
        val h = (box.bottom.toInt() - y).coerceIn(1, imageH - y)
        return intArrayOf(x, y, w, h)
    }
}
