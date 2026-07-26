package com.spendlens.data

import android.content.Context
import com.spendlens.core.model.MoneyFormat

/**
 * The handful of preferences the app has.
 *
 * Plain SharedPreferences rather than DataStore: there are two values, neither is
 * secret, and DataStore was already removed from this project once for dragging a
 * native library into every APK to do less than this file does.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("spendlens_settings", Context.MODE_PRIVATE)

    /**
     * The currency the UI formats in.
     *
     * A display setting only. Every stored transaction keeps the currency it was
     * parsed with - the parser refuses to guess one - so changing this never
     * rewrites history or reinterprets an old amount. It decides the symbol and
     * grouping shown, and what a new manual entry defaults to.
     */
    var currency: String
        get() = prefs.getString(KEY_CURRENCY, DEFAULT_CURRENCY) ?: DEFAULT_CURRENCY
        set(value) {
            prefs.edit().putString(KEY_CURRENCY, value).apply()
            MoneyFormat.displayCurrency = value
        }

    /** Pushes the stored preference into the formatter at startup. */
    fun applyToFormatter() {
        MoneyFormat.displayCurrency = currency
    }

    companion object {
        private const val KEY_CURRENCY = "currency"
        const val DEFAULT_CURRENCY = "INR"

        /**
         * Offered in the picker. Deliberately short and India-first rather than
         * every ISO code: a list of 180 currencies is a worse experience than a
         * list of the twelve anyone using this app is likely to want.
         */
        val COMMON_CURRENCIES = listOf(
            "INR", "USD", "EUR", "GBP", "AED", "SGD",
            "AUD", "CAD", "JPY", "MYR", "LKR", "NPR"
        )
    }
}
