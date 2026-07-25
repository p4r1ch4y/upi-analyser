package com.spendlens.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * SpendLens theme.
 * Wraps MaterialTheme but uses custom color tokens and typography.
 * Dynamic color explicitly disabled - the palette is the identity.
 */

private val LightColorScheme = lightColorScheme(
    primary = LightSpendColors.ink,
    secondary = LightSpendColors.graphite,
    background = LightSpendColors.paper,
    surface = LightSpendColors.paper,
    onPrimary = LightSpendColors.paper,
    onSecondary = LightSpendColors.paper,
    onBackground = LightSpendColors.ink,
    onSurface = LightSpendColors.ink,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkSpendColors.ink,
    secondary = DarkSpendColors.graphite,
    background = DarkSpendColors.paper,
    surface = DarkSpendColors.paper,
    onPrimary = DarkSpendColors.paper,
    onSecondary = DarkSpendColors.paper,
    onBackground = DarkSpendColors.ink,
    onSurface = DarkSpendColors.ink,
)

@Composable
fun SpendLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val spendColors = if (darkTheme) DarkSpendColors else LightSpendColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = spendColors.paper.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalSpendColors provides spendColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SpendTypography,
            content = content
        )
    }
}

// Extension property for easy access to SpendColors
object SpendTheme {
    val colors: SpendColors
        @Composable
        get() = LocalSpendColors.current
}
