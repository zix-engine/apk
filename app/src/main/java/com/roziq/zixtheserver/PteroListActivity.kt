package com.roziq.zixtheserver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import kotlin.concurrent.thread

/** Daftar backup atau jadwal sebuah game server Pterodactyl (mode "backups" / "schedules"). */
class PteroListActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var server: Server
    private lateinit var pid: String
    private lateinit var mode: String
    private lateinit var adapter: SimpleToolAdapter
    private var backups: List<PteroApi.Backup> = emptyList()
    private var scheds: List<PteroApi.Sched> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = intent.getStringExtra(EXTRA_ID)?.let { ServerStore(this).get(it) }
        val id = intent.getStringExtra(EXTRA_PID)
        val m = intent.getStringExtra(EXTRA_MODE)
        if (found == null || id == null || m == null) {
            finish()
            return
        }
        server = found
        pid = id
        mode = m
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        val isBackup = mode == "backups"
        b.tvTitle.text = getString(if (isBackup) R.string.p_backups else R.string.p_schedules)
        b.tvSub.text = intent.getStringExtra(EXTRA_NAME).orEmpty()
        b.etSearch.visibility = View.GONE
        b.btnTop.visibility = View.GONE
        if (isBackup) {
            b.fabAdd.visibility = View.VISIBLE
            b.fabAdd.text = getString(R.string.p_backup_new)
            b.fabAdd.setOnClickListener { createBackup() }
        }
        adapter = SimpleToolAdapter(::onItem)
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.btnReload.setOnClickListener { load() }
        load()
    }

    private fun load() {
        b.busy.visibility = View.VISIBLE
        b.tvMsg.visibility = View.GONE
        thread(name = "ptero-load") {
            try {
                if (mode == "backups") backups = PteroApi.backups(server, pid)
                else scheds = PteroApi.schedules(server, pid)
                onUi {
                    b.busy.visibility = View.INVISIBLE
                    render()
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                onUi {
                    b.busy.visibility = View.INVISIBLE
                    b.tvMsg.text = getString(R.string.ptero_error, msg)
                    b.tvMsg.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun render() {
        val items = if (mode == "backups") {
            backups.map {
                Tools.Item(
                    it.name.ifBlank { it.uuid.take(8) },
                    "%.1f MB • %s".format(it.bytes / 1048576.0, it.created.take(16).replace('T', ' ')),
                    if (it.ok) "✓" else "✗", it.uuid, if (it.ok) 1 else 3
                )
            }
        } else {
            scheds.map {
                Tools.Item(
                    it.name,
                    getString(R.string.p_next, it.next.take(16).replace('T', ' ')),
                    if (it.active) "ON" else "OFF", it.id.toString(), if (it.active) 1 else 0
                )
            }
        }
        adapter.set(items)
        if (items.isEmpty()) {
            b.tvMsg.setText(R.string.tool_empty)
            b.tvMsg.visibility = View.VISIBLE
        }
    }

    private fun bg(done: String, work: () -> Unit) {
        b.busy.visibility = View.VISIBLE
        thread(name = "ptero-act") {
            try {
                work()
                onUi {
                    toast(done)
                    load()
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                onUi {
                    b.busy.visibility = View.INVISIBLE
                    toast(getString(R.string.ptero_error, msg))
                }
            }
        }
    }

    private fun createBackup() {
        bg(getString(R.string.p_backup_started)) { PteroApi.createBackup(server, pid) }
    }

    private fun onItem(pos: Int) {
        if (mode == "backups") {
            val bk = backups.getOrNull(pos) ?: return
            val labels = arrayOf(
                getString(R.string.files_download),
                getString(R.string.p_restore),
                getString(R.string.menu_delete)
            )
            MaterialAlertDialogBuilder(this)
                .setTitle(bk.name.ifBlank { bk.uuid.take(8) })
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> thread(name = "ptero-url") {
                            try {
                                val url = PteroApi.backupUrl(server, pid, bk.uuid)
                                onUi { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            } catch (e: Exception) {
                                val msg = e.message ?: ""
                                onUi { toast(getString(R.string.ptero_error, msg)) }
                            }
                        }
                        1 -> confirm(R.string.p_restore_msg) {
                            bg(getString(R.string.p_restore_started)) { PteroApi.restoreBackup(server, pid, bk.uuid) }
                        }
                        2 -> confirm(R.string.p_delete_msg) {
                            bg(getString(R.string.tool_done)) { PteroApi.deleteBackup(server, pid, bk.uuid) }
                        }
                    }
                }
                .show()
        } else {
            val s = scheds.getOrNull(pos) ?: return
            MaterialAlertDialogBuilder(this)
                .setTitle(s.name)
                .setItems(arrayOf(getString(R.string.sched_run_now))) { _, _ ->
                    bg(getString(R.string.tool_done)) { PteroApi.runSchedule(server, pid, s.id) }
                }
                .show()
        }
    }

    private fun confirm(msg: Int, go: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(msg)
            .setPositiveButton(R.string.run) { _, _ -> go() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "server_id"
        const val EXTRA_PID = "pid"
        const val EXTRA_MODE = "mode"
        const val EXTRA_NAME = "name"
    }
}
