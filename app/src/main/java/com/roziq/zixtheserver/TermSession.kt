package com.roziq.zixtheserver

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Satu sesi shell SSH (satu tab terminal) beserta buffer outputnya. */
class TermSession(
    private val server: Server,
    private val store: ServerStore,
    light: Boolean,
    val index: Int
) {
    interface Listener {
        fun onOutput(s: TermSession)
        fun onState(s: TermSession)
        fun onError(s: TermSession, message: String)
    }

    val buffer = TerminalBuffer(light = light)
    @Volatile var listener: Listener? = null
    @Volatile var state = CONNECTING
    @Volatile var message = ""

    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closing = false
    private val io = Executors.newSingleThreadExecutor()

    val isConnected: Boolean get() = channel?.isConnected == true

    fun connect(initialCommand: String?) {
        state = CONNECTING
        listener?.onState(this)
        thread(name = "ssh-shell-$index") {
            try {
                val s = store.open(server)
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm-256color", 110, 40, 0, 0)
                val input = ch.inputStream
                out = ch.outputStream
                ch.connect(10_000)
                session = s
                channel = ch
                state = CONNECTED
                listener?.onState(this)
                if (!initialCommand.isNullOrBlank()) send(initialCommand + "\n")

                val reader = InputStreamReader(input, Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    buffer.feed(String(buf, 0, n))
                    listener?.onOutput(this)
                }
                if (!closing) {
                    out = null
                    state = CLOSED
                    listener?.onState(this)
                }
            } catch (e: Exception) {
                if (!closing) {
                    val msg = Ssh.friendlyError(e)
                    buffer.feed("\n[Gagal: $msg]\n")
                    out = null
                    message = msg
                    state = FAILED
                    listener?.onOutput(this)
                    listener?.onState(this)
                }
            }
        }
    }

    fun reconnect() {
        if (isConnected) return
        disconnectQuietly()
        buffer.feed("\n[Menyambung ulang…]\n")
        listener?.onOutput(this)
        connect(null)
    }

    fun send(text: String) {
        if (out == null) {
            listener?.onError(this, "not_connected")
            return
        }
        try {
            io.execute {
                try {
                    out?.let {
                        it.write(text.toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                } catch (e: Exception) {
                    listener?.onError(this, e.message ?: "")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun disconnectQuietly() {
        val ch = channel
        val s = session
        channel = null
        session = null
        out = null
        thread(name = "ssh-close-$index") {
            try {
                ch?.disconnect()
                s?.let { Ssh.disconnect(it) }
            } catch (_: Exception) {
            }
        }
    }

    fun close() {
        closing = true
        listener = null
        disconnectQuietly()
        io.shutdown()
    }

    companion object {
        const val CONNECTING = 0
        const val CONNECTED = 1
        const val CLOSED = 2
        const val FAILED = 3
    }
}
