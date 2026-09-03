package com.spendlens.core.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * SpendLens must be able to read its own export back without losing the clock.
 *
 * Found on a real ledger: 25 of 161 payments in one month sat at exactly 00:00,
 * and the correct time was visible in each row's own stored source message. The
 * export writes `Date` and `Time` as two columns and the importer read only the
 * first, so every re-imported payment was filed at midnight — which quietly
 * destroys the one signal that makes a nameless ₹10 row readable later ("between
 * 10am and 6pm, so it was a fare").
 */
class CsvRoundTripTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun timeOf(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    @Test
    fun `a separate Time column survives the round trip`() {
        val outcome = CsvStatementParser.parse(
            lines = listOf(
                "Date,Time,Description,Amount,Currency,Direction",
                "2026-08-06,17:52,Rent,-7413.00,INR,DEBIT"
            ),
            zone = zone
        )
        val txn = outcome.transactions.single()
        val at = timeOf(txn.occurredAt!!)
        assertEquals(17, at.hour)
        assertEquals(52, at.minute)
        assertEquals(741_300L, txn.amountMinor)
    }

    /** An export without a time column still parses, at the start of the day. */
    @Test
    fun `a date-only export still imports`() {
        val outcome = CsvStatementParser.parse(
            lines = listOf(
                "Txn Date,Description,Withdrawal,Deposit",
                "06/08/2026,Rent,7413.00,"
            ),
            zone = zone
        )
        assertEquals(0, timeOf(outcome.transactions.single().occurredAt!!).hour)
    }

    /** A malformed time must not take the row down with it. */
    @Test
    fun `an unreadable time falls back to the date rather than dropping the row`() {
        val outcome = CsvStatementParser.parse(
            lines = listOf(
                "Date,Time,Description,Amount,Currency,Direction",
                "2026-08-06,not-a-time,Rent,-7413.00,INR,DEBIT"
            ),
            zone = zone
        )
        assertEquals(1, outcome.transactions.size)
        assertEquals(0, timeOf(outcome.transactions.single().occurredAt!!).hour)
    }
}
