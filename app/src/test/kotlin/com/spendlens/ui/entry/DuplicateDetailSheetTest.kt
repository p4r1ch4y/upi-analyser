package com.spendlens.ui.entry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.spendlens.ui.theme.SpendLensTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the detail sheet host-side (Robolectric, no emulator) to prove the
 * duplicate affordances actually appear - and, as the control, that they stay
 * absent for an ordinary payment.
 *
 * Deliberately asserts *presence*, not a click on the switch: a known
 * Robolectric limitation (robolectric#9595) is that clicks inside a Compose
 * ModalBottomSheet do not always fire, so the toggle's behaviour is covered by
 * the repository/ingestor logic and the DelayedDuplicateTest instead. What a
 * render test can prove, and what the user asked for, is that the row says
 * "duplicate" and offers the control.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DuplicateDetailSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun detail(isDuplicate: Boolean) = TransactionDetail(
        id = "txn-1",
        occurredAt = 1_700_000_000_000L,
        displayName = "alice@upi",
        counterpartyVpa = "alice@upi",
        amountMinor = 4_000,
        isCredit = false,
        split = null,
        tags = emptyList(),
        note = null,
        sources = emptyList(),
        isDuplicate = isDuplicate
    )

    private fun render(isDuplicate: Boolean) {
        compose.setContent {
            SpendLensTheme {
                TransactionDetailSheet(
                    detail = detail(isDuplicate),
                    allTags = emptyList(),
                    onDismiss = {},
                    onSplit = {},
                    onRemoveSplit = {},
                    onToggleSettled = { _, _ -> },
                    onAddTag = {},
                    onRemoveTag = {},
                    onDelete = {},
                    onRename = {},
                    onNote = {},
                    onToggleDuplicate = {}
                )
            }
        }
    }

    @Test
    fun `a duplicate payment shows the duplicate affordances`() {
        render(isDuplicate = true)

        // The eyebrow label and the toggle's action line.
        compose.onNodeWithText("DUPLICATE").assertIsDisplayed()
        compose.onNodeWithText("Count this payment").assertIsDisplayed()
    }

    @Test
    fun `an ordinary payment shows none of the duplicate affordances`() {
        render(isDuplicate = false)

        compose.onNodeWithText("DUPLICATE").assertDoesNotExist()
        compose.onNodeWithText("Count this payment").assertDoesNotExist()
    }
}
