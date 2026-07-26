package com.spendlens.core.parser

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import java.math.BigDecimal
import java.math.RoundingMode
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

class TemplateParser(initialTemplates: List<TransactionTemplate> = emptyList()) : TransactionParser {
    private val templates = initialTemplates.toMutableList()

    fun addTemplate(template: TransactionTemplate) {
        templates.add(template)
    }

    fun addTemplates(newTemplates: List<TransactionTemplate>) {
        templates.addAll(newTemplates)
    }

    override fun parse(input: ParserInput): RawTxn? {
        val candidateTemplates = templates.filter { it.routes(input) }

        // Templates are ordered most-specific first; first match wins.
        for (template in candidateTemplates) {
            val result = template.extract(input)
            if (result != null) return result
        }

        return null
    }
}

/**
 * A single extraction rule.
 *
 * [pattern] is matched against `"<title> <body>"`. Values come from *named*
 * capture groups; a template only declares the groups it can actually fill.
 * Recognised group names:
 *
 *  - `amount`      (required) numeric amount in major units, e.g. `1,234.50`
 *  - `currency`    optional explicit currency token
 *  - `name`        optional counterparty display name
 *  - `vpa`         optional counterparty VPA
 *  - `rrn`         optional UPI reference number
 *  - `accountTail` optional last four digits of the funding account
 *
 * The counterparty name is never taken from the notification title. Payment apps
 * put their own name there ("Bharat Interface for Money", "Google Pay"), and a
 * merchant called "Google Pay" in your ledger is worse than no name at all.
 */
