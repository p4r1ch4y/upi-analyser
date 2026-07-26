package com.spendlens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import com.spendlens.core.model.MoneyFormat

/**
 * The currency amounts are rendered in.
 *
 * A CompositionLocal rather than a global read inside each composable, because a
 * global is invisible to Compose: changing `MoneyFormat.displayCurrency` alone
 * changed nothing on screen, since the screens' own parameters had not changed
 * and Compose correctly skipped recomposing them. Reading it through here makes
 * every amount a genuine composition dependency, so switching currency redraws
 * exactly the things that display money.
 */
val LocalCurrency = compositionLocalOf { MoneyFormat.displayCurrency }

/** Formats an amount in the currency currently provided to the composition. */
@Composable
@ReadOnlyComposable
fun money(amountMinor: Long): String = MoneyFormat.money(amountMinor, LocalCurrency.current)
