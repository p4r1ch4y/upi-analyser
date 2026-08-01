package com.spendlens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {

    @Test
    fun `indian grouping puts the last three digits together then pairs`() {
        assertEquals("620", MoneyFormat.groupIndian(620))
        assertEquals("1,840", MoneyFormat.groupIndian(1_840))
        assertEquals("18,40,000", MoneyFormat.groupIndian(18_40_000))
        assertEquals("1,23,45,678", MoneyFormat.groupIndian(1_23_45_678))
    }

    @Test
    fun `grouping never drops digits`() {
        for (value in listOf(1L, 12L, 123L, 1234L, 12345L, 123456L, 1234567L, 12345678L)) {
            val rendered = MoneyFormat.groupIndian(value)
            assertEquals(value.toString(), rendered.replace(",", ""))
        }
    }

    @Test
    fun `paise are zero padded, and hidden when zero`() {
        assertEquals("₹250", MoneyFormat.rupees(25_000))
        assertEquals("₹250.05", MoneyFormat.rupees(25_005))
        assertEquals("₹250.50", MoneyFormat.rupees(25_050))
        assertEquals("₹0", MoneyFormat.rupees(0))
    }

    @Test
    fun `parsing keeps exact paise instead of rounding through a double`() {
        // 2999.95 * 100 is 299994.999... in binary floating point.
        assertEquals(Money(299_995, "INR"), Money.parse("₹2,999.95"))
        assertEquals(Money(1_010, "INR"), Money.parse("Rs. 10.10"))
    }

    @Test
    fun `parsing reads the stated currency rather than assuming INR`() {
        assertEquals("USD", Money.parse("$25.00")?.currency)
        assertEquals("INR", Money.parse("₹25")?.currency)
        // No token at all: only then does the caller's default apply.
        assertEquals("AUD", Money.parse("25.00", defaultCurrency = "AUD")?.currency)
    }

    @Test
    fun `unreadable amounts are null, not zero`() {
        assertNull(Money.parse("₹"))
        assertNull(Money.parse("no digits here"))
    }

    @Test
    fun `currency must be a three letter ISO code`() {
        assertThrows(IllegalArgumentException::class.java) { Money(100, "Rs") }
    }

    @Test
    fun `adding across currencies is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Money(100, "INR") + Money(100, "USD")
        }
    }

    @Test
    fun `formatIndian falls back to locale formatting for other currencies`() {
        assertEquals("₹1,840", Money(1_84_000, "INR").formatIndian())
        // Not asserting the exact USD string - platform locale data owns that.
        assert(Money(2_500, "USD").formatIndian().contains("25"))
    }

    /**
     * `plain` exists so a stored amount can be put back into the text box it was
     * typed in. Grouping or a symbol here would be a bug, not a nicety: the entry
     * sheets' amount parser rejects both, so an ₹8,000 budget would open for
     * editing showing "8,000" and refuse to save.
     */
    @Test
    fun `plain is what the amount parser accepts back`() {
        assertEquals("8000", MoneyFormat.plain(8_000_00))
        assertEquals("250.05", MoneyFormat.plain(250_05))
        assertEquals("0", MoneyFormat.plain(0))
        assertEquals("1840000", MoneyFormat.plain(18_40_000_00))
    }

    @Test
    fun `plain round-trips through the amount parser`() {
        for (minor in listOf(1L, 99L, 100L, 250_05L, 8_000_00L, 18_40_000_00L)) {
            assertEquals(minor, Money.parse(MoneyFormat.plain(minor))?.amountMinor)
        }
    }
}
