package com.spendlens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SpendSeriesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(date: String, time: String = "12:00"): Long =
        LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    private fun spent(date: String, rupees: Long, time: String = "12:00") =
        SpendEvent(at(date, time), rupees * 100, isCredit = false)

    private fun received(date: String, rupees: Long) =
        SpendEvent(at(date), rupees * 100, isCredit = true)

    @Test
    fun `payments land in the day they happened`() {
        val buckets = SpendSeries.byDay(
            events = listOf(spent("2026-07-24", 250), spent("2026-07-24", 80), spent("2026-07-26", 20)),
            from = LocalDate.parse("2026-07-24"),
            to = LocalDate.parse("2026-07-26"),
            zone = zone
        )
        assertEquals(3, buckets.size)
        assertEquals(33_000L, buckets[0].spentMinor)
        assertEquals(2, buckets[0].count)
        assertEquals(0L, buckets[1].spentMinor)
        assertEquals(2_000L, buckets[2].spentMinor)
    }

    /**
     * A spend chart that omits the quiet days compresses its own axis and makes a
     * fortnight of nothing look like continuous activity.
     */
    @Test
    fun `days with no payments are still emitted as zero`() {
        val buckets = SpendSeries.byDay(
            events = listOf(spent("2026-07-01", 100)),
            from = LocalDate.parse("2026-07-01"),
            to = LocalDate.parse("2026-07-31"),
            zone = zone
        )
        assertEquals(31, buckets.size)
        assertEquals(30, buckets.count { it.spentMinor == 0L })
    }

    @Test
    fun `credits are kept apart from spending, not netted into it`() {
        val buckets = SpendSeries.byDay(
            events = listOf(spent("2026-07-24", 250), received("2026-07-24", 1_000)),
            from = LocalDate.parse("2026-07-24"),
            to = LocalDate.parse("2026-07-24"),
            zone = zone
        )
        assertEquals(25_000L, buckets.single().spentMinor)
        assertEquals(1_00_000L, buckets.single().receivedMinor)
        assertEquals(-75_000L, SpendSeries.netMinor(buckets))
    }

    /**
     * A payment at 00:30 IST is the same UTC instant as 19:00 the previous day.
     * Bucketing in SQL would need the offset passed in and would file this on the
     * wrong day.
     */
    @Test
    fun `bucketing follows the local day, not utc`() {
        val justAfterMidnight = spent("2026-07-25", 100, time = "00:30")
        val buckets = SpendSeries.byDay(
            events = listOf(justAfterMidnight),
            from = LocalDate.parse("2026-07-24"),
            to = LocalDate.parse("2026-07-25"),
            zone = zone
        )
        assertEquals(0L, buckets[0].spentMinor)
        assertEquals(10_000L, buckets[1].spentMinor)
    }

    /**
     * Averaging only over days that had payments answers "what do I spend on a day
     * I spend anything", which always reads high and is not what anyone means.
     */
    @Test
    fun `the daily average counts the quiet days too`() {
        val buckets = SpendSeries.byDay(
            events = listOf(spent("2026-07-01", 700)),
            from = LocalDate.parse("2026-07-01"),
            to = LocalDate.parse("2026-07-07"),
            zone = zone
        )
        assertEquals(10_000L, SpendSeries.dailyAverageMinor(buckets))  // ₹700 over 7 days
    }

    @Test
    fun `the busiest day is the one with the most spent, not the most payments`() {
        val buckets = SpendSeries.byDay(
            events = listOf(
                spent("2026-07-01", 10), spent("2026-07-01", 10), spent("2026-07-01", 10),
                spent("2026-07-02", 500)
            ),
            from = LocalDate.parse("2026-07-01"),
            to = LocalDate.parse("2026-07-02"),
            zone = zone
        )
        assertEquals(LocalDate.parse("2026-07-02"), SpendSeries.busiestDay(buckets)!!.date)
    }

    @Test
    fun `an empty range is handled without dividing by zero`() {
        val buckets = SpendSeries.byDay(emptyList(), LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-01"), zone)
        assertEquals(1, buckets.size)
        assertEquals(0L, SpendSeries.dailyAverageMinor(buckets))
        assertEquals(0L, SpendSeries.dailyAverageMinor(emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a backwards range is rejected rather than silently returning nothing`() {
        SpendSeries.byDay(emptyList(), LocalDate.parse("2026-07-31"), LocalDate.parse("2026-07-01"), zone)
    }

    @Test
    fun `a split payment contributes only the user's share`() {
        // The caller reduces to effective cost; this pins the contract that the
        // series never sees the gross amount of a split payment.
        val myShare = SpendEvent(at("2026-07-24"), 60_000, isCredit = false)
        val buckets = SpendSeries.byDay(listOf(myShare), LocalDate.parse("2026-07-24"), LocalDate.parse("2026-07-24"), zone)
        assertEquals(60_000L, buckets.single().spentMinor)
        assertTrue(buckets.single().spentMinor < 2_40_000L)
    }
}
