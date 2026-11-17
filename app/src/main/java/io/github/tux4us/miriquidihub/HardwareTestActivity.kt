package io.github.tux4us.miriquidihub

import android.Manifest
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tux4us.miriquidihub.databinding.ActivityHardwareTestBinding
import io.github.tux4us.miriquidihub.databinding.ItemHwTestBinding
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Hardware-Selbsttest: geführte und automatische Tests (Touch, Display, Lautsprecher,
 * Mikrofon, Vibration, Tasten, Kamera, Sensoren, Akku, Ladeanschluss, GPS, Funkmodule).
 * Die Ergebnisse werden lokal gespeichert und vom Geräte-Report als Abschnitt
 * übernommen. Testlogik: [HardwareTester]; Persistenz: [HardwareTestStore].
 */
class HardwareTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHardwareTestBinding
    private lateinit var tester: HardwareTester
    private lateinit var store: HardwareTestStore
    private lateinit var extra: HardwareTesterExtra

    private var busy = false

    private var pendingPermission: CancellableContinuation<Boolean>? = null
    private var pendingPhoto: CancellableContinuation<Bitmap?>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.takeIf { it.isActive }?.resume(granted)
            pendingPermission = null
        }

    // Die System-Kamera-App wird per Intent gestartet. Dafür ist keine CAMERA-Berechtigung
    // nötig, solange sie nicht im Manifest deklariert ist.
    private val exportDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) writeExportDocument(uri)
        }

    private val photoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            pendingPhoto?.takeIf { it.isActive }?.resume(bitmap)
            pendingPhoto = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityHardwareTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.btn_hardware_test)

        ViewCompat.setOnApplyWindowInsetsListener(binding.contentScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        tester = HardwareTester(this)
        store = HardwareTestStore(this)
        extra = HardwareTesterExtra(this)

        binding.btnRunAuto.setOnClickListener { runAutomaticTests() }
        binding.btnReset.setOnClickListener {
            // Ergebnisse vor dem Zurücksetzen im Verlauf ablegen
            store.archiveCurrent()
            store.clear()
            refreshUi()
        }
        binding.btnCopy.setOnClickListener { copyResults() }
        binding.btnShare.setOnClickListener { shareResults() }

        refreshUi()
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private fun refreshUi() {
        val container = binding.testsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        var passed = 0
        var failed = 0
        var pending = 0
        for (id in HwTestId.values()) {
            val result = store.get(id)
            when (result.status) {
                HwStatus.PASSED -> passed++
                HwStatus.FAILED -> failed++
                else -> pending++
            }
            val item = ItemHwTestBinding.inflate(inflater, container, false)
            item.tvStatus.text = symbolFor(result.status)
            item.tvStatus.setTextColor(colorFor(result.status, item.tvStatus))
            item.tvTitle.setText(id.titleRes)
            item.tvDesc.setText(id.descRes)
            if (result.detail.isBlank()) {
                item.tvDetail.visibility = View.GONE
            } else {
                item.tvDetail.visibility = View.VISIBLE
                item.tvDetail.text = result.detail
            }
            item.btnStart.setText(if (result.status == HwStatus.PENDING) R.string.hw_start else R.string.hw_repeat)
            item.btnStart.isEnabled = !busy
            item.btnStart.setOnClickListener { runTest(id) }
            container.addView(item.root)
        }

        val overall = when {
            failed > 0 -> HwStatus.FAILED
            passed > 0 && pending == 0 -> HwStatus.PASSED
            else -> HwStatus.PENDING
        }
        binding.summaryIcon.text = symbolFor(overall)
        binding.summaryIcon.setTextColor(colorFor(overall, binding.summaryIcon))
        binding.tvSummary.text = getString(R.string.hw_summary, passed, failed, pending)

        binding.btnRunAuto.isEnabled = !busy
        binding.btnReset.isEnabled = !busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun symbolFor(status: HwStatus): String = when (status) {
        HwStatus.PASSED -> "✔"
        HwStatus.FAILED -> "✖"
        HwStatus.SKIPPED -> "▲"
        HwStatus.PENDING -> "○"
    }

    private fun colorFor(status: HwStatus, anchor: View): Int = when (status) {
        HwStatus.PASSED -> ContextCompat.getColor(this, R.color.diag_ok)
        HwStatus.FAILED -> ContextCompat.getColor(this, R.color.diag_error)
        HwStatus.SKIPPED -> ContextCompat.getColor(this, R.color.diag_warn)
        HwStatus.PENDING -> MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOutline)
    }

    private fun copyResults() {
        val text = store.formatReportBlock(this)
        if (text == null) {
            Toast.makeText(this, R.string.hw_no_results, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.hw_share_subject), text))
        Toast.makeText(this, R.string.hw_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareResults() {
        val text = store.formatReportBlock(this)
        if (text == null) {
            Toast.makeText(this, R.string.hw_no_results, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.hw_share_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.hw_share_chooser)))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ------------------------------------------------------------------
    // Ablaufsteuerung
    // ------------------------------------------------------------------

    private fun runAutomaticTests() {
        if (busy) return
        busy = true
        refreshUi()
        lifecycleScope.launch {
            try {
                for (id in HwTestId.values().filter { it.automatic }) {
                    store.put(id, executeSafely(id))
                    refreshUi()
                }
            } finally {
                busy = false
                refreshUi()
            }
        }
    }

    private fun runTest(id: HwTestId) {
        if (busy) return
        busy = true
        refreshUi()
        lifecycleScope.launch {
            try {
                store.put(id, executeSafely(id))
            } finally {
                busy = false
                refreshUi()
            }
        }
    }

    private suspend fun executeSafely(id: HwTestId): HwResult = try {
        execute(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HwResult(HwStatus.FAILED, getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
    }

    private suspend fun execute(id: HwTestId): HwResult = when (id) {
        HwTestId.FEATURES -> tester.testFeatures()
        HwTestId.SENSORS -> tester.testSensors()
        HwTestId.BATTERY -> tester.testBattery()
        HwTestId.TOUCH -> runTouchTest()
        HwTestId.DISPLAY -> runDisplayTest()
        HwTestId.SPEAKER -> runSpeakerTest()
        HwTestId.MICROPHONE -> runMicrophoneTest()
        HwTestId.VIBRATION -> runVibrationTest()
        HwTestId.VOLUME_KEYS -> runVolumeKeyTest()
        HwTestId.CAMERA -> runCameraTest()
        HwTestId.CHARGER -> runChargerTest()
        HwTestId.GPS -> runGpsTest()
        HwTestId.DISPLAY_ADVANCED -> runDisplayAdvancedTest()
        HwTestId.COMPASS -> runCompassTest()
        HwTestId.LIGHT -> runLightTest()
        HwTestId.PROXIMITY -> runProximityTest()
        HwTestId.WIFI_SCAN -> runWifiScanTest()
        HwTestId.BLUETOOTH_SCAN -> runBluetoothScanTest()
        HwTestId.AUDIO_JACK -> runAudioJackTest()
        HwTestId.BIOMETRIC -> runBiometricTest()
    }

    // ------------------------------------------------------------------
    // Hilfsfunktionen für Dialoge und Berechtigungen
    // ------------------------------------------------------------------

    private suspend fun requestPermission(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return true
        return suspendCancellableCoroutine { cont ->
            pendingPermission = cont
            permissionLauncher.launch(permission)
        }
    }

    /** Ja/Nein-Dialog. Rückgabe null, wenn der Dialog abgebrochen wurde. */
    private suspend fun askYesNo(@StringRes title: Int, @StringRes message: Int): Boolean? =
        suspendCancellableCoroutine { cont ->
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.yes) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(R.string.no) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    private fun confirmedResult(answer: Boolean?, @StringRes okDetail: Int, @StringRes failDetail: Int): HwResult =
        when (answer) {
            true -> HwResult(HwStatus.PASSED, getString(okDetail))
            false -> HwResult(HwStatus.FAILED, getString(failDetail))
            null -> HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))
        }

    /**
     * Zeigt [content] im Vollbild. [setup] erhält eine Funktion, mit der die Ansicht
     * beendet wird. Rückgabe true = über diese Funktion beendet, false = abgebrochen (Zurück).
     */
    private suspend fun showFullscreen(content: View, setup: (finish: () -> Unit) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(content)
            dialog.setOnCancelListener { if (cont.isActive) cont.resume(false) }
            val finish: () -> Unit = {
                if (cont.isActive) cont.resume(true)
                dialog.dismiss()
            }
            setup(finish)
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
            dialog.window?.let { window ->
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = attrs
                }
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }

    /**
     * Zeigt einen Wartedialog mit Abbrechen-Schaltfläche, während [work] läuft.
     * Rückgabe null, wenn der Nutzer abbricht.
     */
    private suspend fun <T> withWaitingDialog(
        @StringRes title: Int,
        initialMessage: String,
        work: suspend (update: (String) -> Unit) -> T
    ): T? {
        var dialog: AlertDialog? = null
        try {
            return coroutineScope {
                val job = async { work { text -> dialog?.setMessage(text) } }
                dialog = MaterialAlertDialogBuilder(this@HardwareTestActivity)
                    .setTitle(title)
                    .setMessage(initialMessage)
                    .setCancelable(false)
                    .setNegativeButton(R.string.btn_cancel) { _, _ -> job.cancel() }
                    .show()
                try {
                    job.await()
                } catch (e: CancellationException) {
                    // Abbruch durch den Nutzer; Abbruch des übergeordneten Scopes weiterreichen.
                    currentCoroutineContext().ensureActive()
                    null
                }
            }
        } finally {
            dialog?.dismiss()
        }
    }

    // ------------------------------------------------------------------
    // Einzeltests mit Nutzerinteraktion
    // ------------------------------------------------------------------

    private suspend fun runTouchTest(): HwResult {
        val grid = TouchGridView(this)
        grid.hint = getString(R.string.hw_touch_hint)
        val completed = showFullscreen(grid) { finish -> grid.onAllTouched = { finish() } }
        if (completed) {
            return HwResult(HwStatus.PASSED, getString(R.string.hw_touch_ok, grid.totalCells, grid.maxPointers))
        }
        val detail = getString(R.string.hw_touch_partial, grid.touchedCells, grid.totalCells)
        val defect = askYesNo(R.string.hw_touch_title, R.string.hw_touch_ask_defect)
        return if (defect == true) HwResult(HwStatus.FAILED, detail) else HwResult(HwStatus.SKIPPED, detail)
    }

    private suspend fun runDisplayTest(): HwResult {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK)
        val root = FrameLayout(this)
        val hint = TextView(this).apply {
            text = getString(R.string.hw_display_hint)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { bottomMargin = 96 }
        )

        fun show(index: Int) {
            root.setBackgroundColor(colors[index])
            val dark = ColorUtils.calculateLuminance(colors[index]) < 0.5
            hint.setTextColor(if (dark) Color.WHITE else Color.BLACK)
        }
        show(0)

        var index = 0
        val completed = showFullscreen(root) { finish ->
            root.setOnClickListener {
                index++
                if (index >= colors.size) finish() else show(index)
            }
        }
        if (!completed) return HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))

        val answer = askYesNo(R.string.hw_display_title, R.string.hw_display_confirm)
        return confirmedResult(answer, R.string.hw_display_ok, R.string.hw_display_fail)
    }

    private suspend fun runSpeakerTest(): HwResult {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            Toast.makeText(this, R.string.hw_speaker_volume_zero, Toast.LENGTH_LONG).show()
        }
        tester.playTestTone()
        val answer = askYesNo(R.string.hw_speaker_title, R.string.hw_speaker_confirm)
        return confirmedResult(answer, R.string.hw_speaker_ok, R.string.hw_speaker_fail)
    }

    private suspend fun runMicrophoneTest(): HwResult {
        if (!requestPermission(Manifest.permission.RECORD_AUDIO)) {
            return HwResult(HwStatus.SKIPPED, getString(R.string.hw_permission_denied))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hw_mic_title)
            .setMessage(R.string.hw_mic_recording)
            .setCancelable(false)
            .show()
        val stats = try {
            tester.recordMic(3_000)
        } finally {
            dialog.dismiss()
        }
        return when {
            stats == null -> HwResult(HwStatus.FAILED, getString(R.string.hw_mic_unavailable))
            stats.peak >= MIC_MIN_PEAK -> HwResult(HwStatus.PASSED, getString(R.string.hw_mic_ok, stats.peak, stats.rms))
            else -> HwResult(HwStatus.FAILED, getString(R.string.hw_mic_weak, stats.peak))
        }
    }

    private suspend fun runVibrationTest(): HwResult {
        if (!tester.hasVibrator()) return HwResult(HwStatus.SKIPPED, getString(R.string.hw_vibration_absent))
        tester.vibrate()
        val answer = askYesNo(R.string.hw_vibration_title, R.string.hw_vibration_confirm)
        return confirmedResult(answer, R.string.hw_vibration_ok, R.string.hw_vibration_fail)
    }

    private suspend fun runVolumeKeyTest(): HwResult = suspendCancellableCoroutine { cont ->
        var up = false
        var down = false

        fun statusMessage(): String = getString(
            R.string.hw_keys_status,
            getString(if (up) R.string.hw_keys_detected else R.string.hw_keys_waiting),
            getString(if (down) R.string.hw_keys_detected else R.string.hw_keys_waiting)
        )

        fun detail(): String = getString(
            R.string.hw_keys_detail,
            getString(if (up) R.string.hw_keys_detected else R.string.hw_keys_missing),
            getString(if (down) R.string.hw_keys_detected else R.string.hw_keys_missing)
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hw_keys_title)
            .setMessage(getString(R.string.hw_keys_prompt) + "\n\n" + statusMessage())
            .setNegativeButton(R.string.hw_keys_defect) { _, _ ->
                if (cont.isActive) cont.resume(HwResult(HwStatus.FAILED, detail()))
            }
            .setNeutralButton(R.string.btn_cancel) { _, _ ->
                if (cont.isActive) cont.resume(HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled)))
            }
            .setOnCancelListener {
                if (cont.isActive) cont.resume(HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled)))
            }
            .create()

        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            ) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) up = true else down = true
                dialog.setMessage(getString(R.string.hw_keys_prompt) + "\n\n" + statusMessage())
                if (up && down && cont.isActive) {
                    d.dismiss()
                    cont.resume(HwResult(HwStatus.PASSED, detail()))
                }
                true
            } else {
                false
            }
        }
        cont.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

    private suspend fun runCameraTest(): HwResult {
        val cameras = tester.describeCameras()
        val bitmap = try {
            takePhoto()
        } catch (e: ActivityNotFoundException) {
            return HwResult(HwStatus.FAILED, getString(R.string.hw_camera_no_app) + "\n" + cameras)
        }
        return if (bitmap != null) {
            HwResult(
                HwStatus.PASSED,
                getString(R.string.hw_camera_ok, bitmap.width, bitmap.height) + "\n" + cameras
            )
        } else {
            HwResult(HwStatus.SKIPPED, getString(R.string.hw_camera_cancelled) + "\n" + cameras)
        }
    }

    private suspend fun takePhoto(): Bitmap? = suspendCancellableCoroutine { cont ->
        pendingPhoto = cont
        try {
            photoLauncher.launch(null)
        } catch (e: ActivityNotFoundException) {
            pendingPhoto = null
            cont.resumeWithException(e)
        }
    }

    private suspend fun runChargerTest(): HwResult {
        val result = withWaitingDialog(R.string.hw_charger_title, getString(R.string.hw_charger_prompt)) {
            tester.waitForCharger(CHARGER_TIMEOUT_MS)
        }
        return result ?: HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))
    }

    private suspend fun runGpsTest(): HwResult {
        if (!requestPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return HwResult(HwStatus.SKIPPED, getString(R.string.hw_permission_denied))
        }
        if (!tester.isGpsEnabled()) {
            return HwResult(HwStatus.SKIPPED, getString(R.string.hw_gps_disabled))
        }
        val outcome = withWaitingDialog(R.string.hw_gps_title, getString(R.string.hw_gps_waiting, 0, 0)) { update ->
            tester.waitForGpsFix(GPS_TIMEOUT_MS) { visible, used ->
                update(getString(R.string.hw_gps_waiting, visible, used))
            }
        } ?: return HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))

        return if (outcome.fixed) {
            val accuracy = if (outcome.accuracyMeters >= 0f) {
                getString(R.string.hw_gps_accuracy, outcome.accuracyMeters.toDouble())
            } else {
                getString(R.string.hw_gps_accuracy_unknown)
            }
            HwResult(
                HwStatus.PASSED,
                getString(R.string.hw_gps_ok, outcome.seconds, accuracy, outcome.usedSatellites, outcome.visibleSatellites)
            )
        } else {
            HwResult(
                HwStatus.FAILED,
                getString(R.string.hw_gps_timeout, (GPS_TIMEOUT_MS / 1000).toInt(), outcome.visibleSatellites)
            )
        }
    }

    // ------------------------------------------------------------------
    // Zusätzliche Tests
    // ------------------------------------------------------------------

    private suspend fun runDisplayAdvancedTest(): HwResult {
        val pattern = DisplayPatternView(this)
        val hints = intArrayOf(
            R.string.hw_dispadv_hint_0, R.string.hw_dispadv_hint_1, R.string.hw_dispadv_hint_2,
            R.string.hw_dispadv_hint_3, R.string.hw_dispadv_hint_4
        )
        val hint = TextView(this).apply {
            setText(hints[0])
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(32, 20, 32, 20)
        }
        val root = FrameLayout(this)
        root.addView(pattern, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            hint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { bottomMargin = 96 }
        )
        var page = 0
        val completed = showFullscreen(root) { finish ->
            root.setOnClickListener {
                page++
                if (page >= DisplayPatternView.PAGE_COUNT) {
                    finish()
                } else {
                    pattern.page = page
                    hint.setText(hints[page])
                }
            }
        }
        if (!completed) return HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))

        @Suppress("DEPRECATION")
        val rate = String.format(Locale.US, "%.0f Hz", windowManager.defaultDisplay.refreshRate)
        val answer = askYesNo(R.string.hw_dispadv_title, R.string.hw_dispadv_confirm)
        return when (answer) {
            true -> HwResult(HwStatus.PASSED, getString(R.string.hw_dispadv_ok, rate))
            false -> HwResult(HwStatus.FAILED, getString(R.string.hw_dispadv_fail, rate))
            null -> HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))
        }
    }

    /**
     * Gemeinsamer Ablauf für Tests mit Live-Anzeige eines Sensors im Vollbild. Beendet sich bei
     * [autoFinish] selbst, sobald [isPassed] erfüllt ist; sonst bestätigt der Nutzer über "Passt".
     */
    private suspend fun runLiveSensorTest(
        sensorType: Int,
        @StringRes title: Int,
        @StringRes hint: Int,
        autoFinish: Boolean,
        format: (FloatArray) -> String,
        track: (FloatArray) -> Unit,
        isPassed: () -> Boolean,
        summary: () -> String
    ): HwResult {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(sensorType)
            ?: return HwResult(HwStatus.SKIPPED, getString(R.string.hw_live_sensor_absent))

        val titleView = TextView(this).apply {
            setText(title)
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val valueView = TextView(this).apply {
            text = "–"
            textSize = 40f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 48, 0, 24)
        }
        val hintView = TextView(this).apply {
            setText(hint)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 48)
        }
        val okButton = MaterialButton(this).apply { setText(R.string.hw_live_sensor_ok) }
        val failButton = MaterialButton(this).apply { setText(R.string.hw_sensor_unresponsive) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(48, 48, 48, 48)
            addView(titleView)
            addView(valueView)
            addView(hintView)
            if (!autoFinish) addView(okButton)
            addView(failButton)
        }

        var verdict: HwStatus? = null
        var finishFn: (() -> Unit)? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val values = event?.values ?: return
                track(values)
                valueView.text = format(values)
                if (autoFinish && verdict == null && isPassed()) {
                    verdict = HwStatus.PASSED
                    finishFn?.invoke()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val completed = try {
            showFullscreen(root) { finish ->
                finishFn = finish
                okButton.setOnClickListener { verdict = HwStatus.PASSED; finish() }
                failButton.setOnClickListener { verdict = HwStatus.FAILED; finish() }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        } finally {
            sm.unregisterListener(listener)
        }
        if (!completed) return HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))
        return when (verdict) {
            HwStatus.PASSED -> HwResult(HwStatus.PASSED, summary())
            HwStatus.FAILED -> HwResult(HwStatus.FAILED, summary())
            else -> HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))
        }
    }

    private suspend fun runCompassTest(): HwResult {
        var azimuth = -1
        val directions = resources.getStringArray(R.array.hw_compass_directions)
        return runLiveSensorTest(
            sensorType = Sensor.TYPE_ROTATION_VECTOR,
            title = R.string.hw_compass_title,
            hint = R.string.hw_compass_hint,
            autoFinish = false,
            format = { values ->
                val matrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, values)
                SensorManager.getOrientation(matrix, orientation)
                azimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toInt()
                "$azimuth° ${directions[((azimuth + 22) / 45) % 8]}"
            },
            track = {},
            isPassed = { true },
            summary = { getString(R.string.hw_compass_result, azimuth) }
        )
    }

    private suspend fun runLightTest(): HwResult {
        var min = Float.MAX_VALUE
        var max = 0f
        return runLiveSensorTest(
            sensorType = Sensor.TYPE_LIGHT,
            title = R.string.hw_light_title,
            hint = R.string.hw_light_hint,
            autoFinish = true,
            format = { values -> String.format(Locale.US, "%.0f lx", values[0]) },
            track = { values ->
                if (values[0] < min) min = values[0]
                if (values[0] > max) max = values[0]
            },
            isPassed = { max >= min * 2f + 10f },
            summary = {
                getString(R.string.hw_light_result, if (min == Float.MAX_VALUE) 0f else min, max)
            }
        )
    }

    private suspend fun runProximityTest(): HwResult {
        val seen = sortedSetOf<Float>()
        return runLiveSensorTest(
            sensorType = Sensor.TYPE_PROXIMITY,
            title = R.string.hw_prox_title,
            hint = R.string.hw_prox_hint,
            autoFinish = true,
            format = { values -> String.format(Locale.US, "%.1f cm", values[0]) },
            track = { values -> seen.add(values[0]) },
            isPassed = { seen.size >= 2 },
            summary = {
                getString(R.string.hw_prox_result, seen.joinToString(", ") { String.format(Locale.US, "%.1f", it) })
            }
        )
    }

    private suspend fun runWifiScanTest(): HwResult {
        if (!requestPermission(WifiScanHelper.requiredPermission())) {
            return HwResult(HwStatus.SKIPPED, getString(R.string.hw_permission_denied))
        }
        return withWaitingDialog(R.string.hw_wifiscan_title, getString(R.string.hw_wifiscan_waiting)) {
            extra.wifiScan()
        } ?: HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))
    }

    private suspend fun runBluetoothScanTest(): HwResult {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!requestPermission(permission)) {
            return HwResult(HwStatus.SKIPPED, getString(R.string.hw_permission_denied))
        }
        val outcome = withWaitingDialog(
            R.string.hw_btscan_title,
            getString(R.string.hw_btscan_waiting, (BT_SCAN_MS / 1000).toInt())
        ) { extra.bluetoothScan(BT_SCAN_MS) } ?: return HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))
        return when (outcome) {
            BluetoothScanOutcome.NoAdapter -> HwResult(HwStatus.SKIPPED, getString(R.string.hw_btscan_absent))
            BluetoothScanOutcome.Disabled -> HwResult(HwStatus.SKIPPED, getString(R.string.hw_btscan_off))
            BluetoothScanOutcome.StartFailed -> HwResult(HwStatus.FAILED, getString(R.string.hw_btscan_failed))
            is BluetoothScanOutcome.Done ->
                if (outcome.devicesFound > 0) {
                    HwResult(HwStatus.PASSED, getString(R.string.hw_btscan_ok, outcome.devicesFound))
                } else {
                    HwResult(HwStatus.SKIPPED, getString(R.string.hw_btscan_none))
                }
        }
    }

    private suspend fun runAudioJackTest(): HwResult {
        val result = withWaitingDialog(R.string.hw_jack_title, getString(R.string.hw_jack_prompt)) {
            extra.waitForAudioJack(JACK_TIMEOUT_MS)
        }
        return result ?: HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))
    }

    private suspend fun runBiometricTest(): HwResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return HwResult(HwStatus.SKIPPED, getString(R.string.hw_bio_unsupported))
        }
        val hasSensor = packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
            packageManager.hasSystemFeature("android.hardware.biometrics.face")
        if (!hasSensor) return HwResult(HwStatus.SKIPPED, getString(R.string.hw_bio_absent))
        return authenticateBiometric()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun authenticateBiometric(): HwResult = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        val executor = ContextCompat.getMainExecutor(this)
        var failedAttempts = 0
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.hw_bio_prompt_title))
            .setNegativeButton(getString(R.string.btn_cancel), executor) { _, _ -> }
            .build()
        prompt.authenticate(signal, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                if (cont.isActive) cont.resume(HwResult(HwStatus.PASSED, getString(R.string.hw_bio_ok, failedAttempts)))
            }

            override fun onAuthenticationFailed() {
                failedAttempts++
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (!cont.isActive) return
                // 5 = abgebrochen, 10 = vom Nutzer abgebrochen, 13 = Abbrechen-Schaltfläche
                val result = when (errorCode) {
                    5, 10, 13 -> HwResult(HwStatus.SKIPPED, getString(R.string.hw_cancelled))
                    11, 14 -> HwResult(HwStatus.SKIPPED, getString(R.string.hw_bio_not_enrolled))
                    12 -> HwResult(HwStatus.SKIPPED, getString(R.string.hw_bio_absent))
                    7, 9 -> HwResult(HwStatus.SKIPPED, getString(R.string.hw_bio_lockout))
                    else -> HwResult(HwStatus.FAILED, getString(R.string.hw_bio_error, errorCode, errString?.toString() ?: ""))
                }
                cont.resume(result)
            }
        })
    }

    // ------------------------------------------------------------------
    // Verlauf und Export
    // ------------------------------------------------------------------

    private fun statusWord(name: String): String = getString(
        when (name) {
            HwStatus.PASSED.name -> R.string.hw_status_passed
            HwStatus.FAILED.name -> R.string.hw_status_failed
            HwStatus.SKIPPED.name -> R.string.hw_status_skipped
            else -> R.string.hw_status_pending
        }
    )

    private fun showHistory() {
        val history = store.history()
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val sb = StringBuilder()
        if (history.isEmpty()) {
            sb.append(getString(R.string.hw_history_empty))
        } else {
            for (entry in history) {
                sb.append("• ").append(df.format(Date(entry.timestamp))).append('\n')
                    .append("   ").append(getString(R.string.hw_history_line, entry.passed, entry.failed, entry.skipped)).append('\n')
            }
            val changes = store.changesSince(history.first())
            if (changes.isNotEmpty()) {
                sb.append('\n').append(getString(R.string.hw_history_changes)).append('\n')
                for ((id, before, now) in changes) {
                    sb.append("• ").append(getString(id.titleRes)).append(": ")
                        .append(statusWord(before)).append(" → ").append(statusWord(now.name)).append('\n')
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.hw_history_title)
            .setMessage(sb.toString().trim())
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.hw_history_clear) { _, _ -> store.clearHistory() }
            .show()
    }

    private fun exportFileName(): String =
        "Hardware-Selbsttest_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".md"

    private fun writeExportDocument(uri: android.net.Uri) {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(store.formatDocument(this, version).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.hw_export_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_generic, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_hardware_test, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_hw_history -> {
            showHistory()
            true
        }
        R.id.action_hw_archive -> {
            val saved = store.archiveCurrent()
            Toast.makeText(this, if (saved) R.string.hw_archived else R.string.hw_nothing_to_archive, Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_hw_export_file -> {
            if (store.counts().executed == 0) {
                Toast.makeText(this, R.string.hw_no_results, Toast.LENGTH_SHORT).show()
            } else {
                exportDocument.launch(exportFileName())
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val MIC_MIN_PEAK = 2_000
        private const val CHARGER_TIMEOUT_MS = 30_000L
        private const val GPS_TIMEOUT_MS = 60_000L
        private const val BT_SCAN_MS = 8_000L
        private const val JACK_TIMEOUT_MS = 30_000L
    }
}
