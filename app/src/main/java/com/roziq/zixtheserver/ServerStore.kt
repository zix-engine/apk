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
    }

    private fun fromJson(o: JSONObject) = Server(
        id = o.optString("id"),
        name = o.optString("name"),
        host = o.optString("host"),
        port = o.optInt("port", 22),
        username = o.optString("username", "root"),
        password = o.optString("password"),
        privateKey = o.optString("privateKey"),
        hostKey = o.optString("hostKey")
    )

    companion object {
        private const val KEY = "servers"
        private val LOCK = Any()
    }
}
