package com.roziq.zixtheserver

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.ColorUtils
import com.roziq.zixtheserver.databinding.ActivityConsoleBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Konsol live game server Pterodactyl lewat WebSocket Wings. */
class PteroConsoleActivity : BaseActivity() {

    private lateinit var b: ActivityConsoleBinding
    private lateinit var server: Server
    private lateinit var pid: String
    private lateinit var buffer: TerminalBuffer

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closing = false
    private val ui = Handler(Looper.getMainLooper())
    private val renderQueued = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = intent.getStringExtra(EXTRA_ID)?.let { ServerStore(this).get(it) }
        val id = intent.getStringExtra(EXTRA_PID)
        if (found == null || id == null) {
            finish()
            return
        }
        server = found
        pid = id
        b = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(b.root)
        buffer = TerminalBuffer(light = ColorUtils.calculateLuminance(zColor(R.attr.zTermBg)) > 0.5)
        b.tvTitle.text = intent.getStringExtra(EXTRA_NAME) ?: pid
        b.tvOut.textSize = prefs.termFont.toFloat()
        b.btnBack.setOnClickListener { finish() }
        b.btnSend.setOnClickListener { sendCommand() }
        b.etInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                sendCommand()
                true
            } else {
                false
            }
        }
        connect()
    }

    override fun onDestroy() {
        closing = true
        ui.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private fun setStatus(text: String, attr: Int) {
        onUi {
            b.tvStatus.text = text
            b.tvStatus.setTextColor(zColor(attr))
        }
    }

    private fun connect() {
        setStatus(getString(R.string.console_connecting), R.attr.zTextDim)
        thread(name = "ptero-ws") {
            try {
                val (token, socket) = PteroApi.websocket(server, pid)
                val req = Request.Builder()
                    .url(socket)
                    .header("Origin", PteroApi.baseUrl(server))
                    .build()
                ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(msg("auth", token))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handle(webSocket, text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!closing) setStatus(getString(R.string.console_failed, t.message ?: ""), R.attr.zBad)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!closing) setStatus(getString(R.string.console_closed), R.attr.zBad)
                    }
                })
            } catch (e: Exception) {
                setStatus(getString(R.string.console_failed, e.message ?: ""), R.attr.zBad)
            }
        }
    }

    private fun msg(event: String, vararg args: Any?): String {
        val arr = JSONArray()
        for (a in args) arr.put(a ?: JSONObject.NULL)
        return JSONObject().put("event", event).put("args", arr).toString()
    }

    private fun handle(socket: WebSocket, text: String) {
        try {
            val o = JSONObject(text)
            val args = o.optJSONArray("args")
            val first = args?.optString(0).orEmpty()
            when (o.optString("event")) {
                "auth success" -> {
                    setStatus(getString(R.string.console_live), R.attr.zOk)
                    socket.send(msg("send logs", null))
                }
                "console output", "install output", "daemon message" -> append(first)
                "status" -> setStatus(getString(R.string.console_state, first), R.attr.zOk)
                "token expiring", "token expired" -> refreshToken(socket)
                "jwt error" -> setStatus(getString(R.string.console_failed, first), R.attr.zBad)
            }
        } catch (_: Exception) {
        }
    }

    private fun refreshToken(socket: WebSocket) {
        thread(name = "ptero-token") {
            try {
                socket.send(msg("auth", PteroApi.websocket(server, pid).first))
            } catch (_: Exception) {
            }
        }
    }

    private fun append(line: String) {
        buffer.feed(line + "\n")
        if (renderQueued.compareAndSet(false, true)) {
            ui.postDelayed({
                renderQueued.set(false)
                if (isDestroyed) return@postDelayed
                val sv = b.scroll
                val child = sv.getChildAt(0)
                val near = child == null || child.bottom - (sv.height + sv.scrollY) < 300
                b.tvOut.text = buffer.spannable()
                if (near) sv.post { sv.scrollTo(0, sv.getChildAt(0)?.height ?: 0) }
            }, 120)
        }
    }

    private fun sendCommand() {
        val cmd = b.etInput.text.toString().trim()
        if (cmd.isEmpty()) return
        b.etInput.setText("")
        if (ws?.send(msg("send command", cmd)) != true) toast(R.string.not_connected)
    }

    companion object {
        const val EXTRA_ID = "server_id"
        const val EXTRA_PID = "pid"
        const val EXTRA_NAME = "name"
    }
}
