package com.spendlens.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money is always an integer of minor units plus an ISO-4217 currency code.
 * Never a float. Never an implicit INR.
 */
data class Money(
    val amountMinor: Long,  // Always positive, in minor units (paise for INR)
    val currency: String    // ISO 4217 code
) {
    init {
        require(amountMinor >= 0) { "Amount must be positive" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "Currency must be 3-letter ISO code" }
    }

    /** Format for display using the platform's rules: ₹1,840.00 or $25.00 */
    fun format(locale: java.util.Locale = MoneyFormat.INDIA): String {
        val majorUnits = BigDecimal.valueOf(amountMinor).movePointLeft(2)
        val formatter = java.text.NumberFormat.getCurrencyInstance(locale)
        formatter.currency = java.util.Currency.getInstance(currency)
        return formatter.format(majorUnits)
    }

    /** Indian digit grouping, paise only when non-zero: ₹18,40,000 / ₹250.05 */
    fun formatIndian(): String {
        if (currency != "INR") return format()
        return MoneyFormat.rupees(amountMinor)
    }

    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add different currencies" }
        return copy(amountMinor = amountMinor + other.amountMinor)
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency) { "Cannot subtract different currencies" }
        require(amountMinor >= other.amountMinor) { "Result would be negative" }
        return copy(amountMinor = amountMinor - other.amountMinor)
    }

    companion object {
        val ZERO_INR = Money(0, "INR")
        val ZERO_USD = Money(0, "USD")

        private val CURRENCY_TOKEN = Regex("""₹|Rs\.?|INR|USD|\$|AUD|EUR|GBP""", RegexOption.IGNORE_CASE)

        /**
         * Parse a human-written amount. Returns null rather than guessing when the
         * numeric part is unreadable; [defaultCurrency] applies only when the text
         * carries no currency token at all.
         */
        fun parse(amount: String, defaultCurrency: String = "INR"): Money? {
            val currency = CURRENCY_TOKEN.find(amount)?.value?.let {
                when (it.uppercase().removeSuffix(".")) {
                    "₹", "RS", "INR" -> "INR"
                    "$", "USD" -> "USD"
                    "AUD" -> "AUD"
                    "EUR" -> "EUR"
                    "GBP" -> "GBP"
                    else -> defaultCurrency
                }
            } ?: defaultCurrency

            val numericPart = amount
                .replace(CURRENCY_TOKEN, "")
                .replace(Regex("""[,\s]"""), "")
                .trim()
            if (numericPart.isEmpty()) return null

            // BigDecimal, not Double: 2999.95 * 100 is 299994.999... in binary
            // floating point and would silently become ₹2999.94.
            val minor = try {
                BigDecimal(numericPart)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
            } catch (_: NumberFormatException) {
                return null
            } catch (_: ArithmeticException) {
                return null
            }

            return if (minor < 0) null else Money(minor, currency)
        }
    }
}

/**
 * Shared money rendering, so the notification, the foreground service and the UI
 * cannot drift apart on how a total looks.
 */
object MoneyFormat {

    val INDIA: java.util.Locale = java.util.Locale.forLanguageTag("en-IN")

    /**
     * The currency the UI formats in, set by the user.
     *
     * Deliberately a display concern only. Every stored transaction keeps the
     * currency it was actually parsed with - the parser refuses to guess one -
     * so changing this never rewrites history or reinterprets an old amount. It
     * decides the symbol and grouping shown, and the default for a manual entry.
     */
    @Volatile
    var displayCurrency: String = "INR"
        set(value) {
            field = if (value.matches(Regex("[A-Z]{3}"))) value else "INR"
        }

    /** Symbols people expect. Anything unlisted falls back to its ISO code. */
    private val SYMBOLS = mapOf(
        "INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥",
        "AUD" to "A$", "CAD" to "C$", "SGD" to "S$", "AED" to "AED ",
        "CHF" to "CHF ", "CNY" to "¥", "NZD" to "NZ$", "ZAR" to "R",
        "LKR" to "Rs ", "NPR" to "Rs ", "BDT" to "৳", "PKR" to "Rs ",
        "MYR" to "RM", "THB" to "฿", "IDR" to "Rp", "PHP" to "₱",
        "SAR" to "SAR ", "QAR" to "QAR ", "KWD" to "KWD ", "OMR" to "OMR "
    )

    /** Currencies whose smallest unit is the whole unit — no decimal part. */
    private val ZERO_DECIMAL = setOf("JPY", "KRW", "VND", "IDR", "CLP", "ISK")

    fun symbolFor(code: String): String = SYMBOLS[code] ?: "$code "

    /**
     * Formats in [displayCurrency] unless a specific one is given.
     *
     * Grouping follows the currency, not the device locale: rupees are grouped
     * in lakhs and crores (18,40,000) and everything else in thousands
     * (1,840,000). Getting that wrong is immediately obvious to the reader and
     * makes the whole app feel foreign.
     */
    fun money(amountMinor: Long, currency: String = displayCurrency): String {
        val symbol = symbolFor(currency)
        if (currency in ZERO_DECIMAL) {
            val whole = amountMinor / 100
            return symbol + groupFor(currency, whole)
        }
        val major = amountMinor / 100
        val minor = amountMinor % 100
        val fraction = if (minor > 0) ".${minor.toString().padStart(2, '0')}" else ""
        return "$symbol${groupFor(currency, major)}$fraction"
    }

    private fun groupFor(currency: String, value: Long): String =
        if (currency == "INR") groupIndian(value) else groupWestern(value)

    /** 1840000 -> "1,840,000". */
    fun groupWestern(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        return digits.reversed().chunked(3).joinToString(",").reversed()
    }

    /**
     * `₹` + [groupIndian] of the major units, plus paise only when non-zero.
     *
     * Kept because it is what the parser tests assert against, but the UI should
     * call [money] so the user's chosen currency is honoured.
     */
    fun rupees(amountMinor: Long): String {
        val major = amountMinor / 100
        val paise = amountMinor % 100
        val fraction = if (paise > 0) ".${paise.toString().padStart(2, '0')}" else ""
        return "₹${groupIndian(major)}$fraction"
    }

    /**
     * Indian digit grouping: last three digits, then pairs.
     * 1840000 -> "18,40,000"; 620 -> "620".
     */
    fun groupIndian(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        val groupedHead = head.reversed().chunked(2).joinToString(",").reversed()
        return "$groupedHead,$tail"
    }
}
