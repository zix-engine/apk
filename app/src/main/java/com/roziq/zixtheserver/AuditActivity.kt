package com.roziq.zixtheserver

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Audit log dari backend (Owner, atau akun dengan izin audit.view). */
class AuditActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var api: ApiClient
    private lateinit var adapter: SimpleToolAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = ApiClient.get(this)
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = "Audit log"
        b.tvSub.text = "200 kejadian terbaru"
        b.etSearch.visibility = View.GONE
        b.btnTop.visibility = View.GONE
        adapter = SimpleToolAdapter { }
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.btnReload.setOnClickListener { load() }
        load()
    }

    private fun load() {
        b.busy.visibility = View.VISIBLE
        b.tvMsg.visibility = View.GONE
        runApi({ api.call("GET", "/api/audit?limit=200").getJSONArray("entries") }, { arr ->
            b.busy.visibility = View.INVISIBLE
            val df = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault())
            val items = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                val action = e.optString("action")
                val who = e.optString("actor").ifEmpty { "-" }
                val target = e.optString("target").ifEmpty { "-" }
                Tools.Item(
                    action,
                    who + " → " + target + (if (e.optString("ip").isNotEmpty()) " • " + e.optString("ip") else ""),
                    df.format(Date(e.optLong("ts"))),
                    e.optLong("id").toString(),
                    when {
                        action.contains("fail") || action.contains("locked") || action.contains("disabled") -> 3
                        action.contains("delete") || action.contains("reset") -> 2
                        action.contains("success") || action.contains("create") -> 1
                        else -> 0
                    }
                )
            }
            adapter.set(items)
            if (items.isEmpty()) {
                b.tvMsg.text = "Belum ada catatan."
                b.tvMsg.visibility = View.VISIBLE
            }
        }, { e ->
            b.busy.visibility = View.INVISIBLE
            b.tvMsg.text = e.message ?: e.code
            b.tvMsg.visibility = View.VISIBLE
        })
    }
}
