package com.spendlens.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.spendlens.core.database.SpendLensDatabase
import com.spendlens.core.model.Direction
import com.spendlens.core.model.FusedTxn
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import com.spendlens.core.parser.BuiltInTemplates
import com.spendlens.core.parser.ParserInput
import com.spendlens.core.parser.TemplateParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real ingestor and repository against a real in-memory SQLite database
 * built from the SQLDelight schema: duplicate handling end to end, SQL included.
 */
class DuplicateIngestTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: TransactionRepository
    private lateinit var ingestor: TransactionIngestor
    private val parser = TemplateParser(BuiltInTemplates.all())

    private var clock = 1_000L

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val phonePe = "com.phonepe.app"
    private val gPay = "com.google.android.apps.nbu.paisa.user"

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SpendLensDatabase.Schema.create(driver)
        repository = TransactionRepository(SpendLensDatabase(driver), Dispatchers.Unconfined)
        // Strictly increasing so generated ids never collide.
        ingestor = TransactionIngestor(repository, now = { clock++ })
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun notification(
        pkg: String,
        body: String,
        postTime: Long,
        `when`: Long? = postTime,
        key: String? = "$pkg|1|null"
    ): RawTxn = parser.parse(
        ParserInput(
            source = Source.NOTIFICATION,
            packageName = pkg,
            title = "Payment",
            body = body,
            timestamp = postTime,
            notificationWhen = `when`,
            notificationKey = key
        )
    ) ?: error("parser refused: $body")

    private suspend fun rows() = repository.transactionsSinceOnce(0)
    private suspend fun total() = repository.dayTotalMinor(0, Long.MAX_VALUE)

    @Test
    fun `same app twice ten minutes apart is two payments`() = runTest {
        val a = notification(phonePe, "₹20 has been sent to chai@ybl", t0, `when` = t0, key = "k1")
        val b = notification(phonePe, "₹20 has been sent to chai@ybl", t0 + 10 * minute, `when` = t0 + 10 * minute, key = "k2")

        assertTrue(ingestor.ingest(a) is TransactionIngestor.Result.Inserted)
        assertTrue(ingestor.ingest(b) is TransactionIngestor.Result.Inserted)

        assertEquals(2, rows().size)
        assertEquals(4000L, total())
    }

    @Test
    fun `exact repost hours later is dropped`() = runTest {
        val first = notification(phonePe, "₹20 has been sent to chai@ybl", t0, `when` = t0, key = "k1")
        val repost = notification(phonePe, "₹20 has been sent to chai@ybl", t0 + 6 * hour, `when` = t0, key = "k1")
        // Fresh post time means a fresh body hash: only the repost key can catch it.
        assertTrue(first.bodyHash != repost.bodyHash)

        assertTrue(ingestor.ingest(first) is TransactionIngestor.Result.Inserted)
        assertEquals(TransactionIngestor.Result.Duplicate, ingestor.ingest(repost))

        assertEquals(1, rows().size)
        assertEquals(2000L, total())
    }

    @Test
    fun `other app echo ten minutes later is stored flagged and not counted`() = runTest {
        val original = notification(phonePe, "₹20 has been sent to chai@ybl", t0)
        val echo = notification(gPay, "₹20 has been sent to chai@ybl", t0 + 10 * minute)

        val first = ingestor.ingest(original) as TransactionIngestor.Result.Inserted
        val second = ingestor.ingest(echo)

        assertTrue("expected StoredDuplicate but was $second", second is TransactionIngestor.Result.StoredDuplicate)
        second as TransactionIngestor.Result.StoredDuplicate
        assertEquals(first.txn.id.value, second.originalId)

        val stored = rows().single { it.id == second.txn.id.value }
        assertTrue(stored.flags.toInt() and FusedTxn.FLAG_DUPLICATE != 0)
        assertEquals(first.txn.id.value, stored.linked_txn_id)
        assertEquals(2, rows().size)
        assertEquals(2000L, total())
    }

    @Test
    fun `other app echo six hours later is a new payment`() = runTest {
        val original = notification(phonePe, "₹20 has been sent to chai@ybl", t0)
        val later = notification(gPay, "₹20 has been sent to chai@ybl", t0 + 6 * hour)

        ingestor.ingest(original)
        val result = ingestor.ingest(later)

        assertTrue("expected Inserted but was $result", result is TransactionIngestor.Result.Inserted)
        assertEquals(0, rows().count { it.flags.toInt() and FusedTxn.FLAG_DUPLICATE != 0 })
        assertEquals(4000L, total())
    }

    @Test
    fun `other app with a different RRN is a different payment`() = runTest {
        val original = notification(phonePe, "₹20 has been sent to chai@ybl. UPI Ref ID 431713243698", t0)
        val other = notification(gPay, "₹20 has been sent to chai@ybl. UPI Ref ID 431713999999", t0 + 10 * minute)
        assertEquals("431713243698", original.rrn)
        assertEquals("431713999999", other.rrn)

        ingestor.ingest(original)
        val result = ingestor.ingest(other)

        assertTrue("expected Inserted but was $result", result is TransactionIngestor.Result.Inserted)
        assertEquals(4000L, total())
    }

    private fun digestRaw(source: Source, body: String, at: Long) = RawTxn(
        source = source,
        observedAt = at,
        occurredAt = at,
        amountMinor = 4000,
        currency = "INR",
        direction = Direction.DEBIT,
        counterpartyVpa = "alice@upi",
        counterpartyNameRaw = null,
        rrn = null,
        accountTail = null,
        channel = null,
        instrument = null,
        templateId = null,
        bodyHash = "h-$source-$at",
        sourceBody = body,
        sourceOrigin = if (source == Source.NOTIFICATION) phonePe else "VM-HDFCBK"
    )

    private val digestBody = "2 new completed transaction updates. ₹40 has been sent to alice@upi"

    @Test
    fun `repair flags a notification-only digest row once and respects the user's confirmation`() = runTest {
        // The parser now refuses digests, so build the legacy row directly.
        val result = ingestor.ingest(digestRaw(Source.NOTIFICATION, digestBody, t0)) as TransactionIngestor.Result.Inserted
        val id = result.txn.id.value
        assertEquals(4000L, total())

        assertEquals(1, repository.flagStoredDigests(BuiltInTemplates::isDigest))
        val flagged = rows().single { it.id == id }
        assertTrue(flagged.flags.toInt() and FusedTxn.FLAG_DUPLICATE != 0)
        assertEquals(0L, total())

        repository.setDuplicate(id, false)
        assertEquals(0, repository.flagStoredDigests(BuiltInTemplates::isDigest))
        val confirmed = rows().single { it.id == id }
        assertEquals(0, confirmed.flags.toInt() and FusedTxn.FLAG_DUPLICATE)
        assertTrue(confirmed.flags.toInt() and FusedTxn.FLAG_COUNT_CONFIRMED != 0)
        assertEquals(4000L, total())
    }

    @Test
    fun `repair skips a digest-bodied row that also has an SMS source`() = runTest {
        val result = ingestor.ingest(digestRaw(Source.NOTIFICATION, digestBody, t0)) as TransactionIngestor.Result.Inserted
        val id = result.txn.id.value
        // A bank SMS seen for the same payment means real money moved.
        repository.recordSource(id, digestRaw(Source.SMS, "Rs.40 debited from a/c XX1234", t0 + minute))

        assertEquals(0, repository.flagStoredDigests(BuiltInTemplates::isDigest))
        assertEquals(0, rows().single { it.id == id }.flags.toInt() and FusedTxn.FLAG_DUPLICATE)
        assertEquals(4000L, total())
    }

    /** The "Counted twice? Mark as duplicate" action on a row the app did not catch. */
    @Test
    fun `marking a counted row as duplicate drops it from totals and clears a prior confirmation`() = runTest {
        val a = (ingestor.ingest(notification(phonePe, "₹20 has been sent to chai@ybl", t0, key = "k1"))
            as TransactionIngestor.Result.Inserted).txn.id.value
        ingestor.ingest(notification(phonePe, "₹20 has been sent to chai@ybl", t0 + minute, key = "k2"))
        assertEquals(4000L, total())

        repository.setDuplicate(a, true)
        assertEquals(2000L, total())

        // Confirm, then mark again: the confirmation must not survive the new verdict.
        repository.setDuplicate(a, false)
        repository.setDuplicate(a, true)
        val flags = rows().single { it.id == a }.flags.toInt()
        assertTrue(flags and FusedTxn.FLAG_DUPLICATE != 0)
        assertEquals(0, flags and FusedTxn.FLAG_COUNT_CONFIRMED)
        assertEquals(2000L, total())
    }

    @Test
    fun `clearing the duplicate flag keeps a user exclusion`() = runTest {
        val echoOf = notification(phonePe, "₹20 has been sent to chai@ybl", t0)
        val echo = notification(gPay, "₹20 has been sent to chai@ybl", t0 + 10 * minute)
        ingestor.ingest(echoOf)
        val dup = (ingestor.ingest(echo) as TransactionIngestor.Result.StoredDuplicate).txn.id.value

        // No repository method sets FLAG_EXCLUDED, so set the bit directly.
        driver.execute(null, "UPDATE transactions SET flags = flags | ${FusedTxn.FLAG_EXCLUDED} WHERE id = ?", 1) {
            bindString(0, dup)
        }

        repository.setDuplicate(dup, false)

        val row = rows().single { it.id == dup }
        val flags = row.flags.toInt()
        assertTrue("FLAG_EXCLUDED lost: $flags", flags and FusedTxn.FLAG_EXCLUDED != 0)
        assertEquals(0, flags and FusedTxn.FLAG_DUPLICATE)
        assertTrue(flags and FusedTxn.FLAG_COUNT_CONFIRMED != 0)
        // Still excluded from totals, so only the original counts.
        assertEquals(2000L, total())
    }
}
