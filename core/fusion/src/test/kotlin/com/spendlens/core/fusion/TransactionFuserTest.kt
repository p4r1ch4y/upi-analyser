package com.spendlens.core.fusion

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionFuserTest {

    private val fuser = TransactionFuser()
    private val base = 1_700_000_000_000L

    private fun txn(
        source: Source,
        at: Long = base,
        amountMinor: Long = 25_000,
        rrn: String? = null,
        name: String? = null,
        vpa: String? = null,
        accountTail: String? = null
    ) = RawTxn(
        source = source,
        observedAt = at,
        occurredAt = at,
        amountMinor = amountMinor,
        currency = "INR",
        direction = Direction.DEBIT,
        counterpartyVpa = vpa,
        counterpartyNameRaw = name,
        rrn = rrn,
        accountTail = accountTail,
        channel = Channel.UPI,
        instrument = null,
        templateId = "test",
        bodyHash = "hash-$source-$at"
    )

    @Test
    fun `a matching rrn is a certain match`() {
        val notification = txn(Source.NOTIFICATION, rrn = "123456789012", name = "Swiggy")
        val sms = txn(Source.SMS, rrn = "123456789012", vpa = "swiggy@ybl")

        val result = fuser.findMatch(sms, listOf(notification))

        assertNotNull(result)
        assertEquals(1.0f, result!!.confidence, 0.0f)
    }

    @Test
    fun `same money within the window matches without an rrn`() {
        val notification = txn(Source.NOTIFICATION, at = base, name = "Swiggy")
        val sms = txn(Source.SMS, at = base + 30_000, vpa = "swiggy@ybl")

        val result = fuser.findMatch(sms, listOf(notification))

        assertNotNull(result)
        assertEquals(0.8f, result!!.confidence, 0.0f)
    }

    @Test
    fun `the same amount well outside the window is a different payment`() {
        val notification = txn(Source.NOTIFICATION, at = base)
        val sms = txn(Source.SMS, at = base + 10 * 60 * 1000)

        assertNull(fuser.findMatch(sms, listOf(notification)))
    }

    @Test
    fun `different amounts never fuse`() {
        val notification = txn(Source.NOTIFICATION, amountMinor = 25_000)
        val sms = txn(Source.SMS, amountMinor = 25_001)

        assertNull(fuser.findMatch(sms, listOf(notification)))
    }

    @Test
    fun `merged fields take the name from the notification and the rrn from the sms`() {
        val notification = txn(Source.NOTIFICATION, rrn = "999999999999", name = "Swiggy")
        val sms = txn(Source.SMS, rrn = "999999999999", vpa = "swiggy@ybl", accountTail = "1234")

        val merged = fuser.findMatch(sms, listOf(notification))!!.mergedFields

        assertEquals("Swiggy", merged.counterpartyNameRaw)
        assertEquals("999999999999", merged.rrn)
        assertEquals("1234", merged.accountTail)
        assertEquals("swiggy@ybl", merged.counterpartyVpa)
    }

    @Test
    fun `the source mask records every rail that contributed`() {
        val notification = txn(Source.NOTIFICATION, rrn = "1")
        val sms = txn(Source.SMS, rrn = "1")

        val merged = fuser.findMatch(sms, listOf(notification))!!.mergedFields

        assertEquals(
            Source.NOTIFICATION.toMask() or Source.SMS.toMask(),
            merged.sourceMask
        )
    }
}
