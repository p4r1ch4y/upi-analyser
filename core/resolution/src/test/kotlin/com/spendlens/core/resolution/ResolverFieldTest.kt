package com.spendlens.core.resolution

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions found by installing the app and looking at the dashboard, which no
 * unit test had caught: several hundred imported bank messages were labelled
 * "Manual entry", and a Google Play mandate rendered as its 32-character VPA hash.
 */
class ResolverFieldTest {

    private val resolver = MerchantResolver()

    private fun raw(
        source: Source,
        name: String? = null,
        vpa: String? = null,
        accountTail: String? = null
    ) = RawTxn(
        source = source,
        observedAt = 0L,
        occurredAt = 0L,
        amountMinor = 10_000,
        currency = "INR",
        direction = Direction.DEBIT,
        counterpartyVpa = vpa,
        counterpartyNameRaw = name,
        rrn = null,
        accountTail = accountTail,
        channel = Channel.UPI,
        instrument = null,
        templateId = "test",
        bodyHash = "hash"
    )

    /**
     * Bank SMS does sometimes name the payee. Ignoring it fell through to the VPA
     * and rendered "UPI AutoPay b116f631...@upi for Google Play" as the hash.
     */
    @Test
    fun `a name captured from bank sms is used, not the vpa behind it`() {
        val resolution = resolver.resolve(
            raw(Source.SMS, name = "Google Play", vpa = "b116f631bb034f6aa0f7f1c2f4bb12cf@upi")
        )
        assertEquals("Google Play", resolution.displayName)
        assertEquals(2, resolution.rung)
    }

    @Test
    fun `a name captured from a notification is still used`() {
        assertEquals("Swiggy", resolver.resolve(raw(Source.NOTIFICATION, name = "Swiggy")).displayName)
    }

    /**
     * A CSV narration is raw bank text, so the VPA inside it beats the string.
     */
    @Test
    fun `a statement narration does not outrank the vpa it contains`() {
        val resolution = resolver.resolve(
            raw(Source.STATEMENT, name = "UPI-SWIGGY-swiggy@ybl-ICIC-123", vpa = "swiggy@ybl")
        )
        assertTrue(resolution.rung > 2)
    }

    /** Labelling an imported bank message "Manual entry" is simply untrue. */
    @Test
    fun `an unnamed bank message is never labelled as manually entered`() {
        val resolution = resolver.resolve(raw(Source.SMS, accountTail = "2793"))
        assertFalse(resolution.displayName.contains("Manual", ignoreCase = true))
        assertTrue(resolution.displayName.contains("2793"))
    }

    @Test
    fun `an unnamed bank message with no account still gets an honest label`() {
        val resolution = resolver.resolve(raw(Source.SMS))
        assertFalse(resolution.displayName.contains("Manual", ignoreCase = true))
        assertFalse(resolution.displayName.contains("Unknown", ignoreCase = true))
        assertTrue(resolution.displayName.isNotBlank())
    }

    /** The promise the product makes: never render the word "Unknown". */
    @Test
    fun `no source ever resolves to Unknown`() {
        for (source in Source.entries) {
            val resolution = resolver.resolve(raw(source))
            assertFalse(
                "$source resolved to ${resolution.displayName}",
                resolution.displayName.contains("Unknown", ignoreCase = true)
            )
        }
    }
}
