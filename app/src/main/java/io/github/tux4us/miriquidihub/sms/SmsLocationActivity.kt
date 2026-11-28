package io.github.tux4us.miriquidihub.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.tux4us.miriquidihub.R
import io.github.tux4us.miriquidihub.SecureStore
import io.github.tux4us.miriquidihub.databinding.ActivitySmsLocationBinding
import io.github.tux4us.miriquidihub.databinding.ItemSmsContactBinding

/** Kopplung, Einrichtung und Abfrage der SMS-Ortung ohne Internet. */
class SmsLocationActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmsLocationBinding
    private lateinit var store: SmsLocationStore

    private val handler = Handler(Looper.getMainLooper())
    /** Aktualisiert die Altersangaben, solange der Bildschirm sichtbar ist. */
    private val ticker = object : Runnable {
        override fun run() {
            renderContacts()
            handler.postDelayed(this, 30_000L)
        }
    }
    private val changeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = renderContacts()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            renderReadiness()
            if (results.values.any { !it }) showPermissionDeniedHelp()
        }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val invite = PairingCode.parseInvite(text)
        if (invite == null) toast(R.string.smsloc_invalid_code) else showJoinDialog(invite)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySmsLocationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.smsloc_title)
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
        store = SmsLocationStore(this)

        binding.btnPairShow.setOnClickListener { showInviteDialog() }
        binding.btnPairScan.setOnClickListener { startScan() }
        binding.btnPairManual.setOnClickListener { showJoinDialog(null) }
        binding.btnLog.setOnClickListener { showLog() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, changeReceiver, IntentFilter(SmsLocationStore.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ticker.run()
    }

    override fun onResume() {
        super.onResume()
        renderReadiness()
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        unregisterReceiver(changeReceiver)
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── Einsatzbereitschaft ───────────────────────────────────────────────

    private data class Check(val ok: Boolean, val textRes: Int, val fix: () -> Unit)

    private fun checks(): List<Check> {
        val list = mutableListOf(
            Check(DeviceState.hasSmsPermissions(this), R.string.smsloc_check_sms) {
                permissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
            },
            Check(DeviceState.hasFineLocation(this), R.string.smsloc_check_location_permission) {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            },
            Check(DeviceState.hasBackgroundLocation(this), R.string.smsloc_check_background) { requestBackgroundLocation() },
            Check(DeviceState.locationEnabled(this), R.string.smsloc_check_location_on) {
                open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            },
            Check(DeviceState.ignoresBatteryOptimizations(this), R.string.smsloc_check_battery) { requestBatteryExemption() }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android entzieht länger ungenutzten Apps sonst Berechtigungen.
            list.add(Check(packageManager.isAutoRevokeWhitelisted, R.string.smsloc_check_auto_revoke) {
                showAutoRevokeHelp()
            })
        }
        list.add(Check(DeviceState.notificationsEnabled(this), R.string.smsloc_check_notifications) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !DeviceState.granted(this, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                openAppDetails()
            }
        })
        return list
    }

    private fun renderReadiness() {
        binding.readinessList.removeAllViews()
        val all = checks()
        val ready = all.all { it.ok } && DeviceState.hasTelephony(this)
        binding.tvReadiness.setText(
            when {
                !DeviceState.hasTelephony(this) -> R.string.smsloc_no_telephony
                ready -> R.string.smsloc_ready
                else -> R.string.smsloc_not_ready
            }
        )
        for (check in all) {
            val row = TextView(this).apply {
                text = getString(
                    if (check.ok) R.string.smsloc_check_ok else R.string.smsloc_check_open, getString(check.textRes)
                )
                textSize = 13f
                minHeight = dp(if (check.ok) 28 else 48)
                gravity = android.view.Gravity.CENTER_VERTICAL
                if (!check.ok) setOnClickListener { check.fix() }
            }
            binding.readinessList.addView(row)
        }
    }

    private fun requestBackgroundLocation() {
        if (!DeviceState.hasFineLocation(this)) {
            toast(R.string.smsloc_location_first)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smsloc_check_background)
            .setMessage(R.string.smsloc_background_rationale)
            .setPositiveButton(R.string.smsloc_continue) { _, _ ->
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @SuppressLint("BatteryLife") // Die Antwort auf eine Notfallanfrage darf nicht verzögert werden.
    private fun requestBatteryExemption() {
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun showAutoRevokeHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smsloc_check_auto_revoke)
            .setMessage(R.string.smsloc_auto_revoke_help)
            .setPositiveButton(R.string.smsloc_open_app_info) { _, _ -> openAppDetails() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showPermissionDeniedHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smsloc_permission_denied_title)
            .setMessage(R.string.smsloc_permission_denied_help)
            .setPositiveButton(R.string.smsloc_open_app_info) { _, _ -> openAppDetails() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun openAppDetails() =
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun open(intent: Intent, errorRes: Int = R.string.smsloc_no_app) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(errorRes)
        }
    }

    // ── Gekoppelte Geräte ─────────────────────────────────────────────────

    private fun renderContacts() {
        val contacts = store.contacts()
        binding.tvNoContacts.isVisible = contacts.isEmpty()
        binding.contactList.removeAllViews()
        for (contact in contacts.sortedBy { it.name.lowercase() }) {
            val item = ItemSmsContactBinding.inflate(layoutInflater, binding.contactList, false)
            item.tvContactName.text = contact.name
            item.tvContactNumber.text = contact.number
            val state = stateText(contact)
            item.tvContactState.isVisible = state != null
            item.tvContactState.text = state

            val position = contact.lastPosition
            item.tvContactPosition.isVisible = position != null
            item.positionActions.isVisible = position != null
            if (position != null) {
                item.tvContactPosition.text = SmsLocationTexts.positionDetails(this, position)
                item.btnNavigate.setOnClickListener {
                    open(SmsLocationTexts.navigationIntent(contact.name, position), R.string.smsloc_no_navigation_app)
                }
                item.btnSharePosition.setOnClickListener {
                    open(SmsLocationTexts.shareIntent(this, contact.name, position))
                }
            }

            item.switchAllowLocate.isChecked = contact.allowLocate
            item.switchAllowLocate.setOnCheckedChangeListener { _, checked ->
                if (store.contact(contact.pairId)?.allowLocate != checked) {
                    store.update(contact.pairId) { it.copy(allowLocate = checked) }
                }
            }
            item.btnRequest.setOnClickListener { confirmRequest(contact) }
            item.btnContactMenu.setOnClickListener { showContactMenu(contact) }
            binding.contactList.addView(item.root)
        }
    }

    private fun stateText(contact: SmsContact): String? {
        val state = contact.requestState?.let { name -> RequestState.values().firstOrNull { it.name == name } }
            ?: return null
        val res = when (state) {
            RequestState.SENDING -> R.string.smsloc_state_sending
            RequestState.SENT -> R.string.smsloc_state_sent
            RequestState.SEND_FAILED -> R.string.smsloc_state_send_failed
            RequestState.ANSWER_WAITING_FOR_FIX -> R.string.smsloc_state_waiting_fix
            RequestState.ANSWERED -> R.string.smsloc_state_answered
            RequestState.NO_FIX -> R.string.smsloc_state_no_fix
            RequestState.LOCATION_OFF -> R.string.smsloc_state_location_off
            RequestState.NO_PERMISSION -> R.string.smsloc_state_no_permission
        }
        return getString(res) + " (" + SmsLocationTexts.dateTime(contact.requestStateAt * 1000) + ")"
    }

    private fun confirmRequest(contact: SmsContact) {
        if (!DeviceState.hasSmsPermissions(this)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.smsloc_request_title, contact.name))
            .setMessage(getString(R.string.smsloc_request_message, contact.number))
            .setPositiveButton(R.string.smsloc_send) { _, _ -> SmsLocationRequester.request(this, contact.pairId) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showContactMenu(contact: SmsContact) {
        val actions = mutableListOf<Pair<Int, () -> Unit>>(
            R.string.smsloc_edit to { showEditDialog(contact) }
        )
        if (contact.ownRole() == SmsLocationProtocol.Role.INVITER) {
            actions.add(R.string.smsloc_show_code to { showStoredCode(contact) })
        }
        actions.add(R.string.smsloc_unpair to { confirmDelete(contact) })
        MaterialAlertDialogBuilder(this)
            .setTitle(contact.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun showEditDialog(contact: SmsContact) {
        val form = form()
        val name = form.field(R.string.smsloc_other_name, contact.name)
        val number = form.field(R.string.smsloc_other_number, contact.number, InputType.TYPE_CLASS_PHONE)
        showForm(R.string.smsloc_edit, form) {
            val newName = requireText(name) ?: return@showForm false
            val newNumber = requireNumber(number) ?: return@showForm false
            store.update(contact.pairId) { it.copy(name = newName, number = newNumber) }
            true
        }
    }

    private fun confirmDelete(contact: SmsContact) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smsloc_unpair)
            .setMessage(getString(R.string.smsloc_unpair_message, contact.name))
            .setPositiveButton(R.string.delete) { _, _ -> store.remove(contact.pairId) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Kopplung ──────────────────────────────────────────────────────────

    /** Gerät A: Kopplung erzeugen und Code anzeigen. */
    private fun showInviteDialog() {
        val form = form()
        form.info(R.string.smsloc_invite_info)
        val otherName = form.field(R.string.smsloc_other_name, "")
        val otherNumber = form.field(R.string.smsloc_other_number, "", InputType.TYPE_CLASS_PHONE)
        val ownName = form.field(R.string.smsloc_own_name, store.ownName.ifBlank { Build.MODEL })
        val ownNumber = form.field(R.string.smsloc_own_number, store.ownNumber, InputType.TYPE_CLASS_PHONE)
        val allow = form.checkBox(R.string.smsloc_allow_locate_consent)
        showForm(R.string.smsloc_pair_new, form) {
            val name = requireText(otherName) ?: return@showForm false
            val number = requireNumber(otherNumber) ?: return@showForm false
            val myName = requireText(ownName) ?: return@showForm false
            val myNumber = requireNumber(ownNumber) ?: return@showForm false
            store.ownName = myName
            store.ownNumber = myNumber
            val contact = store.add(
                SmsLocationProtocol.newSecret(), SmsLocationProtocol.Role.INVITER, name, number, allow.isChecked
            )
            showStoredCode(contact)
            true
        }
    }

    private fun showStoredCode(contact: SmsContact) {
        val code = SecureStore.decrypt(contact.secret)
        val secret = PairingCode.parseCode(code)
        if (secret == null) {
            toast(R.string.smsloc_invalid_code)
            return
        }
        val form = form()
        form.info(R.string.smsloc_code_info)
        val size = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 7 / 10
        form.layout.addView(ImageView(this).apply {
            setImageBitmap(QrCodes.create(PairingCode.qrContent(secret, store.ownNumber, store.ownName), size))
            adjustViewBounds = true
            contentDescription = getString(R.string.smsloc_qr_description)
        })
        form.layout.addView(TextView(this).apply {
            text = getString(R.string.smsloc_code_text, code, store.ownNumber)
            textSize = 16f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.smsloc_code_title, contact.name))
            .setView(form.view)
            .setPositiveButton(R.string.smsloc_done, null)
            .create()
        // Der Code ist ein Schlüssel: keine Screenshots und keine Vorschau in der App-Übersicht.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.smsloc_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    /** Gerät B: Code übernehmen, entweder aus dem QR-Scan ([invite]) oder per Eingabe. */
    private fun showJoinDialog(invite: PairingCode.Invite?) {
        val form = form()
        val codeField = if (invite == null) {
            form.field(
                R.string.smsloc_code_hint, "",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            )
        } else {
            null
        }
        val otherName = form.field(R.string.smsloc_other_name, invite?.name.orEmpty())
        val otherNumber = form.field(R.string.smsloc_other_number, invite?.number.orEmpty(), InputType.TYPE_CLASS_PHONE)
        form.info(R.string.smsloc_check_number)
        val allow = form.checkBox(R.string.smsloc_allow_locate_consent)
        showForm(R.string.smsloc_pair_accept, form) {
            val secret = invite?.secret ?: PairingCode.parseInvite(codeField?.text?.toString().orEmpty())?.secret
            if (secret == null) {
                codeField?.error = getString(R.string.smsloc_invalid_code)
                return@showForm false
            }
            val existing = store.contact(SmsLocationProtocol.pairId(secret))
            if (existing != null && existing.ownRole() == SmsLocationProtocol.Role.INVITER) {
                toast(R.string.smsloc_own_code)
                return@showForm false
            }
            val name = requireText(otherName) ?: return@showForm false
            val number = requireNumber(otherNumber) ?: return@showForm false
            store.add(secret, SmsLocationProtocol.Role.JOINER, name, number, allow.isChecked)
            toast(R.string.smsloc_paired)
            true
        }
    }

    // ── Protokoll ─────────────────────────────────────────────────────────

    private fun showLog() {
        val text = store.logEntries().joinToString("\n\n") {
            SmsLocationTexts.dateTime(it.timeMillis) + "\n" + it.text
        }.ifEmpty { getString(R.string.smsloc_log_empty) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.smsloc_show_log)
            .setMessage(text)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.delete) { _, _ -> store.clearLog() }
            .show()
    }

    // ── Formulare ─────────────────────────────────────────────────────────

    private inner class Form {
        val layout = LinearLayout(this@SmsLocationActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val view: View = ScrollView(this@SmsLocationActivity).apply { addView(layout) }

        fun info(textRes: Int) {
            layout.addView(TextView(this@SmsLocationActivity).apply {
                setText(textRes)
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
            })
        }

        fun field(labelRes: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            layout.addView(TextView(this@SmsLocationActivity).apply {
                setText(labelRes)
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
            return EditText(this@SmsLocationActivity).apply {
                inputType = type
                setText(value)
                this@Form.layout.addView(this)
            }
        }

        fun checkBox(textRes: Int) = CheckBox(this@SmsLocationActivity).apply {
            setText(textRes)
            isChecked = true
            this@Form.layout.addView(this)
        }
    }

    private fun form() = Form()

    /** Dialog, der bei ungültiger Eingabe offen bleibt. */
    private fun showForm(titleRes: Int, form: Form, onSave: () -> Boolean) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(form.view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (onSave()) dialog.dismiss() }
    }

    private fun requireText(field: EditText): String? {
        val value = field.text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) field.error = getString(R.string.smsloc_required)
        return value.take(40).ifEmpty { null }
    }

    private fun requireNumber(field: EditText): String? {
        val value = PhoneNumbers.normalize(field.text?.toString().orEmpty())
        if (value == null) field.error = getString(R.string.smsloc_invalid_number)
        return value
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()
}
