package com.roziq.zixtheserver

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/** Jadwal perintah otomatis (backup, restart, dll.) yang dijalankan di latar lewat SSH. */
class SchedulesActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var store: ServerStore
    private lateinit var adapter: SimpleToolAdapter
    private var list: List<Schedule> = emptyList()

    private val dayNames = arrayOf("Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerStore(this)
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = getString(R.string.sched_title)
        b.tvSub.text = getString(R.string.sched_sub)
        b.etSearch.visibility = View.GONE
        b.btnTop.visibility = View.GONE
        b.btnReload.visibility = View.GONE
        b.fabAdd.visibility = View.VISIBLE
        b.fabAdd.text = getString(R.string.sched_add)
        adapter = SimpleToolAdapter(::onItem)
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.fabAdd.setOnClickListener { showForm(null) }
        render()
    }

    private fun daysLabel(mask: Int): String =
        if (mask == 127) getString(R.string.sched_daily)
        else dayNames.indices.filter { mask and (1 shl it) != 0 }.joinToString(",") { dayNames[it].take(3) }

    private fun render() {
        list = prefs.schedules()
        val servers = store.list().associateBy { it.id }
        adapter.set(
            list.map { s ->
                val last = prefs.raw("sched_last_" + s.id)
                var lastText = ""
                if (last.contains('|')) {
                    val ts = last.substringBefore('|').toLongOrNull() ?: 0L
                    lastText = " • " + last.substringAfter('|') + " " +
                        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(ts))
                }
                Tools.Item(
                    s.title,
                    String.format("%02d:%02d • %s • %s%s", s.hour, s.minute, daysLabel(s.days),
                        servers[s.serverId]?.name ?: "?", lastText),
                    if (s.enabled) "ON" else "OFF",
                    s.id,
                    if (s.enabled) 1 else 0
                )
            }
        )
        if (list.isEmpty()) {
            b.tvMsg.setText(R.string.sched_empty)
            b.tvMsg.visibility = View.VISIBLE
        } else {
            b.tvMsg.visibility = View.GONE
        }
    }

    private fun onItem(pos: Int) {
        val s = list.getOrNull(pos) ?: return
        val labels = arrayOf(
            getString(R.string.sched_run_now),
            getString(if (s.enabled) R.string.sched_disable else R.string.sched_enable),
            getString(R.string.menu_edit),
            getString(R.string.menu_delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(s.title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> runNow(s)
                    1 -> save(s.copy(enabled = !s.enabled))
                    2 -> showForm(s)
                    3 -> {
                        ScheduleWorker.cancel(this, s.id)
                        prefs.saveSchedules(prefs.schedules().filterNot { it.id == s.id })
                        render()
                    }
                }
            }
            .show()
    }

    private fun save(s: Schedule) {
        val all = prefs.schedules().toMutableList()
        val i = all.indexOfFirst { it.id == s.id }
        if (i >= 0) all[i] = s else all.add(s)
        prefs.saveSchedules(all)
        ScheduleWorker.apply(this, s)
        render()
    }

    private fun runNow(s: Schedule) {
        val server = store.get(s.serverId)
        if (server == null) {
            toast(R.string.sched_no_server)
            return
        }
        b.busy.visibility = View.VISIBLE
        thread(name = "sched-run") {
            val r = ScheduleWorker.runOnce(store, server, s.command)
            prefs.putRaw("sched_last_" + s.id, System.currentTimeMillis().toString() + "|" + r.first)
            onUi {
                b.busy.visibility = View.INVISIBLE
                render()
                MaterialAlertDialogBuilder(this)
                    .setTitle((if (r.first == "ok") "✓ " else "✗ ") + s.title)
                    .setMessage(r.second.take(3000))
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }

    private fun showForm(existing: Schedule?) {
        val servers = store.list()
        if (servers.isEmpty()) {
            toast(R.string.empty_servers)
            return
        }
        var serverId = existing?.serverId ?: servers[0].id
        var hour = existing?.hour ?: 3
        var minute = existing?.minute ?: 0
        var days = existing?.days ?: 127
        val dp = resources.displayMetrics.density

        val btnServer = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val btnTime = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val btnDays = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val etTitle = EditText(this).apply {
            hint = getString(R.string.snippet_title)
            setSingleLine(true)
            setText(existing?.title.orEmpty())
        }
        val etCmd = EditText(this).apply {
            hint = getString(R.string.snippet_command)
            minLines = 3
            typeface = android.graphics.Typeface.MONOSPACE
            setText(existing?.command.orEmpty())
        }

        fun refreshButtons() {
            btnServer.text = getString(R.string.sched_server, servers.firstOrNull { it.id == serverId }?.name ?: "?")
            btnTime.text = getString(R.string.sched_time, String.format("%02d:%02d", hour, minute))
            btnDays.text = getString(R.string.sched_days, daysLabel(days))
        }
        refreshButtons()

        btnServer.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setItems(servers.map { it.name }.toTypedArray()) { _, i ->
                    serverId = servers[i].id
                    refreshButtons()
                }
                .show()
        }
        btnTime.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .build()
            picker.addOnPositiveButtonClickListener {
                hour = picker.hour
                minute = picker.minute
                refreshButtons()
            }
            picker.show(supportFragmentManager, "time")
        }
        btnDays.setOnClickListener {
            val checked = BooleanArray(7) { days and (1 shl it) != 0 }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sched_pick_days)
                .setMultiChoiceItems(dayNames, checked) { _, i, c -> checked[i] = c }
                .setPositiveButton(R.string.save) { _, _ ->
                    var m = 0
                    for (i in 0..6) if (checked[i]) m = m or (1 shl i)
                    days = if (m == 0) 127 else m
                    refreshButtons()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(btnServer)
            addView(etTitle)
            addView(etCmd)
            addView(btnTime)
            addView(btnDays)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.sched_add else R.string.menu_edit)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val t = etTitle.text.toString().trim()
                val c = etCmd.text.toString().trim()
                if (t.isEmpty() || c.isEmpty()) {
                    toast(R.string.required)
                } else {
                    save(
                        Schedule(
                            existing?.id ?: UUID.randomUUID().toString(),
                            serverId, t, c, hour, minute, days, existing?.enabled ?: true
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
