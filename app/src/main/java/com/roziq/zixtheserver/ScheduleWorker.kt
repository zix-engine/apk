package com.roziq.zixtheserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Menjalankan perintah terjadwal di server lewat SSH, lalu memberi notifikasi hasilnya. */
class ScheduleWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val sch = prefs.schedules().firstOrNull { it.id == id } ?: return Result.success()
        if (!sch.enabled) return Result.success()

        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        if (sch.days and (1 shl today) == 0) return Result.success()

        val store = ServerStore(ctx)
        val server = store.get(sch.serverId) ?: return Result.success()
        val summary = runOnce(store, server, sch.command)
        prefs.putRaw("sched_last_" + sch.id, System.currentTimeMillis().toString() + "|" + summary.first)
        notify(ctx, sch, summary)
        return Result.success()
    }

    private fun notify(ctx: Context, sch: Schedule, summary: Pair<String, String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("zix_sched", ctx.getString(R.string.sched_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val n = NotificationCompat.Builder(ctx, "zix_sched")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle((if (summary.first == "ok") "✓ " else "✗ ") + sch.title)
            .setContentText(summary.second.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.second.take(600)))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(("sch" + sch.id).hashCode(), n)
    }

    companion object {
        /** Mengembalikan (status "ok"/"fail", ringkasan output). */
        fun runOnce(store: ServerStore, server: Server, command: String): Pair<String, String> {
            return try {
                val session = store.open(server)
                try {
                    val r = Ssh.execCommand(session, command, 5 * 60_000L)
                    Pair(if (r.exit == 0) "ok" else "fail", r.output.trim().ifBlank { "(tanpa output, exit ${r.exit})" })
                } finally {
                    Ssh.disconnect(session)
                }
            } catch (e: Exception) {
                Pair("fail", Ssh.friendlyError(e))
            }
        }

        fun delayUntil(hour: Int, minute: Int): Long {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, hour)
            c.set(Calendar.MINUTE, minute)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis - System.currentTimeMillis()
        }

        fun apply(ctx: Context, s: Schedule) {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            if (!s.enabled) {
                wm.cancelUniqueWork("sched_" + s.id)
                return
            }
            val req = PeriodicWorkRequest.Builder(ScheduleWorker::class.java, 24, TimeUnit.HOURS)
                .setInitialDelay(delayUntil(s.hour, s.minute), TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putString("id", s.id).build())
                .build()
            wm.enqueueUniquePeriodicWork("sched_" + s.id, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context, id: String) {
            WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork("sched_$id")
        }
    }
}
