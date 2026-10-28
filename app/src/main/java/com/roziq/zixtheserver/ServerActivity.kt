package com.roziq.zixtheserver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityServerBinding
import com.roziq.zixtheserver.databinding.DialogSnippetBinding
import com.roziq.zixtheserver.databinding.ItemActionBinding
import com.roziq.zixtheserver.databinding.ItemGaugeBinding
import com.roziq.zixtheserver.databinding.ItemHeaderBinding
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

sealed class Row {
    data class Header(val key: String, val title: String, val count: Int, val collapsed: Boolean) : Row()
    data class Item(val action: Action, val fav: Boolean, val snippetId: String? = null) : Row()
    object AddSnippet : Row()
}

class ServerActivity : BaseActivity() {

    private lateinit var b: ActivityServerBinding
    private lateinit var store: ServerStore
    private lateinit var server: Server
    private lateinit var adapter: ActionAdapter

    private val ui = Handler(Looper.getMainLooper())
    private val loading = AtomicBoolean(false)
    private var query = ""
    private var statsShown = false

    private val tick = object : Runnable {
        override fun run() {
            if (prefs.autoRefresh) {
                loadStats(silent = true)
                ui.postDelayed(this, prefs.refreshSec * 1000L)
            }
        }
    }

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
        b.gCpu.tvLabel.text = "CPU"
        b.gRam.tvLabel.text = "RAM"
        b.gDisk.tvLabel.text = "DISK"
        bindFav()
        bindAuto()

