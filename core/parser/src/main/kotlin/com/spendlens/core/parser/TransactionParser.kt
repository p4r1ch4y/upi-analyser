package com.spendlens.core.parser

import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import com.spendlens.core.model.Direction
import com.spendlens.core.model.Channel
import java.security.MessageDigest

/**
 * Template-based parser for notifications and SMS.
 * 100% unit-testable, no Android dependencies.
 */
interface TransactionParser {
    fun parse(input: ParserInput): RawTxn?
}

data class ParserInput(
    val source: Source,
    val packageName: String? = null,  // For notifications
    val sender: String? = null,        // For SMS
    val title: String? = null,
    val body: String,
    val extras: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

class TemplateParser : TransactionParser {
    private val templates = mutableListOf<TransactionTemplate>()

    fun addTemplate(template: TransactionTemplate) {
        templates.add(template)
    }

    fun addTemplates(newTemplates: List<TransactionTemplate>) {
        templates.addAll(newTemplates)
    }

    override fun parse(input: ParserInput): RawTxn? {
        // Route by package name (notifications) or sender ID (SMS)
        val candidateTemplates = templates.filter { template ->
            when (input.source) {
                Source.NOTIFICATION -> input.packageName in template.packageNames
                Source.SMS -> input.sender in template.senderIds
                else -> false
            }
        }

        // Try each template until one matches
        for (template in candidateTemplates) {
            val result = template.extract(input)
            if (result != null) return result
        }

        return null
    }
}

data class TransactionTemplate(
    val id: String,
    val packageNames: List<String> = emptyList(),
    val senderIds: List<String> = emptyList(),
    val pattern: Regex,
    val direction: Direction,
    val channel: Channel,
    val currencyDefault: String? = null,  // null = no default, must parse explicitly
    val extractors: Map<String, String>   // Field name -> capture group name
) {
    fun extract(input: ParserInput): RawTxn? {
        val text = input.title?.let { "$it ${input.body}" } ?: input.body
        val match = pattern.find(text) ?: return null

        val amountStr = match.groups[extractors["amount"]]?.value ?: return null
        val amount = parseAmount(amountStr) ?: return null

        val currency = match.groups[extractors["currency"]]?.value
            ?: detectCurrency(text)
            ?: currencyDefault
            ?: return null  // No currency found and no default

        return RawTxn(
            source = input.source,
            observedAt = input.timestamp,
            occurredAt = input.timestamp,  // TODO: Parse from message if available
            amountMinor = amount,
            currency = currency,
            direction = direction,
            counterpartyVpa = match.groups[extractors["vpa"]]?.value,
            counterpartyNameRaw = match.groups[extractors["name"]]?.value
                ?: input.title?.takeIf { input.source == Source.NOTIFICATION },
            rrn = match.groups[extractors["rrn"]]?.value,
            accountTail = match.groups[extractors["accountTail"]]?.value,
            channel = channel,
            instrument = null,  // TODO: Extract from message
            templateId = id,
            bodyHash = hashBody(text)
        )
    }

    private fun parseAmount(str: String): Long? {
        val cleaned = str.replace(Regex("[,\\s]"), "")
        val value = cleaned.toDoubleOrNull() ?: return null
        return (value * 100).toLong()
    }

    private fun detectCurrency(text: String): String? {
        return when {
            text.contains("₹") || text.contains(Regex("Rs\\.?")) || text.contains("INR") -> "INR"
            text.contains("$") || text.contains("USD") -> "USD"
            text.contains("AUD") -> "AUD"
            text.contains("EUR") -> "EUR"
            text.contains("GBP") -> "GBP"
            else -> null
        }
    }

    private fun hashBody(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }
}

/** Built-in templates for common UPI apps */
object BuiltInTemplates {
    val googlePay = TransactionTemplate(
        id = "gpay.upi.debit.v1",
        packageNames = listOf("com.google.android.apps.nbu.paisa.user"),
        senderIds = emptyList(),
        pattern = Regex("(?:₹|Rs\\.?)\\s*(?<amount>[\\d,]+\\.?\\d*)\\s+(?:paid to|sent to)\\s+(?<name>[^·]+)"),
        direction = Direction.DEBIT,
        channel = Channel.UPI,
        currencyDefault = null,  // Require explicit currency detection
        extractors = mapOf("amount" to "amount", "name" to "name")
    )

    val phonePe = TransactionTemplate(
        id = "phonepe.upi.debit.v1",
        packageNames = listOf("com.phonepe.app"),
        senderIds = emptyList(),
        pattern = Regex("(?:₹|Rs\\.?)\\s*(?<amount>[\\d,]+\\.?\\d*)\\s+to\\s+(?<name>[^·]+)"),
        direction = Direction.DEBIT,
        channel = Channel.UPI,
        currencyDefault = null,
        extractors = mapOf("amount" to "amount", "name" to "name")
    )

    val paytm = TransactionTemplate(
        id = "paytm.upi.debit.v1",
        packageNames = listOf("net.one97.paytm"),
        senderIds = emptyList(),
        pattern = Regex("(?:₹|Rs\\.?)\\s*(?<amount>[\\d,]+\\.?\\d*)\\s+paid to\\s+(?<name>[^·]+)"),
        direction = Direction.DEBIT,
        channel = Channel.UPI,
        currencyDefault = null,
        extractors = mapOf("amount" to "amount", "name" to "name")
    )

    // HDFC Bank SMS
    val hdfcUpiDebit = TransactionTemplate(
        id = "hdfc.sms.upi.debit.v3",
        packageNames = emptyList(),
        senderIds = listOf("VK-HDFCBK", "VM-HDFCBK", "AD-HDFCBK"),
        pattern = Regex(
            "(?:Rs\\.?|INR)\\s*(?<amount>[\\d,]+\\.?\\d*)\\s+debited from.*a/c.*\\*\\*(?<accountTail>\\d{4}).*VPA\\s+(?<vpa>[\\w.@-]+).*Ref\\s+(?<rrn>\\d+)"
        ),
        direction = Direction.DEBIT,
        channel = Channel.UPI,
        currencyDefault = null,
        extractors = mapOf(
            "amount" to "amount",
            "accountTail" to "accountTail",
            "vpa" to "vpa",
            "rrn" to "rrn"
        )
    )

    fun all(): List<TransactionTemplate> = listOf(
        googlePay,
        phonePe,
        paytm,
        hdfcUpiDebit
    )
}
