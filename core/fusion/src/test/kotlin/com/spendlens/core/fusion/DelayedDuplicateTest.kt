package com.spendlens.core.fusion

import com.spendlens.core.model.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard for another rail announcing a payment after fusion's short window:
 * a second app or the bank's SMS, minutes late. Matches are flagged, not
 * counted, and reversible.
 *
 * The whole point is to catch the echo *without* collapsing two genuine
 * payments, so these tests push hardest on the cases that must stay apart -
 * above all a repeat payment announced by the same app.
 */
class DelayedDuplicateTest {

    private val window = 2L * HOUR  // the ingestor default

    private val phonePe = SourceRef(Source.NOTIFICATION, "com.phonepe.app")
    private val gPay = SourceRef(Source.NOTIFICATION, "com.google.android.apps.nbu.paisa.user")
    private val bankSms = SourceRef(Source.SMS, "AX-HDFCBK")

    private fun key(
        vpa: String?,
        amount: Long,
        at: Long,
        direction: String = "DEBIT",
        currency: String = "INR",
        rrn: String? = null
    ) = DuplicateKey(vpa, amount, currency, direction, at, rrn)

    /** Incoming from GPay against a row PhonePe reported, unless told otherwise. */
    private fun dup(
        incoming: DuplicateKey,
        stored: DuplicateKey,
        from: SourceRef = gPay,
        storedSeenOn: List<SourceRef> = listOf(phonePe)
    ) = isDelayedDuplicate(incoming, from, stored, storedSeenOn, window)

    @Test
    fun `another app announcing the same vpa and amount minutes later is a duplicate`() {
        val original = key("alice@upi", 4_000, at = AT)
        val echo = key("alice@upi", 4_000, at = AT + 10 * MINUTE)
        assertTrue(dup(echo, original))
    }

    @Test
    fun `a notification after the bank sms beyond the fusion window is a duplicate`() {
        val fromSms = key("alice@upi", 4_000, at = AT)
        val late = key("alice@upi", 4_000, at = AT + 5 * MINUTE)
        assertTrue(dup(late, fromSms, from = phonePe, storedSeenOn = listOf(bankSms)))
    }

    /** The reported false positive: the same chai, paid again via the same app. */
    @Test
    fun `the same app announcing the same payee and amount again is a second payment`() {
        val first = key("chai@ybl", 2_000, at = AT)
        val second = key("chai@ybl", 2_000, at = AT + 10 * MINUTE)
        assertFalse(dup(second, first, from = phonePe, storedSeenOn = listOf(phonePe)))
    }

    @Test
    fun `a repeat is still a second payment when the stored row was also seen on sms`() {
        val first = key("chai@ybl", 2_000, at = AT)
        val second = key("chai@ybl", 2_000, at = AT + 10 * MINUTE)
        assertFalse(dup(second, first, from = phonePe, storedSeenOn = listOf(bankSms, phonePe)))
    }

    @Test
    fun `two different reference numbers are two different payments`() {
        val first = key("alice@upi", 4_000, at = AT, rrn = "412345678901")
        val second = key("alice@upi", 4_000, at = AT + 10 * MINUTE, rrn = "412345678902")
        assertFalse(dup(second, first))
    }

    @Test
    fun `a reference number on only one side does not block a match`() {
        val original = key("alice@upi", 4_000, at = AT, rrn = "412345678901")
        val echo = key("alice@upi", 4_000, at = AT + 10 * MINUTE)
        assertTrue(dup(echo, original))
    }

    @Test
    fun `same amount to a different payee is a different payment`() {
        val toAlice = key("alice@upi", 4_000, at = AT)
        val toBob = key("bob@upi", 4_000, at = AT + 10 * MINUTE)
        assertFalse(dup(toBob, toAlice))
    }

    @Test
    fun `a different amount to the same payee is a different payment`() {
        val forty = key("alice@upi", 4_000, at = AT)
        val fifty = key("alice@upi", 5_000, at = AT + 10 * MINUTE)
        assertFalse(dup(fifty, forty))
    }

    @Test
    fun `opposite directions never match`() {
        val paid = key("alice@upi", 4_000, at = AT, direction = "DEBIT")
        val received = key("alice@upi", 4_000, at = AT + 10 * MINUTE, direction = "CREDIT")
        assertFalse(dup(received, paid))
    }

    @Test
    fun `different currencies never match`() {
        val inr = key("alice@upi", 4_000, at = AT, currency = "INR")
        val usd = key("alice@upi", 4_000, at = AT + 10 * MINUTE, currency = "USD")
        assertFalse(dup(usd, inr))
    }

    @Test
    fun `a payment with no vpa is never matched as a duplicate`() {
        val original = key("alice@upi", 4_000, at = AT)
        val noVpa = key(null, 4_000, at = AT + 10 * MINUTE)
        assertFalse("incoming without a VPA must not match", dup(noVpa, original))
        assertFalse("stored without a VPA must not match", dup(original, noVpa))
    }

    @Test
    fun `a blank vpa is treated as no vpa`() {
        val original = key("alice@upi", 4_000, at = AT)
        val blank = key("   ", 4_000, at = AT + 10 * MINUTE)
        assertFalse(dup(blank, original))
    }

    @Test
    fun `vpa comparison ignores case`() {
        val original = key("Alice@UPI", 4_000, at = AT)
        val echo = key("alice@upi", 4_000, at = AT + 10 * MINUTE)
        assertTrue(dup(echo, original))
    }

    /** Hours later via another app is a real payment, not an echo. */
    @Test
    fun `the same payee and amount beyond the window is left alone`() {
        val original = key("alice@upi", 4_000, at = AT)
        val later = key("alice@upi", 4_000, at = AT + 6 * HOUR)
        assertFalse(dup(later, original))
    }

    @Test
    fun `an echo earlier than the stored occurrence still matches within the window`() {
        // occurredAt can be the message's stated time, which may land either side.
        val stored = key("alice@upi", 4_000, at = AT)
        val earlier = key("alice@upi", 4_000, at = AT - 30 * MINUTE)
        assertTrue(dup(earlier, stored))
    }

    private companion object {
        const val AT = 1_700_000_000_000L
        const val MINUTE = 60L * 1000
        const val HOUR = 60 * MINUTE
    }
}
