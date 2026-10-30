package com.roziq.zixtheserver

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityToolBinding
import java.util.Locale

/** Daftar proyek backtest (kind = "backtest") atau jurnal (kind = "journal"). */
class ProjectsActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding
    private lateinit var db: ZixDb
    private lateinit var adapter: SimpleToolAdapter
    private lateinit var kind: String
    private var list: List<Project> = emptyList()
    private var showArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ZixDb(this)
        kind = intent.getStringExtra(EXTRA_KIND) ?: "backtest"
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        val isBt = kind == "backtest"
        b.tvTitle.text = if (isBt) "Backtest" else "Jurnal trading"
        b.tvSub.text = if (isBt) "Catat uji strategi, rekap otomatis dari datamu" else "Catat transaksi nyata/demo, statistik otomatis"
        b.etSearch.visibility = View.GONE
        b.btnReload.visibility = View.GONE
        b.fabAdd.visibility = View.VISIBLE
        b.fabAdd.text = if (isBt) "Proyek baru" else "Jurnal baru"
        adapter = SimpleToolAdapter(::onItem)
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.fabAdd.setOnClickListener { showForm(null) }
        b.btnTop.setOnClickListener { showMenu() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list = db.projects(kind, showArchived)
        adapter.set(
            list.map { p ->
                val st = TradeStats.compute(db.trades(p.id))
                val n = st.wins + st.losses + st.be + st.partial
                Tools.Item(
                    p.name + (if (p.archived) "  (arsip)" else ""),
                    listOf(p.strategy, p.instrument, p.timeframe).filter { it.isNotBlank() }.joinToString(" • "),
                    if (n == 0) "kosong" else String.format(Locale.US, "%d • %+.1fR", n, st.netR),
                    p.id,
                    if (n == 0) 0 else if (st.netR > 0) 1 else if (st.netR < 0) 3 else 2
                )
            }
        )
        if (list.isEmpty()) {
            b.tvMsg.text = if (kind == "backtest") {
                "Belum ada proyek backtest. Buat satu untuk strategi seperti MSNR, SMC, atau ICT."
            } else {
                "Belum ada jurnal. Buat satu (mis. “Akun Real”) lalu catat transaksimu."
            }
            b.tvMsg.visibility = View.VISIBLE
        } else {
            b.tvMsg.visibility = View.GONE
        }
    }

    private fun showMenu() {
        val labels = arrayOf(
            "Bandingkan semua proyek",
            if (showArchived) "Sembunyikan arsip" else "Tampilkan arsip"
        )
        MaterialAlertDialogBuilder(this)
            .setItems(labels) { _, i ->
                if (i == 0) compare() else {
                    showArchived = !showArchived
                    render()
                }
            }
            .show()
    }

    private fun compare() {
        val all = db.projects(kind, false)
        if (all.isEmpty()) {
            toast("Belum ada proyek.")
            return
        }
        val lines = all.joinToString("\n") { TradeStats.compareLine(it.name, TradeStats.compute(db.trades(it.id))) }
        showText("Perbandingan proyek", lines + "\n\nn = transaksi terklasifikasi, net dalam R, DD = max drawdown R.")
    }

    private fun onItem(pos: Int) {
        val p = list.getOrNull(pos) ?: return
        val labels = arrayOf(
            "Buka", "Edit", "Duplikat", if (p.archived) "Pulihkan dari arsip" else "Arsipkan", "Hapus"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setItems(labels) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this, ProjectActivity::class.java).putExtra(ProjectActivity.EXTRA_ID, p.id))
                    1 -> showForm(p)
                    2 -> {
                        db.duplicateProject(p.id, " (salinan)")
                        render()
                    }
                    3 -> {
                        db.saveProject(p.copy(archived = !p.archived))
                        render()
                    }
                    4 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Hapus “${p.name}”?")
                        .setMessage("Proyek beserta SEMUA transaksinya akan dihapus permanen. Buat Full Backup dulu bila perlu.")
                        .setPositiveButton(R.string.menu_delete) { _, _ ->
                            db.deleteProject(p.id)
                            render()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun showForm(existing: Project?) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dpPx(22), dpPx(6), dpPx(22), dpPx(6))
        fun field(hint: String, value: String, multi: Boolean = false): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.textSize = 14f
            e.setTextColor(zColor(R.attr.zText))
            e.setHintTextColor(zColor(R.attr.zTextDim))
            if (multi) {
                e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            box.addView(e)
            return e
        }
        val base = existing ?: Project(kind = kind)
        val eName = field("Nama", base.name)
        val eStrat = field("Strategi (MSNR / SMC / ICT / lainnya)", base.strategy)
        val eInst = field("Instrumen (mis. XAUUSD)", base.instrument)
        val eTf = field("Timeframe", base.timeframe)
        val ePeriod = field("Periode pengujian", base.period)
        val eRules = field("Aturan entry/exit", base.rules, true)
        val eParams = field("Parameter strategi", base.params, true)
        val eNotes = field("Catatan", base.notes, true)
        val sv = ScrollView(this)
        sv.addView(box)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Proyek baru" else "Edit proyek")
            .setView(sv)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = eName.text.toString().trim()
                if (name.isEmpty()) {
                    eName.error = getString(R.string.required)
                    return@setOnClickListener
                }
                db.saveProject(
                    base.copy(
                        name = name, strategy = eStrat.text.toString().trim(),
                        instrument = eInst.text.toString().trim().uppercase(Locale.US),
                        timeframe = eTf.text.toString().trim().uppercase(Locale.US),
                        period = ePeriod.text.toString().trim(), rules = eRules.text.toString().trim(),
                        params = eParams.text.toString().trim(), notes = eNotes.text.toString().trim()
                    )
                )
                render()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    companion object {
        const val EXTRA_KIND = "kind"
    }
}
