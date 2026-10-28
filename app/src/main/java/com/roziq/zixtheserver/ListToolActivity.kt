package com.roziq.zixtheserver

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.jcraft.jsch.Session
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import com.roziq.zixtheserver.databinding.ItemToolBinding
import java.util.concurrent.Executors

/** Layar generik untuk alat server: proses, layanan, docker, firewall, cron, update, log, login gagal. */
class ListToolActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var store: ServerStore
    private lateinit var server: Server
    private lateinit var spec: Tools.Spec
    private lateinit var adapter: ToolAdapter

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var session: Session? = null
    private var all: List<Tools.Item> = emptyList()
    private var query = ""
    private var firstList = true
    private var listCmd = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerStore(this)
        val found = intent.getStringExtra(EXTRA_ID)?.let { store.get(it) }
        val sp = intent.getStringExtra(EXTRA_TOOL)?.let { Tools.byId(it) }
        if (found == null || sp == null) {
            finish()
            return
        }
        server = found
        spec = sp
        listCmd = spec.listCmd

        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = spec.title
        b.tvSub.text = server.name
        adapter = ToolAdapter(::onItem)
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.rvItems.layoutAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_down)

        b.btnBack.setOnClickListener { finish() }
        b.btnReload.setOnClickListener { load() }
        b.btnTop.setOnClickListener { showTopMenu() }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bb: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })
        if (spec.addLabel != null) {
            b.fabAdd.visibility = View.VISIBLE
            b.fabAdd.text = spec.addLabel
            b.fabAdd.setOnClickListener { askAdd() }
        }
        load()
    }

    override fun onDestroy() {
        val s = session
        Thread {
            try {
                s?.let { Ssh.disconnect(it) }
            } catch (_: Exception) {
            }
        }.start()
        io.shutdown()
        super.onDestroy()
    }

    private fun conn(): Session {
        session?.let { if (it.isConnected) return it }
        val s = store.open(server)
        session = s
        return s
    }

    private fun run(command: String, onDone: (Ssh.ExecResult) -> Unit) {
        b.busy.visibility = View.VISIBLE
        try {
            io.execute {
                try {
                    val r = Ssh.execCommand(conn(), command)
                    onUi {
                        b.busy.visibility = View.INVISIBLE
                        onDone(r)
                    }
                } catch (e: Exception) {
                    val msg = Ssh.friendlyError(e)
                    onUi {
                        b.busy.visibility = View.INVISIBLE
                        b.tvMsg.text = msg
                        b.tvMsg.visibility = View.VISIBLE
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun load() {
        b.tvMsg.visibility = View.GONE
        run(listCmd) { r ->
            all = try {
                spec.parse(r.output)
            } catch (e: Exception) {
                emptyList()
            }
            if (all.isEmpty() && r.exit != 0) {
                b.tvMsg.text = r.output.trim().take(400).ifBlank { getString(R.string.tool_failed) }
                b.tvMsg.visibility = View.VISIBLE
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val q = query.lowercase()
        val list = if (q.isEmpty()) all else all.filter {
            "${it.title} ${it.sub} ${it.tag}".lowercase().contains(q)
        }
        adapter.submitList(list) {
            if (firstList && list.isNotEmpty()) {
                firstList = false
                b.rvItems.scheduleLayoutAnimation()
            }
        }
        if (list.isEmpty() && b.tvMsg.visibility != View.VISIBLE) {
            b.tvMsg.setText(R.string.tool_empty)
            b.tvMsg.visibility = View.VISIBLE
        } else if (list.isNotEmpty()) {
            b.tvMsg.visibility = View.GONE
        }
    }

    private fun onItem(item: Tools.Item) {
        val acts = spec.acts(item)
        if (acts.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title.take(60))
            .setItems(acts.map { it.label }.toTypedArray()) { _, which -> execute(acts[which]) }
            .show()
    }

    private fun showTopMenu() {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (spec.sources.isNotEmpty()) {
            labels.add(getString(R.string.tool_source))
            actions.add { chooseSource() }
        }
        for (a in spec.top) {
            labels.add(a.label)
            actions.add { execute(a) }
        }
        if (labels.isEmpty()) {
            toast(R.string.tool_no_menu)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun chooseSource() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tool_source)
            .setItems(spec.sources.map { it.first }.toTypedArray()) { _, which ->
                listCmd = spec.sources[which].second
                b.tvSub.text = server.name + " • " + spec.sources[which].first
                load()
            }
            .show()
    }

    private fun execute(a: Tools.Act) {
        val go = {
            if (a.terminal) {
                startActivity(
                    Intent(this, TerminalActivity::class.java)
                        .putExtra(TerminalActivity.EXTRA_ID, server.id)
                        .putExtra(TerminalActivity.EXTRA_COMMAND, a.command)
                )
            } else {
                run(a.command) { r ->
                    if (r.exit == 0) {
                        toast(R.string.tool_done)
                    } else {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(a.label)
                            .setMessage(r.output.trim().take(1500).ifBlank { getString(R.string.tool_failed) })
                            .setPositiveButton(R.string.close, null)
                            .show()
                    }
                    load()
                }
            }
        }
        if (a.confirm) {
            MaterialAlertDialogBuilder(this)
                .setTitle(a.label)
                .setMessage(getString(R.string.tool_confirm, a.command))
                .setPositiveButton(R.string.run) { _, _ -> go() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            go()
        }
    }

    private fun askAdd() {
        val input = EditText(this).apply {
            hint = spec.addHint
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
            .setTitle(spec.addLabel)
            .setView(holder)
            .setPositiveButton(R.string.run) { _, _ ->
                val v = input.text.toString().trim()
                val make = spec.addCmd
                if (v.isNotEmpty() && make != null) {
                    execute(Tools.Act(spec.addLabel ?: "", make(v)))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    inner class ToolAdapter(private val onClick: (Tools.Item) -> Unit) :
        ListAdapter<Tools.Item, ToolAdapter.VH>(DIFF) {

        inner class VH(val v: ItemToolBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val it = getItem(position)
            holder.v.tvTitle.text = it.title
            holder.v.tvSub.text = it.sub
            holder.v.tvSub.visibility = if (it.sub.isEmpty()) View.GONE else View.VISIBLE
            holder.v.tvTag.text = it.tag
            val color = when (it.level) {
                1 -> zColor(R.attr.zOk)
                2 -> zColor(R.attr.zWarn)
                3 -> zColor(R.attr.zBad)
                else -> zColor(R.attr.zTextDim)
            }
            holder.v.tvTag.setTextColor(color)
            holder.v.tvTitle.setTextColor(
                if (it.level >= 2 && it.tag.isEmpty()) color else zColor(R.attr.zText)
            )
            holder.v.root.setOnClickListener { _ -> onClick(it) }
        }
    }

    companion object {
        const val EXTRA_ID = "server_id"
        const val EXTRA_TOOL = "tool"
        private val DIFF = object : DiffUtil.ItemCallback<Tools.Item>() {
            override fun areItemsTheSame(a: Tools.Item, b: Tools.Item) = a.key == b.key && a.title == b.title
            override fun areContentsTheSame(a: Tools.Item, b: Tools.Item) = a == b
        }
    }
}
