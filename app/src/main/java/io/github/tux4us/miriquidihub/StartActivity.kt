package io.github.tux4us.miriquidihub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.GridLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import io.github.tux4us.miriquidihub.databinding.ActivityStartBinding
import io.github.tux4us.miriquidihub.databinding.ItemStartTileBinding

class StartActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStartBinding

    /** Eintrag der Startseite: Icon, Titel und Ziel-Activity. Reihenfolge = Reihenfolge im Grid. */
    private data class Tile(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        val target: Class<out Activity>
    )

    private val tiles = listOf(
        Tile(R.drawable.ic_report, R.string.btn_report_support, DeviceReportActivity::class.java),
        Tile(R.drawable.ic_location, R.string.location_sharing, LocationShareActivity::class.java),
        Tile(R.drawable.ic_simple_screenshot, R.string.btn_simple_screenshot, SimpleScreenshotActivity::class.java),
        Tile(R.drawable.ic_long_screenshot, R.string.btn_screenshot, ScreenshotActivity::class.java),
        Tile(R.drawable.ic_storage, R.string.btn_storage_analysis, StorageAnalysisActivity::class.java),
        Tile(R.drawable.ic_fm_folder, R.string.fm_title, FileManagerActivity::class.java),
        Tile(R.drawable.ic_network, R.string.btn_network_diagnostic, NetworkDiagnosticActivity::class.java),
        Tile(R.drawable.ic_hardware, R.string.btn_hardware_test, HardwareTestActivity::class.java),
        Tile(R.drawable.ic_tools, R.string.tools_title, ToolsActivity::class.java)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "MiriquidiHub"

        setupTiles()
        UpdateNotice.showIfUpgraded(this)
    }

    private fun setupTiles() {
        val grid = binding.startGrid
        grid.columnCount = GridColumns.count(this, MIN_TILE_DP, HORIZONTAL_INSET_DP)
        tiles.forEach { tile ->
            val item = ItemStartTileBinding.inflate(layoutInflater, grid, false)
            item.tileIcon.setImageResource(tile.icon)
            item.tileTitle.setText(tile.title)
            item.root.setOnClickListener { startActivity(Intent(this, tile.target)) }
            // Spalten teilen sich die Breite gleichmäßig, Kacheln einer Zeile werden auf gleiche Höhe gestreckt
            (item.root.layoutParams as GridLayout.LayoutParams).apply {
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL)
            }
            grid.addView(item.root)
        }

        binding.btnNavDonate.apply {
            tileIcon.setImageResource(R.drawable.ic_donate)
            tileTitle.setText(R.string.donate_card_title)
            root.setOnClickListener { DonationDialog.show(this@StartActivity) }
        }
    }

    private companion object {
        /** Mindestbreite einer Kachel bei Standardschrift; Richtwerte: Handy hochkant 1, quer 2–3, Tablet 3–4 Spalten. */
        const val MIN_TILE_DP = 260f

        /** Seitenrand 2 × 10 dp (zuzüglich 6 dp Kachelabstand je Seite, im Kachelmaß enthalten). */
        const val HORIZONTAL_INSET_DP = 20
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // Auf der Startseite ohne Funktion (kein Suchen/Aktualisieren/Speichern/Home).
        menu.findItem(R.id.action_home)?.isVisible = false
        menu.findItem(R.id.action_save)?.isVisible = false
        menu.findItem(R.id.action_search)?.isVisible = false
        menu.findItem(R.id.action_refresh)?.isVisible = false
        menu.findItem(R.id.action_report)?.isVisible = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_developer -> {
                AppInfoDialog.show(this)
                true
            }
            R.id.action_lang_de -> {
                LanguageHelper.setLanguage(this, LanguageHelper.LANG_DE)
                true
            }
            R.id.action_lang_en -> {
                LanguageHelper.setLanguage(this, LanguageHelper.LANG_EN)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
