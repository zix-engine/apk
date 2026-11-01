package com.roziq.zixtheserver

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** Kesalahan dari backend atau jaringan. [code] mis. INVALID_CREDENTIALS, TOKEN_EXPIRED, NETWORK, TLS. */
class ApiException(val code: String, message: String, val status: Int = 0) : Exception(message)

/**
 * Klien REST untuk backend ZixFunction. Hanya HTTPS. Bila [AuthStore.pin] terisi, koneksi hanya
 * diterima jika sidik jari SHA-256 sertifikat server cocok (untuk server tanpa domain / self-signed).
 * Semua fungsi memblokir — panggil dari thread latar.
 */
class ApiClient private constructor(context: Context) {

    private val store = AuthStore.get(context)
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    private fun normalize(fp: String) = fp.replace(":", "").replace(" ", "").lowercase()

    private fun buildClient(pin: String): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        if (pin.isNotEmpty()) {
            val expected = normalize(pin)
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException("Tidak dipakai")
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty() || normalize(fingerprint(chain[0])) != expected) {
                        throw CertificateException("Sidik jari sertifikat server tidak cocok dengan yang dipercaya")
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            b.sslSocketFactory(ctx.socketFactory, tm)
            // Sertifikat sudah dikunci lewat sidik jari, jadi nama host/IP tidak perlu dicocokkan lagi.
            b.hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }

    private fun requireBase(): String {
        val base = store.baseUrl
        if (!base.startsWith("https://")) throw ApiException("CONFIG", "Alamat server harus diawali https://")
        return base
    }

    /** Membaca sidik jari sertifikat server TANPA mengirim kredensial (hanya GET /health). */
    fun probeFingerprint(baseUrl: String): String {
        val url = baseUrl.trim().trimEnd('/')
        if (!url.startsWith("https://")) throw ApiException("CONFIG", "Alamat server harus diawali https://")
        var found: String? = null
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isNotEmpty()) found = fingerprint(chain[0])
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), null)
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
        try {
            client.newCall(Request.Builder().url("$url/health").get().build()).execute().use { }
        } catch (e: IOException) {
            if (found == null) throw ApiException("NETWORK", "Tidak bisa terhubung ke server: " + (e.message ?: ""))
        }
        return found ?: throw ApiException("TLS", "Server tidak mengirim sertifikat")
    }

    private fun execute(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val base = requireBase()
        val builder = Request.Builder().url(base + path).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        val needsBody = method == "POST" || method == "PATCH" || method == "PUT"
        val rb = if (needsBody) (body ?: JSONObject()).toString().toRequestBody(json) else null
        builder.method(method, rb)
        try {
            val result = buildClient(store.pin).newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = try {
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                } catch (e: Exception) {
                    JSONObject()
                }
                if (!resp.isSuccessful) {
                    throw ApiException(
                        obj.optString("error", "HTTP_" + resp.code),
                        obj.optString("message", "Server membalas HTTP " + resp.code),
                        resp.code
                    )
                }
                obj
            }
            return result
        } catch (e: SSLException) {
            throw ApiException("TLS", "Sertifikat server tidak dipercaya: " + (e.message ?: ""))
        } catch (e: IOException) {
            throw ApiException("NETWORK", "Tidak bisa terhubung ke server: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun saveSession(o: JSONObject): AuthUser {
        store.accessToken = o.getString("accessToken")
        store.refreshToken = o.getString("refreshToken")
        val u = AuthUser.fromJson(o.getJSONObject("user"))
        store.user = u
        return u
    }

    fun login(username: String, password: String, device: String): AuthUser {
        val o = execute(
            "POST", "/api/auth/login",
            JSONObject().put("username", username).put("password", password).put("device", device), null
        )
        store.mode = "remote"
        store.lastUsername = username
        return saveSession(o)
    }

    @Synchronized
    private fun refresh() {
        val rt = store.refreshToken
        if (rt.isEmpty()) throw ApiException("SESSION_EXPIRED", "Sesi berakhir. Silakan login lagi.", 401)
        try {
            saveSession(execute("POST", "/api/auth/refresh", JSONObject().put("refreshToken", rt), null))
        } catch (e: ApiException) {
            if (e.status == 401 || e.code == "SESSION_EXPIRED") store.clearSession()
            throw e
        }
    }

    /** Panggilan terautentikasi; otomatis refresh token satu kali bila kedaluwarsa. */
    fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        if (store.accessToken.isEmpty()) refresh()
        return try {
            execute(method, path, body, store.accessToken)
        } catch (e: ApiException) {
            if (e.status == 401 && (e.code == "TOKEN_EXPIRED" || e.code == "UNAUTHENTICATED")) {
                refresh()
                execute(method, path, body, store.accessToken)
            } else {
                throw e
            }
        }
    }

    /** Memuat ulang data akun sendiri (role/izin terbaru). */
    fun me(): AuthUser {
        val u = AuthUser.fromJson(call("GET", "/api/me").getJSONObject("user"))
        store.user = u
        return u
    }

    /** Cek kesehatan server (tanpa login). Melempar ApiException(TLS/NETWORK) bila gagal. */
    fun ping(): JSONObject = execute("GET", "/health", null, null)

    fun logout() {
        try {
            if (store.accessToken.isNotEmpty()) execute("POST", "/api/auth/logout", null, store.accessToken)
        } catch (_: Exception) {
            // Tetap hapus sesi lokal walau server tidak terjangkau.
        }
        store.clearSession()
    }

    companion object {
        @Volatile private var instance: ApiClient? = null
        fun get(context: Context): ApiClient =
            instance ?: synchronized(this) {
                instance ?: ApiClient(context.applicationContext).also { instance = it }
            }
    }
}
