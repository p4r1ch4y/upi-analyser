package com.spendlens.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTest {

    @Test
    fun `an even split divides exactly when it can`() {
        val split = Split.evenlyAmong(totalMinor = 2_40_000, otherCount = 3)  // ₹2,400 four ways
        assertEquals(4, split.wayCount)
        assertTrue(split.shares.all { it.amountMinor == 60_000L })
        assertEquals(60_000L, split.myShareMinor)
        assertEquals(1_80_000L, split.owedToMeMinor)
    }

    /**
     * The invariant the whole feature rests on. Rounding each share independently
     * loses or invents a paisa, and over a trip that becomes "we're ₹4 out and
     * nobody knows why".
     */
    @Test
    fun `shares always sum to the total, however awkward the division`() {
        for (total in listOf(1L, 7L, 99L, 100L, 1_00_000L, 33_333L, 9_99_999L, 1_23_457L)) {
            for (people in 2..9) {
                val split = Split.evenly(total, (1..people).map { "P$it" })
                assertEquals(
                    "total=$total people=$people",
                    total,
                    split.shares.sumOf { it.amountMinor }
                )
            }
        }
    }

    @Test
    fun `the remainder is spread, never dumped on one person`() {
        // ₹1,000 three ways: 33333.34 / 33333.33 / 33333.33
        val split = Split.evenly(1_00_000, listOf("A", "B", "C"))
        assertEquals(listOf(33_334L, 33_333L, 33_333L), split.shares.map { it.amountMinor })

        val spread = split.shares.maxOf { it.amountMinor } - split.shares.minOf { it.amountMinor }
        assertTrue("largest and smallest share differ by at most one paisa", spread <= 1)
    }

    @Test
    fun `a one paisa payment split three ways gives the paisa to exactly one person`() {
        val split = Split.evenly(1, listOf("A", "B", "C"))
        assertEquals(1L, split.shares.sumOf { it.amountMinor })
        assertEquals(1, split.shares.count { it.amountMinor == 1L })
    }

    @Test
    fun `settling a share moves it out of what is owed`() {
        val split = Split.evenlyAmong(30_000, otherCount = 2)  // ₹300 three ways
        assertEquals(20_000L, split.owedToMeMinor)
        assertFalse(split.isFullySettled)

        val settled = split.copy(
            shares = split.shares.map { if (it.name == "Person 1") it.copy(settledAt = 1L) else it }
        )
        assertEquals(10_000L, settled.owedToMeMinor)
        assertEquals(10_000L, settled.settledMinor)
        assertFalse(settled.isFullySettled)

        val allSettled = settled.copy(
            shares = settled.shares.map { if (it.isMe) it else it.copy(settledAt = 1L) }
        )
        assertEquals(0L, allSettled.owedToMeMinor)
        assertTrue(allSettled.isFullySettled)
    }

    @Test
    fun `my own share is never something I owe myself`() {
        val split = Split.evenlyAmong(40_000, otherCount = 3)
        assertEquals(10_000L, split.myShareMinor)
        assertTrue(split.shares.single { it.isMe }.name == Split.ME)
        assertEquals(30_000L, split.owedToMeMinor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a split whose shares do not sum to the total is rejected on construction`() {
        Split(
            totalMinor = 1_000,
            shares = listOf(SplitShare("A", 400), SplitShare("B", 400))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `splitting with nobody else is rejected`() {
        Split.evenlyAmong(1_000, otherCount = 0)
    }
}
