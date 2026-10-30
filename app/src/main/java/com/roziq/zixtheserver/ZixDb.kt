package com.roziq.zixtheserver

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/** Proyek backtest atau jurnal. [kind] = "backtest" | "journal". */
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: String = "backtest",
    val strategy: String = "",
    val instrument: String = "",
    val timeframe: String = "",
    val period: String = "",
    val rules: String = "",
    val params: String = "",
    val notes: String = "",
    val archived: Boolean = false,
    val created: Long = System.currentTimeMillis()
)

/** Satu transaksi. Semua angka opsional kecuali yang dibutuhkan untuk menghitung R. */
data class Trade(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String = "",
    val ts: Long = System.currentTimeMillis(),
    val instrument: String = "",
    val tf: String = "",
    val side: String = "BUY",
    val entry: Double? = null,
    val sl: Double? = null,
    val tp: Double? = null,
    val exit: Double? = null,
    /** "", "WIN", "LOSS", "BE", "PARTIAL" */
    val result: String = "",
    val riskPct: Double? = null,
    val rOverride: Double? = null,
    val pnl: Double? = null,
    val fees: Double = 0.0,
    val setup: String = "",
    val session: String = "",
    val reason: String = "",
    val notes: String = "",
    val mistakes: String = ""
) {
    /**
     * Hasil dalam satuan R. Urutan: nilai R yang diisi manual; dihitung dari entry/SL/exit;
     * dari TP bila hasil WIN tanpa exit; -1R bila LOSS; 0R bila BE. Null bila tidak bisa dihitung.
     */
    fun r(): Double? {
        rOverride?.let { return it }
        val e = entry
        val s = sl
        if (e != null && s != null && e != s) {
            val risk = abs(e - s)
            val x = exit
            if (x != null) {
                val move = if (side == "BUY") x - e else e - x
                return move / risk
            }
            when (result) {
                "WIN" -> tp?.let { return abs(it - e) / risk }
                "LOSS" -> return -1.0
                "BE" -> return 0.0
            }
        } else {
            when (result) {
                "LOSS" -> return -1.0
                "BE" -> return 0.0
            }
        }
        return null
    }

    /** Kategori hasil: WIN/LOSS/BE/PARTIAL, atau OPEN bila belum bisa ditentukan. */
    fun category(): String {
        if (result == "WIN" || result == "LOSS" || result == "BE" || result == "PARTIAL") return result
        val v = r() ?: return "OPEN"
        return if (v > 0) "WIN" else if (v < 0) "LOSS" else "BE"
    }
}

