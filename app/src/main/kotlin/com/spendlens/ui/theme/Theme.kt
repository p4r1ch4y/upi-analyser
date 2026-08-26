package com.spendlens.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
    /** SYSTEM, LIGHT or DARK — see SettingsStore.THEME_MODES. */
    themeMode: String = "SYSTEM",
    /** GROTESQUE or SERIF — see SettingsStore.TYPEFACES. */
    typeface: String = "GROTESQUE",
    /**
     * How large to draw, as a multiple of the phone's own display size.
     *
     * Applied by scaling [LocalDensity], which is what "display size" means to
     * Android: a `dp` is `value * density` pixels and an `sp` is
     * `value * fontScale * density`, so one multiplier moves text and layout
     * together and nothing inside the app has to know about it.
     *
     * A multiplier rather than an absolute, because the system setting is still
     * the user's choice - one dial for every app at once - and this refines it
     * for this app instead of overriding it.
     */
    displayScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    // An explicit choice overrides the system; SYSTEM follows it. Following the
    // system is the default because a money app that ignores the phone's night
    // setting is the one glaring white rectangle at 2am.
    val darkTheme = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    val typography = if (typeface == "SERIF") SpendTypography.withSerifDisplay() else SpendTypography
    val spendColors = if (darkTheme) DarkSpendColors else LightSpendColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Only the icon tint is set. Window.statusBarColor is a no-op from
            // API 35 onward under edge-to-edge; the bar is transparent and the
            // Compose background shows through it.
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val base = LocalDensity.current
    val scaled = remember(base, displayScale) {
        if (displayScale == 1.0f) base
        else Density(density = base.density * displayScale, fontScale = base.fontScale)
    }

    CompositionLocalProvider(
        LocalSpendColors provides spendColors,
        LocalDensity provides scaled
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
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
