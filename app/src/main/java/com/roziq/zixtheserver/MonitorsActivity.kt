package com.roziq.zixtheserver

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import java.util.UUID
import java.util.concurrent.Executors

/** Monitor website (kode HTTP, masa berlaku SSL) dan port TCP (mis. port game). */
class MonitorsActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var adapter: SimpleToolAdapter
    private val pool = Executors.newFixedThreadPool(4)
    private var targets: List<Target> = emptyList()
    private val results = HashMap<String, Monitors.Check>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = getString(R.string.monitors_title)
        b.tvSub.text = getString(R.string.monitors_sub)
        b.etSearch.visibility = View.GONE
        b.btnTop.visibility = View.GONE
        b.fabAdd.visibility = View.VISIBLE
        b.fabAdd.text = getString(R.string.monitors_add)
        adapter = SimpleToolAdapter(::onItem)
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.btnReload.setOnClickListener { checkAll() }
        b.fabAdd.setOnClickListener { showAdd() }
        checkAll()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        targets = prefs.targets()
        adapter.set(
            targets.map { t ->
                val c = results[t.id]
                val tag: String
                val level: Int
                var sub = t.addr
                if (c == null) {
                    tag = "…"
                    level = 0
                } else {
                    tag = if (c.up) "${c.ms} ms" else "DOWN"
                    level = if (c.up) 1 else 3
                    sub += " • " + c.info
                    c.sslDays?.let { sub += " • SSL $it hari" }
                }
                val ssl = c?.sslDays
                Tools.Item(t.name, sub, tag, t.id, if (ssl != null && ssl < 14 && c?.up == true) 2 else level)
            }
        )
        if (targets.isEmpty()) {
            b.tvMsg.setText(R.string.monitors_empty)
            b.tvMsg.visibility = View.VISIBLE
        } else {
            b.tvMsg.visibility = View.GONE
        }
    }

    private fun checkAll() {
        results.clear()
        render()
        b.busy.visibility = if (targets.isEmpty()) View.INVISIBLE else View.VISIBLE
        var left = targets.size
        for (t in targets) {
            try {
                pool.execute {
                    val c = Monitors.check(t)
                    onUi {
                        results[t.id] = c
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
        val t = targets.getOrNull(pos) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(t.name)
            .setItems(arrayOf(getString(R.string.menu_delete))) { _, _ ->
                prefs.saveTargets(prefs.targets().filterNot { it.id == t.id })
                results.remove(t.id)
                render()
            }
            .show()
    }

    private fun showAdd() {
        val dp = resources.displayMetrics.density
        val name = EditText(this).apply {
            hint = getString(R.string.monitors_hint_name)
            setSingleLine(true)
        }
        val addr = EditText(this).apply {
            hint = getString(R.string.monitors_hint_addr)
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(addr)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.monitors_add)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                var a = addr.text.toString().trim()
                if (a.isEmpty()) return@setPositiveButton
                if (!a.startsWith("http://") && !a.startsWith("https://") && !a.contains(':')) {
                    a = "https://$a"
                }
                val n = name.text.toString().trim().ifBlank { a }
                prefs.saveTargets(prefs.targets() + Target(UUID.randomUUID().toString(), n, a))
                checkAll()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
