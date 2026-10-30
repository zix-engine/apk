package com.roziq.zixtheserver

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** Kalkulator trading. Setiap rumus ditulis di [note] agar asumsinya transparan. */
object TradeCalc {

    class Field(val key: String, val label: String, val default: String = "")

    class Calc(
        val id: String,
        val title: String,
        val desc: String,
        val note: String,
        val fields: List<Field>,
        val compute: (Map<String, Double>) -> String
    )

    private fun n(v: Double, d: Int = 2) = String.format(Locale.US, "%." + d + "f", v)

    private fun need(m: Map<String, Double>, vararg keys: String): String? {
        for (k in keys) if (!m.containsKey(k)) return "Isi semua kolom dengan angka yang valid."
        return null
    }

    val all: List<Calc> = listOf(
        Calc(
            "lot", "Ukuran lot (forex/emas)",
            "Berapa lot agar risiko sesuai persentase akun.",
            "Risiko = saldo × risiko%. Jarak SL (pip) = |entry − SL| ÷ ukuran pip. Lot = risiko ÷ (jarak SL × nilai pip per lot). " +
                "Nilai pip per lot berbeda tiap broker/instrumen — isi sesuai spesifikasi brokermu.",
            listOf(
                Field("bal", "Saldo akun", "1000"), Field("risk", "Risiko (%)", "1"),
                Field("entry", "Harga entry", "1.1000"), Field("sl", "Harga stop loss", "1.0950"),
                Field("pip", "Ukuran 1 pip (0.0001 / 0.01 / 0.1)", "0.0001"),
                Field("pv", "Nilai 1 pip per 1 lot (mata uang akun)", "10")
            )
        ) { m ->
            need(m, "bal", "risk", "entry", "sl", "pip", "pv") ?: run {
                val dist = abs(m["entry"]!! - m["sl"]!!)
                if (dist <= 0 || m["pip"]!! <= 0 || m["pv"]!! <= 0) "Entry/SL/pip/nilai pip harus valid dan entry ≠ SL."
                else {
                    val riskAmt = m["bal"]!! * m["risk"]!! / 100.0
                    val pips = dist / m["pip"]!!
                    val lots = riskAmt / (pips * m["pv"]!!)
                    "Risiko     : ${n(riskAmt)}\nJarak SL   : ${n(pips, 1)} pip\nLot        : ${n(lots, 3)}\n" +
                        "Lot (dibulatkan ke bawah 0.01): ${n(floor(lots * 100 + 1e-9) / 100.0)}"
                }
            }
        },
        Calc(
            "units", "Ukuran posisi (crypto/saham)",
            "Jumlah unit dari risiko dan jarak SL.",
            "Unit = (saldo × risiko%) ÷ |entry − SL|. Nilai posisi = unit × entry. Belum termasuk biaya/spread.",
            listOf(
                Field("bal", "Saldo akun", "1000"), Field("risk", "Risiko (%)", "1"),
                Field("entry", "Harga entry", "60000"), Field("sl", "Harga stop loss", "59000")
            )
        ) { m ->
            need(m, "bal", "risk", "entry", "sl") ?: run {
                val dist = abs(m["entry"]!! - m["sl"]!!)
                if (dist <= 0) "Entry dan SL tidak boleh sama." else {
                    val riskAmt = m["bal"]!! * m["risk"]!! / 100.0
                    val units = riskAmt / dist
                    "Risiko      : ${n(riskAmt)}\nUnit        : ${n(units, 6)}\nNilai posisi: ${n(units * m["entry"]!!)}"
                }
            }
        },
        Calc(
            "rr", "Risk/Reward",
            "Rasio R:R dan win rate impas.",
            "Risiko = |entry − SL|; reward = |TP − entry|; R:R = reward ÷ risiko. Win rate impas = 1 ÷ (1 + R:R) (tanpa biaya).",
            listOf(Field("entry", "Entry", "100"), Field("sl", "Stop loss", "98"), Field("tp", "Take profit", "106"))
        ) { m ->
            need(m, "entry", "sl", "tp") ?: run {
                val risk = abs(m["entry"]!! - m["sl"]!!)
                val reward = abs(m["tp"]!! - m["entry"]!!)
                if (risk <= 0) "Entry dan SL tidak boleh sama." else {
                    val rr = reward / risk
                    "Risiko       : ${n(risk, 5)}\nReward       : ${n(reward, 5)}\nR:R          : 1 : ${n(rr)}\n" +
                        "Win rate impas: ${n(100.0 / (1 + rr), 1)}%"
                }
            }
        },
        Calc(
            "dist", "Jarak SL/TP (pip)",
            "Jarak entry ke SL dan TP dalam pip.",
            "Pip = |harga − entry| ÷ ukuran pip.",
            listOf(
                Field("entry", "Entry", "1.1000"), Field("sl", "Stop loss", "1.0950"),
                Field("tp", "Take profit", "1.1100"), Field("pip", "Ukuran 1 pip", "0.0001")
            )
        ) { m ->
            need(m, "entry", "sl", "tp", "pip") ?: run {
                if (m["pip"]!! <= 0) "Ukuran pip harus > 0." else
                    "SL: ${n(abs(m["entry"]!! - m["sl"]!!) / m["pip"]!!, 1)} pip\nTP: ${n(abs(m["tp"]!! - m["entry"]!!) / m["pip"]!!, 1)} pip"
            }
        },
        Calc(
            "margin", "Margin & leverage",
            "Perkiraan margin yang dipakai.",
            "Margin = lot × ukuran kontrak × harga ÷ leverage (dalam mata uang kuotasi). Untuk pair berbasis USD isi harga = 1. " +
                "Ini perkiraan; broker bisa memakai rumus berbeda.",
            listOf(
                Field("lots", "Lot", "0.1"), Field("contract", "Ukuran kontrak", "100000"),
                Field("price", "Harga", "1.1000"), Field("lev", "Leverage (mis. 100)", "100")
            )
        ) { m ->
            need(m, "lots", "contract", "price", "lev") ?: run {
                if (m["lev"]!! <= 0) "Leverage harus > 0." else
                    "Margin: ${n(m["lots"]!! * m["contract"]!! * m["price"]!! / m["lev"]!!)}"
            }
        },
        Calc(
            "pnl", "Profit / Loss",
            "Hasil posisi pada harga exit tertentu.",
            "P/L = (exit − entry) × arah × lot × ukuran kontrak, arah: +1 beli, −1 jual. Hasil dalam mata uang kuotasi.",
            listOf(
                Field("entry", "Entry", "1.1000"), Field("exit", "Exit", "1.1050"),
                Field("lots", "Lot", "0.1"), Field("contract", "Ukuran kontrak", "100000"),
                Field("dir", "Arah (1 = beli, -1 = jual)", "1")
            )
        ) { m ->
            need(m, "entry", "exit", "lots", "contract", "dir") ?: run {
                if (m["dir"] != 1.0 && m["dir"] != -1.0) "Arah harus 1 atau -1." else
                    "P/L: ${n((m["exit"]!! - m["entry"]!!) * m["dir"]!! * m["lots"]!! * m["contract"]!!)}"
            }
        },
        Calc(
            "be", "Break-even dengan biaya",
            "Harga impas setelah komisi/biaya.",
            "Selisih harga impas = total biaya ÷ (lot × ukuran kontrak). Beli: entry + selisih; jual: entry − selisih.",
            listOf(
                Field("entry", "Entry", "1.1000"), Field("fees", "Total biaya (komisi+swap+spread)", "7"),
                Field("lots", "Lot", "1"), Field("contract", "Ukuran kontrak", "100000"),
                Field("dir", "Arah (1 = beli, -1 = jual)", "1")
            )
        ) { m ->
            need(m, "entry", "fees", "lots", "contract", "dir") ?: run {
                val q = m["lots"]!! * m["contract"]!!
                if (q <= 0) "Lot dan ukuran kontrak harus > 0." else {
                    val diff = m["fees"]!! / q
                    "Harga impas: ${n(m["entry"]!! + diff * m["dir"]!!, 5)}  (selisih ${n(diff, 5)})"
                }
            }
        },
        Calc(
            "avg", "Harga entry rata-rata",
            "Rata-rata tertimbang dari beberapa entry.",
            "Rata-rata = Σ(harga × ukuran) ÷ Σ ukuran. Kosongkan entry yang tidak dipakai.",
            listOf(
                Field("p1", "Harga entry 1", "100"), Field("s1", "Ukuran 1", "1"),
                Field("p2", "Harga entry 2", "95"), Field("s2", "Ukuran 2", "2"),
                Field("p3", "Harga entry 3"), Field("s3", "Ukuran 3"),
                Field("p4", "Harga entry 4"), Field("s4", "Ukuran 4")
            )
        ) { m ->
            var sumPS = 0.0
            var sumS = 0.0
            for (i in 1..4) {
                val p = m["p$i"]
                val s = m["s$i"]
                if (p != null && s != null && s > 0) {
                    sumPS += p * s
                    sumS += s
                }
            }
            if (sumS <= 0) "Isi minimal satu pasang harga dan ukuran." else
                "Rata-rata: ${n(sumPS / sumS, 5)}\nTotal ukuran: ${n(sumS, 4)}"
        },
        Calc(
            "partial", "Partial close",
            "Hasil sebagian posisi yang ditutup.",
            "Lot ditutup = lot × persen ÷ 100. P/L terealisasi = (harga tutup − entry) × arah × lot ditutup × ukuran kontrak.",
            listOf(
                Field("entry", "Entry", "1.1000"), Field("close", "Harga tutup parsial", "1.1040"),
                Field("lots", "Lot total", "1"), Field("pct", "Persen ditutup (%)", "50"),
                Field("contract", "Ukuran kontrak", "100000"), Field("dir", "Arah (1 / -1)", "1")
            )
        ) { m ->
            need(m, "entry", "close", "lots", "pct", "contract", "dir") ?: run {
                if (m["pct"]!! !in 0.0..100.0) "Persen harus 0–100." else {
                    val closed = m["lots"]!! * m["pct"]!! / 100.0
                    val pnl = (m["close"]!! - m["entry"]!!) * m["dir"]!! * closed * m["contract"]!!
                    "Lot ditutup: ${n(closed, 3)}\nLot tersisa: ${n(m["lots"]!! - closed, 3)}\nP/L terealisasi: ${n(pnl)}"
                }
            }
        },
        Calc(
            "multi", "Risiko beberapa posisi",
            "Total risiko gabungan.",
            "Total risiko = Σ risiko% tiap posisi. Nominal = saldo × total ÷ 100. Asumsi: semua posisi bisa kena SL bersamaan (skenario terburuk).",
            listOf(
                Field("bal", "Saldo akun", "1000"), Field("r1", "Risiko posisi 1 (%)", "1"),
                Field("r2", "Risiko posisi 2 (%)"), Field("r3", "Risiko posisi 3 (%)"),
                Field("r4", "Risiko posisi 4 (%)")
            )
        ) { m ->
            if (!m.containsKey("bal")) "Isi saldo akun." else {
                val total = (1..4).sumOf { m["r$it"] ?: 0.0 }
                "Total risiko: ${n(total)}%\nNominal     : ${n(m["bal"]!! * total / 100.0)}"
            }
        },
        Calc(
            "fx", "Konversi mata uang (kurs manual)",
            "Konversi dengan kurs yang kamu isi.",
            "Hasil = jumlah × kurs. Aplikasi tidak mengambil kurs otomatis, jadi isi kurs dari sumber tepercaya.",
            listOf(Field("amt", "Jumlah", "100"), Field("rate", "Kurs", "16000"))
        ) { m ->
            need(m, "amt", "rate") ?: "Hasil: ${n(m["amt"]!! * m["rate"]!!)}"
        },
        Calc(
            "cost", "Biaya transaksi",
            "Biaya spread + komisi per transaksi.",
            "Biaya = spread (pip) × nilai pip per lot × lot + komisi per lot × lot.",
            listOf(
                Field("spread", "Spread (pip)", "1.2"), Field("pv", "Nilai pip per lot", "10"),
                Field("lots", "Lot", "1"), Field("comm", "Komisi per lot", "7")
            )
        ) { m ->
            need(m, "spread", "pv", "lots", "comm") ?: run {
                val spreadCost = m["spread"]!! * m["pv"]!! * m["lots"]!!
                val comm = m["comm"]!! * m["lots"]!!
                "Biaya spread: ${n(spreadCost)}\nKomisi      : ${n(comm)}\nTotal       : ${n(spreadCost + comm)}"
            }
        }
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
