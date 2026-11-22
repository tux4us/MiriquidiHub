package io.github.tux4us.miriquidihub

import android.content.Context

/**
 * Spaltenzahl für Kachel-Grids aus nutzbarer Fensterbreite (dp) und Schriftskalierung: Je größer die Schrift,
 * desto breiter muss eine Kachel sein, damit Titel nicht gequetscht werden. `screenWidthDp` berücksichtigt
 * Anzeigegröße, Querformat und Mehrfenster; die Activity wird bei Änderung neu erzeugt.
 */
internal object GridColumns {

    /**
     * @param minTileDp Mindestbreite einer Kachel bei Standardschrift (inklusive Außenabstand der Kachel)
     * @param horizontalInsetDp Summe der Seitenabstände links und rechts, die vom Fenster abgehen
     */
    fun count(context: Context, minTileDp: Float, horizontalInsetDp: Int, maxColumns: Int = 4): Int {
        val config = context.resources.configuration
        val fontScale = config.fontScale.coerceAtLeast(1f)
        val availableDp = config.screenWidthDp - horizontalInsetDp
        return (availableDp / (minTileDp * fontScale)).toInt().coerceIn(1, maxColumns)
    }
}
