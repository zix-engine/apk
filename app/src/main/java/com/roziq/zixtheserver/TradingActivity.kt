package com.roziq.zixtheserver

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityToolBinding

/** Hub modul trading. Fitur yang butuh sumber data/API eksternal ditandai jelas "belum terhubung". */
class TradingActivity : BaseActivity() {

    private lateinit var b: ActivityToolBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityToolBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvTitle.text = "Trading"
        b.tvSub.text = "Hitung, catat, dan analisis — semua tersimpan di HP-mu"
        b.etSearch.visibility = View.GONE
        b.btnTop.visibility = View.GONE
        b.btnReload.visibility = View.GONE
        val adapter = SimpleToolAdapter(::onItem)
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        adapter.set(
            listOf(
                Tools.Item("Kalkulator trading", "Lot, risk/reward, margin, P/L, break-even, partial close, dll.", "siap", "calc", 1),
                Tools.Item("Backtest & rekap statistik", "Proyek strategi, transaksi, win rate, profit factor, drawdown, equity curve.", "siap", "bt", 1),
                Tools.Item("Jurnal trading", "Catat transaksi, setup, kesalahan; statistik dari data yang tersimpan.", "siap", "journal", 1),
                Tools.Item("Market, watchlist & chart", "Butuh sumber data harga dan lisensi chart.", "belum terhubung", "market", 2),
                Tools.Item("Kalender ekonomi & berita", "Butuh API data pasar yang sah.", "belum terhubung", "news", 2),
                Tools.Item("AI Assistant", "Butuh backend dan API key penyedia AI.", "belum terhubung", "ai", 2)
            )
        )
    }

    private fun onItem(pos: Int) {
        when (pos) {
            0 -> chooseCalc()
            1 -> startActivity(Intent(this, ProjectsActivity::class.java).putExtra(ProjectsActivity.EXTRA_KIND, "backtest"))
            2 -> startActivity(Intent(this, ProjectsActivity::class.java).putExtra(ProjectsActivity.EXTRA_KIND, "journal"))
            else -> showText(
                "Belum terhubung",
                "Fitur ini membutuhkan sumber data atau layanan eksternal (API harga, lisensi chart, backend, atau API key) " +
                    "yang belum dikonfigurasi. Aplikasi tidak menampilkan data palsu sebagai data live. Lihat IMPLEMENTATION_STATUS.md untuk rencananya.",
                mono = false
            )
        }
    }

    private fun chooseCalc() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Pilih kalkulator")
            .setItems(TradeCalc.all.map { it.title }.toTypedArray()) { _, i ->
                startActivity(Intent(this, CalcActivity::class.java).putExtra(CalcActivity.EXTRA_ID, TradeCalc.all[i].id))
            }
            .show()
    }
}
