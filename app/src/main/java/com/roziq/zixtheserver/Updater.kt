package com.roziq.zixtheserver

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Cek & pasang update dari GitHub Releases. */
object Updater {

    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val size: Long
    )

    /** Mengambil rilis terbaru. Melempar exception jika gagal. */
    fun fetchLatest(repo: String): Release {
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "ZixTheServer")
            val code = conn.responseCode
            if (code == 404) throw IllegalStateException("Rilis belum ada di repo $repo (atau repo private).")
            if (code != 200) throw IllegalStateException("GitHub membalas HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val assets = o.optJSONArray("assets")
            var apkUrl = ""
            var apkName = ""
            var size = -1L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        apkName = a.optString("name")
                        size = a.optLong("size", -1L)
                        break
                    }
                }
            }
            if (apkUrl.isEmpty()) throw IllegalStateException("Rilis terbaru tidak memiliki file APK.")
            return Release(
                tag = o.optString("tag_name"),
                name = o.optString("name").ifBlank { o.optString("tag_name") },
                notes = o.optString("body"),
                apkUrl = apkUrl,
                apkName = apkName,
                size = size
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun parts(v: String): List<Int> =
        Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

    /** True jika [tag] lebih baru dari [current] (bandingkan angka versi). */
    fun isNewer(tag: String, current: String): Boolean {
        val a = parts(tag)
        val b = parts(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Mengunduh APK ke [dest]. [onProgress] menerima 0..100 (atau -1 jika ukuran tak diketahui). */
    fun download(rel: Release, dest: File, onProgress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val conn = URL(rel.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ZixTheServer")
            conn.connect()
            if (conn.responseCode != 200) {
                throw IllegalStateException("Unduhan gagal: HTTP ${conn.responseCode}")
            }
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else rel.size
            var done = 0L
            var lastPct = -2
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    fun updateFile(activity: Activity): File = File(File(activity.cacheDir, "updates"), "update.apk")

    /** Membuka installer paket sistem untuk APK yang sudah diunduh. */
    fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}
