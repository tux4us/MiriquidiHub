package io.github.tux4us.miriquidihub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SimpleScreenshotActivity : AppCompatActivity() {
    private lateinit var projectionManager: MediaProjectionManager

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kein Layout nötig, da wir nur die Berechtigung anfragen und sofort starten
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        showDelayDialog()
    }

    private var delaySeconds = 0

    /** Auswahl der Verzögerung (zuletzt gewählter Wert vorausgewählt) vor der Systemabfrage. */
    private fun showDelayDialog() {
        val settings = ScreenshotSettings(this)
        val options = ScreenshotSettings.DELAYS_S
        val labels = options.map {
            if (it == 0) getString(R.string.screenshot_delay_none) else getString(R.string.screenshot_delay_seconds, it)
        }.toTypedArray()
        var selected = options.indexOf(settings.delaySeconds).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.screenshot_delay_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.screenshot_start) { _, _ ->
                delaySeconds = options[selected]
                settings.delaySeconds = delaySeconds
                // Erst kurz warten, damit die Activity sicher im Vordergrund ist, bevor der System-Dialog kommt
                handler.postDelayed({ startMediaProjection() }, 100)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun startMediaProjection() {
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, SimpleScreenshotService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                    putExtra("delaySeconds", delaySeconds)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
