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
    val currencyDefault: String? = null,
    /**
     * If this matches, the template declines however well [pattern] fits.
     *
     * Banks describe money that has *not* moved in exactly the same grammar as
     * money that has: "INR 605.00 will be debited", "Rs. 349.0 has failed",
     * "Rs. 419.00 will be credited". Without a veto those become phantom
     * transactions, which is worse than missing a real one.
     */
    val veto: Regex? = null
) {
    fun routes(input: ParserInput): Boolean = when (input.source) {
        Source.NOTIFICATION -> input.packageName != null && input.packageName in packageNames
        Source.SMS -> anySender || matchesSender(input.sender)
        else -> false
    }

    /**
     * Indian sender IDs wrap the bank code in routing noise on both sides -
     * `VK-HDFCBK`, `AD-HDFCBK` and `JD-HDFCBK` are one bank, and so are `AIRBNK-S`
     * and `AIRBNK-G`. The bank code is the longest hyphen-separated part; the
     * operator prefix is two characters and the circle suffix is one.
     */
    fun matchesSender(sender: String?): Boolean {
        if (sender == null || senderIds.isEmpty()) return false
        val code = normalizeSender(sender)
        return senderIds.any { normalizeSender(it) == code }
    }

    fun extract(input: ParserInput): RawTxn? {
        val text = input.title?.let { "$it ${input.body}" } ?: input.body
        if (veto?.containsMatchIn(text) == true) return null

        val match = pattern.find(text) ?: return null

        val amount = match.groupOrNull("amount")?.let(::parseAmountMinor) ?: return null

        val currency = match.groupOrNull("currency")?.let(::normalizeCurrency)
            ?: detectCurrency(text)
            ?: currencyDefault
            ?: return null  // No currency found and no default

        return RawTxn(
            source = input.source,
            observedAt = input.timestamp,
            // The message's own timestamp when it states one credibly, otherwise
            // when it arrived. A bank SMS sent at 23:58 can land at 00:01, which
            // files the payment on the wrong day and leaves two daily totals
            // wrong - found twice in a real six-month ledger.
            occurredAt = MessageFacts.statedTime(text, input.timestamp) ?: input.timestamp,
            amountMinor = amount,
            currency = currency,
            direction = direction,
            counterpartyVpa = match.groupOrNull("vpa")?.lowercase(),
            counterpartyNameRaw = match.groupOrNull("name")?.let(::cleanName),
            // The reference and account number sit in a trailer that varies far
            // more than the sentence stating the payment, so they are scanned for
            // across the whole message rather than pinned into every template.
            rrn = match.groupOrNull("rrn") ?: findReference(text),
            accountTail = (match.groupOrNull("accountTail") ?: findAccountTail(text))?.takeLast(4),
            // A template declares the channel it is sure of; where it is not sure,
            // the message usually says. Most bank SMS shapes are rail-agnostic
            // ("Rs.X debited from a/c ..."), and leaving them all UNKNOWN made
            // "how you paid" a single meaningless bar.
            channel = if (channel == Channel.UNKNOWN) detectChannel(text) else channel,
            instrument = null,  // TODO: Extract from message
            templateId = id,
            bodyHash = dedupeHash(text, input.timestamp),
            institution = MessageFacts.institution(text),
            // The body alone, not the title-prefixed text the patterns run
            // against - this is shown to the user, so it should read exactly as
            // it did in their notification shade or inbox.
            sourceBody = input.body,
            sourceOrigin = input.packageName ?: input.sender
        )
    }

    private companion object {
        fun normalizeSender(sender: String): String =
            sender.split('-').maxByOrNull { it.length }.orEmpty().trim().uppercase()

        /**
         * `a/c XX2793`, `Ac XXXXXXXX00022793`, `A/c X0563`, `account XXXXXXXX7080`,
         * `Card x5678`. Only the last four digits are kept, so the varying mask
         * length does not matter.
         *
         * A masking character is required, which is what keeps the ATM's own id
         * out of `... through ATMXX3644` when the real account is quoted earlier -
         * `find` takes the leftmost match, and the account always leads.
         */
        val ACCOUNT_SCAN = Regex(
            """\b(?:a/c|ac|acct|account|card)\s*(?:no\.?)?\s*[:\s]*[Xx*]+\s*(\d{3,})""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Every reference trailer seen in the wild: `UPI Ref ID 431713243698`,
         * `(UPI Ref ID:414160752628)`, `IMPS Ref no 408008533167`,
         * `Ref No 602442799714`, `RRN406715003806`, `Txn ID: 113347733869`,
         * `thru UPI:430696560905`.
         */
        val REFERENCE_SCANS = listOf(
            Regex(
                """(?:(?:UPI|IMPS|NEFT|RTGS)\s*)?Ref(?:erence)?\s*(?:ID|No)?\s*[:.#]?\s*(\d{6,})""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""RRN\s*[:.#]?\s*(\d{6,})""", RegexOption.IGNORE_CASE),
            Regex("""Txn\s*(?:ID|No)\s*[:.#]?\s*(\d{6,})""", RegexOption.IGNORE_CASE),
            Regex("""(?:thru|through)\s+UPI\s*[:.]\s*(\d{6,})""", RegexOption.IGNORE_CASE)
        )

        fun findAccountTail(text: String): String? =
            ACCOUNT_SCAN.find(text)?.groupValues?.getOrNull(1)

        fun findReference(text: String): String? =
            REFERENCE_SCANS.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }

        /** Reads the rail off the message when the template could not name it. */
        fun detectChannel(text: String): Channel = when {
            Regex("""\bATM\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Channel.ATM
            Regex("""\bUPI\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Channel.UPI
            Regex("""\bIMPS\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Channel.IMPS
            Regex("""\bNEFT\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Channel.NEFT
            Regex("""\bRTGS\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Channel.RTGS
            Regex("""\bcard\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Channel.CARD
            else -> Channel.UNKNOWN
        }

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

    /**
     * `Rs. 100.00 has been debited from your account towards Google Play.`
     *
     * Standing-instruction and autopay debits phrase the payee with "towards"
     * rather than "to". Without this the catch-all caught the amount but not the
     * name, and the row read "Payment" while the message plainly said who it went
     * to - which is exactly the gap the Source section made visible.
     */
    val debitTowards = upi(
        id = "upi.debit.towards.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?debited\s+(?:from\s+your\s+account\s+)?towards\s+$NAME_GROUP(?:\s*[(·|.\n]|$)""",
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
     * Rebuilt against a 4,103-message backup from a real handset. The previous
     * set matched 1 of 652 candidate messages, because every template demanded an
     * account number immediately after the verb and almost no bank writes them
     * that way.
     *
     * Two things drive the design:
     *
     *  - **The account number is optional and moves.** It can lead
     *    (`A/c XX2793 debited INR 249.00`), trail
     *    (`INR 30.00 sent from your account XXXXXXXX7080`), or be absent entirely
     *    (`Rs. 1.00 debited from Airtel Payments Bank a/c`). It is scanned for
     *    globally instead of being pinned into the sentence.
     *  - **There is no catch-all here.** Notifications from a payment app are
     *    few and almost all real, so a loose fallback pays off. An SMS inbox is
     *    thousands of messages of marketing, OTPs and bill reminders written in
     *    the same grammar as payments, so a loose fallback invents transactions.
     *    Every shape below is anchored to a verb sitting directly against its
     *    amount, which is also what stops a closing balance being read as the
     *    amount paid.
     */

    /**
     * Sender IDs are matched on the bank code, ignoring both the rotating
     * operator prefix and the circle suffix (`AIRBNK-S`, `VK-HDFCBK`).
     *
     * Routing is by message shape, not by this list: bank short codes are not
     * standardised, and the corpus alone contained AIRBNK, PNBSMS, FedFiB and
     * SBIUPI in four different layouts. The list documents what has been seen.
     */
    val bankSenders: List<String> = listOf(
        "HDFCBK", "HDFCBN", "SBIUPI", "SBIINB", "SBIPSG", "CBSSBI", "ATMSBI",
        "ICICIB", "ICICIT", "AXISBK", "AXISBN", "KOTAKB", "PNBSMS", "PNBBNK",
        "BOBTXN", "BOBSMS", "CANBNK", "IDFCFB", "YESBNK", "INDUSB", "FEDBNK",
        "UNIONB", "IOBCHN", "CENTBK", "BOIIND", "UCOBNK", "IDBIBK", "RBLBNK",
        "AUBANK", "BANDAN", "JIOPAY", "PYTMBK", "AIRBNK", "EQUTAS", "FEDFIB",
        "BHIMAP", "SRIRAM"
    )

    /**
     * Money that has not actually moved.
     *
     * Banks announce intentions, failures and bills in the same grammar as
     * completed payments - "INR 605.00 will be debited from your account",
     * "Payment of Rs. 349.0 has failed", "Rs. 419.00 will be credited",
     * "Total amount payable: Rs. 706.82". A phantom transaction is worse than a
     * missed one, because the user cannot tell it is wrong without opening their
     * bank app.
     */
    private val NOT_A_TRANSACTION = Regex(
        """\b(?:will\s+be\s+(?:debited|credited|refunded)|has\s+failed|failed\s+for""" +
            """|has\s+requested|requested\s+money|is\s+due\s+for\s+payment|amount\s+payable""" +
            """|refund\s+for\s+.{0,20}initiated|if\s+debited)\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private fun bankSms(id: String, pattern: String, direction: Direction, channel: Channel = Channel.UPI) =
        TransactionTemplate(
            id = id,
            senderIds = bankSenders,
            anySender = true,
            pattern = Regex(pattern, SMS),
            direction = direction,
            channel = channel,
            veto = NOT_A_TRANSACTION
        )

    // ------------------------------------------------------------- SMS: debits

    /** `Rs.250.00 debited from a/c **1234 to VPA swiggy@ybl ... UPI Ref No 1234` */
    val smsDebitToVpa = bankSms(
        id = "sms.debit.to-vpa.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?debited.{0,80}?(?:to\s+(?:VPA\s+)?|VPA\s*[:\s])$VPA_GROUP""",
        direction = Direction.DEBIT
    )

    /**
     * `UPI AutoPay <vpa> for Google Play Debited Rs.119.00 scheduled on 04/07/2026`
     * - the only SMS shape in the corpus that names the merchant outright.
     */
    val smsDebitAutoPay = bankSms(
        id = "sms.debit.autopay.v1",
        pattern = """UPI\s*AutoPay\s+$VPA_GROUP\s+for\s+$NAME_GROUP\s+debited\s+$CUR\s*$AMOUNT""",
        direction = Direction.DEBIT
    )

    /**
     * `Rs 20.00 sent via UPI on 18-12-2025 at 01:55:31 to THE RICH TABLE.Ref:5352...`
     *
     * Federal Bank names the payee, which no other SMS debit shape in the corpus
     * does, so this runs well ahead of the anonymous shapes that would otherwise
     * swallow the same message.
     */
    val smsDebitSentViaUpi = bankSms(
        id = "sms.debit.sent-via-upi.v1",
        pattern = """$CUR\s*$AMOUNT\s+sent\s+via\s+UPI\b.{0,60}?\s+to\s+(?<name>[^.\n]{2,50}?)\s*\.\s*Ref""",
        direction = Direction.DEBIT
    )

    /** `Subscription payment to X CORP. PAID FEATURES for Rs 89.00 is successful` */
    val smsDebitPaymentTo = bankSms(
        id = "sms.debit.payment-to.v1",
        pattern = """payment\s+to\s+(?<name>[^\n]{2,50}?)\s+for\s+$CUR\s*$AMOUNT\s+is\s+success""",
        direction = Direction.DEBIT,
        channel = Channel.CARD
    )

    /** `Rs. 1.00 debited from Airtel Payments Bank a/c Txn ID 815926821055 Bal:5.17` */
    val smsDebitAmountFirst = bankSms(
        id = "sms.debit.amount-first.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?debited\b""",
        direction = Direction.DEBIT,
        channel = Channel.UNKNOWN
    )

    /**
     * `A/c XX2793 debited INR 249.00 Dt 01-11-24 thru UPI:430696560905`
     * `Ac XX2793 debited with Rs.300.00,20-03-2024 through ATMXX3644`
     *
     * The account leads, so the amount is only trustworthy when it sits directly
     * against the verb - otherwise the closing balance further along the message
     * is an equally good match.
     */
    val smsDebitAccountFirst = bankSms(
        id = "sms.debit.account-first.v1",
        pattern = """\b(?:a/c|ac|acct|account)\b.{0,30}?[-\s]debited\s*(?:with|by|for)?\s*$CUR\s*$AMOUNT""",
        direction = Direction.DEBIT,
        channel = Channel.UNKNOWN
    )

    /** `INR 30.00 sent from your account XXXXXXXX7080 Sent to your beneficiary` */
    val smsDebitSentFrom = bankSms(
        id = "sms.debit.sent-from-account.v1",
        pattern = """$CUR\s*$AMOUNT\s+sent\s+from\s+your\s+(?:a/c|ac|account)""",
        direction = Direction.DEBIT,
        channel = Channel.UNKNOWN
    )

    /** `You've spent INR 85.56 at 18:34 on July 7, 2025.` (Fi / Federal Bank) */
    val smsDebitSpent = bankSms(
        id = "sms.debit.spent.v1",
        pattern = """\byou(?:'ve|\s+have)?\s+spent\s+$CUR\s*$AMOUNT""",
        direction = Direction.DEBIT,
        channel = Channel.UNKNOWN
    )

    /** `Withdrawn: INR 200.00 | This transaction occurred on March 28, 2024` */
    val smsDebitWithdrawn = bankSms(
        id = "sms.debit.withdrawn.v1",
        pattern = """\bwithdrawn\s*[:\-]?\s*$CUR\s*$AMOUNT""",
        direction = Direction.DEBIT,
        channel = Channel.CARD
    )

    /** `Rs.1200.00 spent on HDFC Bank Card x1234 at AMAZON on 26-07-26` */
    val smsCardSpend = bankSms(
        id = "sms.debit.card-spend.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?(?:spent|used)\s+(?:on|at|using)\s+.{0,40}?""" +
            """card\s*(?:no\.?)?\s*[Xx*]*\d{4}.{0,20}?\s+at\s+(?<name>[^.\n]{1,60})""",
        direction = Direction.DEBIT,
        channel = Channel.CARD
    )

    // ------------------------------------------------------------ SMS: credits

    /** `Rs.1,500.00 credited to a/c XX0563 from VPA ramesh@okicici` */
    val smsCreditFromVpa = bankSms(
        id = "sms.credit.from-vpa.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:has\s+been\s+)?credited.{0,80}?(?:from\s+(?:VPA\s+)?|VPA\s*[:\s])$VPA_GROUP""",
        direction = Direction.CREDIT
    )

    /**
     * `your A/c X0563-credited by Rs.15000 on 24Jan26 transfer from RAJ KUMAR
     *  CHOUDHURY Ref No 602442799714` - SBI names the sender, so this runs ahead
     * of the shapes that would capture the same money anonymously.
     */
    val smsCreditNamedTransfer = bankSms(
        id = "sms.credit.named-transfer.v1",
        pattern = """credited\s*(?:with|by|for)?\s*$CUR\s*$AMOUNT.{0,40}?\btransfer\s+from\s+(?<name>[^.\n]{2,40}?)""" +
            """(?=\s+(?:Ref|RRN|Txn|UPI|on\b)|[.\n]|$)""",
        direction = Direction.CREDIT
    )

    /**
     * `Airtel Payments Bank a/c is credited with Rs.1000.00`
     * `Ac XXXXXXXX00022793 Credited with Rs.9299.00 ... Aval Bal Rs.60941.98`
     * `Your a/c XX2793 is credited for INR 4000.00 ... Available Bal INR 4530.98`
     * `your a/c no XX793 is credited by Rs 2000.00 ... (IMPS Ref no ...)`
     *
     * Anchoring the amount hard against `credited` is what keeps the balance that
     * follows out of the ledger.
     */
    val smsCreditWith = bankSms(
        id = "sms.credit.credited-with.v1",
        pattern = """\bcredited\s+(?:with|for|by)\s+$CUR\s*$AMOUNT""",
        direction = Direction.CREDIT,
        channel = Channel.UNKNOWN
    )

    /** `Rs.5.0 cashback for Prepaid Recharge credited to your Airtel a/c` */
    val smsCreditAmountFirst = bankSms(
        id = "sms.credit.amount-first.v1",
        pattern = """$CUR\s*$AMOUNT\s+(?:[\w\s]{0,40}?\s+)?credited\s+(?:to|in)\b""",
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
        debitTowards,
        debitPaymentOf,
        debitPaidTo,
        debitAmountFirst,
        creditAmountFirst,
        creditReceivedFrom,
        // Bank SMS, richest first: a shape that names the counterparty must be
        // offered before one that would capture the same money anonymously.
        smsDebitToVpa,
        smsCreditFromVpa,
        smsDebitSentViaUpi,
        smsDebitAutoPay,
        smsDebitPaymentTo,
        smsCreditNamedTransfer,
        smsCardSpend,
        smsDebitWithdrawn,
        smsDebitSentFrom,
        smsDebitSpent,
        smsDebitAmountFirst,
        smsDebitAccountFirst,
        smsCreditWith,
        smsCreditAmountFirst,
        // Nothing matched: capture it anyway, flagged for review.
        // Notifications only - see the note above the SMS block.
        genericCreditVerbFirst,
        genericCreditAmountFirst,
        genericDebitVerbFirst,
        genericDebitAmountFirst
    )
}
