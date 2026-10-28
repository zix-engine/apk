package com.roziq.zixtheserver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Spanned
import android.text.style.BackgroundColorSpan
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
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.roziq.zixtheserver.databinding.ActivityTerminalBinding
import java.util.concurrent.atomic.AtomicBoolean

class TerminalActivity : BaseActivity(), TermSession.Listener {

    private lateinit var b: ActivityTerminalBinding
    private lateinit var store: ServerStore
    private lateinit var server: Server

    private val sessions = ArrayList<TermSession>()
    private var cur = 0
    @Volatile private var shown: TermSession? = null
    private var nextIndex = 1
    private var light = false
    private var keepAliveStarted = false

    private val ui = Handler(Looper.getMainLooper())
    private val renderQueued = AtomicBoolean(false)
    private var secretMode = false
    private var searchQuery = ""
    private var searchFrom = 0

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
        val scheme = TermSchemes.byId(prefs.termScheme)
        if (scheme.id == "theme") {
            light = ColorUtils.calculateLuminance(zColor(R.attr.zTermBg)) > 0.5
        } else {
            light = ColorUtils.calculateLuminance(scheme.bg) > 0.5
            b.root.setBackgroundColor(scheme.bg)
            b.tvOut.setTextColor(scheme.fg)
            b.etInput.setTextColor(scheme.fg)
        }
        applyFont()

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
            shown?.buffer?.clear()
            render()
        }
        b.btnCopy.setOnClickListener { copyOutput() }
        b.btnMore.setOnClickListener { showMore(it) }
        b.btnReconnect.setOnClickListener { shown?.reconnect() }
        b.btnNewTab.setOnClickListener {
            haptic(it)
            addSession(null)
        }
        b.btnBottom.setOnClickListener {
            haptic(it)
            scrollToBottom()
        }
        b.scroll.viewTreeObserver.addOnScrollChangedListener { updateBottomButton() }

        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                cur = tab.position
                shown = sessions.getOrNull(cur)
                showStatus()
                render(forceBottom = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sessions.any { it.isConnected }) {
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

        addSession(intent.getStringExtra(EXTRA_COMMAND))
    }

    // ------------------------------------------------------------ Tab

    private fun addSession(initialCommand: String?) {
        val s = TermSession(server, store, light, nextIndex++)
        s.listener = this
        sessions.add(s)
        b.tabs.addTab(b.tabs.newTab().setText(tabTitle(s)), true)
        s.connect(initialCommand)
    }

    private fun tabTitle(s: TermSession): String {
        val mark = if (s.state == TermSession.CONNECTED) "●" else "○"
        return "$mark ${s.index}"
    }

    private fun updateTabs() {
        sessions.forEachIndexed { i, s -> b.tabs.getTabAt(i)?.setText(tabTitle(s)) }
    }

    private fun closeCurrentTab() {
        if (sessions.size <= 1) {
            finish()
            return
        }
        val i = cur
        val s = sessions.removeAt(i)
        s.close()
        b.tabs.removeTabAt(i)
    }

    // ------------------------------------------------------------ Listener (thread latar)

    override fun onOutput(s: TermSession) {
        if (s === shown) scheduleRender()
    }

    override fun onState(s: TermSession) {
        onUi {
            updateTabs()
            if (s === shown) showStatus()
            if (s.state == TermSession.CONNECTED) startKeepAlive()
        }
    }

    override fun onError(s: TermSession, message: String) {
        onUi {
            if (message == "not_connected") {
                Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.send_failed, message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------ Tampilan

    private fun applyFont() {
        b.tvOut.textSize = prefs.termFont.toFloat()
    }

    private fun showStatus() {
        val s = shown ?: return
        when (s.state) {
            TermSession.CONNECTING -> {
                setStatus(getString(R.string.term_connecting, server.name), R.attr.zTextDim)
                b.btnReconnect.visibility = View.GONE
            }
            TermSession.CONNECTED -> {
                setStatus(getString(R.string.term_connected, server.username, server.host), R.attr.zOk)
                b.btnReconnect.visibility = View.GONE
            }
            TermSession.CLOSED -> {
                setStatus(getString(R.string.term_closed), R.attr.zBad)
                b.btnReconnect.visibility = View.VISIBLE
            }
            else -> {
                setStatus(s.message, R.attr.zBad)
                b.btnReconnect.visibility = View.VISIBLE
            }
        }
    }

    private fun setStatus(text: String, colorAttr: Int) {
        b.tvConn.text = text
        b.tvConn.setTextColor(zColor(colorAttr))
    }

    private fun scheduleRender() {
        if (renderQueued.compareAndSet(false, true)) {
            ui.postDelayed({
                renderQueued.set(false)
                if (!isDestroyed) render()
            }, 120)
        }
    }

    private fun render(forceBottom: Boolean = false, noScroll: Boolean = false) {
        val s = shown ?: return
        val sv = b.scroll
        val child = sv.getChildAt(0)
        val nearBottom = forceBottom || child == null || child.bottom - (sv.height + sv.scrollY) < 300
        val sp = s.buffer.spannable()
        if (searchQuery.isNotEmpty()) {
            val low = sp.toString().lowercase()
            var i = low.indexOf(searchQuery)
            var n = 0
            while (i >= 0 && n < 500) {
                sp.setSpan(BackgroundColorSpan(0x88FACC15.toInt()), i, i + searchQuery.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = low.indexOf(searchQuery, i + searchQuery.length)
                n++
            }
        }
        b.tvOut.text = sp
        if (nearBottom && !noScroll) {
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

    // ------------------------------------------------------------ Input

    private fun sendInput() {
        val text = b.etInput.text.toString()
        b.etInput.setText("")
        // Input rahasia (password) tidak pernah masuk riwayat.
        if (!secretMode && text.length >= 2) prefs.addHistory(text)
        shown?.send(text + "\n")
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
        cm.setPrimaryClip(ClipData.newPlainText("terminal", shown?.buffer?.text().orEmpty()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun showMore(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.term_history))
        popup.menu.add(0, 2, 1, getString(R.string.term_share))
        popup.menu.add(0, 3, 2, getString(R.string.term_font_up))
        popup.menu.add(0, 4, 3, getString(R.string.term_font_down))
        popup.menu.add(0, 5, 4, getString(R.string.tab_close))
        popup.menu.add(0, 6, 5, getString(R.string.term_search))
        if (searchQuery.isNotEmpty()) {
            popup.menu.add(0, 7, 6, getString(R.string.term_search_next))
            popup.menu.add(0, 8, 7, getString(R.string.term_search_clear))
        }
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
                5 -> closeCurrentTab()
                6 -> askSearch()
                7 -> findNext()
                8 -> {
                    searchQuery = ""
                    render(noScroll = true)
                }
            }
            true
        }
        popup.show()
    }

    private fun askSearch() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.term_search_hint)
            setSingleLine(true)
            setText(searchQuery)
            setTextColor(zColor(R.attr.zText))
            setHintTextColor(zColor(R.attr.zTextDim))
        }
        val holder = android.widget.FrameLayout(this).apply {
            val pad = dp(24)
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.term_search)
            .setView(holder)
            .setPositiveButton(R.string.term_search) { _, _ ->
                searchQuery = input.text.toString().lowercase()
                searchFrom = 0
                if (searchQuery.isNotEmpty()) findNext() else render(noScroll = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun findNext() {
        val text = shown?.buffer?.text()?.lowercase().orEmpty()
        if (searchQuery.isEmpty() || text.isEmpty()) return
        var idx = text.indexOf(searchQuery, searchFrom)
        if (idx < 0) idx = text.indexOf(searchQuery, 0)
        if (idx < 0) {
            toast(R.string.term_search_none)
            render(noScroll = true)
            return
        }
        searchFrom = idx + searchQuery.length
        render(noScroll = true)
        b.tvOut.postDelayed({
            val layout = b.tvOut.layout
            if (layout != null && idx < layout.text.length) {
                val line = layout.getLineForOffset(idx)
                b.scroll.smoothScrollTo(0, (layout.getLineTop(line) - dp(60)).coerceAtLeast(0))
            }
        }, 80)
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
        val text = shown?.buffer?.text().orEmpty()
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
                shown?.send(sequence)
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36))
            lp.marginEnd = dp(6)
            b.keys.addView(btn, lp)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startKeepAlive() {
        if (keepAliveStarted) return
        keepAliveStarted = true
        try {
            ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        } catch (e: Exception) {
            // Layanan latar gagal dimulai; sesi tetap jalan selama aplikasi terbuka.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        sessions.forEach { it.close() }
        sessions.clear()
        stopService(Intent(this, KeepAliveService::class.java))
    }

    companion object {
        const val EXTRA_ID = "server_id"
        const val EXTRA_COMMAND = "command"
    }
}
