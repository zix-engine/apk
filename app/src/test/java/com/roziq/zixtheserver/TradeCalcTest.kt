package com.roziq.zixtheserver

import org.junit.Assert.assertTrue
import org.junit.Test

class TradeCalcTest {

    private fun run(id: String, vararg v: Pair<String, Double>): String =
        TradeCalc.byId(id)!!.compute(mapOf(*v))

    @Test
    fun lotSizeRoundsDownWithoutFloatingError() {
        val out = run("lot", "bal" to 1000.0, "risk" to 1.0, "entry" to 1.1, "sl" to 1.095, "pip" to 0.0001, "pv" to 10.0)
        assertTrue(out, out.contains("50.0 pip"))
        assertTrue(out, out.contains("0.020"))
        assertTrue(out, out.contains("0.02"))
        assertTrue(out, !out.contains(": 0.01"))
    }

    @Test
    fun riskReward() {
        val out = run("rr", "entry" to 100.0, "sl" to 98.0, "tp" to 106.0)
        assertTrue(out, out.contains("1 : 3.00"))
        assertTrue(out, out.contains("25.0%"))
    }

    @Test
    fun weightedAverageEntry() {
        val out = run("avg", "p1" to 100.0, "s1" to 1.0, "p2" to 95.0, "s2" to 2.0)
        assertTrue(out, out.contains("96.66667"))
    }

    @Test
    fun invalidInputsGiveMessageNotCrash() {
        val out = run("lot", "bal" to 1000.0)
        assertTrue(out, out.contains("Isi semua kolom"))
        val same = run("rr", "entry" to 100.0, "sl" to 100.0, "tp" to 101.0)
        assertTrue(same, same.contains("tidak boleh sama"))
    }

    @Test
    fun partialClose() {
        val out = run("partial", "entry" to 1.1, "close" to 1.104, "lots" to 1.0, "pct" to 50.0, "contract" to 100000.0, "dir" to 1.0)
        assertTrue(out, out.contains("0.500"))
        assertTrue(out, out.contains("200.00"))
    }
}
