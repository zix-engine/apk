package com.roziq.zixtheserver

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.util.Properties

object Ssh {

    class HostKeyChangedException : Exception(
        "Fingerprint host BERUBAH (server di-install ulang, atau ada serangan MITM). " +
            "Koneksi dibatalkan. Jika yakin aman, pilih \"Reset host key\" di menu server."
    )

    /**
     * Membuka sesi SSH. Host key diverifikasi dengan model trust-on-first-use:
     * fingerprint pertama disimpan lewat [onNewHostKey], lalu koneksi berikutnya
     * ditolak jika fingerprint berbeda.
     */
    fun connect(server: Server, onNewHostKey: (String) -> Unit): Session {
        val jsch = JSch()
        val useKey = server.privateKey.isNotBlank()
        if (useKey) {
            val pass = server.password.takeIf { it.isNotEmpty() }?.toByteArray()
            jsch.addIdentity(
                "zix-key",
                (server.privateKey.trim() + "\n").toByteArray(),
                null,
                pass
            )
        }

        val session = jsch.getSession(server.username, server.host, server.port)
        if (!useKey) session.setPassword(server.password)

        val config = Properties()
        config.setProperty("StrictHostKeyChecking", "no")
        config.setProperty(
            "PreferredAuthentications",
            if (useKey) "publickey" else "password,keyboard-interactive"
        )
        session.setConfig(config)
        session.setServerAliveInterval(15_000)
        session.setServerAliveCountMax(4)
        session.connect(15_000)

        val fingerprint = session.hostKey.getFingerPrint(jsch)
        if (server.hostKey.isNotEmpty() && server.hostKey != fingerprint) {
            session.disconnect()
            throw HostKeyChangedException()
        }
        if (server.hostKey.isEmpty()) onNewHostKey(fingerprint)
        return session
    }

    /** Menjalankan skrip shell lewat stdin dan mengembalikan outputnya. */
    fun execScript(session: Session, script: String): String {
        val ch = session.openChannel("exec") as ChannelExec
        try {
            ch.setCommand("sh -s")
            ch.setInputStream(ByteArrayInputStream(script.toByteArray()))
            val input = ch.inputStream
            ch.connect(10_000)
            return input.bufferedReader().readText()
        } finally {
            ch.disconnect()
        }
    }

    fun friendlyError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            e is HostKeyChangedException -> msg
            msg.contains("Auth fail", ignoreCase = true) ->
                "Login gagal. Periksa username, password, atau private key."
            msg.contains("UnknownHost", ignoreCase = true) ->
                "Host tidak ditemukan. Periksa alamat/IP server."
            msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ->
                "Koneksi time out. Periksa IP, port SSH, dan firewall server."
            msg.contains("refused", ignoreCase = true) ->
                "Koneksi ditolak. Pastikan SSH aktif di port tersebut."
            else -> msg
        }
    }
}
