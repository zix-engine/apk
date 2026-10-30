package com.roziq.zixtheserver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Perintah buatan pengguna (snippet) yang bisa dijalankan dari dashboard server. */
data class Snippet(val id: String, val title: String, val command: String)

/** Pengaturan aplikasi (tidak berisi kredensial). */
class Prefs(context: Context) {

    private val p = context.applicationContext
        .getSharedPreferences("zix_prefs", Context.MODE_PRIVATE)

    var theme: String
        get() = p.getString("theme", "indigo") ?: "indigo"
        set(v) = p.edit().putString("theme", v).apply()

    var termFont: Int
        get() = p.getInt("term_font", 12)
        set(v) = p.edit().putInt("term_font", v.coerceIn(8, 24)).apply()

    var keepScreen: Boolean
        get() = p.getBoolean("keep_screen", true)
        set(v) = p.edit().putBoolean("keep_screen", v).apply()

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()

    var autoRefresh: Boolean
        get() = p.getBoolean("auto_refresh", false)
        set(v) = p.edit().putBoolean("auto_refresh", v).apply()

    var refreshSec: Int
        get() = p.getInt("refresh_sec", 15)
        set(v) = p.edit().putInt("refresh_sec", v).apply()

    var splash: Boolean
        get() = p.getBoolean("splash", true)
        set(v) = p.edit().putBoolean("splash", v).apply()

    var autoUpdate: Boolean
        get() = p.getBoolean("auto_update", true)
        set(v) = p.edit().putBoolean("auto_update", v).apply()

    /** Repo GitHub "pemilik/nama" untuk cek update. Kosong = pakai bawaan build. */
    var repoOverride: String
        get() = p.getString("repo_override", "") ?: ""
        set(v) = p.edit().putString("repo_override", v.trim()).apply()

    val repo: String
        get() = repoOverride.ifBlank { BuildConfig.GITHUB_REPO }

    var lastUpdateCheck: Long
        get() = p.getLong("last_update_check", 0L)
        set(v) = p.edit().putLong("last_update_check", v).apply()

    // ---------- Aksi favorit ----------

    fun favActions(): Set<String> =
        p.getStringSet("fav_actions", emptySet())?.toSet() ?: emptySet()

    fun toggleFavAction(key: String): Boolean {
        val set = favActions().toMutableSet()
        val added = set.add(key)
        if (!added) set.remove(key)
        p.edit().putStringSet("fav_actions", HashSet(set)).apply()
        return added
    }

    // ---------- Section aksi yang dilipat ----------

    fun collapsed(): Set<String> =
        p.getStringSet("collapsed", emptySet())?.toSet() ?: emptySet()

    fun toggleCollapsed(title: String) {
        val set = collapsed().toMutableSet()
        if (!set.add(title)) set.remove(title)
        p.edit().putStringSet("collapsed", HashSet(set)).apply()
    }

    // ---------- Riwayat perintah terminal ----------