/** Database lokal SQLite untuk backtest dan jurnal trading. */
class ZixDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "zixfunction.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE projects (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, " +
                "strategy TEXT, instrument TEXT, timeframe TEXT, period TEXT, rules TEXT, params TEXT, " +
                "notes TEXT, archived INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE trades (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, ts INTEGER NOT NULL, " +
                "instrument TEXT, tf TEXT, side TEXT NOT NULL, entry REAL, sl REAL, tp REAL, exit_price REAL, " +
                "result TEXT, risk_pct REAL, r_override REAL, pnl REAL, fees REAL NOT NULL DEFAULT 0, " +
                "setup TEXT, session TEXT, reason TEXT, notes TEXT, mistakes TEXT, " +
                "FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX idx_trades_project ON trades(project_id, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versi 1 adalah skema pertama. Migrasi berikutnya ditambahkan di sini tanpa menghapus data.
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    // ------------------------------------------------------------ Proyek

    private fun s(c: Cursor, name: String): String = c.getString(c.getColumnIndexOrThrow(name)) ?: ""
    private fun d(c: Cursor, name: String): Double? {
        val i = c.getColumnIndexOrThrow(name)
        return if (c.isNull(i)) null else c.getDouble(i)
    }

    private fun readProject(c: Cursor) = Project(
        id = s(c, "id"), name = s(c, "name"), kind = s(c, "kind"), strategy = s(c, "strategy"),
        instrument = s(c, "instrument"), timeframe = s(c, "timeframe"), period = s(c, "period"),
        rules = s(c, "rules"), params = s(c, "params"), notes = s(c, "notes"),
        archived = c.getInt(c.getColumnIndexOrThrow("archived")) == 1,
        created = c.getLong(c.getColumnIndexOrThrow("created"))
    )

    fun projects(kind: String, includeArchived: Boolean): List<Project> {
        val out = ArrayList<Project>()
        val sql = "SELECT * FROM projects WHERE kind = ?" + (if (includeArchived) "" else " AND archived = 0") +
            " ORDER BY created DESC"
        readableDatabase.rawQuery(sql, arrayOf(kind)).use { c ->
            while (c.moveToNext()) out.add(readProject(c))
        }
        return out
    }

    fun project(id: String): Project? {
        readableDatabase.rawQuery("SELECT * FROM projects WHERE id = ?", arrayOf(id)).use { c ->
            return if (c.moveToFirst()) readProject(c) else null
        }
    }

    private fun projectValues(p: Project) = ContentValues().apply {
        put("id", p.id); put("name", p.name); put("kind", p.kind); put("strategy", p.strategy)
        put("instrument", p.instrument); put("timeframe", p.timeframe); put("period", p.period)
        put("rules", p.rules); put("params", p.params); put("notes", p.notes)
        put("archived", if (p.archived) 1 else 0); put("created", p.created)
    }

    fun saveProject(p: Project) {
        writableDatabase.insertWithOnConflict("projects", null, projectValues(p), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteProject(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("trades", "project_id = ?", arrayOf(id))
            db.delete("projects", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Menggandakan proyek beserta semua transaksinya. */
    fun duplicateProject(id: String, suffix: String): String? {
        val src = project(id) ?: return null
        val copy = src.copy(id = UUID.randomUUID().toString(), name = src.name + suffix, archived = false, created = System.currentTimeMillis())
        val db = writableDatabase
        db.beginTransaction()
        try {
            saveProject(copy)
            trades(id).forEach { saveTrade(it.copy(id = UUID.randomUUID().toString(), projectId = copy.id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return copy.id
    }

    // ------------------------------------------------------------ Transaksi

    private fun readTrade(c: Cursor) = Trade(
        id = s(c, "id"), projectId = s(c, "project_id"), ts = c.getLong(c.getColumnIndexOrThrow("ts")),
        instrument = s(c, "instrument"), tf = s(c, "tf"), side = s(c, "side").ifEmpty { "BUY" },
        entry = d(c, "entry"), sl = d(c, "sl"), tp = d(c, "tp"), exit = d(c, "exit_price"),
        result = s(c, "result"), riskPct = d(c, "risk_pct"), rOverride = d(c, "r_override"),
        pnl = d(c, "pnl"), fees = d(c, "fees") ?: 0.0, setup = s(c, "setup"), session = s(c, "session"),
        reason = s(c, "reason"), notes = s(c, "notes"), mistakes = s(c, "mistakes")
    )

    fun trades(projectId: String): List<Trade> {
        val out = ArrayList<Trade>()
        readableDatabase.rawQuery("SELECT * FROM trades WHERE project_id = ? ORDER BY ts ASC", arrayOf(projectId)).use { c ->
            while (c.moveToNext()) out.add(readTrade(c))
        }
        return out
    }

    private fun tradeValues(t: Trade) = ContentValues().apply {
        put("id", t.id); put("project_id", t.projectId); put("ts", t.ts); put("instrument", t.instrument)
        put("tf", t.tf); put("side", t.side)
        if (t.entry != null) put("entry", t.entry) else putNull("entry")
        if (t.sl != null) put("sl", t.sl) else putNull("sl")
        if (t.tp != null) put("tp", t.tp) else putNull("tp")
        if (t.exit != null) put("exit_price", t.exit) else putNull("exit_price")
        put("result", t.result)
        if (t.riskPct != null) put("risk_pct", t.riskPct) else putNull("risk_pct")
        if (t.rOverride != null) put("r_override", t.rOverride) else putNull("r_override")
        if (t.pnl != null) put("pnl", t.pnl) else putNull("pnl")
        put("fees", t.fees); put("setup", t.setup); put("session", t.session)
        put("reason", t.reason); put("notes", t.notes); put("mistakes", t.mistakes)
    }

    fun saveTrade(t: Trade) {
        writableDatabase.insertWithOnConflict("trades", null, tradeValues(t), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteTrade(id: String) {
        writableDatabase.delete("trades", "id = ?", arrayOf(id))
    }

    fun countProjects(): Int = count("projects")
    fun countTrades(): Int = count("trades")

    private fun count(table: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ------------------------------------------------------------ Ekspor / impor (backup)

    fun projectJson(p: Project): JSONObject = JSONObject()
        .put("id", p.id).put("name", p.name).put("kind", p.kind).put("strategy", p.strategy)
        .put("instrument", p.instrument).put("timeframe", p.timeframe).put("period", p.period)
        .put("rules", p.rules).put("params", p.params).put("notes", p.notes)
        .put("archived", p.archived).put("created", p.created)

    fun tradeJson(t: Trade): JSONObject {
        val o = JSONObject()
            .put("id", t.id).put("projectId", t.projectId).put("ts", t.ts).put("instrument", t.instrument)
            .put("tf", t.tf).put("side", t.side).put("result", t.result).put("fees", t.fees)
            .put("setup", t.setup).put("session", t.session).put("reason", t.reason)
            .put("notes", t.notes).put("mistakes", t.mistakes)
        t.entry?.let { o.put("entry", it) }
        t.sl?.let { o.put("sl", it) }
        t.tp?.let { o.put("tp", it) }
        t.exit?.let { o.put("exit", it) }
        t.riskPct?.let { o.put("riskPct", it) }
        t.rOverride?.let { o.put("rOverride", it) }
        t.pnl?.let { o.put("pnl", it) }
        t.r()?.let { o.put("r", it) }
        return o
    }

    private fun optD(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null

    fun exportAll(): JSONObject {
        val projectsArr = JSONArray()
        val tradesArr = JSONArray()
        for (kind in listOf("backtest", "journal")) {
            for (p in projects(kind, true)) {
                projectsArr.put(projectJson(p))
                trades(p.id).forEach { tradesArr.put(tradeJson(it)) }
            }
        }
        return JSONObject().put("projects", projectsArr).put("trades", tradesArr)
    }

    /** Mengimpor dalam satu transaksi. [replace] = hapus semua data trading lebih dulu. */
    fun importAll(o: JSONObject, replace: Boolean): Pair<Int, Int> {
        val db = writableDatabase
        var np = 0
        var nt = 0
        db.beginTransaction()
        try {
            if (replace) {
                db.delete("trades", null, null)
                db.delete("projects", null, null)
            }
            val ps = o.optJSONArray("projects") ?: JSONArray()
            for (i in 0 until ps.length()) {
                val j = ps.getJSONObject(i)
                saveProject(
                    Project(
                        id = j.getString("id"), name = j.optString("name"), kind = j.optString("kind", "backtest"),
                        strategy = j.optString("strategy"), instrument = j.optString("instrument"),
                        timeframe = j.optString("timeframe"), period = j.optString("period"),
                        rules = j.optString("rules"), params = j.optString("params"), notes = j.optString("notes"),
                        archived = j.optBoolean("archived", false), created = j.optLong("created", System.currentTimeMillis())
                    )
                )
                np++
            }
            val ts = o.optJSONArray("trades") ?: JSONArray()
            for (i in 0 until ts.length()) {
                val j = ts.getJSONObject(i)
                saveTrade(
                    Trade(
                        id = j.getString("id"), projectId = j.getString("projectId"), ts = j.optLong("ts"),
                        instrument = j.optString("instrument"), tf = j.optString("tf"), side = j.optString("side", "BUY"),
                        entry = optD(j, "entry"), sl = optD(j, "sl"), tp = optD(j, "tp"), exit = optD(j, "exit"),
                        result = j.optString("result"), riskPct = optD(j, "riskPct"), rOverride = optD(j, "rOverride"),
                        pnl = optD(j, "pnl"), fees = j.optDouble("fees", 0.0), setup = j.optString("setup"),
                        session = j.optString("session"), reason = j.optString("reason"),
                        notes = j.optString("notes"), mistakes = j.optString("mistakes")
                    )
                )
                nt++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return Pair(np, nt)
    }
}
