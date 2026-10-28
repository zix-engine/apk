package com.roziq.zixtheserver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Widget layar utama: ringkasan status server (online/offline) dengan tombol refresh. */
class ServerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context, manager, ids, goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ServerWidget::class.java))
            refresh(context, manager, ids, goAsync())
        } else {
            super.onReceive(context, intent)
        }
    }

    private fun refresh(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        pending: android.content.BroadcastReceiver.PendingResult
    ) {
        val app = context.applicationContext
        Thread {
            try {
                val servers = try {
                    ServerStore(app).list()
                } catch (e: Exception) {
                    emptyList()
                }
                val pool = Executors.newFixedThreadPool(4)
                val futures = servers.take(12).map { s -> pool.submit(java.util.concurrent.Callable { Net.ping(s.host, s.port, 3000) }) }
                val results = futures.map {
                    try {
                        it.get(6, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        -1
                    }
                }
                pool.shutdown()
                val shown = servers.take(12)
                val online = results.count { it > 0 }
                for (id in ids) {
                    manager.updateAppWidget(id, build(app, shown, results, online, servers.size))
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun build(ctx: Context, servers: List<Server>, ms: List<Int>, online: Int, total: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_servers)
        v.setTextViewText(
            R.id.wSummary,
            if (total == 0) ctx.getString(R.string.widget_empty) else ctx.getString(R.string.summary_fmt, total, online)
        )
        val rows = intArrayOf(R.id.wRow1, R.id.wRow2, R.id.wRow3, R.id.wRow4)
        // Tampilkan server offline lebih dulu agar langsung terlihat.
        val order = servers.indices.sortedBy { if (ms[it] > 0) 1 else 0 }
        for ((slot, rowId) in rows.withIndex()) {
            val i = order.getOrNull(slot)
            if (i == null) {
                v.setTextViewText(rowId, "")
            } else {
                val up = ms[i] > 0
                v.setTextViewText(rowId, (if (up) "● " else "○ ") + servers[i].name + if (up) "  ${ms[i]} ms" else "  offline")
                v.setTextColor(rowId, if (up) 0xFF34D399.toInt() else 0xFFF87171.toInt())
            }
        }
        val refresh = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, ServerWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        v.setOnClickPendingIntent(R.id.wRefresh, refresh)
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        v.setOnClickPendingIntent(R.id.wTitle, open)
        return v
    }

    companion object {
        const val ACTION_REFRESH = "com.roziq.zixtheserver.WIDGET_REFRESH"
    }
}