        adapter = ActionAdapter(::onToggleHeader, ::onAction, ::onActionLong, ::showSnippetEditor)
        b.rvActions.layoutManager = LinearLayoutManager(this)
        b.rvActions.isNestedScrollingEnabled = false
        b.rvActions.itemAnimator = null
        b.rvActions.adapter = adapter
        b.rvActions.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_down)
        rebuild()
        b.rvActions.scheduleLayoutAnimation()

        b.btnBack.setOnClickListener { finish() }
        b.btnTerminal.setOnClickListener {
            haptic(it)
            openTerminal(null)
        }
        b.btnRefresh.setOnClickListener {
            haptic(it)
            loadStats(silent = false)
        }
        b.btnFav.setOnClickListener {
            haptic(it)
            server = server.copy(favorite = !server.favorite)
            store.save(server)
            bindFav()
        }
        b.btnTools.setOnClickListener {
            haptic(it)
            showTools()
        }
        b.btnFiles.setOnClickListener {
            haptic(it)
            startActivity(
                Intent(this, FilesActivity::class.java).putExtra(FilesActivity.EXTRA_ID, server.id)
            )
        }
        b.btnPtero.setOnClickListener {
            haptic(it)
            if (server.panelUrl.isBlank() || server.panelKey.isBlank()) {
                toast(R.string.ptero_not_set)
            } else {
                startActivity(
                    Intent(this, PteroActivity::class.java).putExtra(PteroActivity.EXTRA_ID, server.id)
                )
            }
        }
        b.btnAuto.setOnClickListener {
            haptic(it)
            prefs.autoRefresh = !prefs.autoRefresh
            bindAuto()
            restartTicker()
        }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b4: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                rebuild()
            }
        })

        loadStats(silent = false)
    }

    override fun onResume() {
        super.onResume()
        restartTicker()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun restartTicker() {
        ui.removeCallbacks(tick)
        if (prefs.autoRefresh) ui.postDelayed(tick, prefs.refreshSec * 1000L)
    }

    private fun bindFav() {
        b.btnFav.setImageResource(if (server.favorite) R.drawable.ic_star else R.drawable.ic_star_outline)
    }

    private fun bindAuto() {
        b.btnAuto.text = if (prefs.autoRefresh) {
            getString(R.string.auto_on, prefs.refreshSec)
        } else {
            getString(R.string.auto_off)
        }
    }

    // ------------------------------------------------------------ Daftar aksi

    private fun rebuild() {
        val q = query.lowercase()
        val searching = q.isNotEmpty()
        val fav = prefs.favActions()
        val collapsed = prefs.collapsed()
        val rows = mutableListOf<Row>()

        fun matches(a: Action) =
            !searching || "${a.title} ${a.desc} ${a.command}".lowercase().contains(q)

        fun addSection(key: String, title: String, items: List<Row.Item>, extra: Row? = null) {
            if (items.isEmpty() && extra == null) return
            val isCollapsed = !searching && collapsed.contains(key)
            rows.add(Row.Header(key, title, items.size, isCollapsed))
            if (!isCollapsed) {
                rows.addAll(items)
                if (extra != null) rows.add(extra)
            }
        }

        val builtIn = Actions.sections.flatMap { it.actions }
        val favItems = builtIn.filter { fav.contains(it.title) && matches(it) }
            .map { Row.Item(it, true) }
        addSection("★ Favorit", getString(R.string.sec_favorites), favItems)

        val snippetItems = prefs.snippets()
            .map { Row.Item(Action(it.title, it.command, it.command), false, it.id) }
            .filter { matches(it.action) }
        addSection(
            "Perintahku", getString(R.string.sec_mine), snippetItems,
            if (searching) null else Row.AddSnippet
        )

        for (sec in Actions.sections) {
            val items = sec.actions.filter { matches(it) }
                .map { Row.Item(it, fav.contains(it.title)) }
            addSection(sec.title, sec.title, items)
        }
        adapter.submitList(rows)
    }

    private fun onToggleHeader(key: String) {
        prefs.toggleCollapsed(key)
        rebuild()
    }

    private fun onAction(item: Row.Item) {
        val action = item.action
        if (item.snippetId != null) {
            askVars(action.command) { cmd -> runAction(action, cmd) }
        } else {
            runAction(action, action.command)
        }
    }

    /** Perintah buatan sendiri boleh memakai {variabel}; nilainya ditanyakan sebelum dijalankan. */
    private fun askVars(command: String, onReady: (String) -> Unit) {
        val names = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}").findAll(command)
            .map { it.groupValues[1] }.filter { it != "SSH_PORT" }.distinct().toList()
        if (names.isEmpty()) {
            onReady(command)
            return
        }
        val fields = names.map { n ->
            android.widget.EditText(this).apply {
                hint = n
                setSingleLine(true)
                setTextColor(zColor(R.attr.zText))
                setHintTextColor(zColor(R.attr.zTextDim))
            }
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            fields.forEach { addView(it) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vars_title)
            .setView(box)
            .setPositiveButton(R.string.run) { _, _ ->
                var cmd = command
                names.forEachIndexed { i, n ->
                    cmd = cmd.replace("{" + n + "}", fields[i].text.toString())
                }
                onReady(cmd)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runAction(action: Action, raw: String) {
        val command = Actions.resolve(raw, server)
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

    private fun showTools() {
        val labels = Tools.all.map { it.title + " — " + it.desc }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tools)
            .setItems(labels) { _, which ->
                startActivity(
                    Intent(this, ListToolActivity::class.java)
                        .putExtra(ListToolActivity.EXTRA_ID, server.id)
                        .putExtra(ListToolActivity.EXTRA_TOOL, Tools.all[which].id)
                )
            }
            .show()
    }

    private fun onActionLong(item: Row.Item) {
        val action = item.action
        if (item.snippetId != null) {
            val labels = arrayOf(
                getString(R.string.menu_edit),
                getString(R.string.menu_delete),
                getString(R.string.copy_command)
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(action.title)
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> prefs.snippets().firstOrNull { it.id == item.snippetId }
                            ?.let { showSnippetEditor(it) }
                        1 -> {
                            prefs.deleteSnippet(item.snippetId)
                            rebuild()
                        }
                        2 -> copy(action.command)
                    }
                }
                .show()
        } else {
            val labels = arrayOf(
                getString(if (item.fav) R.string.fav_remove else R.string.fav_add),
                getString(R.string.copy_command),
                getString(R.string.view_command)
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(action.title)
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> {
                            prefs.toggleFavAction(action.title)
                            rebuild()
                        }
                        1 -> copy(Actions.resolve(action.command, server))
                        2 -> MaterialAlertDialogBuilder(this)
                            .setTitle(action.title)
                            .setMessage(Actions.resolve(action.command, server))
                            .setPositiveButton(R.string.close, null)
                            .show()
                    }
                }
                .show()
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("command", text))
        toast(R.string.copied)
    }

    private fun showSnippetEditor(existing: Snippet?) {
        val d = DialogSnippetBinding.inflate(layoutInflater)
        if (existing != null) {
            d.etTitle.setText(existing.title)
            d.etCommand.setText(existing.command)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.snippet_add else R.string.menu_edit)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val title = d.etTitle.text.toString().trim()
                val cmd = d.etCommand.text.toString().trim()
                d.tilTitle.error = if (title.isEmpty()) getString(R.string.required) else null
                d.tilCommand.error = if (cmd.isEmpty()) getString(R.string.required) else null
                if (title.isEmpty() || cmd.isEmpty()) return@setOnClickListener
                prefs.saveSnippet(Snippet(existing?.id ?: UUID.randomUUID().toString(), title, cmd))
                rebuild()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun openTerminal(command: String?) {
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_ID, server.id)
                .putExtra(TerminalActivity.EXTRA_COMMAND, command)
        )
    }

    // ------------------------------------------------------------ Statistik

    private fun loadStats(silent: Boolean) {
        if (!loading.compareAndSet(false, true)) return
        store.get(server.id)?.let { server = it }
        if (!silent) {
            b.tvConn.setTextColor(zColor(R.attr.zTextDim))
            b.tvConn.text = getString(R.string.connecting)
            b.loading.visibility = View.VISIBLE
        }

        val current = server
        thread(name = "ssh-stats") {
            try {
                val session = store.open(current)
                val script = assets.open("stats.sh").bufferedReader().use { it.readText() }
                val result = try {
                    Ssh.execScript(session, script)
                } finally {
                    Ssh.disconnect(session)
                }
                val map = linkedMapOf<String, String>()
                result.lineSequence().forEach { line ->
                    val i = line.indexOf('=')
                    if (i > 0) map[line.substring(0, i)] = line.substring(i + 1).trim()
                }
                onUi {
                    loading.set(false)
                    showStats(map)
                }
            } catch (e: Exception) {
                val msg = Ssh.friendlyError(e)
                onUi {
                    loading.set(false)
                    showError(msg)
                }
            }
        }
    }

    private fun showError(message: String) {
        b.loading.visibility = View.INVISIBLE
        b.tvConn.setTextColor(zColor(R.attr.zBad))
        b.tvConn.text = message
    }

    private fun gauge(g: ItemGaugeBinding, pct: Int?, text: String) {
        g.tvValue.text = text
        if (pct == null) return
        val color = when {
            pct < 60 -> zColor(R.attr.zOk)
            pct < 85 -> zColor(R.attr.zWarn)
            else -> zColor(R.attr.zBad)
        }
        g.bar.setIndicatorColor(color)
        g.bar.setProgressCompat(pct.coerceIn(0, 100), true)
    }

    private fun showStats(m: Map<String, String>) {
        fun v(key: String) = m[key].orEmpty().ifBlank { "-" }

        b.loading.visibility = View.INVISIBLE
        b.tvConn.setTextColor(zColor(R.attr.zOk))
        b.tvConn.text = getString(R.string.connected_online, v("HOST"), v("IP"))

        val cpu = m["CPU_PCT"]?.toIntOrNull()
        val ram = m["MEM_PCT"]?.toIntOrNull()
        val disk = m["DISK_PCT"]?.toIntOrNull()
        gauge(b.gCpu, cpu, if (cpu != null) "$cpu%" else "--")
        gauge(b.gRam, ram, if (ram != null) "$ram%" else "--")
        gauge(b.gDisk, disk, if (disk != null) "$disk%" else "--")

        b.tvStatus.text = buildString {
            append("OS      : ").append(v("OS")).append('\n')
            append("Kernel  : ").append(v("KERNEL")).append('\n')
            append("Uptime  : ").append(v("UPTIME")).append('\n')
            append("Load    : ").append(v("LOAD")).append("  (").append(v("CPUS")).append(" core)\n")
            append("RAM     : ").append(v("MEM")).append('\n')
            append("Swap    : ").append(v("SWAP")).append('\n')
            append("Disk /  : ").append(v("DISK")).append('\n')
            append("Proses  : ").append(v("PROCS")).append("   Login: ").append(v("USERS"))
            if (m.containsKey("DOCKER")) {
                append('\n').append("Docker  : ").append(v("DOCKER")).append(" container jalan/total")
            }
        }

        val services = m.filterKeys { it.startsWith("SVC_") }
        if (services.isEmpty()) {
            b.tvServices.text = getString(R.string.no_services)
        } else {
            val ok = zColor(R.attr.zOk)
            val bad = zColor(R.attr.zBad)
            val warn = zColor(R.attr.zWarn)
            val sb = SpannableStringBuilder()
            services.forEach { (key, state) ->
                val start = sb.length
                sb.append("● ").append(key.removePrefix("SVC_")).append("    ")
                val color = when (state) {
                    "active" -> ok
                    "failed" -> bad
                    else -> warn
                }
                sb.setSpan(ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            b.tvServices.text = sb
        }

        // Riwayat untuk grafik (tersimpan selama aplikasi hidup).
        val hist = history.getOrPut(server.id) { Pair(ArrayList<Int>(), ArrayList<Int>()) }
        if (cpu != null) hist.first.add(cpu)
        if (ram != null) hist.second.add(ram)
        while (hist.first.size > SparkView.MAX_POINTS) hist.first.removeAt(0)
        while (hist.second.size > SparkView.MAX_POINTS) hist.second.removeAt(0)
        b.spark.setData(hist.first, hist.second)
        val upPct = prefs.uptimePercent(server.id)
        b.tvChartLegend.text = getString(R.string.chart_legend) +
            (if (upPct != null) "  •  " + getString(R.string.uptime_fmt, upPct) else "")

        if (!statsShown) {
            statsShown = true
            b.gaugeRow.visibility = View.VISIBLE
            b.cardChart.visibility = View.VISIBLE
            b.cardInfo.visibility = View.VISIBLE
            reveal(b.gaugeRow, 0)
            reveal(b.cardChart, 100)
            reveal(b.cardInfo, 200)
        }
    }

    private fun reveal(v: View, delay: Long) {
        v.alpha = 0f
        v.translationY = 28f
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(380).start()
    }

    companion object {
        const val EXTRA_ID = "server_id"
        private val history = HashMap<String, Pair<ArrayList<Int>, ArrayList<Int>>>()
    }
}

class ActionAdapter(
    private val onHeader: (String) -> Unit,
    private val onClick: (Row.Item) -> Unit,
    private val onLong: (Row.Item) -> Unit,
    private val onAddSnippet: (Snippet?) -> Unit
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    class HeaderVH(val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class ActionVH(val b: ItemActionBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Header -> 0
        is Row.Item -> 1
        Row.AddSnippet -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderVH(ItemHeaderBinding.inflate(inflater, parent, false))
        } else {
            ActionVH(ItemActionBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> {
                val h = holder as HeaderVH
                h.b.tvHeader.text = row.title
                h.b.tvCount.text = row.count.toString()
                h.b.ivChevron.rotation = if (row.collapsed) -90f else 0f
                h.b.root.setOnClickListener { onHeader(row.key) }
            }
            is Row.Item -> {
                val h = holder as ActionVH
                val a = row.action
                h.b.tvTitle.text = if (a.confirm) "⚠  ${a.title}" else a.title
                h.b.tvDesc.text = a.desc
                h.b.root.setOnClickListener { onClick(row) }
                h.b.root.setOnLongClickListener {
                    onLong(row)
                    true
                }
            }
            Row.AddSnippet -> {
                val h = holder as ActionVH
                h.b.tvTitle.text = h.itemView.context.getString(R.string.snippet_add)
                h.b.tvDesc.text = h.itemView.context.getString(R.string.snippet_add_desc)
                h.b.root.setOnClickListener { onAddSnippet(null) }
                h.b.root.setOnLongClickListener(null)
                h.b.root.isLongClickable = false
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row): Boolean = when {
                a is Row.Header && b is Row.Header -> a.key == b.key
                a is Row.Item && b is Row.Item ->
                    a.snippetId == b.snippetId && a.action.title == b.action.title
                else -> a == b
            }

            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
