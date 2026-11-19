package io.github.tux4us.miriquidihub.files

/** Reine Rechenhilfen für die Rasteransicht und das Laden von Vorschaubildern. */
object ThumbMath {

    /**
     * Größter Teilerfaktor (Potenz von 2), bei dem die kürzere Seite noch mindestens [target] Pixel hat.
     * Mindestens 1.
     */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        val shortSide = minOf(width, height)
        var sample = 1
        while (shortSide / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Anzahl der Spalten für eine Breite; mindestens [minSpans]. */
    fun spanCount(widthPx: Int, cellMinPx: Int, minSpans: Int = 2): Int {
        if (widthPx <= 0 || cellMinPx <= 0) return minSpans
        return maxOf(minSpans, widthPx / cellMinPx)
    }
}
