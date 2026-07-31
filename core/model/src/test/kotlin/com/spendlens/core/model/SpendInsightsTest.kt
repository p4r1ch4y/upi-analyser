package com.spendlens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The figures that turn a total into a judgement.
 *
 * A total on its own answers nothing — ₹8,000 this month is only news against
 * what last month was.
 */
class SpendInsightsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun spent(date: String, rupees: Long) = SpendEvent(
        LocalDateTime.parse("${date}T12:00").atZone(zone).toInstant().toEpochMilli(),
        rupees * 100, isCredit = false
    )

    private fun buckets(from: String, to: String, vararg events: SpendEvent) =
        SpendSeries.byDay(events.toList(), LocalDate.parse(from), LocalDate.parse(to), zone)

    @Test
    fun `change against the previous period is signed and proportional`() {
        val now = buckets("2026-07-01", "2026-07-07", spent("2026-07-01", 1200))
        val before = buckets("2026-06-24", "2026-06-30", spent("2026-06-24", 1000))
        val change = SpendSeries.changeVsPrevious(now, before)!!
        assertEquals(20_000L, change.deltaMinor)
        assertTrue(change.isUp)
        assertEquals(0.2f, change.fraction, 0.001f)
    }

    @Test
    fun `spending less reads as a fall, not a negative rise`() {
        val now = buckets("2026-07-01", "2026-07-07", spent("2026-07-01", 800))
        val before = buckets("2026-06-24", "2026-06-30", spent("2026-06-24", 1000))
        val change = SpendSeries.changeVsPrevious(now, before)!!
        assertTrue(!change.isUp)
        assertEquals(-0.2f, change.fraction, 0.001f)
    }

    /** No baseline means no claim — better silent than dividing by zero. */
    @Test
    fun `a period with nothing before it reports no change rather than infinity`() {
        val now = buckets("2026-07-01", "2026-07-07", spent("2026-07-01", 800))
        val before = buckets("2026-06-24", "2026-06-30")
        assertNull(SpendSeries.changeVsPrevious(now, before))
    }

    @Test
    fun `spend-free days are counted`() {
        val b = buckets("2026-07-01", "2026-07-07", spent("2026-07-01", 100), spent("2026-07-03", 100))
        assertEquals(5, SpendSeries.spendFreeDays(b))
    }

    /**
     * The two averages answer different questions and the gap between them is
     * the interesting part, so both are reported.
     */
    @Test
    fun `the two averages differ when there are quiet days`() {
        val b = buckets("2026-07-01", "2026-07-10", spent("2026-07-01", 500))
        assertEquals(5_000L, SpendSeries.dailyAverageMinor(b))
        assertEquals(50_000L, SpendSeries.averageOnSpendingDays(b))
    }

    @Test
    fun `no spending at all does not divide by zero`() {
        val b = buckets("2026-07-01", "2026-07-07")
        assertEquals(0L, SpendSeries.averageOnSpendingDays(b))
        assertEquals(7, SpendSeries.spendFreeDays(b))
    }
}
