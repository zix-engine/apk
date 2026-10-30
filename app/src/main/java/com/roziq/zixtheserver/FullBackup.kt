package com.roziq.zixtheserver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full Backup / Full Restore satu langkah untuk SEMUA data lokal aplikasi:
 * daftar server, pengaturan (termasuk snippet, jadwal, target monitor), serta proyek backtest, jurnal, dan transaksi.
 *
 * Format: ZIP berisi manifest.json + servers/settings/trading (.json atau .json.enc bila dienkripsi).
 * Kredensial server (password, private key, API key Panel) HANYA disertakan bila backup dienkripsi passphrase.
 * Tanpa passphrase, kredensial dikosongkan dan itu dicatat di manifest dan laporan.
 */
object FullBackup {

    const val FORMAT = "zixfunction-backup"
    const val VERSION = 1
    private const val MAX_ENTRY = 50L * 1024 * 1024
    private const val MAX_TOTAL = 100L * 1024 * 1024
    private val ALLOWED = setOf(
        "manifest.json", "servers.json", "servers.json.enc", "settings.json", "settings.json.enc",
        "trading.json", "trading.json.enc"
    )

    class Report(val status: String, val lines: List<String>, val bytes: Long = 0)
    class Parsed(val manifest: JSONObject, val entries: Map<String, ByteArray>)
    class Payload(val servers: JSONObject?, val settings: JSONObject?, val trading: JSONObject?, val secretsIncluded: Boolean)

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ Backup

