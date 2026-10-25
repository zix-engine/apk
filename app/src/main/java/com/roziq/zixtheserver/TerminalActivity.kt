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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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

class TerminalActivity : BaseActivity() {

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
        "Ctrl+Z" to "\u001a",
        "Ctrl+L" to "\u000c",
        "Tab" to "\t",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "←" to "\u001b[D",
        "→" to "\u001b[C",
        "Esc" to "\u001b",
        "Home" to "\u001b[H",
        "End" to "\u001b[F",
        "|" to "|",
        "/" to "/",
        "-" to "-",
        "~" to "~",
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
        if (prefs.keepScreen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFont()
        setStatus(getString(R.string.term_connecting, server.name), R.attr.zTextDim)

        buildKeys()
        b.btnSend.setOnClickListener {
            haptic(it)
            sendInput()
        }
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
        b.btnSecret.setOnClickListener {
            haptic(it)
            toggleSecret()
        }
        b.btnClear.setOnClickListener {
            term.clear()
            render()
        }
        b.btnCopy.setOnClickListener { copyOutput() }
        b.btnMore.setOnClickListener { showMore(it) }
        b.btnReconnect.setOnClickListener { reconnect() }
        b.btnBottom.setOnClickListener {
            haptic(it)
            scrollToBottom()
        }
        b.scroll.viewTreeObserver.addOnScrollChangedListener { updateBottomButton() }

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

    private fun applyFont() {
        b.tvOut.textSize = prefs.termFont.toFloat()
    }

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
                    b.btnReconnect.visibility = View.GONE
                    setStatus(
                        getString(R.string.term_connected, current.username, current.host),
                        R.attr.zOk
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
                    shellOut = null
                    onUi {
                        setStatus(getString(R.string.term_closed), R.attr.zBad)
                        b.btnReconnect.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                val msg = Ssh.friendlyError(e)
                term.feed("\n[Gagal: $msg]\n")
                scheduleRender()
                shellOut = null
                onUi {
                    setStatus(msg, R.attr.zBad)
                    b.btnReconnect.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun reconnect() {
        if (channel?.isConnected == true) return
        val oldCh = channel
        val oldSession = session
        channel = null
        session = null
        shellOut = null
        thread(name = "ssh-close-old") {
            try {
                oldCh?.disconnect()
                oldSession?.disconnect()
            } catch (_: Exception) {
            }
        }
        b.btnReconnect.visibility = View.GONE
        term.feed("\n[Menyambung ulang…]\n")
        render()
        setStatus(getString(R.string.term_connecting, server.name), R.attr.zTextDim)
        connect(null)
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

    private fun scrollToBottom() {
        val sv = b.scroll
        sv.post { sv.smoothScrollTo(0, sv.getChildAt(0)?.height ?: 0) }
    }

    private fun updateBottomButton() {
        val sv = b.scroll
        val child = sv.getChildAt(0) ?: return
        val far = child.bottom - (sv.height + sv.scrollY) > 600
        b.btnBottom.visibility = if (far) View.VISIBLE else View.GONE
    }

    private fun setStatus(text: String, colorAttr: Int) {
        b.tvConn.text = text
        b.tvConn.setTextColor(zColor(colorAttr))
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
        // Input rahasia (password) tidak pernah masuk riwayat.
        if (!secretMode && text.length >= 2) prefs.addHistory(text)
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

    private fun showMore(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.term_history))
        popup.menu.add(0, 2, 1, getString(R.string.term_share))
        popup.menu.add(0, 3, 2, getString(R.string.term_font_up))
        popup.menu.add(0, 4, 3, getString(R.string.term_font_down))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showHistory()
                2 -> shareOutput()
                3 -> {
                    prefs.termFont = prefs.termFont + 1
                    applyFont()
                }
                4 -> {
                    prefs.termFont = prefs.termFont - 1
                    applyFont()
                }
            }
            true
        }
        popup.show()
    }

    private fun showHistory() {
        val list = prefs.history()
        if (list.isEmpty()) {
            toast(R.string.history_empty)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.term_history)
            .setItems(list.toTypedArray()) { _, which ->
                b.etInput.setText(list[which])
                b.etInput.setSelection(b.etInput.text.length)
                b.etInput.requestFocus()
            }
            .setNeutralButton(R.string.history_clear) { _, _ -> prefs.clearHistory() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareOutput() {
        val text = term.text()
        if (text.isBlank()) {
            toast(R.string.output_empty)
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, server.name)
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.term_share)))
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
            btn.setOnClickListener {
                haptic(it)
                send(sequence)
            }
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
