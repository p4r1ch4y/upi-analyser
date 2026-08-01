package com.spendlens.core.parser

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Both real names are taken verbatim from receipts shared off a physical handset,
 * which is the only reason to trust the shape at all.
 */
class ReceiptFileNameTest {

    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")

    /** Well after either receipt, so the future-tolerance check never fires. */
    private val observedAt = 1_800_000_000_000L

    private fun parse(name: String?) =
        ReceiptFileName.parse(name, currency = "INR", observedAt = observedAt)

    private fun localOf(millis: Long): ZonedDateTime =
        Instant.ofEpochMilli(millis).atZone(kolkata)

    @Test
    fun `a Google Pay debit receipt is read whole`() {
        val txn = parse("1738737495 - 165.00 To Krishnendu Diyan on Google Pay.png")
        assertNotNull(txn)
        requireNotNull(txn)

        assertEquals(165_00L, txn.amountMinor)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("Krishnendu Diyan", txn.counterpartyNameRaw)
        assertEquals(Channel.UPI, txn.channel)
        assertEquals("INR", txn.currency)
        assertEquals("Google Pay", txn.institution)

        // The receipt itself reads "5 Feb 2025, 12:08 pm".
        val local = localOf(txn.occurredAt!!)
        assertEquals(2025, local.year)
        assertEquals(2, local.monthValue)
        assertEquals(5, local.dayOfMonth)
        assertEquals(12, local.hour)
        assertEquals(8, local.minute)
    }

    @Test
    fun `From means money coming in`() {
        val txn = parse("1759307622 - 150.00 From Kaustab Sarkar on Google Pay.png")
        requireNotNull(txn)
        assertEquals(Direction.CREDIT, txn.direction)
        assertEquals(150_00L, txn.amountMinor)
        assertEquals("Kaustab Sarkar", txn.counterpartyNameRaw)
    }

    /**
     * The other half of the real sample. PhonePe names its receipts after nothing
     * at all, which is exactly why the image itself still needs reading.
     */
    @Test
    fun `a PhonePe receipt name says nothing and is refused`() {
        assertNull(parse("TransactionReceipt4551195680020140631.jpeg"))
        assertNull(parse("TransactionReceipt8105146896730573738.jpeg"))
    }

    @Test
    fun `ordinary screenshots are refused`() {
        assertNull(parse("Screenshot_20260726-145315.png"))
        assertNull(parse("IMG_20260801_140233.jpg"))
        assertNull(parse("homeless-ant-ant.png"))
        assertNull(parse(null))
        assertNull(parse(""))
    }

    /**
     * The amount must carry its paise. Without that, "1738737495 - 165 To ..."
     * could as easily be a crop count or an id, and this is meant to be a pattern
     * a renamed file does not hit by accident.
     */
    @Test
    fun `an amount without paise is not enough to match on`() {
        assertNull(parse("1738737495 - 165 To Krishnendu Diyan on Google Pay.png"))
    }

    @Test
    fun `a name containing on does not swallow the app`() {
        val txn = parse("1738737495 - 40.00 To Ration on Wheels on Google Pay.png")
        requireNotNull(txn)
        assertEquals("Ration on Wheels", txn.counterpartyNameRaw)
        assertEquals("Google Pay", txn.institution)
    }

    @Test
    fun `grouped amounts survive`() {
        val txn = parse("1738737495 - 12,499.00 To Vijay Sales on Google Pay.png")
        requireNotNull(txn)
        assertEquals(12_499_00L, txn.amountMinor)
    }

    /** Millisecond epochs are accepted too, since not every app uses seconds. */
    @Test
    fun `a millisecond epoch is not filed fifty thousand years out`() {
        val txn = parse("1738737495000 - 165.00 To Krishnendu Diyan on Google Pay.png")
        requireNotNull(txn)
        assertEquals(2025, localOf(txn.occurredAt!!).year)
    }

    /**
     * A number that is neither a plausible second- nor millisecond-epoch is
     * refused outright. Filing a payment in 1970 or in 4000 is worse than not
     * reading the name at all.
     */
    @Test
    fun `an implausible timestamp is refused rather than filed`() {
        assertNull(parse("100000000 - 165.00 To Someone on Google Pay.png"))
        assertNull(parse("9999999999 - 165.00 To Someone on Google Pay.png"))
    }

    /**
     * Sharing the same receipt twice is one payment, so the dedupe hash must not
     * fold in the moment it was shared - which is the opposite of what the
     * notification rail needs.
     */
    @Test
    fun `the same receipt shared twice hashes the same`() {
        val name = "1738737495 - 165.00 To Krishnendu Diyan on Google Pay.png"
        val first = ReceiptFileName.parse(name, "INR", observedAt = 1_800_000_000_000L)
        val second = ReceiptFileName.parse(name, "INR", observedAt = 1_800_000_999_000L)
        assertEquals(first?.bodyHash, second?.bodyHash)
    }

    @Test
    fun `currency is taken from the caller and never invented`() {
        val txn = ReceiptFileName.parse(
            "1738737495 - 165.00 To Someone on Google Pay.png",
            currency = "AUD",
            observedAt = observedAt
        )
        assertEquals("AUD", txn?.currency)
    }
}
