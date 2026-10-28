package com.roziq.zixtheserver

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import java.util.concurrent.Executors

/** Menjalankan satu perintah ke beberapa server sekaligus dan menampilkan hasil tiap server. */
class BroadcastActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var store: ServerStore
    private lateinit var adapter: SimpleToolAdapter
    private val pool = Executors.newFixedThreadPool(4)

    private var servers: List<Server> = emptyList()
    private var command = ""
    private val status = HashMap<String, String>()   // id -> "run" | "ok" | "fail"
    private val output = HashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerStore(this)
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = getString(R.string.broadcast_title)
        b.tvSub.text = getString(R.string.broadcast_sub)
        b.etSearch.visibility = View.GONE
        b.btnTop.visibility = View.GONE
        b.fabAdd.visibility = View.VISIBLE
        b.fabAdd.text = getString(R.string.broadcast_new)
        adapter = SimpleToolAdapter(::onItem)
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.btnReload.setOnClickListener { if (servers.isNotEmpty()) runAll() }
        b.fabAdd.setOnClickListener { pickServers() }
        pickServers()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun pickServers() {
        val all = store.list()
        if (all.isEmpty()) {
            toast(R.string.empty_servers)
            return
        }
        val checked = BooleanArray(all.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.broadcast_pick)
            .setMultiChoiceItems(all.map { it.name }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton(R.string.continue_) { _, _ ->
                servers = all.filterIndexed { i, _ -> checked[i] }
                if (servers.isEmpty()) toast(R.string.broadcast_none) else askCommand()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askCommand() {
        val input = EditText(this).apply {
            hint = getString(R.string.broadcast_hint)
            minLines = 2
            setText(command)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val holder = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.broadcast_to, servers.size))
            .setView(holder)
            .setPositiveButton(R.string.run) { _, _ ->
                val c = input.text.toString().trim()
                if (c.isNotEmpty()) {
                    command = c
                    runAll()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun render() {
        adapter.set(
            servers.map { s ->
                val st = status[s.id] ?: "run"
                val out = output[s.id].orEmpty()
                Tools.Item(
                    s.name,
                    if (st == "run") getString(R.string.broadcast_running) else out.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    when (st) { "ok" -> "✓"; "fail" -> "✗"; else -> "…" },
                    s.id,
                    when (st) { "ok" -> 1; "fail" -> 3; else -> 0 }
                )
            }
        )
    }

    private fun runAll() {
        status.clear()
        output.clear()
        render()
        b.busy.visibility = View.VISIBLE
        var left = servers.size
        for (s in servers) {
            try {
                pool.execute {
                    val r = ScheduleWorker.runOnce(store, s, command)
                    onUi {
                        status[s.id] = r.first
                        output[s.id] = r.second
                        render()
                        left--
                        if (left <= 0) b.busy.visibility = View.INVISIBLE
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                return
            }
        }
    }

    private fun onItem(pos: Int) {
        val s = servers.getOrNull(pos) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(s.name)
            .setMessage(output[s.id].orEmpty().take(3000).ifBlank { getString(R.string.broadcast_running) })
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
