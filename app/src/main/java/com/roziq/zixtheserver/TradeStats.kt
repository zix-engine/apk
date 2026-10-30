package com.roziq.zixtheserver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class Group(val key: String, val n: Int, val netR: Double, val winRate: Double?)

data class Stats(
    val total: Int,
    val open: Int,
    val wins: Int,
    val losses: Int,
    val be: Int,
    val partial: Int,
    val winRate: Double?,
    val winRateExBE: Double?,
    val rCount: Int,
    val netR: Double,
    val avgR: Double?,
    val avgWinR: Double?,
    val avgLossR: Double?,
    val expectancy: Double?,
    val grossProfit: Double?,
    val grossLoss: Double?,
    val netPnl: Double?,
    val profitFactor: Double?,
    val pfBasis: String,
    val maxDdR: Double,
    val maxLossStreak: Int,
    val equity: List<Double>,
    val from: Long?,
    val to: Long?,
    val byMonth: List<Group>,
    val byInstrument: List<Group>,
    val byTf: List<Group>,
    val bySetup: List<Group>,
    val bySession: List<Group>
)

/**
 * Semua angka dihitung dari transaksi yang tersimpan. Definisi:
 * - Kategori: WIN/LOSS/BE/PARTIAL dari kolom hasil, atau dari tanda R bila kolom hasil kosong.
 *   Transaksi tanpa hasil dan tanpa R yang bisa dihitung = OPEN (tidak ikut statistik).
 * - Win rate = WIN / (WIN + LOSS + BE + PARTIAL). Win rate tanpa BE = WIN / (WIN + LOSS).
 * - Metrik R (net R, rata-rata, expectancy, drawdown) hanya memakai transaksi yang R-nya bisa dihitung.
 * - Profit factor: dari nominal (pnl - biaya) bila ada transaksi ber-pnl; jika tidak, dari R.
 *   Bila total kerugian = 0 hasilnya N/A (null), bukan angka tak hingga.
 * - Max drawdown (R) = selisih terbesar antara puncak equity kumulatif R dan titik setelahnya, urut waktu.
 */
object TradeStats {

    private val FMT_MONTH = SimpleDateFormat("yyyy-MM", Locale.US)

    fun compute(all: List<Trade>): Stats {
        val trades = all.sortedBy { it.ts }
        var wins = 0
        var losses = 0
        var be = 0
        var partial = 0
        var open = 0
        val rs = ArrayList<Double>()
        val equity = ArrayList<Double>()
        equity.add(0.0)
        var cum = 0.0
        var peak = 0.0
        var maxDd = 0.0
        var streak = 0
        var maxStreak = 0

        var gp = 0.0
        var gl = 0.0
        var net = 0.0
        var pnlCount = 0

        for (t in trades) {
            when (t.category()) {
                "WIN" -> wins++
                "LOSS" -> losses++
                "BE" -> be++
                "PARTIAL" -> partial++
                else -> open++
            }
            val r = t.r()
            if (r != null && t.category() != "OPEN") {
                rs.add(r)
                cum += r
                equity.add(cum)
                if (cum > peak) peak = cum
                if (peak - cum > maxDd) maxDd = peak - cum
                if (r < 0) {
                    streak++
                    if (streak > maxStreak) maxStreak = streak
                } else {
                    streak = 0
                }
            }
            val p = t.pnl
            if (p != null) {
                val v = p - t.fees
                pnlCount++
                net += v
                if (v > 0) gp += v else gl += -v
            }
        }

        val classified = wins + losses + be + partial
        val winRate = if (classified > 0) wins * 100.0 / classified else null
        val winRateEx = if (wins + losses > 0) wins * 100.0 / (wins + losses) else null
        val netR = rs.sum()
        val avgR = if (rs.isNotEmpty()) netR / rs.size else null
        val winRs = rs.filter { it > 0 }
        val lossRs = rs.filter { it < 0 }
        val avgWin = if (winRs.isNotEmpty()) winRs.average() else null
        val avgLoss = if (lossRs.isNotEmpty()) lossRs.average() else null

        val pf: Double?
        val basis: String
        if (pnlCount > 0) {
            basis = "nominal"
            pf = if (gl > 0) gp / gl else null
        } else {
            basis = "R"
            val gR = winRs.sum()
            val lR = -lossRs.sum()
            pf = if (lR > 0) gR / lR else null
        }

        fun group(key: (Trade) -> String): List<Group> {
            val map = LinkedHashMap<String, MutableList<Trade>>()
            for (t in trades) {
                if (t.category() == "OPEN") continue
                val k = key(t).trim().ifEmpty { "-" }
                map.getOrPut(k) { ArrayList() }.add(t)
            }
            return map.map { (k, list) ->
                val w = list.count { it.category() == "WIN" }
                val rr = list.mapNotNull { it.r() }.sum()
                Group(k, list.size, rr, if (list.isNotEmpty()) w * 100.0 / list.size else null)
            }.sortedByDescending { it.netR }
        }

        val counted = trades.filter { it.category() != "OPEN" }
        return Stats(
            total = trades.size, open = open, wins = wins, losses = losses, be = be, partial = partial,
            winRate = winRate, winRateExBE = winRateEx, rCount = rs.size, netR = netR, avgR = avgR,
            avgWinR = avgWin, avgLossR = avgLoss, expectancy = avgR,
            grossProfit = if (pnlCount > 0) gp else null, grossLoss = if (pnlCount > 0) gl else null,
            netPnl = if (pnlCount > 0) net else null, profitFactor = pf, pfBasis = basis,
            maxDdR = maxDd, maxLossStreak = maxStreak, equity = equity,
            from = counted.firstOrNull()?.ts, to = counted.lastOrNull()?.ts,
            byMonth = group { FMT_MONTH.format(Date(it.ts)) }.sortedBy { it.key },
            byInstrument = group { it.instrument }, byTf = group { it.tf },
            bySetup = group { it.setup }, bySession = group { it.session }
        )
    }

