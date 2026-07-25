package com.spendlens.core.model

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

    /** Format for display: ₹1,840 or $25.00 */
    fun format(locale: java.util.Locale = java.util.Locale("en", "IN")): String {
        val majorUnits = amountMinor / 100.0
        val formatter = java.text.NumberFormat.getCurrencyInstance(locale)
        formatter.currency = java.util.Currency.getInstance(currency)
        return formatter.format(majorUnits)
    }

    /** Indian digit grouping: ₹1,84,000 */
    fun formatIndian(): String {
        if (currency != "INR") return format()
        val major = amountMinor / 100
        val minor = amountMinor % 100
        val formatted = major.toString()
            .reversed()
            .chunked(3) { if (it.length == 3) it else it }
            .mapIndexed { index, chunk -> 
                if (index == 0) chunk else chunk.take(2)
            }
            .joinToString(",")
            .reversed()
        return "₹$formatted${if (minor > 0) ".$minor" else ""}"
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
        
        fun parse(amount: String, defaultCurrency: String = "INR"): Money? {
            val cleanedAmount = amount.replace(Regex("[,\\s]"), "")
            val currencyPattern = Regex("(?:₹|Rs\\.?|INR|USD|\\$|AUD|EUR|GBP)")
            val currency = currencyPattern.find(amount)?.value?.let {
                when (it) {
                    "₹", "Rs", "Rs.", "INR" -> "INR"
                    "$", "USD" -> "USD"
                    "AUD" -> "AUD"
                    "EUR" -> "EUR"
                    "GBP" -> "GBP"
                    else -> defaultCurrency
                }
            } ?: defaultCurrency
            
            val numericPart = cleanedAmount.replace(currencyPattern, "")
            val amountValue = numericPart.toDoubleOrNull() ?: return null
            val minor = (amountValue * 100).toLong()
            
            return Money(minor, currency)
        }
    }
}
