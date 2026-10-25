package com.roziq.zixtheserver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityMainBinding
import com.roziq.zixtheserver.databinding.DialogServerBinding
import com.roziq.zixtheserver.databinding.ItemServerBinding
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : BaseActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: ServerStore
    private lateinit var adapter: ServerAdapter

    private val pool = Executors.newFixedThreadPool(4)
    private var pingGen = 0
    private var query = ""
    private var all: List<Server> = emptyList()
    private var firstLoad = true

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = ServerStore(this)
        adapter = ServerAdapter(this, ::openServer, ::showMenu, ::toggleFavorite)
        b.rvServers.layoutManager = LinearLayoutManager(this)
        b.rvServers.adapter = adapter
        b.rvServers.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_down)

        b.swipe.setColorSchemeColors(zColor(R.attr.zAccent))
        b.swipe.setProgressBackgroundColorSchemeColor(zColor(R.attr.zSurface2))
        b.swipe.setOnRefreshListener { pingAll() }

        b.fabAdd.setOnClickListener {
            haptic(it)
            showEditor(null)
        }
        b.btnSettings.setOnClickListener {
            haptic(it)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b4: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val sixHours = 6 * 60 * 60 * 1000L
        if (prefs.autoUpdate && System.currentTimeMillis() - prefs.lastUpdateCheck > sixHours) {
            checkUpdate(false)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        pingAll()
    }

    override fun onDestroy() {
        pingGen++
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        all = store.list().sortedWith(
            compareByDescending<Server> { it.favorite }
                .thenByDescending { it.lastUsed }
                .thenBy { it.name.lowercase() }
        )
        applyFilter()
    }

    private fun applyFilter() {
        val q = query.lowercase()
        val list = if (q.isEmpty()) all else all.filter {
            it.name.lowercase().contains(q) || it.host.lowercase().contains(q) ||
                it.username.lowercase().contains(q)
        }
        adapter.submitList(list) {
            if (firstLoad && list.isNotEmpty()) {
                firstLoad = false
                b.rvServers.scheduleLayoutAnimation()
            }
        }
        b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.tvEmpty.setText(if (all.isEmpty()) R.string.empty_servers else R.string.no_results)
        updateSummary()
    }

    private fun updateSummary() {
        if (all.isEmpty()) {
            b.tvSummary.setText(R.string.tagline)
            return
        }
        val online = all.count { adapter.pingOf(it.id) > 0 }
        b.tvSummary.text = getString(R.string.summary_fmt, all.size, online)
    }

    private fun pingAll() {
        val gen = ++pingGen
        val servers = all
        if (servers.isEmpty()) {
            b.swipe.isRefreshing = false
            return
        }
        adapter.resetPings()
        val left = AtomicInteger(servers.size)
        for (s in servers) {
            try {
                pool.execute {
                    val ms = Net.ping(s.host, s.port)
                    runOnUiThread {
                        if (gen == pingGen && !isDestroyed) {
                            adapter.setPing(s.id, ms)
                            updateSummary()
                            if (left.decrementAndGet() == 0) b.swipe.isRefreshing = false
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                return
            }
        }
    }

    private fun openServer(s: Server) {
        store.touch(s.id)
        startActivity(
            Intent(this, ServerActivity::class.java).putExtra(ServerActivity.EXTRA_ID, s.id)
        )
    }

    private fun toggleFavorite(s: Server) {
        store.save(s.copy(favorite = !s.favorite))
        refresh()
    }

    private fun showMenu(s: Server, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.menu_edit))
        popup.menu.add(0, 2, 1, getString(R.string.menu_duplicate))
        popup.menu.add(0, 3, 2, getString(R.string.menu_copy_ssh))
        popup.menu.add(0, 4, 3, getString(R.string.menu_reset_hostkey))
        popup.menu.add(0, 5, 4, getString(R.string.menu_delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showEditor(s)
                2 -> {
                    store.save(
                        s.copy(
                            id = UUID.randomUUID().toString(),
                            name = s.name + getString(R.string.copy_suffix),
                            favorite = false,
                            lastUsed = 0L
                        )
                    )
                    refresh()
                    pingAll()
                }
                3 -> {
                    val cmd = "ssh ${s.username}@${s.host} -p ${s.port}"
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ssh", cmd))
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                }
                4 -> {
                    store.save(s.copy(hostKey = ""))
                    Toast.makeText(this, R.string.hostkey_reset_done, Toast.LENGTH_SHORT).show()
                }
                5 -> confirmDelete(s)
            }
            true
        }
        popup.show()
    }

    private fun confirmDelete(s: Server) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_delete)
            .setMessage(getString(R.string.confirm_delete, s.name))
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                store.delete(s.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditor(existing: Server?) {
        val d = DialogServerBinding.inflate(layoutInflater)
        if (existing != null) {
            d.etName.setText(existing.name)
            d.etHost.setText(existing.host)
            d.etPort.setText(existing.port.toString())
            d.etUser.setText(existing.username)
            d.etPass.setText(existing.password)
            d.etKey.setText(existing.privateKey)
        } else {
            d.etPort.setText("22")
            d.etUser.setText("root")
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_server else R.string.menu_edit)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = d.etHost.text.toString().trim()
                if (host.isEmpty()) {
                    d.tilHost.error = getString(R.string.required)
                    return@setOnClickListener
                }
                d.tilHost.error = null

                val port = d.etPort.text.toString().toIntOrNull()
                    ?.takeIf { it in 1..65535 } ?: 22
                val hostChanged = existing != null &&
                    (existing.host != host || existing.port != port)

                val base = existing ?: Server()
                store.save(
                    base.copy(
                        name = d.etName.text.toString().trim().ifBlank { host },
                        host = host,
                        port = port,
                        username = d.etUser.text.toString().trim().ifBlank { "root" },
                        password = d.etPass.text.toString(),
                        privateKey = d.etKey.text.toString(),
                        hostKey = if (existing == null || hostChanged) "" else existing.hostKey
                    )
                )
                refresh()
                pingAll()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}

class ServerAdapter(
    private val ctx: Context,
    private val onClick: (Server) -> Unit,
    private val onMenu: (Server, View) -> Unit,
    private val onFav: (Server) -> Unit
) : ListAdapter<Server, ServerAdapter.VH>(DIFF) {

    /** id -> ms (>0 online, -1 offline). Tidak ada entri = sedang dicek. */
    private val pings = HashMap<String, Int>()

    fun pingOf(id: String): Int = pings[id] ?: 0

    fun resetPings() {
        pings.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_PING)
    }

    fun setPing(id: String, ms: Int) {
        pings[id] = ms
        val i = currentList.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i, PAYLOAD_PING)
    }

    class VH(val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = getItem(position)
        holder.b.tvName.text = s.name
        holder.b.tvAddr.text = "${s.username}@${s.host}:${s.port}"
        holder.b.btnFav.setImageResource(if (s.favorite) R.drawable.ic_star else R.drawable.ic_star_outline)
        holder.b.root.setOnClickListener { onClick(s) }
        holder.b.btnMenu.setOnClickListener { onMenu(s, it) }
        holder.b.btnFav.setOnClickListener { onFav(s) }
        bindPing(holder, s)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            bindPing(holder, getItem(position))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private fun bindPing(holder: VH, s: Server) {
        val ms = pings[s.id]
        val color: Int
        val label: String
        when {
            ms == null -> {
                color = ctx.zColor(R.attr.zTextDim)
                label = "…"
            }
            ms > 0 -> {
                color = ctx.zColor(R.attr.zOk)
                label = "$ms ms"
            }
            else -> {
                color = ctx.zColor(R.attr.zBad)
                label = "offline"
            }
        }
        holder.b.tvDot.setTextColor(color)
        holder.b.tvPing.setTextColor(color)
        holder.b.tvPing.text = label
    }

    private companion object {
        const val PAYLOAD_PING = "ping"
        val DIFF = object : DiffUtil.ItemCallback<Server>() {
            override fun areItemsTheSame(a: Server, b: Server) = a.id == b.id
            override fun areContentsTheSame(a: Server, b: Server) = a == b
        }
    }
}
