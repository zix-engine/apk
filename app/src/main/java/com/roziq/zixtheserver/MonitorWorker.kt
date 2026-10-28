package com.roziq.zixtheserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Pemantau latar (±15 menit): status online/offline, riwayat uptime, alert CPU/RAM/disk,
 * layanan yang mati, serta target website/port (termasuk masa berlaku SSL).
 */
class MonitorWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.monitor) return Result.success()

        val store = ServerStore(ctx)
        val servers = try {
            store.list()
        } catch (e: Exception) {
            return Result.retry()
        }
        val old = prefs.monitorState()
        val now = HashMap<String, Boolean>()

        for (s in servers) {
            // Server di balik jump host tidak bisa dicek lewat TCP langsung.
            if (s.jumpId.isNotBlank()) continue
            // Dua percobaan agar gangguan sesaat tidak dianggap down.
            val up = Net.ping(s.host, s.port, 5000) > 0 || Net.ping(s.host, s.port, 5000) > 0
            now[s.id] = up
            prefs.uptimeAppend(s.id, up)
            val before = old[s.id]
            if (before == true && !up) {
                post(ctx, s.id, ctx.getString(R.string.mon_down, s.name), ctx.getString(R.string.mon_down_text, s.host))
            } else if (before == false && up) {
                post(ctx, s.id, ctx.getString(R.string.mon_up, s.name), ctx.getString(R.string.mon_up_text, s.host))
            }
            if (up && s.alerts) checkResources(ctx, prefs, store, s)
        }

        for (t in prefs.targets()) {
            val c = Monitors.check(t)
            val key = "t:" + t.id
            now[key] = c.up
            val before = old[key]
            if (before == true && !c.up) {
                post(ctx, key, ctx.getString(R.string.mon_down, t.name), c.info)
            } else if (before == false && c.up) {
                post(ctx, key, ctx.getString(R.string.mon_up, t.name), t.addr)
            }
            val days = c.sslDays
            if (days != null && days < 14 && cooldown(prefs, "ssl_" + t.id, 24 * 3600_000L)) {
                post(ctx, "ssl:" + t.id, ctx.getString(R.string.mon_ssl, t.name), ctx.getString(R.string.mon_ssl_text, days))
            }
        }
        prefs.saveMonitorState(now)
        return Result.success()
    }

    private fun cooldown(prefs: Prefs, key: String, ms: Long): Boolean {
        val last = prefs.raw(key).toLongOrNull() ?: 0L
        val t = System.currentTimeMillis()
        if (t - last < ms) return false
        prefs.putRaw(key, t.toString())
        return true
    }

    private fun checkResources(ctx: Context, prefs: Prefs, store: ServerStore, s: Server) {
        try {
            val session = store.open(s)
            val out = try {
                val script = ctx.assets.open("stats.sh").bufferedReader().use { it.readText() }
                Ssh.execScript(session, script)
            } finally {
                Ssh.disconnect(session)
            }
            val m = HashMap<String, String>()
            out.lineSequence().forEach { l ->
                val i = l.indexOf('=')
                if (i > 0) m[l.substring(0, i)] = l.substring(i + 1).trim()
            }
            val six = 6 * 3600_000L
            val cpu = m["CPU_PCT"]?.toIntOrNull()
            val ram = m["MEM_PCT"]?.toIntOrNull()
            val disk = m["DISK_PCT"]?.toIntOrNull()
            if (cpu != null && cpu >= prefs.alertCpu && cooldown(prefs, "a_cpu_" + s.id, six)) {
                post(ctx, "cpu:" + s.id, ctx.getString(R.string.mon_hi, s.name, "CPU"), "$cpu%")
            }
            if (ram != null && ram >= prefs.alertRam && cooldown(prefs, "a_ram_" + s.id, six)) {
                post(ctx, "ram:" + s.id, ctx.getString(R.string.mon_hi, s.name, "RAM"), "$ram%")
            }
            if (disk != null && disk >= prefs.alertDisk && cooldown(prefs, "a_disk_" + s.id, six)) {
                post(ctx, "disk:" + s.id, ctx.getString(R.string.mon_hi, s.name, "Disk"), "$disk%")
            }

            // Layanan yang tadinya aktif lalu mati.
            val cur = m.filterKeys { it.startsWith("SVC_") }
                .mapKeys { it.key.removePrefix("SVC_") }
            val prev = prefs.raw("svc_" + s.id).split(';').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            for ((name, state) in cur) {
                if (prev[name] == "active" && state != "active") {
                    post(ctx, "svc:" + s.id + name, ctx.getString(R.string.mon_svc, s.name, name), state)
                }
            }
            prefs.putRaw("svc_" + s.id, cur.entries.joinToString(";") { it.key + "=" + it.value })
        } catch (_: Exception) {
        }
    }

    private fun post(ctx: Context, key: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.mon_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, key.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(ctx).notify(key.hashCode(), n)
    }

    companion object {
        const val CHANNEL = "zix_monitor"
        private const val WORK = "zix_monitor_work"

        fun schedule(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (enabled) {
                val req = PeriodicWorkRequest.Builder(MonitorWorker::class.java, 15, TimeUnit.MINUTES).build()
                wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
            } else {
                wm.cancelUniqueWork(WORK)
            }
        }
    }
}
