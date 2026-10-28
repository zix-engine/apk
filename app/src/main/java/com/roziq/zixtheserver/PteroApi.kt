package com.roziq.zixtheserver

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Klien sederhana untuk Client API Pterodactyl (butuh Client API key: ptlc_...). */
object PteroApi {

    data class PServer(
        val id: String,
        val name: String,
        val node: String,
        val suspended: Boolean,
        val memLimit: Long,
        val diskLimit: Long,
        val cpuLimit: Long
    )

    data class Res(
        val state: String,
        val cpu: Double,
        val memBytes: Long,
        val diskBytes: Long
    )

    fun baseUrl(s: Server): String = base(s)

    private fun base(s: Server): String {
        val u = s.panelUrl.trim().trimEnd('/')
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    private fun request(
        s: Server,
        method: String,
        path: String,
        body: String? = null,
        contentType: String = "application/json"
    ): String {
        val conn = URL(base(s) + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer ${s.panelKey}")
            conn.setRequestProperty("Accept", "Application/vnd.pterodactyl.v1+json")
            conn.setRequestProperty("User-Agent", "ZixTheServer")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                return if (code == 204) "" else conn.inputStream.bufferedReader().use { it.readText() }
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val detail = try {
                JSONObject(err).getJSONArray("errors").getJSONObject(0).optString("detail")
            } catch (e: Exception) {
                ""
            }
            val hint = when (code) {
                401, 403 -> "API key ditolak (pakai Client API key dari Account > API Credentials)."
                404 -> "Endpoint/server tidak ditemukan. Periksa URL Panel."
                else -> ""
            }
            throw IllegalStateException("HTTP $code ${detail.ifBlank { hint }}".trim())
        } finally {
            conn.disconnect()
        }
    }

    fun listServers(s: Server): List<PServer> {
        val out = ArrayList<PServer>()
        var page = 1
        var totalPages = 1
        while (page <= totalPages && page <= 10) {
            val o = JSONObject(request(s, "GET", "/api/client?page=$page"))
            val data = o.getJSONArray("data")
            for (i in 0 until data.length()) {
                val a = data.getJSONObject(i).getJSONObject("attributes")
                val lim = a.optJSONObject("limits")
                out.add(
                    PServer(
                        id = a.getString("identifier"),
                        name = a.optString("name"),
                        node = a.optString("node"),
                        suspended = a.optBoolean("is_suspended", false),
                        memLimit = lim?.optLong("memory", 0) ?: 0,
                        diskLimit = lim?.optLong("disk", 0) ?: 0,
                        cpuLimit = lim?.optLong("cpu", 0) ?: 0
                    )
                )
            }
            totalPages = o.optJSONObject("meta")?.optJSONObject("pagination")
                ?.optInt("total_pages", 1) ?: 1
            page++
        }
        return out
    }

    fun resources(s: Server, id: String): Res {
        val o = JSONObject(request(s, "GET", "/api/client/servers/$id/resources"))
        val a = o.getJSONObject("attributes")
        val r = a.optJSONObject("resources")
        return Res(
            state = a.optString("current_state", "offline"),
            cpu = r?.optDouble("cpu_absolute", 0.0) ?: 0.0,
            memBytes = r?.optLong("memory_bytes", 0) ?: 0,
            diskBytes = r?.optLong("disk_bytes", 0) ?: 0
        )
    }

    /** signal: start | stop | restart | kill */
    fun power(s: Server, id: String, signal: String) {
        request(s, "POST", "/api/client/servers/$id/power", JSONObject().put("signal", signal).toString())
    }

    fun command(s: Server, id: String, cmd: String) {
        request(s, "POST", "/api/client/servers/$id/command", JSONObject().put("command", cmd).toString())
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    // ---------- WebSocket konsol ----------

    /** Mengembalikan (token, url socket). */
    fun websocket(s: Server, id: String): Pair<String, String> {
        val d = JSONObject(request(s, "GET", "/api/client/servers/$id/websocket")).getJSONObject("data")
        return Pair(d.getString("token"), d.getString("socket"))
    }

    // ---------- Backup ----------

    data class Backup(val uuid: String, val name: String, val ok: Boolean, val bytes: Long, val created: String)

    fun backups(s: Server, id: String): List<Backup> {
        val data = JSONObject(request(s, "GET", "/api/client/servers/$id/backups")).getJSONArray("data")
        return (0 until data.length()).map {
            val a = data.getJSONObject(it).getJSONObject("attributes")
            Backup(
                a.getString("uuid"), a.optString("name"), a.optBoolean("is_successful", false),
                a.optLong("bytes", 0), a.optString("created_at")
            )
        }
    }

    fun createBackup(s: Server, id: String) {
        request(s, "POST", "/api/client/servers/$id/backups", "{}")
    }

    fun backupUrl(s: Server, id: String, uuid: String): String {
        val o = JSONObject(request(s, "GET", "/api/client/servers/$id/backups/$uuid/download"))
        return o.getJSONObject("attributes").getString("url")
    }

    fun restoreBackup(s: Server, id: String, uuid: String) {
        request(s, "POST", "/api/client/servers/$id/backups/$uuid/restore", "{\"truncate\":false}")
    }

    fun deleteBackup(s: Server, id: String, uuid: String) {
        request(s, "DELETE", "/api/client/servers/$id/backups/$uuid")
    }

    // ---------- Jadwal ----------

    data class Sched(val id: Int, val name: String, val active: Boolean, val next: String, val last: String)

    fun schedules(s: Server, id: String): List<Sched> {
        val data = JSONObject(request(s, "GET", "/api/client/servers/$id/schedules")).getJSONArray("data")
        return (0 until data.length()).map {
            val a = data.getJSONObject(it).getJSONObject("attributes")
            Sched(
                a.getInt("id"), a.optString("name"), a.optBoolean("is_active", true),
                a.optString("next_run_at"), a.optString("last_run_at")
            )
        }
    }

    fun runSchedule(s: Server, id: String, sid: Int) {
        request(s, "POST", "/api/client/servers/$id/schedules/$sid/execute", "{}")
    }

    // ---------- File ----------

    fun readFile(s: Server, id: String, path: String): String =
        request(s, "GET", "/api/client/servers/$id/files/contents?file=" + enc(path))

    fun writeFile(s: Server, id: String, path: String, text: String) {
        request(s, "POST", "/api/client/servers/$id/files/write?file=" + enc(path), text, "text/plain")
    }
}
