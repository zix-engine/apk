package com.roziq.zixtheserver

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

object Ssh {

    class HostKeyChangedException : Exception(
        "Fingerprint host BERUBAH (server di-install ulang, atau ada serangan MITM). " +
            "Koneksi dibatalkan. Jika yakin aman, pilih \"Reset host key\" di menu server."
    )

    private val jumps: MutableMap<Session, Session> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Session, Session>())

    data class ExecResult(val output: String, val exit: Int)

    /**
     * Membuka sesi SSH. Host key diverifikasi dengan model trust-on-first-use:
     * fingerprint pertama disimpan lewat [onNewHostKey], lalu koneksi berikutnya
     * ditolak jika fingerprint berbeda. Jika [jump] diisi, koneksi lewat server itu.
     */
    fun connect(
        server: Server,
        onNewHostKey: (String) -> Unit,
        jump: Server? = null,
        onJumpHostKey: (String) -> Unit = {}
    ): Session {
        var jumpSession: Session? = null
        var host = server.host
        var port = server.port
        if (jump != null) {
            val js = connect(jump, onJumpHostKey)
            jumpSession = js
            try {
                port = js.setPortForwardingL(0, server.host, server.port)
                host = "127.0.0.1"
            } catch (e: Exception) {
                js.disconnect()
                throw e
            }
        }
        try {
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

            val session = jsch.getSession(server.username, host, port)
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
            if (jumpSession != null) jumps[session] = jumpSession
            return session
        } catch (e: Exception) {
            jumpSession?.disconnect()
            throw e
        }
    }

    /** Menutup sesi beserta jump host-nya (jika ada). */
    fun disconnect(session: Session) {
        try {
            session.disconnect()
        } finally {
            jumps.remove(session)?.disconnect()
        }
    }

    /** Menjalankan satu perintah (stdout+stderr digabung). Mengembalikan output dan exit code. */
    fun execCommand(session: Session, command: String, timeoutMs: Long = 60_000): ExecResult {
        val ch = session.openChannel("exec") as ChannelExec
        try {
            ch.setCommand("($command) 2>&1")
            val input = ch.inputStream
            ch.connect(10_000)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            val start = System.currentTimeMillis()
            while (true) {
                while (input.available() > 0) {
                    val n = input.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (out.size() < 200_000) out.write(buf, 0, n)
                }
                if (ch.isClosed) {
                    if (input.available() > 0) continue
                    break
                }
                if (System.currentTimeMillis() - start > timeoutMs) break
                Thread.sleep(100)
            }
            return ExecResult(out.toString("UTF-8"), ch.exitStatus)
        } finally {
            ch.disconnect()
        }
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
