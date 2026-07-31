package com.spendlens.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Built from a real six-month ledger in which 82% of rows had no payee, because
 * the banks never sent one. These are the facts that *are* in those messages.
 */
class MessageFactsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------ institution

    @Test
    fun `the bank names itself even when it never names the payee`() {
        assertEquals(
            "Airtel Payments Bank",
            MessageFacts.institution("Rs. 10.00 debited from Airtel Payments Bank a/c Txn ID 815926821055 Bal:5.17")
        )
        assertEquals(
            "Federal Bank",
            MessageFacts.institution("You've spent INR 85.56 at 18:34 on July 7, 2025. -Federal Bank")
        )
    }

    /**
     * The same bank writes "PNB" in one message and the full name in the next. A
     * ledger listing both as separate payees is worse than one listing neither.
     */
    @Test
    fun `short forms resolve to the same name as the full form`() {
        val short = MessageFacts.institution("A/c XX2793 debited INR 249.00 thru UPI. Download PNB ONE-PNB")
        val long = MessageFacts.institution("Punjab National Bank: your account was debited")
        assertEquals("Punjab National Bank", short)
        assertEquals(long, short)
    }

    @Test
    fun `a longer bank name wins over a substring of it`() {
        assertEquals(
            "State Bank of India",
            MessageFacts.institution("Received INR 1.00 in your State Bank of India account(XX0563)")
        )
    }

    @Test
    fun `a message naming no bank returns null rather than a guess`() {
        assertNull(MessageFacts.institution("You paid ₹250.00 to Swiggy"))
    }

    // -------------------------------------------------------------- timestamps

    /**
     * The case this exists for: an SMS sent at 23:58 landing at 00:01 files the
     * payment on the wrong day and leaves two daily totals wrong. Found twice in
     * six months of real data.
     */
    @Test
    fun `a payment just before midnight is filed on the day it happened`() {
        val received = at("2024-02-13T00:03:00")
        val stated = MessageFacts.statedTime(
            "Your a/c XX2793 is credited for INR 1000.00 on 12-02-24 23:57 through UPI.",
            received, zone
        )
        assertEquals(at("2024-02-12T23:57:00"), stated)
    }

    @Test
    fun `the Dt shape parses`() {
        val received = at("2023-12-26T21:59:00")
        assertEquals(
            at("2023-12-26T21:58:00"),
            MessageFacts.statedTime("A/c XX2793 debited INR 69.00 Dt 26-12-23 21:58 thru UPI:336034440679.", received, zone)
        )
    }

    @Test
    fun `the four-digit-year shape with seconds parses`() {
        val received = at("2024-01-20T19:29:30")
        assertEquals(
            at("2024-01-20T19:29:11"),
            MessageFacts.statedTime("Ac XXXXXXXX00022793 Credited with Rs.300.00 20-01-2024 19:29:11 thru UPI .", received, zone)
        )
    }

    /**
     * The safety rule. A misparsed date is far worse than a slightly late one:
     * filing a payment in the wrong month corrupts a report the user cannot
     * easily audit, whereas being an hour out is invisible.
     */
    @Test
    fun `a stated time far from when the message arrived is refused`() {
        val received = at("2026-07-27T10:00:00")
        assertNull(
            MessageFacts.statedTime("A/c XX2793 debited INR 69.00 Dt 26-12-23 21:58 thru UPI:33603.", received, zone)
        )
    }

    @Test
    fun `a message stating no time falls back to when it arrived`() {
        val received = at("2026-07-27T10:00:00")
        assertNull(MessageFacts.statedTime("Rs. 10.00 debited from Airtel Payments Bank a/c Txn ID 8159 Bal:5.17", received, zone))
    }

    @Test
    fun `garbage in a date position does not throw`() {
        val received = at("2026-07-27T10:00:00")
        assertNull(MessageFacts.statedTime("Dt 99-99-99 99:99 nonsense", received, zone))
        assertTrue(true)
    }
}
