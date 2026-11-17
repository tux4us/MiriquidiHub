package io.github.tux4us.miriquidihub

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tux4us.miriquidihub.databinding.ActivityReportHistoryBinding
import io.github.tux4us.miriquidihub.databinding.ItemReportHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

class ReportHistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReportHistoryBinding
    private lateinit var reportStorage: ReportStorage

    private val exportArchive = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { ReportArchive.write(reportStorage.getAllReports(), it) } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            val message = if (count >= 0) getString(R.string.archive_exported, count) else getString(R.string.archive_failed)
            Toast.makeText(this@ReportHistoryActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private val importArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                try {
                    val imported = contentResolver.openInputStream(uri)?.use {
                        ReportArchive.read(it, File(filesDir, "report_images"))
                    } ?: return@withContext -1
                    val existing = reportStorage.getAllReports().map { r -> r.id }.toSet()
                    val fresh = imported.filter { r -> r.id !in existing }
                    // älteste zuerst einfügen, damit die Reihenfolge (neueste oben) erhalten bleibt
                    fresh.sortedBy { r -> r.timestamp }.forEach { r -> reportStorage.saveReport(r) }
                    fresh.size
                } catch (e: Exception) {
                    -1
                }
            }
            val message = if (added >= 0) getString(R.string.archive_imported, added) else getString(R.string.archive_failed)
            Toast.makeText(this@ReportHistoryActivity, message, Toast.LENGTH_LONG).show()
            setupRecyclerView()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        reportStorage = ReportStorage(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.report_history_toolbar_title)

        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        val reports = reportStorage.getAllReports()
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = HistoryAdapter(reports.toMutableList()) { reportId ->
            reportStorage.deleteReport(reportId)
            setupRecyclerView() // Refresh
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.add(0, MENU_EXPORT_ARCHIVE, 100, R.string.archive_export)
        menu.add(0, MENU_IMPORT_ARCHIVE, 101, R.string.archive_import)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_home -> {
                val intent = Intent(this, StartActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
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
            R.id.action_developer -> {
                AppInfoDialog.show(this)
                true
            }
            MENU_EXPORT_ARCHIVE -> {
                exportArchive.launch("miriquidihub_berichte.zip")
                true
            }
            MENU_IMPORT_ARCHIVE -> {
                importArchive.launch(arrayOf("application/zip", "application/octet-stream"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    companion object {
        private const val MENU_EXPORT_ARCHIVE = 9001
        private const val MENU_IMPORT_ARCHIVE = 9002
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    inner class HistoryAdapter(
        private val reports: MutableList<DeviceReport>,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemReportHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val report = reports[position]
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            
            holder.binding.tvDate.text = sdf.format(Date(report.timestamp))
            holder.binding.tvTitle.text = report.title.ifEmpty { getString(R.string.report_history_untitled) }
            holder.binding.tvSummary.text = report.note.ifEmpty { getString(R.string.report_history_no_note) }
            holder.binding.tvImageCount.text = getString(R.string.report_history_images_attached, report.imagePaths.size)
            
            holder.binding.root.setOnClickListener {
                val intent = Intent(this@ReportHistoryActivity, DeviceReportActivity::class.java).apply {
                    putExtra("report_json", com.google.gson.Gson().toJson(report))
                }
                startActivity(intent)
            }

            holder.binding.btnDelete.setOnClickListener {
                onDelete(report.id)
            }
        }

        override fun getItemCount() = reports.size
        inner class ViewHolder(val binding: ItemReportHistoryBinding) : RecyclerView.ViewHolder(binding.root)
    }
}