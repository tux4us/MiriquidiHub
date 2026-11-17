package io.github.tux4us.miriquidihub

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.github.tux4us.miriquidihub.databinding.ActivityScreenshotEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Einfacher Screenshot-Editor: Bereiche schwärzen (deckend schwarz), zuschneiden, als Kopie
 * speichern oder teilen. Das Original bleibt unverändert. Geöffnet wird die Datei über die
 * Daten-Uri des Intents (Benachrichtigung nach der Aufnahme oder „Bearbeiten“ aus anderen Apps
 * ist nicht vorgesehen – die Activity ist nicht exportiert).
 */
class ScreenshotEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenshotEditorBinding
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityScreenshotEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.editorRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.btnModeRedact.setOnClickListener { setMode(ScreenshotEditView.Mode.REDACT) }
        binding.btnModeCrop.setOnClickListener { setMode(ScreenshotEditView.Mode.CROP) }
        binding.btnUndo.setOnClickListener { binding.editView.undo() }
        binding.btnSaveCopy.setOnClickListener { saveCopy(share = false) }
        binding.btnShareEdited.setOnClickListener { saveCopy(share = true) }
        binding.editView.onChanged = { binding.btnUndo.isEnabled = binding.editView.hasEdits() }
        binding.btnUndo.isEnabled = false

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        loadImage(uri)
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap == null) {
                Toast.makeText(this@ScreenshotEditorActivity, R.string.editor_load_failed, Toast.LENGTH_LONG).show()
                finish()
            } else {
                binding.editView.setBitmap(bitmap)
            }
        }
    }

    private fun setMode(mode: ScreenshotEditView.Mode) {
        binding.editView.mode = mode
        binding.tvEditorHint.setText(if (mode == ScreenshotEditView.Mode.REDACT) R.string.editor_hint_redact else R.string.editor_hint_crop)
        binding.btnModeRedact.alpha = if (mode == ScreenshotEditView.Mode.REDACT) 1f else 0.6f
        binding.btnModeCrop.alpha = if (mode == ScreenshotEditView.Mode.CROP) 1f else 0.6f
    }

    private fun saveCopy(share: Boolean) {
        if (saving) return
        if (!binding.editView.hasEdits()) {
            Toast.makeText(this, R.string.editor_nothing_changed, Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        setButtonsEnabled(false)
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val result = binding.editView.renderResult() ?: return@withContext null
                try {
                    ScreenshotStore.save(this@ScreenshotEditorActivity, result, "_bearbeitet")
                } finally {
                    result.recycle()
                }
            }
            saving = false
            setButtonsEnabled(true)
            if (uri == null) {
                Toast.makeText(this@ScreenshotEditorActivity, R.string.editor_save_failed, Toast.LENGTH_LONG).show()
            } else if (share) {
                shareUri(uri)
            } else {
                Toast.makeText(this@ScreenshotEditorActivity, R.string.editor_saved_copy, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnSaveCopy.isEnabled = enabled
        binding.btnShareEdited.isEnabled = enabled
    }

    private fun shareUri(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("", uri)
        }
        startActivity(Intent.createChooser(send, getString(R.string.editor_share)))
    }
}
