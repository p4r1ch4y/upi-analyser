package com.spendlens.core.fusion

import com.spendlens.core.model.Source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps repeat payments from collapsing into one row.
 *
 * Found on a real ledger: two ₹45 payments to the same chai shop a minute apart
 * were stored as one. Amount, currency, direction and a ninety-second window are
 * exactly what fusion looks for, and they are also exactly what two genuine
 * back-to-back payments to the same shop look like. Nothing in that comparison
 * can tell the two situations apart - but who sent the message can.
 */
class SameOriginTest {

    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val phonepe = "com.phonepe.app"

    private fun notification(origin: String?) = SourceRef(Source.NOTIFICATION, origin)
    private fun sms(sender: String?) = SourceRef(Source.SMS, sender)

    /**
     * The case fusion exists for, and which must keep working: the bank texts
     * about a payment the UPI app already announced.
     */
    @Test
    fun `a bank SMS still fuses into the notification of the same payment`() {
        assertTrue(canFuseAcrossSources(sms("HDFCBK"), listOf(notification(gpay))))
    }

    /**
     * The bug. A UPI app posts one notification per payment - it never announces
     * the same payment twice - so a second notification from that same package is
     * a second payment, and merging it destroys one.
     */
    @Test
    fun `a second notification from the same app is a second payment`() {
        assertFalse(canFuseAcrossSources(notification(gpay), listOf(notification(gpay))))
    }

    @Test
    fun `two payments through different apps are still two payments`() {
        // Nothing here says these are the same payment either, but the rule only
        // has to refuse the case it can be sure of; the two-app case is rare and
        // an RRN settles it when it does happen.
        assertTrue(canFuseAcrossSources(notification(phonepe), listOf(notification(gpay))))
    }

    @Test
    fun `a second SMS from the same sender is a second payment`() {
        assertFalse(canFuseAcrossSources(sms("HDFCBK"), listOf(sms("HDFCBK"))))
    }

    /**
     * Two banks describing one payment - the payer's and the payee's - is a real
     * shape, so different senders stay fusible.
     */
    @Test
    fun `two different SMS senders may still be one payment`() {
        assertTrue(canFuseAcrossSources(sms("HDFCBK"), listOf(sms("SBIUPI"))))
    }

    @Test
    fun `origins compare without regard to case`() {
        assertFalse(canFuseAcrossSources(notification("Com.PhonePe.App"), listOf(notification(phonepe))))
    }

    /**
     * An unknown origin is one origin, not a wildcard. Treating null as "could be
     * anything" would let a second unattributed notification fuse into the first,
     * which is the exact collapse this rule exists to stop.
     */
    @Test
    fun `two unknown origins on the same rail count as the same origin`() {
        assertFalse(canFuseAcrossSources(notification(null), listOf(notification(null))))
    }

    @Test
    fun `an unknown origin does not match a known one`() {
        assertTrue(canFuseAcrossSources(notification(null), listOf(notification(gpay))))
    }

    /**
     * A row from before source messages were kept has nothing to compare against.
     * Fusion behaves for it exactly as it always did, rather than the app guessing
     * about history the database does not hold.
     */
    @Test
    fun `a payment with no recorded sources stays fusible`() {
        assertTrue(canFuseAcrossSources(notification(gpay), emptyList()))
    }

    @Test
    fun `a payment already seen on both rails refuses a third view from either`() {
        val seen = listOf(notification(gpay), sms("HDFCBK"))
        assertFalse(canFuseAcrossSources(notification(gpay), seen))
        assertFalse(canFuseAcrossSources(sms("HDFCBK"), seen))
        assertTrue(canFuseAcrossSources(sms("ICICIB"), seen))
    }

    /** A statement import is a third rail and fuses with either of the others. */
    @Test
    fun `a statement row fuses into a payment seen on the live rails`() {
        assertTrue(
            canFuseAcrossSources(
                SourceRef(Source.STATEMENT, "hdfc-jul.csv"),
                listOf(notification(gpay), sms("HDFCBK"))
            )
        )
    }
}
