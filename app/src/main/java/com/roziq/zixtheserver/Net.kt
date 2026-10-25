package com.roziq.zixtheserver

import java.net.InetSocketAddress
import java.net.Socket

object Net {
    /** Mengukur waktu koneksi TCP ke host:port dalam ms. -1 jika gagal. */
    fun ping(host: String, port: Int, timeoutMs: Int = 3000): Int {
        val socket = Socket()
        return try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            -1
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
