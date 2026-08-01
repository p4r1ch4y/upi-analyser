package com.spendlens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Every summary on the report has to follow the direction chips.
 *
 * Found on a real ledger after an SMS import: the headline read "₹71,104 income"
 * and directly underneath it sat "biggest day ₹1,50,000" and "days you spent
 * nothing: 225" — both spending figures, both looking like answers to the
 * question the headline had just asked.
 */
class DirectionalSummaryTest {

    private fun day(date: String, spent: Long, received: Long) = DayBucket(
        date = LocalDate.parse(date),
        spentMinor = spent * 100,
        receivedMinor = received * 100,
        count = 1
    )

    private val week = listOf(
        day("2026-08-01", spent = 100, received = 0),
        day("2026-08-02", spent = 0, received = 5_000),
        day("2026-08-03", spent = 900, received = 0)
    )

    @Test
    fun `the daily average follows the direction`() {
        assertEquals(1_000_00L / 3, SpendSeries.dailyAverageMinor(week, credits = false))
        assertEquals(5_000_00L / 3, SpendSeries.dailyAverageMinor(week, credits = true))
    }

    @Test
    fun `the biggest day is the biggest on the side being reported`() {
        assertEquals(
            LocalDate.parse("2026-08-03"),
            SpendSeries.busiestDay(week, credits = false)?.date
        )
        assertEquals(
            LocalDate.parse("2026-08-02"),
            SpendSeries.busiestDay(week, credits = true)?.date
        )
    }

    /** A range with no credits at all has no biggest income day, rather than a zero one. */
    @Test
    fun `a side with nothing on it reports no busiest day`() {
        val spendOnly = listOf(day("2026-08-01", spent = 100, received = 0))
        assertNull(SpendSeries.busiestDay(spendOnly, credits = true))
    }

    @Test
    fun `quiet days are counted on the side being reported`() {
        assertEquals(1, SpendSeries.spendFreeDays(week, credits = false))
        assertEquals(2, SpendSeries.spendFreeDays(week, credits = true))
    }

    @Test
    fun `the average on active days ignores the other side's days`() {
        assertEquals(500_00L, SpendSeries.averageOnSpendingDays(week, credits = false))
        assertEquals(5_000_00L, SpendSeries.averageOnSpendingDays(week, credits = true))
    }

    /**
     * The headline bug: the comparison line reported the change in *spending*
     * while the headline reported income.
     */
    @Test
    fun `the comparison compares the side the headline is about`() {
        val previous = listOf(day("2026-07-01", spent = 500, received = 1_000))

        val spend = SpendSeries.changeVsPrevious(week, previous, credits = false)!!
        assertEquals(1_000_00L, spend.nowMinor)
        assertEquals(500_00L, spend.beforeMinor)

        val income = SpendSeries.changeVsPrevious(week, previous, credits = true)!!
        assertEquals(5_000_00L, income.nowMinor)
        assertEquals(1_000_00L, income.beforeMinor)
    }

    /**
     * A percentage against a near-empty period is arithmetically right and
     * useless. Nobody holds a ninety-one-fold rise in their head as "9127%".
     */
    @Test
    fun `a huge rise is reported as a multiple rather than a percentage`() {
        val tiny = listOf(day("2026-07-01", spent = 10, received = 0))
        val big = listOf(day("2026-08-01", spent = 920, received = 0))

        val change = SpendSeries.changeVsPrevious(big, tiny)!!
        assertTrue(change.isLarge)
        assertEquals(92L, change.multiple)
    }

    @Test
    fun `an ordinary rise stays a percentage`() {
        val before = listOf(day("2026-07-01", spent = 1_000, received = 0))
        val after = listOf(day("2026-08-01", spent = 1_230, received = 0))

        val change = SpendSeries.changeVsPrevious(after, before)!!
        assertFalse(change.isLarge)
        assertEquals(0.23f, change.fraction, 0.001f)
        assertTrue(change.isUp)
    }

    /** No previous figure on this side means no comparison, not a division by zero. */
    @Test
    fun `no credits in the previous period yields no income comparison`() {
        val previous = listOf(day("2026-07-01", spent = 500, received = 0))
        assertNull(SpendSeries.changeVsPrevious(week, previous, credits = true))
    }
}
