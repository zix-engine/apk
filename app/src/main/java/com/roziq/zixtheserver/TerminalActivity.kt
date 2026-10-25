package com.roziq.zixtheserver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import com.roziq.zixtheserver.databinding.ActivityTerminalBinding
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TerminalActivity : AppCompatActivity() {

    private lateinit var b: ActivityTerminalBinding
    private lateinit var store: ServerStore
    private lateinit var server: Server

    private val term = TerminalBuffer()
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val renderQueued = AtomicBoolean(false)

    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var shellOut: OutputStream? = null
    @Volatile private var closing = false
    private var secretMode = false

    private val keys = listOf(
        "Enter" to "\n",
        "Ctrl+C" to "\u0003",
        "Ctrl+D" to "\u0004",
        "Tab" to "\t",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "Esc" to "\u001b",
        "y" to "y\n",
        "n" to "n\n",
        "sudo -i" to "sudo -i\n",
        "clear" to "clear\n"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerStore(this)
        val found = intent.getStringExtra(EXTRA_ID)?.let { store.get(it) }
        if (found == null) {
            finish()
            return
        }
        server = found

        b = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(b.root)
        setStatus(getString(R.string.term_connecting, server.name), R.color.text_dim)

        buildKeys()
        b.btnSend.setOnClickListener { sendInput() }
        b.etInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                sendInput()
                true
            } else {
                false
            }
        }
        b.btnSecret.alpha = 0.5f
        b.btnSecret.setOnClickListener { toggleSecret() }
        b.btnClear.setOnClickListener {
            term.clear()
            render()
        }
        b.btnCopy.setOnClickListener { copyOutput() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (channel?.isConnected == true) {
                    MaterialAlertDialogBuilder(this@TerminalActivity)
                        .setTitle(R.string.close_session)
                        .setMessage(R.string.close_session_msg)
                        .setPositiveButton(R.string.close) { _, _ -> finish() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    finish()
                }
            }
        })

        connect(intent.getStringExtra(EXTRA_COMMAND))
    }

    private fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    private fun connect(initialCommand: String?) {
        val current = server
        thread(name = "ssh-shell") {
            try {
                val s = Ssh.connect(current) { fp ->
                    store.save(current.copy(hostKey = fp))
                }
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm", 110, 40, 0, 0)
                val input = ch.inputStream
                shellOut = ch.outputStream
                ch.connect(10_000)
                session = s
                channel = ch

                onUi {
                    setStatus(
                        getString(R.string.term_connected, current.username, current.host),
                        R.color.ok
                    )
                    startKeepAlive()
                }
                if (!initialCommand.isNullOrBlank()) send(initialCommand + "\n")

                val reader = InputStreamReader(input, Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    term.feed(String(buf, 0, n))
                    scheduleRender()
                }
                if (!closing) {
                    onUi { setStatus(getString(R.string.term_closed), R.color.bad) }
                }
            } catch (e: Exception) {
                val msg = Ssh.friendlyError(e)
                term.feed("\n[Gagal: $msg]\n")
                scheduleRender()
                onUi { setStatus(msg, R.color.bad) }
            }
        }
    }

    private fun scheduleRender() {
        if (renderQueued.compareAndSet(false, true)) {
            ui.postDelayed({
                renderQueued.set(false)
                if (!isDestroyed) render()
            }, 120)
        }
    }

    private fun render() {
        val sv = b.scroll
        val child = sv.getChildAt(0)
        val nearBottom = child == null || child.bottom - (sv.height + sv.scrollY) < 300
        b.tvOut.text = term.text()
        if (nearBottom) {
            sv.post { sv.scrollTo(0, sv.getChildAt(0)?.height ?: 0) }
        }
    }

    private fun setStatus(text: String, colorRes: Int) {
        b.tvConn.text = text
        b.tvConn.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun send(text: String) {
        if (shellOut == null) {
            onUi { Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show() }
            return
        }
        try {
            io.execute {
                try {
                    shellOut?.let {
                        it.write(text.toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                } catch (e: Exception) {
                    onUi {
                        Toast.makeText(this, getString(R.string.send_failed, e.message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Activity sudah ditutup.
        }
    }

    private fun sendInput() {
        val text = b.etInput.text.toString()
        b.etInput.setText("")
        send(text + "\n")
        if (secretMode) toggleSecret()
    }

    private fun toggleSecret() {
        secretMode = !secretMode
        b.etInput.inputType = if (secretMode) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        b.etInput.setSelection(b.etInput.text.length)
        b.btnSecret.alpha = if (secretMode) 1f else 0.5f
    }

    private fun copyOutput() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", term.text()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun buildKeys() {
        for ((label, sequence) in keys) {
            val btn = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            btn.text = label
            btn.setAllCaps(false)
            btn.textSize = 12f
            btn.minWidth = 0
            btn.minimumWidth = 0
            btn.insetTop = 0
            btn.insetBottom = 0
            btn.setOnClickListener { send(sequence) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            lp.marginEnd = dp(6)
            b.keys.addView(btn, lp)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startKeepAlive() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        } catch (e: Exception) {
            // Layanan latar gagal dimulai; sesi tetap jalan selama aplikasi terbuka.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closing = true
        ui.removeCallbacksAndMessages(null)
        val ch = channel
        val s = session
        thread(name = "ssh-close") {
            try {
                ch?.disconnect()
                s?.disconnect()
            } catch (_: Exception) {
            }
        }
        stopService(Intent(this, KeepAliveService::class.java))
        io.shutdown()
    }

    companion object {
        const val EXTRA_ID = "server_id"
        const val EXTRA_COMMAND = "command"
    }
}
