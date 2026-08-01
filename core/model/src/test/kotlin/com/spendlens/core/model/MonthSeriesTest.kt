package com.spendlens.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MonthSeriesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(date: String): Long =
        LocalDateTime.parse("${date}T12:00").atZone(zone).toInstant().toEpochMilli()

    private fun spent(date: String, rupees: Long) =
        SpendEvent(at(date), rupees * 100, isCredit = false)

    private fun received(date: String, rupees: Long) =
        SpendEvent(at(date), rupees * 100, isCredit = true)

    @Test
    fun `payments land in the month they happened`() {
        val months = SpendSeries.byMonth(
            events = listOf(
                spent("2026-06-14", 500),
                spent("2026-08-02", 300),
                received("2026-08-20", 900)
            ),
            monthsBack = 3,
            endingIn = LocalDate.parse("2026-08-01"),
            zone = zone
        )

        assertEquals(3, months.size)
        assertEquals(6, months[0].month)
        assertEquals(50_000L, months[0].spentMinor)
        assertEquals(30_000L, months[2].spentMinor)
        assertEquals(90_000L, months[2].receivedMinor)
    }

    /**
     * The quiet months are the point. A year with three empty months compressed
     * out of it reads as a year of steady spending, and the quiet ones are
     * usually what someone is looking for.
     */
    @Test
    fun `months with nothing in them are still reported`() {
        val months = SpendSeries.byMonth(
            events = listOf(spent("2026-08-02", 300)),
            monthsBack = 3,
            endingIn = LocalDate.parse("2026-08-01"),
            zone = zone
        )
        assertEquals(3, months.size)
        assertEquals(0L, months[0].spentMinor)
        assertEquals(0, months[1].count)
    }

    @Test
    fun `the window runs oldest first and ends in the given month`() {
        val months = SpendSeries.byMonth(
            events = emptyList(),
            monthsBack = 12,
            endingIn = LocalDate.parse("2026-08-14"),
            zone = zone
        )
        assertEquals(12, months.size)
        assertEquals(2025 to 9, months.first().year to months.first().month)
        assertEquals(2026 to 8, months.last().year to months.last().month)
    }

    @Test
    fun `net is spent minus received and may be negative`() {
        val month = SpendSeries.byMonth(
            events = listOf(spent("2026-08-02", 300), received("2026-08-20", 900)),
            monthsBack = 1,
            endingIn = LocalDate.parse("2026-08-14"),
            zone = zone
        ).single()
        assertEquals(-60_000L, month.netMinor)
        assertEquals(LocalDate.parse("2026-08-01"), month.start)
        assertEquals(LocalDate.parse("2026-08-31"), month.end)
    }
}
