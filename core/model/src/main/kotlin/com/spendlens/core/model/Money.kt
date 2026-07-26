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

    /** `₹` + [groupIndian] of the major units, plus paise only when non-zero. */
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
