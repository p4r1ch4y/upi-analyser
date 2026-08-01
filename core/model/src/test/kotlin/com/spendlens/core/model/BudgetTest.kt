package com.spendlens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetWindowTest {

    private fun date(value: String) = LocalDate.parse(value)

    private fun monthly(anchor: String, today: String) =
        BudgetWindow.of(BudgetPeriod.MONTHLY, date(anchor), date(today))

    private fun weekly(anchor: String, today: String) =
        BudgetWindow.of(BudgetPeriod.WEEKLY, date(anchor), date(today))

    @Test
    fun `a monthly budget anchored on the 1st runs the calendar month`() {
        val (start, end) = monthly(anchor = "2026-01-01", today = "2026-08-14")
        assertEquals(date("2026-08-01"), start)
        assertEquals(date("2026-08-31"), end)
    }

    /**
     * The reason the anchor exists at all: salaries land on the 1st, the 7th and
     * the 25th depending on the employer, and a month that resets on a day the
     * money does not arrive is wrong for its own first week.
     */
    @Test
    fun `a monthly budget anchored mid-month runs anchor to anchor`() {
        val (start, end) = monthly(anchor = "2026-03-25", today = "2026-08-14")
        assertEquals(date("2026-07-25"), start)
        assertEquals(date("2026-08-24"), end)
    }

    @Test
    fun `before the anchor day the period is still the previous one`() {
        val (start, end) = monthly(anchor = "2026-03-25", today = "2026-08-24")
        assertEquals(date("2026-07-25"), start)
        assertEquals(date("2026-08-24"), end)
    }

    @Test
    fun `on the anchor day a new period begins`() {
        val (start, end) = monthly(anchor = "2026-03-25", today = "2026-08-25")
        assertEquals(date("2026-08-25"), start)
        assertEquals(date("2026-09-24"), end)
    }

    /** February has no 31st, so the period is pulled back to the last day it has. */
    @Test
    fun `an anchor past the end of a short month clamps to that month`() {
        val (start, end) = monthly(anchor = "2026-01-31", today = "2026-03-01")
        assertEquals(date("2026-02-28"), start)
        assertEquals(date("2026-03-30"), end)
    }

    /**
     * The drift this guards against: computing each period from the previous
     * *start* rather than from the anchor walks a 31st budget back to the 28th in
     * one short month and leaves it there forever.
     */
    @Test
    fun `a clamped month does not drag the following months back with it`() {
        val (start, end) = monthly(anchor = "2026-01-31", today = "2026-04-05")
        // March has a 31st, so the period recovers the anchor day rather than
        // staying on February's 28th. It ends the day before April's clamped
        // 30th, so consecutive periods meet without a gap or an overlap.
        assertEquals(date("2026-03-31"), start)
        assertEquals(date("2026-04-29"), end)
    }

    @Test
    fun `a leap February takes the 29th`() {
        val (start, _) = monthly(anchor = "2024-01-31", today = "2024-02-29")
        assertEquals(date("2024-02-29"), start)
    }

    @Test
    fun `a weekly budget runs seven days from the anchor's weekday`() {
        // 2026-08-03 is a Monday, as is 2026-07-27.
        val (start, end) = weekly(anchor = "2026-07-27", today = "2026-08-06")
        assertEquals(date("2026-08-03"), start)
        assertEquals(date("2026-08-09"), end)
    }

    @Test
    fun `a weekly period starting today is today`() {
        val (start, end) = weekly(anchor = "2026-07-27", today = "2026-08-03")
        assertEquals(date("2026-08-03"), start)
        assertEquals(date("2026-08-09"), end)
    }

    /** The anchor may sit in the future; the window still lands around today. */
    @Test
    fun `a weekly anchor later than today still resolves`() {
        val (start, end) = weekly(anchor = "2026-09-04", today = "2026-08-06")
        assertEquals(date("2026-07-31"), start)   // the most recent Friday
        assertEquals(date("2026-08-06"), end)
    }
}

class BudgetProgressTest {

    private fun date(value: String) = LocalDate.parse(value)

    private fun progress(
        spentRupees: Long,
        limitRupees: Long = 10_000,
        start: String = "2026-08-01",
        end: String = "2026-08-31",
        today: String = "2026-08-10"
    ) = BudgetProgress(
        budget = Budget(
            id = "b1",
            name = "Everything",
            scope = BudgetScope.TOTAL,
            scopeValue = null,
            period = BudgetPeriod.MONTHLY,
            limitMinor = limitRupees * 100,
            currency = "INR",
            anchorAt = 0L
        ),
        spentMinor = spentRupees * 100,
        paymentCount = 12,
        start = date(start),
        end = date(end),
        today = date(today)
    )

    @Test
    fun `today counts as elapsed`() {
        val p = progress(spentRupees = 0)
        assertEquals(31, p.totalDays)
        assertEquals(10, p.elapsedDays)
        assertEquals(21, p.daysLeft)
    }

    @Test
    fun `spending evenly is on pace`() {
        // 10 of 31 days gone, 10/31 of the limit spent.
        val p = progress(spentRupees = 3_225)
        assertTrue(p.isOnPace)
        assertEquals(BudgetProgress.State.ON_TRACK, p.state)
    }

    /**
     * The case the screen exists for: under the limit, so a bare "₹6,000 of
     * ₹10,000" looks fine, and yet the month ends at ₹18,600.
     */
    @Test
    fun `under the limit but spending too fast reads as ahead of pace`() {
        val p = progress(spentRupees = 6_000)
        assertFalse(p.isOver)
        assertFalse(p.isOnPace)
        assertEquals(18_600_00L, p.projectedMinor)
        assertEquals(BudgetProgress.State.AHEAD_OF_PACE, p.state)
    }

    @Test
    fun `over the limit reports a negative remainder and no allowance`() {
        val p = progress(spentRupees = 12_000)
        assertTrue(p.isOver)
        assertEquals(-2_000_00L, p.remainingMinor)
        assertEquals(0L, p.dailyAllowanceMinor)
        assertEquals(BudgetProgress.State.OVER, p.state)
    }

    /** The fill is clamped so an overspent bar is full, never wider than its track. */
    @Test
    fun `the fill never exceeds the bar`() {
        assertEquals(1f, progress(spentRupees = 12_000).fraction, 0.0001f)
    }

    @Test
    fun `the daily allowance spreads what is left over the days that remain`() {
        // ₹4,000 left, 21 days after today plus today itself.
        val p = progress(spentRupees = 6_000)
        assertEquals(4_000_00L / 22, p.dailyAllowanceMinor)
    }

    /** On the last day there are no whole days left, and the divisor must not be zero. */
    @Test
    fun `the last day of a period still yields an allowance`() {
        val p = progress(spentRupees = 6_000, today = "2026-08-31")
        assertEquals(0, p.daysLeft)
        assertEquals(4_000_00L, p.dailyAllowanceMinor)
    }

    @Test
    fun `an untouched budget is called out separately from an on-track one`() {
        assertEquals(BudgetProgress.State.UNTOUCHED, progress(spentRupees = 0).state)
    }
}
