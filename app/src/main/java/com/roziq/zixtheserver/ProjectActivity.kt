package com.roziq.zixtheserver

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityProjectBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Detail proyek backtest/jurnal: statistik, equity curve, daftar transaksi, form, dan ekspor. */
class ProjectActivity : BaseActivity() {

    private lateinit var b: ActivityProjectBinding
    private lateinit var db: ZixDb
    private lateinit var project: Project
    private lateinit var adapter: SimpleToolAdapter

    private var trades: List<Trade> = emptyList()
    private var shown: List<Trade> = emptyList()
    private var filter = ""

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }

    private val csvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) writeText(uri, TradeStats.csv(trades))
        }

    private val jsonLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeText(uri, exportJson())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ZixDb(this)
        val p = intent.getStringExtra(EXTRA_ID)?.let { db.project(it) }
        if (p == null) {
            finish()
            return
        }
        project = p
        b = ActivityProjectBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = project.name
        b.tvSub.text = listOf(project.strategy, project.instrument, project.timeframe)
            .filter { it.isNotBlank() }.joinToString(" • ").ifEmpty { project.kind }
        adapter = SimpleToolAdapter(::onTrade)
        b.rvTrades.layoutManager = LinearLayoutManager(this)
        b.rvTrades.isNestedScrollingEnabled = false
        b.rvTrades.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.fabAdd.setOnClickListener { showTradeForm(null) }
        b.btnMenu.setOnClickListener { showMenu() }
        b.btnBreakdown.setOnClickListener { showBreakdown() }
        b.btnFilter.setOnClickListener { chooseFilter() }
        reload()
    }

    private fun reload() {
        trades = db.trades(project.id)
        val st = TradeStats.compute(trades)
        if (trades.isEmpty()) {
            b.tvStats.text = "Belum ada transaksi — belum ada statistik."
            b.cardEquity.visibility = View.GONE
        } else {
            b.tvStats.text = TradeStats.summary(st)
            b.cardEquity.visibility = View.VISIBLE
            b.equity.setData(st.equity)
        }
        shown = (if (filter.isEmpty()) trades else trades.filter { it.category() == filter }).sortedByDescending { it.ts }
        val df = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        adapter.set(
            shown.map { t ->
                val r = t.r()
                Tools.Item(
                    "${t.side} ${t.instrument} ${t.tf}".trim(),
                    df.format(Date(t.ts)) + (if (t.setup.isNotBlank()) " • " + t.setup else ""),
                    if (r != null) String.format(Locale.US, "%+.2fR", r) else t.category(),
                    t.id,
                    if (r == null) 0 else if (r > 0) 1 else if (r < 0) 3 else 2
                )
            }
        )
        b.tvEmpty.visibility = if (trades.isEmpty()) View.VISIBLE else View.GONE
        b.btnFilter.text = "Filter: " + (if (filter.isEmpty()) "Semua" else filter)
    }

    private fun chooseFilter() {
        val labels = arrayOf("Semua", "WIN", "LOSS", "BE", "PARTIAL", "OPEN")
        MaterialAlertDialogBuilder(this)
            .setTitle("Filter daftar transaksi")
            .setSingleChoiceItems(labels, labels.indexOf(if (filter.isEmpty()) "Semua" else filter)) { d, i ->
                filter = if (i == 0) "" else labels[i]
                d.dismiss()
                reload()
            }
            .show()
    }

    private fun showBreakdown() {
        if (trades.isEmpty()) {
            toast("Belum ada transaksi.")
            return
        }
        val st = TradeStats.compute(trades)
        val labels = arrayOf("Per bulan", "Per instrumen", "Per timeframe", "Per setup", "Per sesi")
        MaterialAlertDialogBuilder(this)
            .setTitle("Rincian performa")
            .setItems(labels) { _, i ->
                val list = when (i) {
                    0 -> st.byMonth
                    1 -> st.byInstrument
                    2 -> st.byTf
                    3 -> st.bySetup
                    else -> st.bySession
                }
                showText(labels[i], TradeStats.groupText(labels[i], list))
            }
            .show()
    }

    private fun showMenu() {
        val labels = arrayOf("Laporan lengkap", "Ekspor CSV", "Ekspor JSON")
        MaterialAlertDialogBuilder(this)
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> showReport()
                    1 -> csvLauncher.launch(safeName() + ".csv")
                    2 -> jsonLauncher.launch(safeName() + ".json")
                }
            }
            .show()
    }

    private fun safeName() = project.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "zixfunction" }

    private fun showReport() {
        val st = TradeStats.compute(trades)
        val sb = StringBuilder()
        sb.append("PROYEK : ${project.name}  (${project.kind})\n")
        if (project.strategy.isNotBlank()) sb.append("Strategi: ${project.strategy}\n")
        if (project.instrument.isNotBlank()) sb.append("Instrumen: ${project.instrument} ${project.timeframe}\n")
        if (project.period.isNotBlank()) sb.append("Periode uji: ${project.period}\n")
        if (project.rules.isNotBlank()) sb.append("\nAturan:\n${project.rules}\n")
        if (project.params.isNotBlank()) sb.append("\nParameter:\n${project.params}\n")
        sb.append("\n").append(if (trades.isEmpty()) "Belum ada transaksi." else TradeStats.summary(st)).append("\n\n")
        sb.append(TradeStats.groupText("Per bulan", st.byMonth)).append("\n\n")
        sb.append(TradeStats.groupText("Per instrumen", st.byInstrument)).append("\n\n")
        sb.append(TradeStats.groupText("Per setup", st.bySetup)).append("\n\n")
        sb.append("ASUMSI PERHITUNGAN\n")
        sb.append("• R dihitung dari entry/SL/exit; bila kosong dari hasil (WIN=RR ke TP, LOSS=-1R, BE=0R) atau R manual.\n")
        sb.append("• Win rate = WIN ÷ semua transaksi berhasil dikategorikan (BE & Partial ikut dihitung).\n")
        sb.append("• Profit factor memakai nominal (pnl − biaya) bila ada, jika tidak memakai R; N/A bila tidak ada kerugian.\n")
        sb.append("• Max drawdown = penurunan terbesar equity kumulatif R dari puncaknya, urut waktu.\n")
        sb.append("• Semua angka berasal dari transaksi yang kamu catat; bukan jaminan hasil di masa depan.")
        showText("Laporan", sb.toString())
    }

    private fun exportJson(): String {
        val o = org.json.JSONObject()
        o.put("app", "ZixFunction")
        o.put("exported", System.currentTimeMillis())
        o.put("project", db.projectJson(project))
        val arr = org.json.JSONArray()
        trades.forEach { arr.put(db.tradeJson(it)) }
        o.put("trades", arr)
        return o.toString(2)
    }

    private fun writeText(uri: Uri, text: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            toast("Ekspor tersimpan")
        } catch (e: Exception) {
            toast("Ekspor gagal: " + (e.message ?: ""))
        }
    }

    // ------------------------------------------------------------ Transaksi

    private fun onTrade(pos: Int) {
        val t = shown.getOrNull(pos) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("${t.side} ${t.instrument}")
            .setItems(arrayOf("Detail", "Edit", "Hapus")) { _, i ->
                when (i) {
                    0 -> showText("Detail transaksi", detail(t))
                    1 -> showTradeForm(t)
                    2 -> MaterialAlertDialogBuilder(this)
                        .setMessage("Hapus transaksi ini? Tidak bisa dibatalkan.")
                        .setPositiveButton(R.string.menu_delete) { _, _ ->
                            db.deleteTrade(t.id)
                            reload()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun detail(t: Trade): String {
        val sb = StringBuilder()
        sb.append("Waktu   : ${dateFmt.format(Date(t.ts))}\n")
        sb.append("${t.side} ${t.instrument} ${t.tf}\n")
        sb.append("Entry ${t.entry ?: "-"}  SL ${t.sl ?: "-"}  TP ${t.tp ?: "-"}  Exit ${t.exit ?: "-"}\n")
        sb.append("Hasil   : ${t.category()}   R: ${t.r()?.let { String.format(Locale.US, "%+.3f", it) } ?: "N/A"}\n")
        if (t.pnl != null) sb.append("PnL ${t.pnl}  biaya ${t.fees}\n")
        if (t.riskPct != null) sb.append("Risiko  : ${t.riskPct}%\n")
        if (t.setup.isNotBlank()) sb.append("Setup   : ${t.setup}\n")
        if (t.session.isNotBlank()) sb.append("Sesi    : ${t.session}\n")
        if (t.reason.isNotBlank()) sb.append("\nAlasan entry:\n${t.reason}\n")
        if (t.notes.isNotBlank()) sb.append("\nCatatan:\n${t.notes}\n")
        if (t.mistakes.isNotBlank()) sb.append("\nKesalahan:\n${t.mistakes}\n")
        return sb.toString()
    }

    private fun num(e: EditText): Double? = e.text.toString().trim().replace(',', '.').toDoubleOrNull()

    private fun showTradeForm(existing: Trade?) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dpPx(22), dpPx(6), dpPx(22), dpPx(6))

        fun field(hint: String, value: String, numeric: Boolean = false, multi: Boolean = false): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.textSize = 14f
            e.setTextColor(zColor(R.attr.zText))
            e.setHintTextColor(zColor(R.attr.zTextDim))
            e.inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            box.addView(e)
            return e
        }

        fun s(v: Double?) = v?.let { java.math.BigDecimal(it.toString()).stripTrailingZeros().toPlainString() } ?: ""

        val t0 = existing ?: Trade(projectId = project.id, instrument = project.instrument, tf = project.timeframe)
        var side = t0.side
        var result = t0.result

        val btnSide = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val btnResult = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        fun refresh() {
            btnSide.text = "Sisi: $side"
            btnResult.text = "Hasil: " + (if (result.isEmpty()) "(otomatis dari R)" else result)
        }
        btnSide.setOnClickListener {
            side = if (side == "BUY") "SELL" else "BUY"
            refresh()
        }
        btnResult.setOnClickListener {
            val order = listOf("", "WIN", "LOSS", "BE", "PARTIAL")
            result = order[(order.indexOf(result) + 1) % order.size]
            refresh()
        }
        refresh()

        val eDate = field("Tanggal & jam (yyyy-MM-dd HH:mm)", dateFmt.format(Date(t0.ts)))
        val eInst = field("Instrumen (mis. XAUUSD)", t0.instrument)
        val eTf = field("Timeframe (mis. M15)", t0.tf)
        box.addView(btnSide)
        val eEntry = field("Entry", s(t0.entry), numeric = true)
        val eSl = field("Stop loss", s(t0.sl), numeric = true)
        val eTp = field("Take profit", s(t0.tp), numeric = true)
        val eExit = field("Exit (kosongkan bila belum/tidak dicatat)", s(t0.exit), numeric = true)
        box.addView(btnResult)
        val eRisk = field("Risiko (% akun)", s(t0.riskPct), numeric = true)
        val eR = field("R aktual (opsional, menimpa hitungan otomatis)", s(t0.rOverride), numeric = true)
        val ePnl = field("Hasil nominal (opsional)", s(t0.pnl), numeric = true)
        val eFees = field("Biaya/komisi/swap/slippage", if (t0.fees == 0.0) "" else s(t0.fees), numeric = true)
        val eSetup = field("Setup/strategi (mis. MSNR, SMC)", t0.setup)
        val eSession = field("Sesi (Asia/London/NY)", t0.session)
        val eReason = field("Alasan entry", t0.reason, multi = true)
        val eNotes = field("Catatan evaluasi", t0.notes, multi = true)
        val eMist = field("Kesalahan", t0.mistakes, multi = true)

        val sv = ScrollView(this)
        sv.addView(box)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Tambah transaksi" else "Edit transaksi")
            .setView(sv)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val ts = try {
                    dateFmt.parse(eDate.text.toString().trim())?.time
                } catch (e: Exception) {
                    null
                }
                if (ts == null) {
                    eDate.error = "Format: 2025-01-31 14:30"
                    return@setOnClickListener
                }
                val entry = num(eEntry)
                val sl = num(eSl)
                val tp = num(eTp)
                val exit = num(eExit)
                for ((label, v) in listOf("Entry" to entry, "SL" to sl, "TP" to tp, "Exit" to exit)) {
                    if (v != null && v <= 0) {
                        toast("$label harus lebih dari 0")
                        return@setOnClickListener
                    }
                }
                if (entry != null && sl != null) {
                    val ok = if (side == "BUY") sl < entry else sl > entry
                    if (!ok) {
                        toast("SL tidak sesuai arah $side")
                        return@setOnClickListener
                    }
                }
                if (entry != null && tp != null) {
                    val ok = if (side == "BUY") tp > entry else tp < entry
                    if (!ok) {
                        toast("TP tidak sesuai arah $side")
                        return@setOnClickListener
                    }
                }
                val risk = num(eRisk)
                if (risk != null && (risk <= 0 || risk > 100)) {
                    toast("Risiko harus 0–100%")
                    return@setOnClickListener
                }
                db.saveTrade(
                    t0.copy(
                        ts = ts, instrument = eInst.text.toString().trim().uppercase(Locale.US),
                        tf = eTf.text.toString().trim().uppercase(Locale.US), side = side,
                        entry = entry, sl = sl, tp = tp, exit = exit, result = result,
                        riskPct = risk, rOverride = num(eR), pnl = num(ePnl), fees = num(eFees) ?: 0.0,
                        setup = eSetup.text.toString().trim(), session = eSession.text.toString().trim(),
                        reason = eReason.text.toString().trim(), notes = eNotes.text.toString().trim(),
                        mistakes = eMist.text.toString().trim()
                    )
                )
                reload()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    companion object {
        const val EXTRA_ID = "project_id"
    }
}
