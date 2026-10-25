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
        get() = p.getString("theme", "cyan") ?: "cyan"
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
}
