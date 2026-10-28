package com.roziq.zixtheserver

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityPteroBinding
import com.roziq.zixtheserver.databinding.ItemPteroBinding
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Kontrol game server di Panel Pterodactyl lewat Client API: start/stop/restart/kill + kirim perintah. */
class PteroActivity : BaseActivity() {

    private lateinit var b: ActivityPteroBinding
    private lateinit var server: Server
    private lateinit var adapter: PAdapter

    private val pool = Executors.newFixedThreadPool(4)
    private val ui = Handler(Looper.getMainLooper())
    private var servers: List<PteroApi.PServer> = emptyList()
    private var first = true

    private val tick = object : Runnable {
        override fun run() {
            refreshResources()
            ui.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = intent.getStringExtra(EXTRA_ID)?.let { ServerStore(this).get(it) }
        if (found == null) {
            finish()
            return
        }
        server = found
        b = ActivityPteroBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvSub.text = server.panelUrl
        adapter = PAdapter()
        b.rvServers.layoutManager = LinearLayoutManager(this)
        b.rvServers.adapter = adapter
        b.rvServers.layoutAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_down)

        b.btnBack.setOnClickListener { finish() }
        b.btnReload.setOnClickListener { loadList() }
        loadList()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 10_000)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun loadList() {
        b.busy.visibility = View.VISIBLE
        b.tvMsg.visibility = View.GONE
        thread(name = "ptero-list") {
            try {
                val list = PteroApi.listServers(server)
                onUi {
                    b.busy.visibility = View.INVISIBLE
                    servers = list
                    adapter.submitList(list) {
                        if (first && list.isNotEmpty()) {
                            first = false
                            b.rvServers.scheduleLayoutAnimation()
                        }
                    }
                    if (list.isEmpty()) {
                        b.tvMsg.setText(R.string.ptero_empty)
                        b.tvMsg.visibility = View.VISIBLE
                    }
                    refreshResources()
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                onUi {
                    b.busy.visibility = View.INVISIBLE
                    b.tvMsg.text = getString(R.string.ptero_error, msg)
                    b.tvMsg.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun refreshResources() {
        for (s in servers) {
            try {
                pool.execute {
                    try {
                        val r = PteroApi.resources(server, s.id)
                        onUi { adapter.setRes(s.id, r) }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                return
            }
        }
    }

    private fun power(s: PteroApi.PServer, signal: String) {
        toast(getString(R.string.ptero_sent, signal))
        thread(name = "ptero-power") {
            try {
                PteroApi.power(server, s.id, signal)
                Thread.sleep(2000)
                onUi { refreshResources() }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                onUi { toast(getString(R.string.ptero_error, msg)) }
            }
        }
    }

    private fun showMore(s: PteroApi.PServer) {
        val labels = arrayOf(
            getString(R.string.p_console),
            getString(R.string.p_cmd_send),
            getString(R.string.p_backups),
            getString(R.string.p_schedules),
            getString(R.string.p_edit_file)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(s.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, PteroConsoleActivity::class.java)
                            .putExtra(PteroConsoleActivity.EXTRA_ID, server.id)
                            .putExtra(PteroConsoleActivity.EXTRA_PID, s.id)
                            .putExtra(PteroConsoleActivity.EXTRA_NAME, s.name)
                    )
                    1 -> sendCommand(s)
                    2, 3 -> startActivity(
                        Intent(this, PteroListActivity::class.java)
                            .putExtra(PteroListActivity.EXTRA_ID, server.id)
                            .putExtra(PteroListActivity.EXTRA_PID, s.id)
                            .putExtra(PteroListActivity.EXTRA_NAME, s.name)
                            .putExtra(PteroListActivity.EXTRA_MODE, if (which == 2) "backups" else "schedules")
                    )
                    4 -> askFilePath(s)
                }
            }
            .show()
    }

    private fun askFilePath(s: PteroApi.PServer) {
        val input = EditText(this).apply {
            setText("/server.properties")
            setSingleLine(true)
            setTextColor(zColor(R.attr.zText))
        }
        val holder = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.p_edit_file)
            .setMessage(R.string.p_edit_file_hint)
            .setView(holder)
            .setPositiveButton(R.string.continue_) { _, _ ->
                val path = input.text.toString().trim().ifEmpty { "/server.properties" }
                thread(name = "ptero-read") {
                    try {
                        val text = PteroApi.readFile(server, s.id, path)
                        onUi { editFile(s, path, text) }
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        onUi { toast(getString(R.string.ptero_error, msg)) }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editFile(s: PteroApi.PServer, path: String, text: String) {
        if (text.length > 300_000) {
            toast(R.string.files_too_big)
            return
        }
        val input = EditText(this).apply {
            setText(text)
            setHorizontallyScrolling(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            gravity = android.view.Gravity.TOP
            minLines = 12
            setTextColor(zColor(R.attr.zText))
        }
        val holder = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(path)
            .setView(holder)
            .setPositiveButton(R.string.save) { _, _ ->
                val body = input.text.toString()
                thread(name = "ptero-write") {
                    try {
                        PteroApi.writeFile(server, s.id, path, body)
                        onUi { toast(R.string.files_saved) }
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        onUi { toast(getString(R.string.ptero_error, msg)) }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmKill(s: PteroApi.PServer) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.p_kill)
            .setMessage(getString(R.string.ptero_kill_msg, s.name))
            .setPositiveButton(R.string.run) { _, _ -> power(s, "kill") }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sendCommand(s: PteroApi.PServer) {
        val input = EditText(this).apply {
            hint = getString(R.string.ptero_cmd_hint)
            setSingleLine(true)
            setTextColor(zColor(R.attr.zText))
            setHintTextColor(zColor(R.attr.zTextDim))
        }
        val holder = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ptero_cmd_title, s.name))
            .setView(holder)
            .setPositiveButton(R.string.send) { _, _ ->
                val cmd = input.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    thread(name = "ptero-cmd") {
                        try {
                            PteroApi.command(server, s.id, cmd)
                            onUi { toast(R.string.ptero_cmd_sent) }
                        } catch (e: Exception) {
                            val msg = e.message ?: ""
                            onUi { toast(getString(R.string.ptero_error, msg)) }
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    inner class PAdapter : ListAdapter<PteroApi.PServer, PAdapter.VH>(DIFF) {

        private val res = HashMap<String, PteroApi.Res>()

        inner class VH(val v: ItemPteroBinding) : RecyclerView.ViewHolder(v.root)

        private val cpuH = HashMap<String, ArrayList<Int>>()
        private val ramH = HashMap<String, ArrayList<Int>>()

        private fun push(map: HashMap<String, ArrayList<Int>>, id: String, v: Int) {
            val l = map.getOrPut(id) { ArrayList() }
            l.add(v.coerceIn(0, 100))
            while (l.size > SparkView.MAX_POINTS) l.removeAt(0)
        }

        fun setRes(id: String, r: PteroApi.Res) {
            res[id] = r
            val ps = currentList.firstOrNull { it.id == id }
            push(cpuH, id, r.cpu.toInt())
            push(ramH, id, if (ps != null && ps.memLimit > 0) (r.memBytes / 1048576 * 100 / ps.memLimit).toInt() else 0)
            val i = currentList.indexOfFirst { it.id == id }
            if (i >= 0) notifyItemChanged(i, "res")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemPteroBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = getItem(position)
            holder.v.tvName.text = s.name
            val mem = if (s.memLimit > 0) "${s.memLimit} MB" else "∞"
            val cpu = if (s.cpuLimit > 0) "${s.cpuLimit}%" else "∞"
            holder.v.tvInfo.text = getString(R.string.ptero_info, s.node, s.id, mem, cpu) +
                if (s.suspended) "  •  " + getString(R.string.ptero_suspended) else ""
            holder.v.btnStart.setOnClickListener { haptic(it); power(s, "start") }
            holder.v.btnRestart.setOnClickListener { haptic(it); power(s, "restart") }
            holder.v.btnStop.setOnClickListener { haptic(it); power(s, "stop") }
            holder.v.btnKill.setOnClickListener { haptic(it); confirmKill(s) }
            holder.v.btnCmd.setOnClickListener { haptic(it); showMore(s) }
            bindState(holder, s)
        }

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) bindState(holder, getItem(position)) else onBindViewHolder(holder, position)
        }

        private fun bindState(holder: VH, s: PteroApi.PServer) {
            val r = res[s.id]
            if (r == null) {
                holder.v.tvState.setTextColor(zColor(R.attr.zTextDim))
                holder.v.tvState.text = "● …"
                return
            }
            val color = when (r.state) {
                "running" -> zColor(R.attr.zOk)
                "starting", "stopping" -> zColor(R.attr.zWarn)
                else -> zColor(R.attr.zBad)
            }
            holder.v.spark.setData(cpuH[s.id] ?: emptyList(), ramH[s.id] ?: emptyList())
            holder.v.tvState.setTextColor(color)
            holder.v.tvState.text = "● ${r.state}   CPU %.1f%%   RAM %d MB   Disk %d MB".format(
                r.cpu, r.memBytes / 1048576, r.diskBytes / 1048576
            )
        }
    }

    companion object {
        const val EXTRA_ID = "server_id"
        private val DIFF = object : DiffUtil.ItemCallback<PteroApi.PServer>() {
            override fun areItemsTheSame(a: PteroApi.PServer, b: PteroApi.PServer) = a.id == b.id
            override fun areContentsTheSame(a: PteroApi.PServer, b: PteroApi.PServer) = a == b
        }
    }
}
