package com.spendlens.core.parser

import com.spendlens.core.model.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distinction this file exists to hold:
 *
 *  - money that was never going to move (an intention, a request, a bill) is
 *    **rejected** — it is not a payment and does not belong in a ledger;
 *  - money that was attempted and did not move is **captured and flagged** — the
 *    user saw their bank announce it, so an app that shows nothing looks like it
 *    missed the payment rather than like it understood.
 *
 * Found in real use: notification templates carried no veto at all, so a payment
 * app saying "failed" was recorded as a completed payment and counted.
 */
class FailedPaymentTest {

    private val parser = TemplateParser(BuiltInTemplates.all())

    private fun notification(body: String) = ParserInput(
        source = Source.NOTIFICATION, packageName = "com.phonepe.app", body = body, timestamp = AT
    )

    private fun sms(body: String) = ParserInput(
        source = Source.SMS, sender = "VK-HDFCBK", body = body, timestamp = AT
    )

    // ------------------------------------------- attempted, did not go through

    @Test
    fun `a failed payment notification is captured but marked failed`() {
        val txn = parser.parse(notification("Payment of ₹349 to Airtel has failed. Any amount debited will be refunded."))
        assertNotNull("a failed payment still belongs in the ledger", txn)
        assertTrue("and must be marked", txn!!.failed)
    }

    @Test
    fun `a declined card payment is marked failed`() {
        val txn = parser.parse(sms("Rs.1200.00 spent on HDFC Bank Card x5678 at AMAZON was declined."))
        assertNotNull(txn)
        assertTrue(txn!!.failed)
    }

    @Test
    fun `a reversal is marked failed`() {
        val txn = parser.parse(notification("₹250 paid to Swiggy has been reversed."))
        assertNotNull(txn)
        assertTrue(txn!!.failed)
    }

    // ------------------------------------------------- never a payment at all

    @Test
    fun `a collect request is still rejected outright`() {
        assertNull(parser.parse(notification("ZOMATO has requested money from you. ₹348.87 will be debited on approving.")))
    }

    @Test
    fun `a bill reminder is still rejected outright`() {
        assertNull(parser.parse(sms("Your bill is due for payment on 27-07-2026. Total amount payable: Rs. 706.82.")))
    }

    // ------------------------------------------------------ the ordinary case

    @Test
    fun `a successful payment is not marked failed`() {
        val txn = parser.parse(notification("You paid ₹250.00 to Swiggy"))
        assertNotNull(txn)
        assertFalse(txn!!.failed)
    }

    /** "successful" must not trip the "unsuccessful" marker. */
    @Test
    fun `the word successful is not read as unsuccessful`() {
        val txn = parser.parse(notification("Payment of ₹80 to Chai stall is successful"))
        assertNotNull(txn)
        assertFalse(txn!!.failed)
    }

    private companion object {
        const val AT = 1_700_000_000_000L
    }
}
