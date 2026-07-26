package com.spendlens.core.parser

import com.spendlens.core.model.Direction
import com.spendlens.core.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CsvStatementParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun parse(vararg lines: String) =
        CsvStatementParser.parse(lines.toList(), observedAt = 0L, zone = zone)

    @Test
    fun `bank export with separate withdrawal and deposit columns`() {
        val outcome = parse(
            "Date,Narration,Chq/Ref No,Withdrawal Amt,Deposit Amt",
            "26/07/2026,UPI-SWIGGY-swiggy@ybl,123456789012,250.00,",
            "25/07/2026,UPI-ASHA-asha@okaxis,998877665544,,1500.00"
        )

        assertEquals(2, outcome.transactions.size)
        assertTrue(outcome.skippedLines.isEmpty())

        val debit = outcome.transactions.first { it.direction == Direction.DEBIT }
        assertEquals(25_000L, debit.amountMinor)
        assertEquals("swiggy@ybl", debit.counterpartyVpa)
        assertEquals("123456789012", debit.rrn)
        assertEquals(Source.STATEMENT, debit.source)

        val credit = outcome.transactions.first { it.direction == Direction.CREDIT }
        assertEquals(1_50_000L, credit.amountMinor)
    }

    @Test
    fun `app export with one signed amount column`() {
        val outcome = parse(
            "Transaction Date,Description,Amount",
            "2026-07-26,Blinkit,-600.50",
            "2026-07-26,Refund,120.00"
        )

        assertEquals(2, outcome.transactions.size)
        assertEquals(Direction.DEBIT, outcome.transactions[0].direction)
        assertEquals(60_050L, outcome.transactions[0].amountMinor)
        assertEquals(Direction.CREDIT, outcome.transactions[1].direction)
    }

    @Test
    fun `a quoted narration containing commas stays one field`() {
        val outcome = parse(
            "Date,Narration,Amount",
            """26/07/2026,"SWIGGY, BANGALORE, IN",-250.00"""
        )
        assertEquals(1, outcome.transactions.size)
        assertEquals("SWIGGY, BANGALORE, IN", outcome.transactions.single().counterpartyNameRaw)
    }

    @Test
    fun `a doubled quote inside a quoted field is a literal quote`() {
        // a,"he said ""hi""",b   ->   [a] ["he said "hi""] [b]
        assertEquals(
            listOf("a", "he said \"hi\"", "b"),
            CsvStatementParser.splitCsvLine("a,\"he said \"\"hi\"\"\",b")
        )
    }

    @Test
    fun `rupee symbols and thousands separators are stripped from amounts`() {
        assertEquals(1_84_000L, CsvStatementParser.parseMinor("₹1,840.00"))
        assertEquals(25_000L, CsvStatementParser.parseMinor("Rs. 250"))
        assertEquals(25_000L, CsvStatementParser.parseMinor("250.00 DR"))
    }

    @Test
    fun `amounts keep exact paise rather than rounding through a double`() {
        assertEquals(2_99_995L, CsvStatementParser.parseMinor("2999.95"))
    }

    @Test
    fun `the common indian date formats all parse`() {
        val expected = CsvStatementParser.parseDate("2026-07-26", zone)
        for (text in listOf("26/07/2026", "26-07-2026", "26 Jul 2026", "26-Jul-2026")) {
            assertEquals("failed on $text", expected, CsvStatementParser.parseDate(text, zone))
        }
    }

    @Test
    fun `unreadable rows are reported by line number, not silently dropped`() {
        val outcome = parse(
            "Date,Narration,Amount",
            "26/07/2026,Swiggy,-250.00",
            "not-a-date,Broken,-10.00",
            "26/07/2026,No amount,"
        )
        assertEquals(1, outcome.transactions.size)
        assertEquals(listOf(3, 4), outcome.skippedLines)
    }

    @Test
    fun `a file with no recognisable header yields nothing`() {
        val outcome = parse("some,random,columns", "1,2,3")
        assertTrue(outcome.transactions.isEmpty())
    }

    @Test
    fun `re-importing the same file produces identical hashes so dedupe catches it`() {
        val rows = arrayOf(
            "Date,Narration,Amount",
            "26/07/2026,Swiggy,-250.00"
        )
        assertEquals(
            parse(*rows).transactions.single().bodyHash,
            parse(*rows).transactions.single().bodyHash
        )
    }

    @Test
    fun `two different rows do not collide`() {
        val outcome = parse(
            "Date,Narration,Amount",
            "26/07/2026,Swiggy,-250.00",
            "26/07/2026,Blinkit,-250.00"
        )
        assertNotEquals(outcome.transactions[0].bodyHash, outcome.transactions[1].bodyHash)
    }

    @Test
    fun `a narration without a vpa leaves the vpa null rather than inventing one`() {
        val outcome = parse(
            "Date,Narration,Amount",
            "26/07/2026,ATM WITHDRAWAL,-2000.00"
        )
        assertNull(outcome.transactions.single().counterpartyVpa)
    }
}