data class TransactionTemplate(
    val id: String,
    val packageNames: List<String> = emptyList(),
    val senderIds: List<String> = emptyList(),
    /** Empty means "any sender" - used by the generic bank-SMS shapes. */
    val anySender: Boolean = false,
    val pattern: Regex,
    val direction: Direction,
    val channel: Channel,
    /** `null` means "refuse to guess" - the message must state its currency. */
    val currencyDefault: String? = null
) {
    fun routes(input: ParserInput): Boolean = when (input.source) {
        Source.NOTIFICATION -> input.packageName != null && input.packageName in packageNames
        Source.SMS -> anySender || matchesSender(input.sender)
        else -> false
    }

    /**
     * Indian sender IDs are `<2-letter operator><separator><bank code>`, and the
     * operator prefix rotates (VK-HDFCBK, AD-HDFCBK, JD-HDFCBK are the same bank).
     * Match on the bank code only.
     */
    fun matchesSender(sender: String?): Boolean {
        if (sender == null || senderIds.isEmpty()) return false
        val code = normalizeSender(sender)
        return senderIds.any { normalizeSender(it) == code }
    }

    fun extract(input: ParserInput): RawTxn? {
        val text = input.title?.let { "$it ${input.body}" } ?: input.body
        val match = pattern.find(text) ?: return null

        val amount = match.groupOrNull("amount")?.let(::parseAmountMinor) ?: return null

        val currency = match.groupOrNull("currency")?.let(::normalizeCurrency)
            ?: detectCurrency(text)
            ?: currencyDefault
            ?: return null  // No currency found and no default

        return RawTxn(
            source = input.source,
            observedAt = input.timestamp,
            occurredAt = input.timestamp,  // TODO: Parse from message body when stated
            amountMinor = amount,
            currency = currency,
            direction = direction,
            counterpartyVpa = match.groupOrNull("vpa")?.lowercase(),
            counterpartyNameRaw = match.groupOrNull("name")?.let(::cleanName),
            rrn = match.groupOrNull("rrn"),
            accountTail = match.groupOrNull("accountTail"),
            channel = channel,
            instrument = null,  // TODO: Extract from message
            templateId = id,
            bodyHash = dedupeHash(text, input.timestamp)
        )
    }

    private companion object {
        fun normalizeSender(sender: String): String =
            sender.substringAfterLast('-').trim().uppercase()

        /**
         * Reading a named group that the pattern does not declare throws, so every
         * lookup goes through here - templates only fill the groups they have.
         */
        fun MatchResult.groupOrNull(name: String): String? =
            try {
                groups[name]?.value?.trim()?.takeIf { it.isNotEmpty() }
            } catch (_: IllegalArgumentException) {
                null
            }

        /**
         * Exact decimal -> minor units. Never goes through Double: `2999.95 * 100`
         * is 299994.999... in binary floating point, which truncates to ₹2999.94.
         */
        fun parseAmountMinor(raw: String): Long? {
            val cleaned = raw.replace(",", "").replace(" ", "")
            if (cleaned.isEmpty()) return null
            return try {
                BigDecimal(cleaned)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
                    .takeIf { it >= 0 }
            } catch (_: ArithmeticException) {
                null
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun normalizeCurrency(token: String): String? = when (token.trim().uppercase()) {
            "₹", "RS", "RS.", "INR" -> "INR"
            "$", "USD" -> "USD"
            "AUD", "A$" -> "AUD"
            "EUR", "€" -> "EUR"
            "GBP", "£" -> "GBP"
            "AED" -> "AED"
            "SGD" -> "SGD"
            else -> null
        }

        fun detectCurrency(text: String): String? = when {
            text.contains("₹") || text.contains("INR", ignoreCase = true) ||
                text.contains(Regex("""\bRs\.?""", RegexOption.IGNORE_CASE)) -> "INR"
            text.contains("USD") || text.contains("$") -> "USD"
            text.contains("AUD") -> "AUD"
            text.contains("EUR") || text.contains("€") -> "EUR"
            text.contains("GBP") || text.contains("£") -> "GBP"
            text.contains("AED") -> "AED"
            text.contains("SGD") -> "SGD"
            else -> null
        }

        /** Strip the trailing noise UPI apps append after the counterparty name. */
        fun cleanName(raw: String): String? = raw
            .trim()
            .trimEnd('.', ',', '-', '·', '|', ':', ';')
            .trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

        const val MAX_NAME_LENGTH = 60

        /**
         * Dedupe key. The post time is folded in on purpose: a listener rebind
         * replays the tray with the *same* post times (so replays collapse), while
         * two genuinely separate ₹20 chai payments carry different ones and both
         * survive.
         */
        fun dedupeHash(text: String, timestamp: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest("$text|$timestamp".toByteArray(Charsets.UTF_8))
            return hash.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Built-in templates.
 *
 * Organised by *message shape* rather than by app. Indian UPI apps all paraphrase
 * the same handful of NPCI sentences, and keying on the app meant a wording the
 * author had not personally seen produced silence. Every shape is offered to
 * every UPI package, and a deliberately loose catch-all runs last so an
 * unrecognised phrasing still lands in the ledger flagged for review instead of
 * disappearing.
 */
object BuiltInTemplates {

    private const val AMOUNT = """(?<amount>[\d,]+(?:\.\d{1,2})?)"""
    private const val CUR = """(?:₹|INR|Rs\.?)"""
    private const val VPA_GROUP = """(?<vpa>[\w.\-]{2,}@[a-zA-Z]{2,})"""
    private const val NAME_GROUP = """(?<name>[^(\n·|]{1,60}?)"""

    /** `account(XX0563)`, `a/c **1234`, `A/c no. XXXXXX0563` */
    private const val ACCOUNT_TAIL =
        """(?:a/c|acct?|account)\s*(?:no\.?)?\s*[(\s]*[Xx*]*(?<accountTail>\d{4})\)?"""

    private const val REF = """(?:UPI\s*)?(?:Ref(?:erence)?|RRN|txn(?:\s*id)?)(?:\s*No)?\.?[:\s#]*(?<rrn>\d{6,})"""

    private val NOTIF = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val SMS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    /**
     * Every app that posts a UPI payment notification worth reading. Adding a
     * package here is enough - it inherits all the shapes below.
     */
    val upiPackages: List<String> = listOf(
        "com.google.android.apps.nbu.paisa.user",  // Google Pay
        "com.phonepe.app",                          // PhonePe
        "com.phonepe.simulator",
        "net.one97.paytm",                          // Paytm
        "in.org.npci.upiapp",                       // BHIM
        "in.amazon.mShop.android.shopping",         // Amazon Pay
        "com.dreamplug.androidapp",                 // CRED
        "money.super.payments",                     // super.money
        "in.slice.android",                         // slice
        "com.jupiter.consumer",                     // Jupiter
        "com.epifi.paisa",                          // Fi
        "com.naviapp",                              // Navi
        "com.whatsapp",                             // WhatsApp Pay
        "com.mobikwik_new",                         // MobiKwik
        "com.freecharge.android",                   // Freecharge
        "com.csam.icici.bank.imobile",              // iMobile Pay
        "com.snapwork.hdfc",                        // HDFC PayZapp
        "com.sbi.upi",                              // BHIM SBI Pay
        "com.axis.mobile",                          // Axis Mobile
        "com.msf.kbank.mobile"                      // Kotak
    )

    private fun upi(id: String, pattern: String, direction: Direction) = TransactionTemplate(
        id = id,
        packageNames = upiPackages,
        pattern = Regex(pattern, NOTIF),
        direction = direction,
        channel = Channel.UPI
    )

    // ------------------------------------------------------- NPCI sentence shapes

    /**
     * `Received INR 1.00 in your State Bank Of India account(XX0563) from
     *  SUBRATA CHOUDHURY (9733230455-3@ybl).`
     *
     * This is the exact BHIM wording; it is also what most bank-branded UPI apps
     * emit, because the string comes from the NPCI common library.
     */
    val creditIntoAccount = upi(
        id = "upi.credit.into-account.v1",
        pattern = """(?:received|credited)\s+$CUR\s*$AMOUNT\s+(?:in|to)\s+your\s+.{0,60}?$ACCOUNT_TAIL""" +
            """\s+from\s+$NAME_GROUP\s*\(\s*$VPA_GROUP\s*\)""",
        direction = Direction.CREDIT
    )

    /** Same shape, without the parenthesised VPA. */
    val creditIntoAccountNoVpa = upi(
        id = "upi.credit.into-account.novpa.v1",
        pattern = """(?:received|credited)\s+$CUR\s*$AMOUNT\s+(?:in|to)\s+your\s+.{0,60}?$ACCOUNT_TAIL""" +
            """\s+from\s+(?<name>[^.\n·|]{1,60})""",
        direction = Direction.CREDIT
    )

    /** `Sent INR 100.00 from your ... account(XX0563) to RAMESH (ramesh@ybl).` */
    val debitFromAccount = upi(
        id = "upi.debit.from-account.v1",
        pattern = """(?:sent|paid|debited)\s+$CUR\s*$AMOUNT\s+(?:from|out\s+of)\s+your\s+.{0,60}?$ACCOUNT_TAIL""" +
            """\s+to\s+$NAME_GROUP\s*\(\s*$VPA_GROUP\s*\)""",
        direction = Direction.DEBIT
    )

    val debitFromAccountNoVpa = upi(
        id = "upi.debit.from-account.novpa.v1",
        pattern = """(?:sent|paid|debited)\s+$CUR\s*$AMOUNT\s+(?:from|out\s+of)\s+your\s+.{0,60}?$ACCOUNT_TAIL""" +
            """\s+to\s+(?<name>[^.\n·|]{1,60})""",
        direction = Direction.DEBIT
    )

    // ------------------------------------------------------- Consumer app shapes

    /** `You paid ₹250.00 to Swiggy` / `Paid ₹250 to Swiggy` / `₹250 sent to Swiggy` */
    val debitPaidTo = upi(
        id = "upi.debit.paid-to.v1",
        pattern = """(?:you\s+)?(?:paid|sent)\s+$CUR\s*$AMOUNT\s+to\s+$NAME_GROUP(?:\s*[(·|.\n]|$)""",
        direction = Direction.DEBIT
    )

    val debitAmountFirst = upi(
        id = "upi.debit.amount-first.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:successfully\s+)?(?:paid|sent|debited)\s+to\s+$NAME_GROUP(?:\s*[(·|.\n]|$)""",
        direction = Direction.DEBIT
    )

    /** `Payment of ₹80 to Chai stall is successful` */
    val debitPaymentOf = upi(
        id = "upi.debit.payment-of.v1",
        pattern = """payment\s+of\s+$CUR\s*$AMOUNT\s+to\s+$NAME_GROUP(?:\s+(?:is\s+)?success\w*|\s*[(·|.\n]|$)""",
        direction = Direction.DEBIT
    )

    // Java regex forbids the same group name twice in one pattern, so the two
    // word orders are two templates rather than one alternation.

    /** `₹500 received from Asha` */
    val creditAmountFirst = upi(
        id = "upi.credit.amount-first.v1",
        pattern = """$CUR\s*$AMOUNT\s+received\s+from\s+$NAME_GROUP(?:\s*[(·|.\n]|$)""",
        direction = Direction.CREDIT
    )

    /** `You received ₹500 from Asha` */
    val creditReceivedFrom = upi(
        id = "upi.credit.received-from.v1",
        pattern = """(?:you\s+)?received\s+$CUR\s*$AMOUNT\s+from\s+$NAME_GROUP(?:\s*[(·|.\n]|$)""",
        direction = Direction.CREDIT
    )

    // When the counterparty is written as a bare VPA, capture it as a VPA rather
    // than as a display name: the resolution ladder can do something useful with
    // a VPA (structure rules, user rules, retroactive relabelling) and nothing at
    // all with the string "9822014455@ybl" sitting in the name column. These must
    // therefore be offered before the free-text name shapes.

    /** `₹80 paid to 9822014455@ybl` */
    val debitToVpa = upi(
        id = "upi.debit.to-vpa.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:paid|sent|debited)?\s*to\s+$VPA_GROUP""",
        direction = Direction.DEBIT
    )

    /** `You paid ₹80 to 9822014455@ybl` */
    val debitPaidToVpa = upi(
        id = "upi.debit.paid-to-vpa.v1",
        pattern = """(?:you\s+)?(?:paid|sent)\s+$CUR\s*$AMOUNT\s+to\s+$VPA_GROUP""",
        direction = Direction.DEBIT
    )

    /** `₹500 received from 9822014455@ybl` */
    val creditFromVpa = upi(
        id = "upi.credit.from-vpa.v1",
        pattern = """$CUR\s*$AMOUNT\s+received\s+from\s+$VPA_GROUP""",
        direction = Direction.CREDIT
    )

    // ---------------------------------------------------------------- Bank SMS

    /**
     * Sender IDs are matched on the bank code only, so the rotating two-letter
     * operator prefix (VK-, AD-, JD-, ...) does not have to be enumerated.
     */
    val bankSenders: List<String> = listOf(
        "HDFCBK", "HDFCBN", "SBIUPI", "SBIINB", "SBIPSG", "CBSSBI", "ATMSBI",
        "ICICIB", "ICICIT", "AXISBK", "AXISBN", "KOTAKB", "PNBSMS", "PNBBNK",
        "BOBTXN", "BOBSMS", "CANBNK", "IDFCFB", "YESBNK", "INDUSB", "FEDBNK",
        "UNIONB", "IOBCHN", "CENTBK", "BOIIND", "UCOBNK", "IDBIBK", "RBLBNK",
        "AUBANK", "BANDAN", "JIOPAY", "PYTMBK", "AIRBNK", "EQUTAS", "SRIRAM"
    )

    private fun bankSms(id: String, pattern: String, direction: Direction, channel: Channel = Channel.UPI) =
        TransactionTemplate(
            id = id,
            senderIds = bankSenders,
            // Any sender: bank short codes are not standardised and new ones
            // appear constantly. Routing is done by the message shape instead,
            // which is far harder to accidentally match than a sender ID.
            anySender = true,
            pattern = Regex(pattern, SMS),
            direction = direction,
            channel = channel
        )

    /** `Rs.250.00 debited from a/c **1234 to VPA swiggy@ybl ... UPI Ref No 123456789012` */
    val smsDebitVpaRef = bankSms(
        id = "sms.debit.vpa-ref.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?debited\s+from\s+(?:your\s+)?$ACCOUNT_TAIL""" +
            """.*?(?:to\s+(?:VPA\s+)?|VPA\s+)$VPA_GROUP.*?$REF""",
        direction = Direction.DEBIT
    )

    val smsCreditVpaRef = bankSms(
        id = "sms.credit.vpa-ref.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?credited\s+to\s+(?:your\s+)?$ACCOUNT_TAIL""" +
            """.*?(?:from\s+(?:VPA\s+)?|VPA\s+)$VPA_GROUP.*?$REF""",
        direction = Direction.CREDIT
    )

    /** `Rs 500 debited from A/c XX1234 and credited to ramesh@okicici` (no ref). */
    val smsDebitVpa = bankSms(
        id = "sms.debit.vpa.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?debited\s+from\s+(?:your\s+)?$ACCOUNT_TAIL""" +
            """.*?(?:to\s+(?:VPA\s+)?|VPA\s+)$VPA_GROUP""",
        direction = Direction.DEBIT
    )

    val smsCreditVpa = bankSms(
        id = "sms.credit.vpa.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?credited\s+to\s+(?:your\s+)?$ACCOUNT_TAIL""" +
            """.*?(?:from\s+(?:VPA\s+)?|VPA\s+)$VPA_GROUP""",
        direction = Direction.CREDIT
    )

    /** `Rs.1200.00 spent on HDFC Bank Card x1234 at AMAZON on 26-07-26` */
    val smsCardSpend = bankSms(
        id = "sms.debit.card-spend.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?(?:spent|used)\s+(?:on|at|using)\s+.{0,40}?""" +
            """(?:card|a/c)\s*(?:no\.?)?\s*[Xx*]*(?<accountTail>\d{4}).*?\s+at\s+(?<name>[^.\n]{1,60})""",
        direction = Direction.DEBIT,
        channel = Channel.CARD
    )

    /** Debit stated with an account, but nothing else legible. */
    val smsDebitMinimal = bankSms(
        id = "sms.debit.minimal.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?debited\s+from\s+(?:your\s+)?$ACCOUNT_TAIL""",
        direction = Direction.DEBIT,
        channel = Channel.UNKNOWN
    )

    val smsCreditMinimal = bankSms(
        id = "sms.credit.minimal.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?credited\s+to\s+(?:your\s+)?$ACCOUNT_TAIL""",
        direction = Direction.CREDIT,
        channel = Channel.UNKNOWN
    )

    // ------------------------------------------------------------- Catch-alls

    /**
     * Last resort, notifications only.
     *
     * Requires a currency-marked amount *and* an unambiguous direction verb, and
     * captures no counterparty at all. It exists so that an unseen phrasing shows
     * up as a reviewable row rather than as silence - which is exactly how the
     * BHIM credit above went missing. Anything it produces lands on resolution
     * rung 6 and is flagged for review.
     */
    private const val DEBIT_VERB = """\b(?:paid|sent|debited|spent|deducted)\b"""
    private const val CREDIT_VERB = """\b(?:received|credited|refunded)\b"""

    val genericDebitVerbFirst = upi(
        id = "upi.generic.debit.verb-first.v1",
        pattern = """$DEBIT_VERB.{0,40}?$CUR\s*$AMOUNT""",
        direction = Direction.DEBIT
    )

    val genericDebitAmountFirst = upi(
        id = "upi.generic.debit.amount-first.v1",
        pattern = """$CUR\s*$AMOUNT.{0,40}?$DEBIT_VERB""",
        direction = Direction.DEBIT
    )

    val genericCreditVerbFirst = upi(
        id = "upi.generic.credit.verb-first.v1",
        pattern = """$CREDIT_VERB.{0,40}?$CUR\s*$AMOUNT""",
        direction = Direction.CREDIT
    )

    val genericCreditAmountFirst = upi(
        id = "upi.generic.credit.amount-first.v1",
        pattern = """$CUR\s*$AMOUNT.{0,40}?$CREDIT_VERB""",
        direction = Direction.CREDIT
    )

    /**
     * Ordered most-specific first: [TemplateParser] returns the first template
     * that matches, so the catch-alls must come last.
     */
    fun all(): List<TransactionTemplate> = listOf(
        // NPCI sentences carry the most fields, so they get first refusal.
        creditIntoAccount,
        debitFromAccount,
        creditIntoAccountNoVpa,
        debitFromAccountNoVpa,
        // A bare VPA counterparty beats a free-text name.
        debitPaidToVpa,
        debitToVpa,
        creditFromVpa,
        // Consumer app phrasings.
        debitPaymentOf,
        debitPaidTo,
        debitAmountFirst,
        creditAmountFirst,
        creditReceivedFrom,
        // Bank SMS, richest first.
        smsDebitVpaRef,
        smsCreditVpaRef,
        smsDebitVpa,
        smsCreditVpa,
        smsCardSpend,
        smsDebitMinimal,
        smsCreditMinimal,
        // Nothing matched: capture it anyway, flagged for review.
        genericCreditVerbFirst,
        genericCreditAmountFirst,
        genericDebitVerbFirst,
        genericDebitAmountFirst
    )
}
