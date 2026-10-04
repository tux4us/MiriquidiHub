package io.github.tux4us.miriquidihub

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import io.github.tux4us.miriquidihub.databinding.ActivityStartBinding

class StartActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStartBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "MiriquidiHub"

        setupButtons()
    }

    private fun setupButtons() {
        binding.btnNavReportFull.setOnClickListener {
            startActivity(Intent(this, DeviceReportActivity::class.java))
        }
        binding.btnNavLocationShare.setOnClickListener {
            startActivity(Intent(this, LocationShareActivity::class.java))
        }
        binding.btnNavScreenshot.setOnClickListener {
            startActivity(Intent(this, ScreenshotActivity::class.java))
        }
        binding.btnNavSimpleScreenshot.setOnClickListener {
            startActivity(Intent(this, SimpleScreenshotActivity::class.java))
        }
        binding.btnNavStorageAnalysis.setOnClickListener {
            startActivity(Intent(this, StorageAnalysisActivity::class.java))
        }
        binding.btnNavNetworkDiagnostic.setOnClickListener {
            startActivity(Intent(this, NetworkDiagnosticActivity::class.java))
        }
        binding.btnNavHardwareTest.setOnClickListener {
            startActivity(Intent(this, HardwareTestActivity::class.java))
        }
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
            R.id.action_theme -> {
                toggleTheme()
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

    private fun toggleTheme() {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val currentDark = prefs.getBoolean("dark_theme", false)
        prefs.edit().putBoolean("dark_theme", !currentDark).apply()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (!currentDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
    }
}
