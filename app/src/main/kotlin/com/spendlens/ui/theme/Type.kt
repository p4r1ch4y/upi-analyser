package com.spendlens.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.spendlens.R

/**
 * SpendLens typography - two faces, precise scale.
 * Bricolage Grotesque for display, IBM Plex Sans for everything else.
 */

// Font families (fonts need to be added to res/font/)
val BricolageGrotesque = FontFamily(
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold)
)

val IBMPlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold)
)

// Typography scale
val SpendTypography = Typography(
    // Hero - Today's total (54sp, Bricolage, -0.02em tracking)
    displayLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 54.sp,
        letterSpacing = (-0.02).sp,
        lineHeight = 56.sp
    ),
    
    // Hero small - Your share, sheet totals (30sp, Bricolage)
    displayMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        letterSpacing = (-0.01).sp,
        lineHeight = 32.sp
    ),
    
    // Day - Past day totals (22sp, Bricolage)
    displaySmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 24.sp
    ),
    
    // Title - Merchant name in detail (19sp, Plex)
    titleLarge = TextStyle(
        fontFamily = IBMPlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
        lineHeight = 24.sp
    ),
    
    // Body - Transaction rows (14sp, Plex)
    bodyMedium = TextStyle(
        fontFamily = IBMPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    
    // Amount - Row amounts with tabular figures (14sp, Plex Medium, tnum)
    bodyLarge = TextStyle(
        fontFamily = IBMPlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum"  // Tabular figures - critical for alignment
    ),
    
    // Meta - "14 taps · 4 merchants" (12.5sp, Plex)
    bodySmall = TextStyle(
        fontFamily = IBMPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 16.sp
    ),
    
    // Stamp - Timestamps with tabular figures (11.5sp, Plex)
    labelMedium = TextStyle(
        fontFamily = IBMPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    ),
    
    // Eyebrow - Uppercase section labels (11sp, Plex Medium, +0.09em)
    labelSmall = TextStyle(
        fontFamily = IBMPlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.09.sp
    )
)

// Extension for tabular figures on any TextStyle
fun TextStyle.withTabularFigures() = this.copy(
    fontFeatureSettings = "tnum"
)

/**
 * A serif display voice, offered as an alternative.
 *
 * Uses the platform's own serif rather than bundling another family: it costs
 * nothing in APK size, it is already hinted for the device, and it gives the
 * editorial feel the suggestion was after without shipping a second megabyte of
 * font for a preference most people will never change.
 *
 * Applied to display and title text only. Amounts and transaction rows keep the
 * body face and its tabular figures — a serif with high thick/thin contrast is
 * lovely in a headline and genuinely worse in a dense column of numbers, which is
 * most of what this app is.
 */
val SerifDisplay = FontFamily.Serif

/** Swaps the display faces while leaving body text and figures alone. */
fun androidx.compose.material3.Typography.withSerifDisplay(): androidx.compose.material3.Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = SerifDisplay),
    displayMedium = displayMedium.copy(fontFamily = SerifDisplay),
    displaySmall = displaySmall.copy(fontFamily = SerifDisplay),
    titleLarge = titleLarge.copy(fontFamily = SerifDisplay)
)
