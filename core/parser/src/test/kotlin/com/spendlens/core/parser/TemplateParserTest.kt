package com.spendlens.core.parser

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateParserTest {

    private val parser = TemplateParser(BuiltInTemplates.all())

    private fun notification(pkg: String, title: String?, body: String, at: Long = AT) = ParserInput(
        source = Source.NOTIFICATION,
        packageName = pkg,
        title = title,
        body = body,
        timestamp = at
    )

    private fun sms(sender: String, body: String, at: Long = AT) = ParserInput(
        source = Source.SMS,
        sender = sender,
        body = body,
        timestamp = at
    )

    // ------------------------------------------------- regression: the live miss

    /**
     * Verbatim from the BHIM notification that the first build silently dropped.
     * If this ever stops passing, real payments stop being recorded.
     */
    @Test
    fun `bhim credit notification from the field is captured in full`() {
        val txn = parser.parse(
            notification(
                pkg = "in.org.npci.upiapp",
                title = "Bharat Interface for Money",
                body = "Received INR 1.00 in your State Bank Of India account(XX0563) from " +
                    "SUBRATA CHOUDHURY (9733230455-3@ybl). For further details, please check " +
                    "the transaction history on your BHIM app."
            )
        )

        assertNotNull("BHIM credit must parse", txn)
        assertEquals(100L, txn!!.amountMinor)
        assertEquals("INR", txn.currency)
        assertEquals(Direction.CREDIT, txn.direction)
        assertEquals("0563", txn.accountTail)
        assertEquals("9733230455-3@ybl", txn.counterpartyVpa)
        assertEquals("SUBRATA CHOUDHURY", txn.counterpartyNameRaw)
    }

    /** The app's own name must never end up as the counterparty. */
    @Test
    fun `the notification title is never used as a merchant name`() {
        val txn = parser.parse(
            notification("in.org.npci.upiapp", "Bharat Interface for Money", "Received INR 1.00 in your SBI account(XX0563) from RAMESH (r@ybl).")
        )
        assertNotEquals("Bharat Interface for Money", txn!!.counterpartyNameRaw)
    }

    @Test
    fun `bhim debit notification`() {
        val txn = parser.parse(
            notification(
                "in.org.npci.upiapp",
                "Bharat Interface for Money",
                "Sent INR 250.00 from your State Bank Of India account(XX0563) to SWIGGY (swiggy@ybl)."
            )
        )
        assertNotNull(txn)
        assertEquals(25_000L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("swiggy@ybl", txn.counterpartyVpa)
        assertEquals("0563", txn.accountTail)
    }

    // ------------------------------------------------------ consumer app shapes

    @Test
    fun `google pay debit notification`() {
        val txn = parser.parse(
            notification("com.google.android.apps.nbu.paisa.user", "Google Pay", "You paid ₹250.00 to Swiggy")
        )
        assertNotNull(txn)
        assertEquals(25_000L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("Swiggy", txn.counterpartyNameRaw)
    }

    @Test
    fun `google pay credit is not read as a debit`() {
        val txn = parser.parse(
            notification("com.google.android.apps.nbu.paisa.user", "Google Pay", "₹500 received from Asha")
        )
        assertEquals(Direction.CREDIT, txn!!.direction)
        assertEquals(50_000L, txn.amountMinor)
    }

    @Test
    fun `phonepe payment-of phrasing`() {
        val txn = parser.parse(
            notification("com.phonepe.app", "PhonePe", "Payment of ₹80 to Chai stall is successful")
        )
        assertNotNull(txn)
        assertEquals(8_000L, txn!!.amountMinor)
        assertEquals("Chai stall", txn.counterpartyNameRaw)
    }

    /**
     * Caught on a real device: the message named the payee, the row said
     * "Payment", and the Source section showed why - only the catch-all matched.
     */
    @Test
    fun `a standing-instruction debit names the payee it says it paid`() {
        val txn = parser.parse(
            notification(
                "in.org.npci.upiapp",
                "BHIM",
                "Rs. 100.00 has been debited from your account towards Google Play. " +
                    "Please check SI history for further details."
            )
        )
        assertNotNull(txn)
        assertEquals(10_000L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("Google Play", txn.counterpartyNameRaw)
    }

    @Test
    fun `paying a bare vpa keeps it as the vpa, not as a name`() {
        val txn = parser.parse(notification("com.phonepe.app", "PhonePe", "₹80 paid to 9822014455@ybl"))
        assertNotNull(txn)
        assertEquals("9822014455@ybl", txn!!.counterpartyVpa)
    }

    @Test
    fun `every upi package inherits every shape`() {
        for (pkg in BuiltInTemplates.upiPackages) {
            assertNotNull("$pkg should parse", parser.parse(notification(pkg, null, "You paid ₹250 to Swiggy")))
        }
    }

    // -------------------------------------------------------------- catch-alls

    /**
     * An unrecognised phrasing must still land in the ledger. Silence is what
     * made the BHIM credit invisible.
     */
    @Test
    fun `an unseen phrasing still produces a reviewable transaction`() {
        val txn = parser.parse(
            notification("com.phonepe.app", "PhonePe", "Your wallet was debited by ₹42 for something new")
        )
        assertNotNull("catch-all must fire", txn)
        assertEquals(4_200L, txn!!.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertNull("catch-all guesses no counterparty", txn.counterpartyNameRaw)
    }

    @Test
    fun `chatter without a direction verb is not invented into a transaction`() {
        assertNull(parser.parse(notification("com.phonepe.app", "PhonePe", "Win cashback up to ₹500 this week!")))
    }

    /**
     * A receipt shared in from a UPI app carries no package. Rejecting it would
     * silently disable the one rail that catches on-device payments the
     * notification listener never sees.
     */
    @Test
    fun `shared text with no package still parses`() {
        val txn = parser.parse(
            ParserInput(source = Source.NOTIFICATION, packageName = null, body = "You paid Rs.45.00 to Chai Point", timestamp = AT)
        )
        assertNotNull(txn)
        assertEquals(4_500L, txn!!.amountMinor)
        assertEquals("Chai Point", txn.counterpartyNameRaw)
    }

    @Test
    fun `unknown package is not parsed`() {
        assertNull(parser.parse(notification("com.example.wallet", null, "₹250 paid to Swiggy")))
    }

    // -------------------------------------------------------------- bank SMS

    @Test
    fun `bank sms yields vpa, account tail and rrn`() {
        val txn = parser.parse(
            sms(
                "VK-HDFCBK",
                "Rs.250.00 debited from a/c **1234 to VPA swiggy@ybl on 26-07-26. " +
                    "UPI Ref No 123456789012. Not you? Call 18002586161."
            )
        )
        assertNotNull(txn)
        assertEquals(25_000L, txn!!.amountMinor)
        assertEquals("swiggy@ybl", txn.counterpartyVpa)
        assertEquals("1234", txn.accountTail)
        assertEquals("123456789012", txn.rrn)
        assertEquals(Direction.DEBIT, txn.direction)
    }

    @Test
    fun `bank sms credit`() {
        val txn = parser.parse(
            sms("AD-SBIUPI", "Rs.1,500.00 credited to a/c XX0563 from VPA ramesh@okicici. UPI Ref 998877665544.")
        )
        assertNotNull(txn)
        assertEquals(1_50_000L, txn!!.amountMinor)
        assertEquals(Direction.CREDIT, txn.direction)
        assertEquals("998877665544", txn.rrn)
    }

    @Test
    fun `card spend sms is captured on the card channel`() {
        val txn = parser.parse(
            sms("VM-HDFCBK", "Rs.1200.00 spent on HDFC Bank Card x5678 at AMAZON on 26-07-26.")
        )
        assertNotNull(txn)
        assertEquals(1_20_000L, txn!!.amountMinor)
        assertEquals(Channel.CARD, txn.channel)
        assertEquals("5678", txn.accountTail)
        assertTrue(txn.counterpartyNameRaw!!.contains("AMAZON"))
    }

    /**
     * Bank short codes are not standardised, so SMS routing is by message shape.
     * A bank whose sender ID nobody has enumerated must still be read.
     */
    @Test
    fun `an unlisted bank sender still parses on shape`() {
        val txn = parser.parse(
            sms("AX-NEWBNK", "Rs.99.00 debited from a/c XX4321 to VPA kirana@okaxis. UPI Ref No 111222333444.")
        )
        assertNotNull(txn)
        assertEquals(9_900L, txn!!.amountMinor)
    }

    @Test
    fun `sms marketing chatter is not parsed`() {
        assertNull(parser.parse(sms("VM-HDFCBK", "Get a pre-approved loan of Rs.5,00,000. Click here.")))
    }

    // ------------------------------------------------------------ correctness

    @Test
    fun `a message with no currency token is refused rather than assumed to be rupees`() {
        val template = TransactionTemplate(
            id = "test.nocurrency",
            packageNames = listOf("com.example.app"),
            pattern = Regex("""paid (?<amount>[\d.]+) to (?<name>\w+)"""),
            direction = Direction.DEBIT,
            channel = Channel.UPI,
            currencyDefault = null
        )
        val strictParser = TemplateParser(listOf(template))
        assertNull(strictParser.parse(notification("com.example.app", null, "paid 250 to Swiggy")))
    }

    @Test
    fun `templates that declare no vpa group do not blow up`() {
        val txn = parser.parse(
            notification("com.google.android.apps.nbu.paisa.user", null, "You paid ₹250 to Swiggy")
        )
        assertNotNull(txn)
        assertNull(txn!!.counterpartyVpa)
        assertNull(txn.rrn)
        assertNull(txn.accountTail)
    }

    @Test
    fun `paise survive the round trip exactly`() {
        val txn = parser.parse(
            notification("com.google.android.apps.nbu.paisa.user", null, "You paid ₹2,999.95 to Zepto")
        )
        assertEquals(2_99_995L, txn!!.amountMinor)
    }

    @Test
    fun `a replayed notification hashes identically so dedupe catches it`() {
        val a = parser.parse(notification("com.phonepe.app", null, "₹20 paid to Chai stall", at = AT))
        val b = parser.parse(notification("com.phonepe.app", null, "₹20 paid to Chai stall", at = AT))
        assertEquals(a!!.bodyHash, b!!.bodyHash)
    }

    @Test
    fun `two identical payments at different times are not deduped away`() {
        val morning = parser.parse(notification("com.phonepe.app", null, "₹20 paid to Chai stall", at = AT))
        val evening = parser.parse(notification("com.phonepe.app", null, "₹20 paid to Chai stall", at = AT + 3_600_000))
        assertNotEquals(morning!!.bodyHash, evening!!.bodyHash)
    }

    private companion object {
        const val AT = 1_700_000_000_000L
    }
}
