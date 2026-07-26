package com.spendlens.core.parser

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bank SMS shapes, taken from a 4,103-message backup off a real handset and then
 * anonymised - account numbers, names, references and amounts are all invented,
 * only the grammar is real.
 *
 * The set these replaced matched 1 of 652 candidate messages, because every
 * template demanded an account number immediately after the verb. The `parses`
 * cases pin the shapes down; the `rejects` cases matter just as much, because a
 * phantom transaction is worse than a missing one - the user cannot tell it is
 * wrong without opening their bank app.
 */
class BankSmsTest {

    private val parser = TemplateParser(BuiltInTemplates.all())

    private fun sms(body: String, sender: String = "AX-TESTBK") =
        parser.parse(ParserInput(source = Source.SMS, sender = sender, body = body, timestamp = AT))

    // ------------------------------------------------------------------ debits

    @Test
    fun `wallet debit with no account number at all`() {
        val txn = sms("Rs. 1.00 debited from Airtel Payments Bank a/c Txn ID 815926821055 Bal:5.17 Call 180023400 for help")
        assertNotNull(txn)
        assertEquals(100L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("815926821055", txn.rrn)
    }

    @Test
    fun `account-first debit`() {
        val txn = sms("A/c XX1234 debited INR 249.00 Dt 01-11-24 11:56 thru UPI:430696560905.Bal INR 180.98")
        assertNotNull(txn)
        assertEquals(24_900L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("1234", txn.accountTail)
        assertEquals("430696560905", txn.rrn)
    }

    @Test
    fun `atm withdrawal quoted as debited-with`() {
        val txn = sms("Ac XX1234 debited with Rs.300.00,20-03-2024 08:31:08 through ATMXX5678 . Balance Rs.223.98 CR.")
        assertNotNull(txn)
        assertEquals(30_000L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
    }

    @Test
    fun `debit stated as sent from your account`() {
        val txn = sms("INR 30.00 sent from your account XXXXXXXX7080 Sent to your beneficiary on December 18, 2025.")
        assertNotNull(txn)
        assertEquals(3_000L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("7080", txn.accountTail)
    }

    @Test
    fun `neobank spend line with no account and no merchant`() {
        val txn = sms("You've spent INR 85.56 at 18:34 on July 7, 2025. If it wasn't done by you, ping us on the Fi app. -Federal Bank")
        assertNotNull(txn)
        assertEquals(8_556L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
    }

    @Test
    fun `withdrawal line`() {
        val txn = sms("Withdrawn: INR 2,000.00 | This transaction occurred on July 19, 2024 at 12:06.")
        assertNotNull(txn)
        assertEquals(2_00_000L, txn!!.amountMinor)
        assertEquals(Channel.CARD, txn.channel)
    }

    @Test
    fun `sent via upi names the payee`() {
        val txn = sms("Rs 20.00 sent via UPI on 18-12-2025 at 01:55:31 to THE RICH TABLE.Ref:535234793508.Not you? Call 18004251199")
        assertNotNull(txn)
        assertEquals(2_000L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("THE RICH TABLE", txn.counterpartyNameRaw)
        assertEquals("535234793508", txn.rrn)
    }

    @Test
    fun `debited via upi to a vpa`() {
        val txn = sms("Rs 20.00 debited via UPI on 20-12-2024 00:52:23 to VPA someone@ybl.Ref No 575033302313.")
        assertNotNull(txn)
        assertEquals("someone@ybl", txn!!.counterpartyVpa)
        assertEquals("575033302313", txn.rrn)
    }

    @Test
    fun `autopay mandate names both the vpa and the merchant`() {
        val txn = sms("UPI AutoPay abc123def456@upi for Google Play Debited Rs.119.00 scheduled on 04/07/2026 Airtel Payments Bank")
        assertNotNull(txn)
        assertEquals(11_900L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("Google Play", txn.counterpartyNameRaw)
        assertEquals("abc123def456@upi", txn.counterpartyVpa)
    }

    // ----------------------------------------------------------------- credits

    @Test
    fun `credited with, no account number`() {
        val txn = sms("Airtel Payments Bank a/c is credited with Rs.1000.00. Txn ID: 113347733869.")
        assertNotNull(txn)
        assertEquals(1_00_000L, txn!!.amountMinor)
        assertEquals(Direction.CREDIT, txn.direction)
    }

    @Test
    fun `credited for, account first`() {
        val txn = sms("Your a/c XX1234 is credited for INR 4000.00 on 12-11-24 09:37 through UPI.Available Bal INR 4530.98 (UPI Ref ID 431713243698)")
        assertNotNull(txn)
        assertEquals(4_00_000L, txn!!.amountMinor)
        assertEquals(Direction.CREDIT, txn.direction)
        assertEquals("1234", txn.accountTail)
        assertEquals("431713243698", txn.rrn)
    }

    @Test
    fun `sbi transfer names the sender`() {
        val txn = sms("Dear SBI User, your A/c X1234-credited by Rs.15000 on 24Jan26 transfer from RAM KUMAR Ref No 602442799714 -SBI")
        assertNotNull(txn)
        assertEquals(15_00_000L, txn!!.amountMinor)  // ₹15,000
        assertEquals(Direction.CREDIT, txn.direction)
        assertEquals("RAM KUMAR", txn.counterpartyNameRaw)
        assertEquals("602442799714", txn.rrn)
    }

    @Test
    fun `cashback credited to the account`() {
        val txn = sms("Rs.5.0 cashback for Prepaid Recharge credited to your Airtel Payments Bank a/c. Txn ID: 560715086709127")
        assertNotNull(txn)
        assertEquals(500L, txn!!.amountMinor)
        assertEquals(Direction.CREDIT, txn.direction)
    }

    // ------------------------------------------------------- the closing balance

    /**
     * The classic corruption: a balance is a currency amount in the same sentence
     * as the payment, and reading it instead silently inflates the day total.
     * Every shape anchors its amount hard against the verb for this reason.
     */
    @Test
    fun `a closing balance is never mistaken for the amount`() {
        assertEquals(100L, sms("Rs. 1.00 debited from Bank a/c Txn ID 815926821055 Bal:5000.17")!!.amountMinor)
        assertEquals(24_900L, sms("A/c XX1234 debited INR 249.00 thru UPI:430696560905.Bal INR 99999.98")!!.amountMinor)
        assertEquals(1_00_000L, sms("Ac XX1234 Credited with Rs.1000.00 thru UPI . Aval Bal Rs.60941.98 CR.")!!.amountMinor)
        assertEquals(3_00_000L, sms("Your a/c is credited with Rs 3000. Balance: Rs 88888.95. Txn ID 560407208423382")!!.amountMinor)
    }

    // ------------------------------------------------- money that has not moved

    @Test
    fun `a failed payment is not a transaction`() {
        assertNull(
            sms("Hi, Payment of Rs. 349.0 has failed for your Airtel Mobile 8796505451. Any amount, if debited will be refunded to your source account within a day.")
        )
    }

    @Test
    fun `a collect request is not a transaction`() {
        assertNull(sms("ZOMATO has requested money from you on PhonePe.Rs.348.87 will be debited from your account on approving the request"))
        assertNull(sms("MAKEMYTRIP PAYU has requested money from you on your BHIM app. On approving the request, INR 605.00 will be debited from your account. NPCI"))
    }

    @Test
    fun `an autopay mandate approval request is not a transaction`() {
        assertNull(sms("netflixupi.payu@hdfcbank has sent UPI Autopay request of amount upto Rs. 149.00 on BHIM. Please check the details in Mandates section before approving."))
        assertNull(sms("AWS India has sent you an AutoPay request for up to Rs. 15000. Click to approve this request now: https://example.test/r2fy6qua"))
    }

    @Test
    fun `a bill reminder is not a transaction`() {
        assertNull(sms("Your bill dated 21-07-2026 for your connection is due for payment on 27-07-2026 . Total amount payable:  Rs. 706.82 ."))
        assertNull(sms("Dear Customer, Bill dated 21-Apr-26 is overdue since 27-Apr-26.  Total amount payable is Rs. 706.82.  Please pay immediately."))
    }

    @Test
    fun `a refund that has only been initiated is not yet money`() {
        assertNull(sms("Refund for Rs. 419 initiated. Rs. 419.00 will be credited in UPI account . Please note that it may take upto 15 days"))
    }

    @Test
    fun `promotional and scam messages are not transactions`() {
        assertNull(sms("FREE 1GB+10 MIN CREDITED on your Vi no. for calls to ALL numbers,valid for 5Days.Recharge with 179"))
        assertNull(sms("AlertUserDetails1 Account(973***0455) credited Rs.10833 Withdraw process @9pm today Click lm27.example/GMH-1"))
        assertNull(sms("Get a pre-approved loan of Rs.5,00,000. Click here."))
    }

    @Test
    fun `an otp for a card transaction is not the transaction`() {
        assertNull(sms("866473 is OTP for txn of INR 89.00 at SOME MERCHANT on ECOM on card ending 1348 .Valid till 5 minutes .Do not share OTP"))
    }

    // -------------------------------------------------------------- sender ids

    @Test
    fun `sender ids are matched on the bank code, ignoring prefix and suffix`() {
        val template = BuiltInTemplates.smsDebitAmountFirst
        for (sender in listOf("VK-HDFCBK", "AD-HDFCBK", "HDFCBK-S", "JD-HDFCBK-G", "HDFCBK")) {
            assertEquals("failed on $sender", true, template.matchesSender(sender))
        }
        assertEquals(false, template.matchesSender("SWIGGY"))
    }

    /** Bank short codes are not standardised, so shape decides, not the sender. */
    @Test
    fun `an unlisted sender still parses on shape`() {
        assertNotNull(sms("Rs.99.00 debited from a/c XX4321 to VPA kirana@okaxis. UPI Ref No 111222333444.", sender = "AX-NEWBNK"))
    }

    private companion object {
        const val AT = 1_700_000_000_000L
    }
}
