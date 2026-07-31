package com.spendlens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.spendlens.core.database.SpendLensDatabase
import com.spendlens.core.database.SpendLensDatabaseFactory
import com.spendlens.core.parser.BuiltInTemplates
import com.spendlens.core.model.Source
import com.spendlens.core.parser.ParserInput
import com.spendlens.core.parser.TemplateParser
import com.spendlens.core.resolution.MerchantResolver
import com.spendlens.data.CsvStatementImporter
import com.spendlens.data.DatabasePassphrase
import com.spendlens.data.Days
import com.spendlens.data.SmsInboxImporter
import com.spendlens.data.CsvExporter
import com.spendlens.data.SettingsStore
import com.spendlens.data.SplitAndTagRepository
import com.spendlens.data.TransactionIngestor
import com.spendlens.data.TransactionRepository
import com.spendlens.notify.NudgeNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SpendLensApp : Application() {

    /**
     * Hand-rolled object graph. Deliberately not a DI framework: the graph is a
     * handful of objects deep and the app takes no third-party SDKs.
     *
     * Everything is lazy so that opening (and therefore decrypting) the ledger is
     * deferred until something actually reads it.
     */
    val graph: Graph by lazy { Graph(this) }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()

        // Before any UI reads a number: the formatter is a global, and starting
        // in rupees then flipping would show the wrong symbol for a frame.
        graph.settings.applyToFormatter()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Dedupe hashes only guard against replays; older ones are dead weight.
            runCatching {
                graph.repository.pruneHashesOlderThan(
                    System.currentTimeMillis() - HASH_RETENTION_MILLIS
                )
            }
            // Display names are resolved once and stored, so a fix to the
            // resolution ladder does nothing for rows already in the ledger.
            // Idempotent, so it costs one no-op statement per launch thereafter.
            runCatching { graph.repository.repairLabels() }
            // Every parser improvement is retroactive, because the message each
            // row was read from is stored beside it. Only labels change here -
            // never an amount, a direction or a date.
            runCatching { graph.relabelStoredRows() }
        }
    }

    // minSdk is 26, so notification channels always exist.
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        // Foreground service channel
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Background Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows your spending total for today"
            setShowBadge(false)
        }

        // Transaction nudge channel
        val nudgeChannel = NotificationChannel(
            CHANNEL_NUDGE,
            "Payment Notifications",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shows spending updates when you make a payment"
            setShowBadge(true)
        }

        notificationManager.createNotificationChannels(listOf(serviceChannel, nudgeChannel))
    }

    class Graph(private val context: Context) {

        val database: SpendLensDatabase by lazy {
            SpendLensDatabaseFactory.create(context, DatabasePassphrase.getOrCreate(context))
        }

        val repository: TransactionRepository by lazy { TransactionRepository(database) }

        val parser: TemplateParser by lazy { TemplateParser(BuiltInTemplates.all()) }

        val ingestor: TransactionIngestor by lazy {
            TransactionIngestor(repository, MerchantResolver())
        }

        val nudgeNotifier: NudgeNotifier by lazy { NudgeNotifier(context) }

        /** Reads bank SMS history. Inert in the `standard` flavour. */
        val smsImporter: SmsInboxImporter by lazy { SmsInboxImporter(context, parser) }

        val csvImporter: CsvStatementImporter by lazy { CsvStatementImporter(context) }

        /** Splits, tags and the dashboard aggregates. */
        val annotations: SplitAndTagRepository by lazy { SplitAndTagRepository(database) }

        val settings: SettingsStore by lazy { SettingsStore(context) }

        val csvExporter: CsvExporter by lazy { CsvExporter(context, repository, annotations) }

        /**
         * Asks the current parser to re-read every placeholder-labelled row.
         *
         * Lives on the graph because it needs the parser and the resolver
         * together, which is the same pairing the live capture path uses - so a
         * re-parse produces exactly the label a fresh capture would.
         */
        suspend fun relabelStoredRows(): Int {
            val resolver = MerchantResolver()
            val rules = repository.userRules()
            return repository.relabelFromSources(labelOf = { record ->
                val source = runCatching { Source.valueOf(record.source) }.getOrNull()
                    ?: return@relabelFromSources null
                val raw = parser.parse(
                    ParserInput(
                        source = source,
                        packageName = record.origin?.takeIf { source == Source.NOTIFICATION },
                        sender = record.origin?.takeIf { source == Source.SMS },
                        body = record.body,
                        timestamp = record.receivedAt
                    )
                ) ?: return@relabelFromSources null
                resolver.resolve(raw, userRules = rules).displayName
            })
        }

        suspend fun todayTotalMinor(): Long {
            val start = Days.startOfToday()
            return repository.dayTotalMinor(start, Days.endOfDay(start))
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "transaction_service"
        const val CHANNEL_NUDGE = "transaction_nudge"

        private const val HASH_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000  // 30 days

        fun graphOf(context: Context): Graph =
            (context.applicationContext as SpendLensApp).graph
    }
}
