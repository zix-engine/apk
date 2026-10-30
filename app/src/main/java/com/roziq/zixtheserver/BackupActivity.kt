package com.roziq.zixtheserver

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Halaman Backup & Restore: Full Backup dan Full Restore satu langkah, plus riwayat. */
class BackupActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var adapter: SimpleToolAdapter
    private var history: List<JSONObject> = emptyList()
    private var pendingPass = ""
    private var pendingName = ""

    private val createLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) runBackup(uri)
        }

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadForRestore(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = "Backup & Restore"
        b.tvSub.text = "Satu langkah untuk semua data lokal"
        b.etSearch.visibility = View.GONE
        b.btnTop.visibility = View.GONE
        b.btnReload.visibility = View.GONE
        adapter = SimpleToolAdapter(::onItem)
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        render()
    }

    private fun render() {
        history = readHistory()
        val df = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        val items = ArrayList<Tools.Item>()
        items.add(Tools.Item("Full Backup", "Server, pengaturan, proyek backtest, jurnal, dan transaksi → satu file ZIP.", "mulai", "backup", 1))
        items.add(Tools.Item("Full Restore", "Pulihkan semua kategori dari file backup sekaligus, dengan pratinjau.", "mulai", "restore", 1))
        for (h in history) {
            val st = h.optString("status")
            items.add(
                Tools.Item(
                    h.optString("name"),
                    df.format(Date(h.optLong("ts"))) + " • " + String.format(Locale.US, "%.1f KB", h.optLong("bytes") / 1024.0),
                    when (st) { "complete" -> "lengkap"; "partial" -> "sebagian"; else -> "gagal" },
                    "h",
                    when (st) { "complete" -> 1; "partial" -> 2; else -> 3 }
                )
            )
        }
        adapter.set(items)
        b.tvMsg.visibility = View.GONE
    }

    private fun onItem(pos: Int) {
        when (pos) {
            0 -> askBackup()
            1 -> openLauncher.launch(arrayOf("*/*"))
            else -> {
                val h = history.getOrNull(pos - 2) ?: return
                MaterialAlertDialogBuilder(this)
                    .setTitle(h.optString("name"))
                    .setItems(arrayOf("Lihat laporan", "Hapus dari riwayat")) { _, i ->
                        if (i == 0) showText("Laporan backup", h.optString("lines"), mono = false)
                        else {
                            val list = readHistory().toMutableList()
                            list.removeAll { it.optLong("ts") == h.optLong("ts") }
                            writeHistory(list)
                            render()
                        }
                    }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------ Backup

    private fun passField(hint: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        e.setSingleLine(true)
        e.setTextColor(zColor(R.attr.zText))
        e.setHintTextColor(zColor(R.attr.zTextDim))
        return e
    }

    private fun holder(e: EditText): FrameLayout {
        val h = FrameLayout(this)
        h.setPadding(dpPx(24), dpPx(8), dpPx(24), 0)
        h.addView(e)
        return h
    }

    private fun askBackup() {
        val input = passField("Passphrase (disarankan)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Full Backup")
            .setMessage(
                "Dengan passphrase, backup dienkripsi (AES-256) dan kredensial server (password, private key, API key Panel) ikut disertakan. " +
                    "TANPA passphrase, kredensial dikosongkan demi keamanan.\n\n" +
                    "Jika passphrase hilang, backup tidak bisa dipulihkan."
            )
            .setView(holder(input))
            .setPositiveButton("Lanjut") { _, _ ->
                pendingPass = input.text.toString()
                pendingName = "zixfunction-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
                createLauncher.launch(pendingName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runBackup(uri: Uri) {
        b.busy.visibility = View.VISIBLE
        val pass = pendingPass
        val name = pendingName
        thread(name = "full-backup") {
            var status = "failed"
            val lines = ArrayList<String>()
            var size = 0L
            try {
                val (bytes, report) = FullBackup.build(this, pass)
                status = report.status
                lines.addAll(report.lines)
                size = bytes.size.toLong()
                contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Tidak bisa menulis ke tujuan")
                // Verifikasi: baca ulang file yang baru ditulis dan cek checksum.
                val back = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                val problems = if (back == null) listOf("Tidak bisa membaca ulang file") else FullBackup.verify(FullBackup.parse(back))
                if (problems.isEmpty()) {
                    lines.add("✓ Verifikasi checksum: lolos (${String.format(Locale.US, "%.1f", size / 1024.0)} KB)")
                } else {
                    status = "failed"
                    lines.add("✗ Verifikasi gagal: " + problems.joinToString("; "))
                }
                if (pass.isEmpty()) lines.add("⚠ Tanpa enkripsi: file ini tidak berisi kredensial server.")
            } catch (e: Exception) {
                status = "failed"
                lines.add("✗ Backup gagal: " + (e.message ?: e.javaClass.simpleName))
            }
            val finalStatus = status
            addHistory(name, size, finalStatus, lines)
            onUi {
                b.busy.visibility = View.INVISIBLE
                render()
                val title = when (finalStatus) { "complete" -> "Backup lengkap"; "partial" -> "Backup SEBAGIAN"; else -> "Backup GAGAL" }
                showText(title, lines.joinToString("\n"), mono = false)
            }
        }
    }

    // ------------------------------------------------------------ Restore

    private fun loadForRestore(uri: Uri) {
        b.busy.visibility = View.VISIBLE
        thread(name = "restore-read") {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("File tidak bisa dibaca")
                if (bytes.size > 120L * 1024 * 1024) throw IllegalStateException("File terlalu besar")
                val parsed = FullBackup.parse(bytes)
                val problems = FullBackup.verify(parsed)
                onUi {
                    b.busy.visibility = View.INVISIBLE
                    if (problems.isNotEmpty()) {
                        showText("File backup tidak valid", problems.joinToString("\n"), mono = false)
                    } else if (FullBackup.isEncrypted(parsed)) {
                        askRestorePass(parsed)
                    } else {
                        previewAndConfirm(parsed, "")
                    }
                }
            } catch (e: Exception) {
                onUi {
                    b.busy.visibility = View.INVISIBLE
                    showText("File backup tidak valid", e.message ?: e.javaClass.simpleName, mono = false)
                }
            }
        }
    }

    private fun askRestorePass(parsed: FullBackup.Parsed) {
        val input = passField("Passphrase backup")
        MaterialAlertDialogBuilder(this)
            .setTitle("Backup terenkripsi")
            .setView(holder(input))
            .setPositiveButton("Buka") { _, _ -> previewAndConfirm(parsed, input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun previewAndConfirm(parsed: FullBackup.Parsed, pass: String) {
        val payload = try {
            FullBackup.open(parsed, pass)
        } catch (e: Exception) {
            toast("Passphrase salah atau file rusak")
            return
        }
        val lines = FullBackup.preview(parsed, payload)
        MaterialAlertDialogBuilder(this)
            .setTitle("Pratinjau restore")
            .setMessage(
                lines.joinToString("\n") + "\n\n" +
                    "Gabung: data di backup ditambahkan/menimpa item yang id-nya sama; data lain tetap ada (aman).\n" +
                    "Ganti semua: data trading saat ini dihapus lebih dulu, lalu diganti isi backup.\n\n" +
                    "Cadangan pengaman dibuat otomatis sebelum menimpa."
            )
            .setPositiveButton("Gabung") { _, _ -> doRestore(payload, false) }
            .setNeutralButton("Ganti semua") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Ganti semua data trading?")
                    .setMessage("Semua proyek, jurnal, dan transaksi yang ada sekarang akan dihapus lalu diganti isi backup. Lanjutkan?")
                    .setPositiveButton("Ya, ganti") { _, _ -> doRestore(payload, true) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doRestore(payload: FullBackup.Payload, replace: Boolean) {
        b.busy.visibility = View.VISIBLE
        thread(name = "restore-apply") {
            val report = FullBackup.restore(this, payload, replace)
            onUi {
                b.busy.visibility = View.INVISIBLE
                val title = when (report.status) { "complete" -> "Restore lengkap"; else -> "Restore SEBAGIAN" }
                showText(title, report.lines.joinToString("\n"), mono = false)
            }
        }
    }

    // ------------------------------------------------------------ Riwayat

    private fun readHistory(): List<JSONObject> = try {
        val arr = JSONArray(prefs.raw("backup_history").ifEmpty { "[]" })
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeHistory(list: List<JSONObject>) {
        val arr = JSONArray()
        list.take(30).forEach { arr.put(it) }
        prefs.putRaw("backup_history", arr.toString())
    }

    private fun addHistory(name: String, bytes: Long, status: String, lines: List<String>) {
        val list = readHistory().toMutableList()
        list.add(0, JSONObject().put("ts", System.currentTimeMillis()).put("name", name).put("bytes", bytes)
            .put("status", status).put("lines", lines.joinToString("\n")))
        writeHistory(list)
    }
}
