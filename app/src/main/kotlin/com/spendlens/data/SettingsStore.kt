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

    /**
     * Light, dark, or whatever the system is set to.
     *
     * Defaults to following the system, because a money app that ignores the
     * phone's night setting is the one glaring white rectangle at 2am.
     */
    var themeMode: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /**
     * Which typeface set the app renders in.
     *
     * Only affects display text — headings, the day total, section labels. The
     * transaction rows keep their tabular figures whatever is chosen, because a
     * column of amounts that does not align is harder to read regardless of how
     * handsome the face is.
     */
    var typeface: String
        get() = prefs.getString(KEY_TYPEFACE, TYPE_GROTESQUE) ?: TYPE_GROTESQUE
        set(value) = prefs.edit().putString(KEY_TYPEFACE, value).apply()

    /** Pushes the stored preference into the formatter at startup. */
    fun applyToFormatter() {
        MoneyFormat.displayCurrency = currency
    }

    companion object {
        private const val KEY_CURRENCY = "currency"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_TYPEFACE = "typeface"

        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"
        val THEME_MODES = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

        /** The current voice: a compact grotesque. */
        const val TYPE_GROTESQUE = "GROTESQUE"
        /** A high-contrast serif for display text, editorial in feel. */
        const val TYPE_SERIF = "SERIF"
        val TYPEFACES = listOf(TYPE_GROTESQUE, TYPE_SERIF)
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
