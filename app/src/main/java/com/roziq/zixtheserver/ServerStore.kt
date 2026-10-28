package com.roziq.zixtheserver

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Penyimpanan daftar server. Kredensial disimpan terenkripsi (Android Keystore)
 * lewat EncryptedSharedPreferences. Jika Keystore perangkat bermasalah,
 * jatuh ke SharedPreferences biasa agar aplikasi tetap bisa dipakai.
 */
class ServerStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "zix_servers_enc",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("zix_servers_plain", Context.MODE_PRIVATE)
    }

    fun list(): List<Server> = synchronized(LOCK) {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(id: String): Server? = list().firstOrNull { it.id == id }

    /** Membuka sesi SSH (mendukung jump host) dan menyimpan host key saat pertama kali. */
    fun open(s: Server): com.jcraft.jsch.Session {
        val jump = if (s.jumpId.isNotBlank()) get(s.jumpId) else null
        return Ssh.connect(
            s,
            { fp -> get(s.id)?.let { save(it.copy(hostKey = fp)) } },
            jump
        ) { fp -> jump?.let { j -> get(j.id)?.let { save(it.copy(hostKey = fp)) } } }
    }

    /** Menghapus semua server (dipakai fitur hapus data darurat). */
    fun clearAll() = synchronized(LOCK) {
        prefs.edit().remove(KEY).apply()
    }

    /** Tandai server baru saja dipakai (untuk urutan di daftar). */
    fun touch(id: String) {
        get(id)?.let { save(it.copy(lastUsed = System.currentTimeMillis())) }
    }

    /** Ekspor semua server ke JSON (BERISI password/private key, simpan dengan aman). */
    fun exportJson(): String {
        val arr = JSONArray()
        list().forEach { arr.put(toJson(it)) }
        return JSONObject().put("app", "ZixTheServer").put("version", 1).put("servers", arr).toString(2)
    }

    /** Impor server dari JSON hasil ekspor. Server dengan id sama ditimpa. Mengembalikan jumlah yang masuk. */
    fun importJson(raw: String): Int = synchronized(LOCK) {
        val root = JSONObject(raw)
        val arr = root.getJSONArray("servers")
        val all = list().toMutableList()
        var count = 0
        for (i in 0 until arr.length()) {
            val s = fromJson(arr.getJSONObject(i))
            if (s.host.isBlank()) continue
            val idx = all.indexOfFirst { it.id == s.id }
            if (idx >= 0) all[idx] = s else all.add(s)
            count++
        }
        write(all)
        count
    }

    fun save(server: Server) = synchronized(LOCK) {
        val all = list().toMutableList()
        val i = all.indexOfFirst { it.id == server.id }
        if (i >= 0) all[i] = server else all.add(server)
        write(all)
    }

    fun delete(id: String) = synchronized(LOCK) {
        write(list().filterNot { it.id == id })
    }

    private fun write(all: List<Server>) {
        val arr = JSONArray()
        all.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(s: Server) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("host", s.host)
        put("port", s.port)
        put("username", s.username)
        put("password", s.password)
        put("privateKey", s.privateKey)
        put("hostKey", s.hostKey)
        put("favorite", s.favorite)
        put("lastUsed", s.lastUsed)
        put("panelUrl", s.panelUrl)
        put("panelKey", s.panelKey)
        put("group", s.group)
        put("mac", s.mac)
        put("jumpId", s.jumpId)
        put("alerts", s.alerts)
    }

    private fun fromJson(o: JSONObject) = Server(
        id = o.optString("id"),
        name = o.optString("name"),
        host = o.optString("host"),
        port = o.optInt("port", 22),
        username = o.optString("username", "root"),
        password = o.optString("password"),
        privateKey = o.optString("privateKey"),
        hostKey = o.optString("hostKey"),
        favorite = o.optBoolean("favorite", false),
        lastUsed = o.optLong("lastUsed", 0L),
        panelUrl = o.optString("panelUrl"),
        panelKey = o.optString("panelKey"),
        group = o.optString("group"),
        mac = o.optString("mac"),
        jumpId = o.optString("jumpId"),
        alerts = o.optBoolean("alerts", false)
    )

    companion object {
        private const val KEY = "servers"
        private val LOCK = Any()
    }
}
