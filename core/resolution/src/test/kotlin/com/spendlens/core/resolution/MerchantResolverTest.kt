package com.spendlens.core.resolution

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantResolverTest {

    private val resolver = MerchantResolver()

    private fun txn(
        source: Source = Source.NOTIFICATION,
        vpa: String? = null,
        name: String? = null
    ) = RawTxn(
        source = source,
        observedAt = 1_700_000_000_000,
        occurredAt = 1_700_000_000_000,
        amountMinor = 25_000,
        currency = "INR",
        direction = Direction.DEBIT,
        counterpartyVpa = vpa,
        counterpartyNameRaw = name,
        rrn = null,
        accountTail = null,
        channel = Channel.UPI,
        instrument = null,
        templateId = "test",
        bodyHash = "hash"
    )

    @Test
    fun `rung 1 - a user rule wins over everything else`() {
        val rule = VpaRule(
            id = "1",
            pattern = "9822014455@ybl",
            matchType = VpaRule.MatchType.EXACT,
            merchantName = "Ramesh",
            merchantId = "m1",
            categoryId = "people"
        )
        val result = resolver.resolve(
            txn(vpa = "9822014455@ybl", name = "Some Other Name"),
            userRules = listOf(rule)
        )
        assertEquals(1, result.rung)
        assertEquals("Ramesh", result.displayName)
        assertEquals(1.0f, result.confidence, 0.0f)
    }

    @Test
    fun `rung 2 - a clean notification name is used as-is`() {
        val result = resolver.resolve(txn(name = "Swiggy"))
        assertEquals(2, result.rung)
        assertEquals("Swiggy", result.displayName)
    }

    @Test
    fun `a vpa masquerading as a notification name falls through to structure parsing`() {
        val result = resolver.resolve(txn(vpa = "9822014455@ybl", name = "9822014455@ybl"))
        assertNotEquals(2, result.rung)
    }

    @Test
    fun `rung 3 - a ten digit handle is recognised as a person`() {
        val result = resolver.resolve(txn(source = Source.SMS, vpa = "9822014455@ybl"))
        assertEquals(3, result.rung)
        assertEquals("people", result.categoryId)
    }

    @Test
    fun `rung 4 - the merchant directory names a known vpa`() {
        val result = resolver.resolve(
            txn(source = Source.SMS, vpa = "q123456@ybl"),
            merchantDirectory = mapOf(
                "q123456@ybl" to MerchantInfo("m2", "Blinkit", "groceries")
            )
        )
        assertEquals(4, result.rung)
        assertEquals("Blinkit", result.displayName)
    }

    @Test
    fun `rung 6 - the fallback shows the vpa and never the word Unknown`() {
        val result = resolver.resolve(txn(source = Source.SMS, vpa = "someshop@okicici"))
        assertTrue(result.displayName.isNotBlank())
        assertNotEquals("Unknown", result.displayName)
        assertTrue(result.displayName.contains("Someshop", ignoreCase = true))
    }

    @Test
    fun `a transaction with nothing to go on still gets a label`() {
        val result = resolver.resolve(txn(source = Source.MANUAL))
        assertTrue(result.displayName.isNotBlank())
        assertNotEquals("Unknown", result.displayName)
    }

    @Test
    fun `vpa rules support prefix and regex matching`() {
        val prefix = VpaRule(
            id = "p", pattern = "paytmqr", matchType = VpaRule.MatchType.PREFIX,
            merchantName = "Paytm QR", merchantId = null, categoryId = null
        )
        assertTrue(prefix.matches("paytmqr2810050501011@paytm"))

        val regex = VpaRule(
            id = "r", pattern = """q\d+@ybl""", matchType = VpaRule.MatchType.REGEX,
            merchantName = "PhonePe QR", merchantId = null, categoryId = null
        )
        assertTrue(regex.matches("q123456@ybl"))
    }
}