    private fun f(v: Double?, digits: Int = 2, suffix: String = ""): String =
        if (v == null || v.isNaN() || v.isInfinite()) "N/A" else String.format(Locale.US, "%." + digits + "f", v) + suffix

    fun summary(s: Stats): String {
        val sb = StringBuilder()
        val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        if (s.from != null && s.to != null) {
            sb.append("Rentang   : ${df.format(Date(s.from))} – ${df.format(Date(s.to))}\n")
        }
        sb.append("Transaksi : ${s.total}")
        if (s.open > 0) sb.append("  (${s.open} belum berhasil/tanpa R, tidak dihitung)")
        sb.append('\n')
        sb.append("Win/Loss  : ${s.wins} / ${s.losses}   BE ${s.be}   Partial ${s.partial}\n")
        sb.append("Win rate  : ${f(s.winRate, 1, "%")}   (tanpa BE: ${f(s.winRateExBE, 1, "%")})\n")
        sb.append("Net R     : ${f(s.netR, 2, "R")}   Rata-rata: ${f(s.avgR, 2, "R")}   (n=${s.rCount})\n")
        sb.append("Avg win   : ${f(s.avgWinR, 2, "R")}   Avg loss: ${f(s.avgLossR, 2, "R")}\n")
        sb.append("Expectancy: ${f(s.expectancy, 2, "R")} per trade\n")
        sb.append("Prof.factor: ${f(s.profitFactor, 2)}  (dasar: ${s.pfBasis})\n")
        if (s.netPnl != null) {
            sb.append("Nominal   : net ${f(s.netPnl)}  profit ${f(s.grossProfit)}  rugi ${f(s.grossLoss)}\n")
        }
        sb.append("Max DD    : ${f(s.maxDdR, 2, "R")}   Loss beruntun maks: ${s.maxLossStreak}")
        return sb.toString()
    }

    fun groupText(title: String, list: List<Group>): String {
        if (list.isEmpty()) return "$title: belum ada data."
        val sb = StringBuilder(title).append('\n')
        for (g in list) {
            sb.append(String.format(Locale.US, "%-14s n=%-3d net %+.2fR  win %s\n", g.key.take(14), g.n, g.netR, f(g.winRate, 0, "%")))
        }
        return sb.toString().trimEnd()
    }

    fun csv(trades: List<Trade>): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("tanggal,instrumen,tf,sisi,entry,sl,tp,exit,hasil,r,pnl,biaya,risiko_persen,setup,sesi,alasan,catatan,kesalahan\n")
        for (t in trades.sortedBy { it.ts }) {
            sb.append(df.format(Date(t.ts))).append(',').append(q(t.instrument)).append(',').append(q(t.tf)).append(',')
                .append(t.side).append(',').append(t.entry ?: "").append(',').append(t.sl ?: "").append(',')
                .append(t.tp ?: "").append(',').append(t.exit ?: "").append(',').append(t.category()).append(',')
                .append(t.r()?.let { String.format(Locale.US, "%.4f", it) } ?: "").append(',')
                .append(t.pnl ?: "").append(',').append(t.fees).append(',').append(t.riskPct ?: "").append(',')
                .append(q(t.setup)).append(',').append(q(t.session)).append(',').append(q(t.reason)).append(',')
                .append(q(t.notes)).append(',').append(q(t.mistakes)).append('\n')
        }
        return sb.toString()
    }

    /** Perbandingan satu baris per proyek. */
    fun compareLine(name: String, s: Stats): String =
        String.format(Locale.US, "%-16s n=%-3d win %s  net %sR  PF %s  DD %sR",
            name.take(16), s.wins + s.losses + s.be + s.partial, f(s.winRate, 0, "%"),
            f(s.netR, 1), f(s.profitFactor, 2), f(s.maxDdR, 1))
}
