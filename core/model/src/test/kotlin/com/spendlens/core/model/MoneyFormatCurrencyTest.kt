package com.spendlens.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFormatCurrencyTest {

    @After
    fun reset() {
        MoneyFormat.displayCurrency = "INR"
    }

    /**
     * Grouping follows the currency, not the device locale. Rupees group in lakhs
     * and crores; everything else groups in thousands. Getting this wrong is
     * immediately obvious to a reader and makes the app feel foreign.
     */
    @Test
    fun `rupees group in lakhs, other currencies in thousands`() {
        assertEquals("₹18,40,000", MoneyFormat.money(18_40_000_00, "INR"))
        assertEquals("$1,840,000", MoneyFormat.money(18_40_000_00, "USD"))
        assertEquals("€1,840,000", MoneyFormat.money(18_40_000_00, "EUR"))
    }

    @Test
    fun `minor units show only when non-zero`() {
        assertEquals("₹250", MoneyFormat.money(25_000, "INR"))
        assertEquals("₹250.05", MoneyFormat.money(25_005, "INR"))
        assertEquals("$19.99", MoneyFormat.money(1_999, "USD"))
    }

    /** Yen has no sub-unit, so a decimal part would be nonsense. */
    @Test
    fun `zero-decimal currencies render no fraction`() {
        assertEquals("¥1,840", MoneyFormat.money(1_840_00, "JPY"))
        assertEquals("Rp5,000", MoneyFormat.money(5_000_00, "IDR"))
    }

    @Test
    fun `an unlisted currency falls back to its iso code rather than a wrong symbol`() {
        assertEquals("SEK 1,200", MoneyFormat.money(1_200_00, "SEK"))
    }

    @Test
    fun `the display currency is used when none is given`() {
        MoneyFormat.displayCurrency = "USD"
        assertEquals("$1,200", MoneyFormat.money(1_200_00))
        MoneyFormat.displayCurrency = "INR"
        assertEquals("₹1,200", MoneyFormat.money(1_200_00))
    }

    /** A malformed setting must never leave the app formatting garbage. */
    @Test
    fun `an invalid display currency falls back to rupees`() {
        MoneyFormat.displayCurrency = "not a code"
        assertEquals("INR", MoneyFormat.displayCurrency)
    }

    @Test
    fun `small amounts are not grouped`() {
        assertEquals("₹620", MoneyFormat.money(620_00, "INR"))
        assertEquals("$620", MoneyFormat.money(620_00, "USD"))
    }
}
