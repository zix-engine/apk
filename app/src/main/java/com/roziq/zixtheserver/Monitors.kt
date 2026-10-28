package com.roziq.zixtheserver

import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection

/** Target pemantauan: URL website (http/https) atau host:port (TCP). */
data class Target(val id: String, val name: String, val addr: String) {
    val isHttp: Boolean get() = addr.startsWith("http://") || addr.startsWith("https://")
}

/** Jadwal perintah. [days] bitmask: bit 0 = Minggu ... bit 6 = Sabtu. */
data class Schedule(
    val id: String,
    val serverId: String,
    val title: String,
    val command: String,
    val hour: Int,
    val minute: Int,
    val days: Int,
    val enabled: Boolean
)

object Monitors {

    data class Check(val up: Boolean, val ms: Int, val info: String, val sslDays: Int?)

    fun check(t: Target): Check {
        return if (t.isHttp) checkHttp(t.addr) else checkTcp(t.addr)
    }

    private fun checkTcp(addr: String): Check {
        val host = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':', "").toIntOrNull()
            ?: return Check(false, -1, "Format harus host:port", null)
        val ms = Net.ping(host, port, 5000)
        return Check(ms > 0, ms, if (ms > 0) "Port terbuka" else "Port tertutup / tidak terjangkau", null)
    }

    private fun checkHttp(url: String): Check {
        val start = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "ZixTheServer")
            val code = conn.responseCode
            val ms = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
            var days: Int? = null
            if (conn is HttpsURLConnection) {
                val cert = conn.serverCertificates.firstOrNull() as? X509Certificate
                if (cert != null) {
                    days = ((cert.notAfter.time - System.currentTimeMillis()) / 86_400_000L).toInt()
                }
            }
            Check(code in 200..399, ms, "HTTP $code", days)
        } catch (e: Exception) {
            Check(false, -1, e.message ?: e.javaClass.simpleName, null)
        } finally {
            conn?.disconnect()
        }
    }
}