    fun build(ctx: Context, passphrase: String): Pair<ByteArray, Report> {
        val enc = passphrase.isNotEmpty()
        val store = ServerStore(ctx)
        val prefs = Prefs(ctx)
        val db = ZixDb(ctx)
        val files = LinkedHashMap<String, ByteArray>()
        val meta = JSONArray()
        val lines = ArrayList<String>()
        val cats = JSONObject()
        var failures = 0
        var sources = 0

        fun put(category: String, content: String) {
            val name = if (enc) "$category.json.enc" else "$category.json"
            val data = (if (enc) BackupCrypto.encrypt(content, passphrase.toCharArray()) else content).toByteArray(Charsets.UTF_8)
            files[name] = data
            meta.put(JSONObject().put("name", name).put("category", category).put("bytes", data.size).put("sha256", sha256(data)))
        }

        sources++
        try {
            val js = JSONObject(store.exportJson())
            val arr = js.getJSONArray("servers")
            if (!enc) {
                for (i in 0 until arr.length()) {
                    arr.getJSONObject(i).put("password", "").put("privateKey", "").put("panelKey", "")
                }
            }
            put("servers", js.toString())
            cats.put("servers", arr.length())
            lines.add("✓ Server: ${arr.length()} " + if (enc) "(kredensial disertakan, terenkripsi)" else "(kredensial TIDAK disertakan — pakai passphrase untuk menyertakannya)")
        } catch (e: Exception) {
            failures++
            lines.add("✗ Server: gagal (${e.message})")
        }

        sources++
        try {
            val st = prefs.exportSettings()
            put("settings", st.toString())
            cats.put("settings", st.length())
            lines.add("✓ Pengaturan: ${st.length()} item (termasuk snippet, jadwal, target monitor)")
        } catch (e: Exception) {
            failures++
            lines.add("✗ Pengaturan: gagal (${e.message})")
        }

        sources++
        try {
            val tr = db.exportAll()
            put("trading", tr.toString())
            val np = tr.getJSONArray("projects").length()
            val nt = tr.getJSONArray("trades").length()
            cats.put("projects", np)
            cats.put("trades", nt)
            lines.add("✓ Trading: $np proyek/jurnal, $nt transaksi")
        } catch (e: Exception) {
            failures++
            lines.add("✗ Trading: gagal (${e.message})")
        }

        val excluded = listOf(
            "Token/sesi login", "Riwayat perintah terminal", "Status kunci aplikasi",
            "Data backend/database server (belum ada di versi ini)", "Isi file/filesystem di server"
        )
        val manifest = JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("schema", 1)
            .put("app", "ZixFunction").put("appVersion", BuildConfig.VERSION_NAME)
            .put("created", System.currentTimeMillis()).put("encrypted", enc)
            .put("secretsIncluded", enc).put("categories", cats).put("entries", meta)
            .put("excluded", JSONArray(excluded))
        lines.add("Tidak termasuk: " + excluded.joinToString("; "))

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            z.closeEntry()
            for ((name, data) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        val status = when {
            failures == 0 -> "complete"
            failures >= sources -> "failed"
            else -> "partial"
        }
        val bytes = out.toByteArray()
        return Pair(bytes, Report(status, lines, bytes.size.toLong()))
    }

    // ------------------------------------------------------------ Baca & verifikasi

    /** Membaca ZIP dengan whitelist nama entri (anti zip-slip) dan batas ukuran (anti archive bomb). */
    fun parse(bytes: ByteArray): Parsed {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name !in ALLOWED) throw IllegalArgumentException("Entri tidak dikenal di arsip: ${e.name.take(60)}")
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var size = 0L
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    size += n
                    total += n
                    if (size > MAX_ENTRY || total > MAX_TOTAL) throw IllegalArgumentException("Arsip terlalu besar / mencurigakan")
                    out.write(buf, 0, n)
                }
                entries[e.name] = out.toByteArray()
            }
        }
        val m = entries["manifest.json"] ?: throw IllegalArgumentException("manifest.json tidak ada — bukan file backup ZixFunction")
        return Parsed(JSONObject(String(m, Charsets.UTF_8)), entries)
    }

    /** Daftar masalah integritas; kosong = valid. */
    fun verify(p: Parsed): List<String> {
        val problems = ArrayList<String>()
        val m = p.manifest
        if (m.optString("format") != FORMAT) problems.add("Format bukan $FORMAT")
        if (m.optInt("version", 0) > VERSION) problems.add("Versi backup (${m.optInt("version")}) lebih baru dari aplikasi ini")
        val arr = m.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val name = e.optString("name")
            val data = p.entries[name]
            when {
                data == null -> problems.add("File hilang: $name")
                sha256(data) != e.optString("sha256") -> problems.add("Checksum tidak cocok: $name (file rusak/diubah)")
            }
        }
        return problems
    }

    fun isEncrypted(p: Parsed) = p.manifest.optBoolean("encrypted", false)

    /** Mendekripsi (bila perlu) dan mengurai isi. Melempar exception bila passphrase salah. */
    fun open(p: Parsed, passphrase: String): Payload {
        val enc = isEncrypted(p)
        fun read(category: String): JSONObject? {
            val name = if (enc) "$category.json.enc" else "$category.json"
            val data = p.entries[name] ?: return null
            val text = String(data, Charsets.UTF_8)
            return JSONObject(if (enc) BackupCrypto.decrypt(text, passphrase.toCharArray()) else text)
        }
        return Payload(read("servers"), read("settings"), read("trading"), p.manifest.optBoolean("secretsIncluded", false))
    }

    fun preview(p: Parsed, pl: Payload): List<String> {
        val lines = ArrayList<String>()
        val c = p.manifest.optJSONObject("categories")
        val created = p.manifest.optLong("created")
        lines.add("Dibuat: " + java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(created)) +
            " • versi aplikasi ${p.manifest.optString("appVersion")}")
        lines.add("Server: ${pl.servers?.optJSONArray("servers")?.length() ?: 0}" +
            if (pl.secretsIncluded) " (dengan kredensial)" else " (tanpa kredensial)")
        lines.add("Pengaturan: ${pl.settings?.length() ?: 0} item")
        lines.add("Proyek/jurnal: ${c?.optInt("projects") ?: 0}, transaksi: ${c?.optInt("trades") ?: 0}")
        return lines
    }

    // ------------------------------------------------------------ Restore

    /** Cadangan pengaman sebelum menimpa data (tanpa kredensial server). Mengembalikan nama file atau null. */
    private fun safetyBackup(ctx: Context): String? = try {
        val (bytes, _) = build(ctx, "")
        val dir = File(ctx.filesDir, "safety")
        dir.mkdirs()
        val f = File(dir, "safety-" + System.currentTimeMillis() + ".zip")
        f.writeBytes(bytes)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        f.name
    } catch (e: Exception) {
        null
    }

    fun restore(ctx: Context, pl: Payload, replace: Boolean): Report {
        val lines = ArrayList<String>()
        var failures = 0
        val store = ServerStore(ctx)
        val prefs = Prefs(ctx)
        val db = ZixDb(ctx)

        val safety = safetyBackup(ctx)
        lines.add(if (safety != null) "Cadangan pengaman dibuat ($safety; tanpa kredensial server)" else "Cadangan pengaman gagal dibuat")

        pl.trading?.let {
            try {
                val (np, nt) = db.importAll(it, replace)
                lines.add("✓ Trading: $np proyek/jurnal, $nt transaksi dipulihkan")
            } catch (e: Exception) {
                failures++
                lines.add("✗ Trading: gagal, data lama dipertahankan (${e.message})")
            }
        }
        pl.settings?.let {
            try {
                val n = prefs.importSettings(it)
                MonitorWorker.schedule(ctx, prefs.monitor)
                prefs.schedules().forEach { s -> ScheduleWorker.apply(ctx, s) }
                lines.add("✓ Pengaturan: $n item dipulihkan")
            } catch (e: Exception) {
                failures++
                lines.add("✗ Pengaturan: gagal (${e.message})")
            }
        }
        pl.servers?.let {
            try {
                val arr = it.getJSONArray("servers")
                val existing = store.list().associateBy { s -> s.id }
                if (!pl.secretsIncluded) {
                    // Backup tanpa kredensial: pertahankan kredensial yang sudah ada pada id yang sama.
                    for (i in 0 until arr.length()) {
                        val j = arr.getJSONObject(i)
                        existing[j.optString("id")]?.let { ex ->
                            j.put("password", ex.password).put("privateKey", ex.privateKey).put("panelKey", ex.panelKey)
                        }
                    }
                }
                if (replace) store.clearAll()
                val n = store.importJson(it.toString())
                lines.add("✓ Server: $n dipulihkan" + if (!pl.secretsIncluded) " (tanpa kredensial baru — isi ulang bila perlu)" else "")
            } catch (e: Exception) {
                failures++
                lines.add("✗ Server: gagal (${e.message})")
            }
        }
        val status = if (failures == 0) "complete" else "partial"
        return Report(status, lines)
    }
}
