package com.roziq.zixtheserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeStatsTest {

    private fun t(
        ts: Long, side: String = "BUY", entry: Double? = null, sl: Double? = null,
        exit: Double? = null, result: String = ""
    ) = Trade(id = "t$ts", projectId = "p", ts = ts, side = side, entry = entry, sl = sl, exit = exit, result = result)

    // WIN +2R, LOSS -1R, WIN +2R (SELL), BE, LOSS tanpa harga, OPEN
    private val sample = listOf(
        t(1, entry = 100.0, sl = 98.0, exit = 104.0),
        t(2, entry = 100.0, sl = 98.0, exit = 98.0),
        t(3, side = "SELL", entry = 100.0, sl = 102.0, exit = 96.0),
        t(4, entry = 100.0, sl = 98.0, result = "BE"),
        t(5, result = "LOSS"),
        t(6)
    )

    @Test
    fun rComputedForBuyAndSell() {
        assertEquals(2.0, sample[0].r()!!, 1e-9)
        assertEquals(-1.0, sample[1].r()!!, 1e-9)
        assertEquals(2.0, sample[2].r()!!, 1e-9)
        assertEquals(0.0, sample[3].r()!!, 1e-9)
        assertEquals(-1.0, sample[4].r()!!, 1e-9)
        assertNull(sample[5].r())
    }

    @Test
    fun counts() {
        val s = TradeStats.compute(sample)
        assertEquals(6, s.total)
        assertEquals(2, s.wins)
        assertEquals(2, s.losses)
        assertEquals(1, s.be)
        assertEquals(1, s.open)
    }

    @Test
    fun ratesAndR() {
        val s = TradeStats.compute(sample)
        assertEquals(40.0, s.winRate!!, 1e-9)
        assertEquals(50.0, s.winRateExBE!!, 1e-9)
        assertEquals(2.0, s.netR, 1e-9)
        assertEquals(0.4, s.avgR!!, 1e-9)
        assertEquals(2.0, s.avgWinR!!, 1e-9)
        assertEquals(-1.0, s.avgLossR!!, 1e-9)
        assertEquals(2.0, s.profitFactor!!, 1e-9)
        assertEquals("R", s.pfBasis)
    }

    @Test
    fun drawdownAndStreak() {
        val s = TradeStats.compute(sample)
        assertEquals(listOf(0.0, 2.0, 1.0, 3.0, 3.0, 2.0), s.equity)
        assertEquals(1.0, s.maxDdR, 1e-9)
        assertEquals(1, s.maxLossStreak)
    }

    @Test
    fun noLossesGivesNaProfitFactor() {
        val s = TradeStats.compute(listOf(t(1, entry = 10.0, sl = 9.0, exit = 12.0), t(2, entry = 10.0, sl = 9.0, exit = 11.0)))
        assertNull(s.profitFactor)
        assertEquals(100.0, s.winRate!!, 1e-9)
    }

    @Test
    fun emptyListIsSafe() {
        val s = TradeStats.compute(emptyList())
        assertEquals(0, s.total)
        assertNull(s.winRate)
        assertNull(s.avgR)
        assertEquals(0.0, s.maxDdR, 1e-9)
    }

    @Test
    fun nominalProfitFactorUsesFees() {
        val a = t(1, entry = 10.0, sl = 9.0, exit = 12.0).copy(pnl = 100.0, fees = 10.0)   // +90
        val b = t(2, entry = 10.0, sl = 9.0, exit = 9.0).copy(pnl = -45.0, fees = 0.0)     // -45
        val s = TradeStats.compute(listOf(a, b))
        assertEquals("nominal", s.pfBasis)
        assertEquals(2.0, s.profitFactor!!, 1e-9)
        assertEquals(45.0, s.netPnl!!, 1e-9)
    }

    @Test
    fun csvEscapesQuotes() {
        val csv = TradeStats.csv(listOf(t(1).copy(notes = "bilang \"halo\"")))
        assertTrue(csv.contains("\"bilang \"\"halo\"\"\""))
    }
}