    fun history(): List<String> {
        val raw = p.getString("history", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addHistory(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        val list = history().toMutableList()
        list.remove(c)
        list.add(0, c)
        val arr = JSONArray()
        list.take(60).forEach { arr.put(it) }
        p.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() {
        p.edit().putString("history", "[]").apply()
    }

    // ---------- Snippet perintah ----------

    fun snippets(): List<Snippet> {
        val raw = p.getString("snippets", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Snippet(o.optString("id"), o.optString("title"), o.optString("command"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSnippet(s: Snippet) {
        val list = snippets().toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        writeSnippets(list)
    }

    fun deleteSnippet(id: String) {
        writeSnippets(snippets().filterNot { it.id == id })
    }

    private fun writeSnippets(list: List<Snippet>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("command", it.command)
            )
        }
        p.edit().putString("snippets", arr.toString()).apply()
    }

    // ---------- Kunci aplikasi & pemantauan ----------

    var lockEnabled: Boolean
        get() = p.getBoolean("lock_enabled", false)
        set(v) = p.edit().putBoolean("lock_enabled", v).apply()

    var monitor: Boolean
        get() = p.getBoolean("monitor", false)
        set(v) = p.edit().putBoolean("monitor", v).apply()

    /** Status online terakhir tiap server (id -> true/false) untuk deteksi perubahan. */
    fun monitorState(): Map<String, Boolean> {
        val raw = p.getString("monitor_state", "{}") ?: "{}"
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optBoolean(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveMonitorState(m: Map<String, Boolean>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        p.edit().putString("monitor_state", o.toString()).apply()
    }

    // ---------- v2.2: ambang alert, wipe, bahasa, skema terminal ----------

    var alertCpu: Int
        get() = p.getInt("alert_cpu", 90)
        set(v) = p.edit().putInt("alert_cpu", v).apply()

    var alertRam: Int
        get() = p.getInt("alert_ram", 90)
        set(v) = p.edit().putInt("alert_ram", v).apply()

    var alertDisk: Int
        get() = p.getInt("alert_disk", 90)
        set(v) = p.edit().putInt("alert_disk", v).apply()

    var wipeEnabled: Boolean
        get() = p.getBoolean("wipe_enabled", false)
        set(v) = p.edit().putBoolean("wipe_enabled", v).apply()

    var lockFails: Int
        get() = p.getInt("lock_fails", 0)
        set(v) = p.edit().putInt("lock_fails", v).apply()

    var termScheme: String
        get() = p.getString("term_scheme", "theme") ?: "theme"
        set(v) = p.edit().putString("term_scheme", v).apply()

    /** Nilai mentah bebas (dipakai pemantau latar untuk cooldown dan status layanan). */
    fun raw(key: String): String = p.getString(key, "") ?: ""

    fun putRaw(key: String, value: String) {
        p.edit().putString(key, value).apply()
    }

    // ---------- Riwayat uptime (7 hari) ----------

    fun uptimeAppend(id: String, up: Boolean) {
        val key = "up_$id"
        val now = System.currentTimeMillis()
        val cutoff = now - 7L * 24 * 3600 * 1000
        val list = raw(key).split(';')
            .filter { it.isNotEmpty() && (it.substringBefore(',').toLongOrNull() ?: 0L) > cutoff }
            .toMutableList()
        list.add("$now,${if (up) 1 else 0}")
        putRaw(key, list.takeLast(700).joinToString(";"))
    }

    /** Persentase uptime 7 hari terakhir, atau null jika belum ada data. */
    fun uptimePercent(id: String): Double? {
        val items = raw("up_$id").split(';').filter { it.contains(',') }
        if (items.isEmpty()) return null
        val up = items.count { it.substringAfter(',') == "1" }
        return up * 100.0 / items.size
    }

    // ---------- Target monitor (website / port) ----------

    fun targets(): List<Target> = try {
        val arr = JSONArray(raw("targets").ifEmpty { "[]" })
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Target(o.optString("id"), o.optString("name"), o.optString("addr"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveTargets(list: List<Target>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("addr", it.addr)) }
        putRaw("targets", arr.toString())
    }

    // ---------- Jadwal perintah ----------

    fun schedules(): List<Schedule> = try {
        val arr = JSONArray(raw("schedules").ifEmpty { "[]" })
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Schedule(
                o.optString("id"), o.optString("serverId"), o.optString("title"),
                o.optString("command"), o.optInt("hour"), o.optInt("minute"),
                o.optInt("days", 127), o.optBoolean("enabled", true)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveSchedules(list: List<Schedule>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("serverId", it.serverId).put("title", it.title)
                    .put("command", it.command).put("hour", it.hour).put("minute", it.minute)
                    .put("days", it.days).put("enabled", it.enabled)
            )
        }
        putRaw("schedules", arr.toString())
    }

    /** Hapus semua data aplikasi (dipakai fitur hapus data darurat). */
    fun wipeAll() {
        p.edit().clear().apply()
    }

    // ---------- Ekspor/impor pengaturan untuk Full Backup ----------

    private val exportStr = listOf("theme", "term_scheme", "repo_override", "snippets", "schedules", "targets")
    private val exportInt = listOf("term_font", "refresh_sec", "alert_cpu", "alert_ram", "alert_disk")
    private val exportBool = listOf("keep_screen", "haptics", "auto_refresh", "splash", "auto_update", "monitor")
    private val exportSet = listOf("fav_actions", "collapsed")

    /** Hanya pengaturan non-rahasia. Status kunci aplikasi, token, dan riwayat perintah sengaja tidak diekspor. */
    fun exportSettings(): JSONObject {
        val o = JSONObject()
        for (k in exportStr) if (p.contains(k)) o.put(k, p.getString(k, "") ?: "")
        for (k in exportInt) if (p.contains(k)) o.put(k, p.getInt(k, 0))
        for (k in exportBool) if (p.contains(k)) o.put(k, p.getBoolean(k, false))
        for (k in exportSet) if (p.contains(k)) {
            val arr = JSONArray()
            (p.getStringSet(k, emptySet()) ?: emptySet()).forEach { arr.put(it) }
            o.put(k, arr)
        }
        return o
    }

    fun importSettings(o: JSONObject): Int {
        val e = p.edit()
        var n = 0
        for (k in exportStr) if (o.has(k)) { e.putString(k, o.optString(k)); n++ }
        for (k in exportInt) if (o.has(k)) { e.putInt(k, o.optInt(k)); n++ }
        for (k in exportBool) if (o.has(k)) { e.putBoolean(k, o.optBoolean(k)); n++ }
        for (k in exportSet) if (o.has(k)) {
            val arr = o.optJSONArray(k) ?: JSONArray()
            val set = HashSet<String>()
            for (i in 0 until arr.length()) set.add(arr.optString(i))
            e.putStringSet(k, set)
            n++
        }
        e.apply()
        return n
    }
}
