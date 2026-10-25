package com.roziq.zixtheserver

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityServerBinding
import com.roziq.zixtheserver.databinding.ItemActionBinding
import com.roziq.zixtheserver.databinding.ItemHeaderBinding
import kotlin.concurrent.thread

class ServerActivity : AppCompatActivity() {

    private lateinit var b: ActivityServerBinding
    private lateinit var store: ServerStore
    private lateinit var server: Server

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerStore(this)
        val found = intent.getStringExtra(EXTRA_ID)?.let { store.get(it) }
        if (found == null) {
            finish()
            return
        }
        server = found

        b = ActivityServerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvTitle.text = server.name
        b.tvSub.text = "${server.username}@${server.host}:${server.port}"
        b.tvRootWarn.visibility = if (server.username == "root") View.GONE else View.VISIBLE

        val items: List<Any> = Actions.sections.flatMap { listOf<Any>(it.title) + it.actions }
        b.rvActions.layoutManager = LinearLayoutManager(this)
        b.rvActions.isNestedScrollingEnabled = false
        b.rvActions.adapter = ActionAdapter(items, ::onAction)

        b.btnTerminal.setOnClickListener { openTerminal(null) }
        b.btnRefresh.setOnClickListener { loadStats() }

        loadStats()
    }

    private fun onAction(action: Action) {
        val command = Actions.resolve(action.command, server)
        if (!action.confirm) {
            openTerminal(command)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(action.title)
            .setMessage(getString(R.string.confirm_run, Actions.resolve(action.desc, server), command))
            .setPositiveButton(R.string.run) { _, _ -> openTerminal(command) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openTerminal(command: String?) {
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_ID, server.id)
                .putExtra(TerminalActivity.EXTRA_COMMAND, command)
        )
    }

    private fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    private fun loadStats() {
        store.get(server.id)?.let { server = it }
        b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        b.tvStatus.text = getString(R.string.connecting)
        b.tvServices.text = ""

        val current = server
        thread(name = "ssh-stats") {
            try {
                val session = Ssh.connect(current) { fp ->
                    store.save(current.copy(hostKey = fp))
                }
                val script = assets.open("stats.sh").bufferedReader().use { it.readText() }
                val result = try {
                    Ssh.execScript(session, script)
                } finally {
                    session.disconnect()
                }
                val map = linkedMapOf<String, String>()
                result.lineSequence().forEach { line ->
                    val i = line.indexOf('=')
                    if (i > 0) map[line.substring(0, i)] = line.substring(i + 1).trim()
                }
                onUi { showStats(map) }
            } catch (e: Exception) {
                onUi { showError(Ssh.friendlyError(e)) }
            }
        }
    }

    private fun showError(message: String) {
        b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.bad))
        b.tvStatus.text = message
        b.tvServices.text = ""
    }

    private fun showStats(m: Map<String, String>) {
        fun v(key: String) = m[key].orEmpty().ifBlank { "-" }

        b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text))
        b.tvStatus.text = buildString {
            append("Host    : ").append(v("HOST")).append("  (").append(v("IP")).append(")\n")
            append("OS      : ").append(v("OS")).append('\n')
            append("Kernel  : ").append(v("KERNEL")).append('\n')
            append("Uptime  : ").append(v("UPTIME")).append('\n')
            append("CPU     : load ").append(v("LOAD")).append("  (").append(v("CPUS")).append(" core)\n")
            append("RAM     : ").append(v("MEM")).append('\n')
            append("Disk /  : ").append(v("DISK"))
        }

        val services = m.filterKeys { it.startsWith("SVC_") }
        if (services.isEmpty()) {
            b.tvServices.text = getString(R.string.no_services)
            return
        }
        val ok = ContextCompat.getColor(this, R.color.ok)
        val bad = ContextCompat.getColor(this, R.color.bad)
        val warn = ContextCompat.getColor(this, R.color.accent)
        val sb = SpannableStringBuilder()
        services.forEach { (key, state) ->
            val start = sb.length
            sb.append("● ").append(key.removePrefix("SVC_")).append("   ")
            val color = when (state) {
                "active" -> ok
                "failed" -> bad
                else -> warn
            }
            sb.setSpan(ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        b.tvServices.text = sb
    }

    companion object {
        const val EXTRA_ID = "server_id"
    }
}

class ActionAdapter(
    private val items: List<Any>,
    private val onClick: (Action) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class HeaderVH(val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class ActionVH(val b: ItemActionBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = if (items[position] is String) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderVH(ItemHeaderBinding.inflate(inflater, parent, false))
        } else {
            ActionVH(ItemActionBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderVH -> holder.b.tvHeader.text = items[position] as String
            is ActionVH -> {
                val a = items[position] as Action
                holder.b.tvTitle.text = if (a.confirm) "⚠  ${a.title}" else a.title
                holder.b.tvDesc.text = a.desc
                holder.b.root.setOnClickListener { onClick(a) }
            }
        }
    }
}
