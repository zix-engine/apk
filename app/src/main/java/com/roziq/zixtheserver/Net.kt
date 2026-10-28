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

    /** Mengirim magic packet Wake-on-LAN (UDP broadcast). */
    fun wol(mac: String): Boolean {
        val hex = mac.replace(Regex("[^0-9a-fA-F]"), "")
        if (hex.length != 12) return false
        val macBytes = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val data = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) data[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(macBytes, 0, data, 6 + i * 6, 6)
        val socket = java.net.DatagramSocket()
        return try {
            socket.broadcast = true
            socket.send(
                java.net.DatagramPacket(data, data.size, java.net.InetAddress.getByName("255.255.255.255"), 9)
            )
            true
        } catch (e: Exception) {
            false
        } finally {
            socket.close()
        }
    }
}
