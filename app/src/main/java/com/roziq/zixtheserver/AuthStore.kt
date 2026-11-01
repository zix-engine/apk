package com.roziq.zixtheserver

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Pengguna yang sedang login pada backend. */
data class AuthUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val email: String,
    val role: String,
    val status: String,
    val mustChangePassword: Boolean,
    val permissions: List<String>
) {
    companion object {
        fun fromJson(o: JSONObject): AuthUser {
            val arr = o.optJSONArray("permissions") ?: JSONArray()
            return AuthUser(
                id = o.optLong("id"), username = o.optString("username"), displayName = o.optString("displayName"),
                email = if (o.isNull("email")) "" else o.optString("email"), role = o.optString("role"),
                status = o.optString("status"), mustChangePassword = o.optBoolean("mustChangePassword"),
                permissions = (0 until arr.length()).map { arr.getString(it) }
            )
        }
    }
}

/**
 * Menyimpan alamat backend, sidik jari sertifikat (pin), mode aplikasi, dan sesi login.
 * Token disimpan terenkripsi (Android Keystore). Bila Keystore bermasalah, token hanya
 * disimpan di memori (tidak ditulis ke disk) — pengguna akan login ulang saat aplikasi dimulai ulang.
 */
class AuthStore private constructor(context: Context) {

    private val secure: Boolean
    private val prefs: SharedPreferences

    init {
        var enc: SharedPreferences? = null
        try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            enc = EncryptedSharedPreferences.create(
                context, "zix_auth_enc", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            enc = null
        }
        secure = enc != null
        prefs = enc ?: context.getSharedPreferences("zix_auth_plain", Context.MODE_PRIVATE)
    }

    // Cadangan di memori bila penyimpanan terenkripsi tidak tersedia.
    private var memAccess = ""
    private var memRefresh = ""

    /** "" = belum memilih, "local" = tanpa akun (fitur lokal), "remote" = login ke backend. */
    var mode: String
        get() = prefs.getString("mode", "") ?: ""
        set(v) = prefs.edit().putString("mode", v).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    /** Sidik jari SHA-256 sertifikat server, format "AA:BB:...". Kosong = pakai validasi sertifikat sistem. */
    var pin: String
        get() = prefs.getString("pin", "") ?: ""
        set(v) = prefs.edit().putString("pin", v).apply()

    var lastUsername: String
        get() = prefs.getString("last_username", "") ?: ""
        set(v) = prefs.edit().putString("last_username", v).apply()

    var accessToken: String
        get() = if (secure) prefs.getString("access", "") ?: "" else memAccess
        set(v) {
            if (secure) prefs.edit().putString("access", v).apply() else memAccess = v
        }

    var refreshToken: String
        get() = if (secure) prefs.getString("refresh", "") ?: "" else memRefresh
        set(v) {
            if (secure) prefs.edit().putString("refresh", v).apply() else memRefresh = v
        }

    var user: AuthUser?
        get() = try {
            prefs.getString("user", null)?.let { AuthUser.fromJson(JSONObject(it)) }
        } catch (e: Exception) {
            null
        }
        set(v) {
            if (v == null) {
                prefs.edit().remove("user").apply()
            } else {
                val o = JSONObject().put("id", v.id).put("username", v.username).put("displayName", v.displayName)
                    .put("email", v.email).put("role", v.role).put("status", v.status)
                    .put("mustChangePassword", v.mustChangePassword)
                    .put("permissions", JSONArray(v.permissions))
                prefs.edit().putString("user", o.toString()).apply()
            }
        }

    fun hasSession(): Boolean = refreshToken.isNotEmpty()

    fun clearSession() {
        accessToken = ""
        refreshToken = ""
        user = null
    }

    /**
     * Pemeriksaan izin untuk menyembunyikan menu di UI. Ini BUKAN batas keamanan untuk data lokal di HP;
     * penegakan sebenarnya hanya berlaku untuk data/aksi yang lewat backend.
     */
    fun can(permission: String): Boolean {
        if (mode != "remote") return true
        val u = user ?: return false
        return u.role == "owner" || u.permissions.contains(permission)
    }

    companion object {
        @Volatile private var instance: AuthStore? = null
        fun get(context: Context): AuthStore =
            instance ?: synchronized(this) {
                instance ?: AuthStore(context.applicationContext).also { instance = it }
            }
    }
}
