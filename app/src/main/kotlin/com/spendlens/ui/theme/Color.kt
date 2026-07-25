package com.spendlens.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * SpendLens color palette - disciplined monochrome with sparse semantic color.
 * Dynamic color explicitly disabled.
 */
@Immutable
data class SpendColors(
    // Core palette
    val ink: Color,
    val graphite: Color,
    val mist: Color,
    val paper: Color,
    val paperSunk: Color,
    val rule: Color,
    val ruleSoft: Color,
    val leader: Color,
    
    // Semantic colors (used sparingly)
    val split: Color,
    val splitBg: Color,
    val review: Color,
    val reviewBg: Color,
    val credit: Color,
    
    // Tag palette (6 muted tones)
    val tagClay: Color,
    val tagMoss: Color,
    val tagSlate: Color,
    val tagPlum: Color,
    val tagOlive: Color,
    val tagSteel: Color,
)

val LightSpendColors = SpendColors(
    ink = Color(0xFF17171F),
    graphite = Color(0xFF6E6E7A),
    mist = Color(0xFF9A9AA4),
    paper = Color(0xFFFCFBF8),
    paperSunk = Color(0xFFF7F5F0),
    rule = Color(0xFFE6E3DC),
    ruleSoft = Color(0xFFEFEDE7),
    leader = Color(0xFFD5D2CA),
    
    split = Color(0xFF4B3FBF),
    splitBg = Color(0xFFF0EEFC),
    review = Color(0xFFA8620A),
    reviewBg = Color(0xFFFAF0DE),
    credit = Color(0xFF146B3A),
    
    tagClay = Color(0xFF7A6A4F),
    tagMoss = Color(0xFF4F6A5E),
    tagSlate = Color(0xFF5B6480),
    tagPlum = Color(0xFF7A5560),
    tagOlive = Color(0xFF6B6A4F),
    tagSteel = Color(0xFF55677A),
)

val DarkSpendColors = SpendColors(
    ink = Color(0xFFF2F1EC),
    graphite = Color(0xFFA3A3AE),
    mist = Color(0xFF75757F),
    paper = Color(0xFF131317),
    paperSunk = Color(0xFF1A1A20),
    rule = Color(0xFF2A2A32),
    ruleSoft = Color(0xFF232329),
    leader = Color(0xFF33333C),
    
    split = Color(0xFF8B82E8),
    splitBg = Color(0xFF221F45),
    review = Color(0xFFE0A34E),
    reviewBg = Color(0xFF2E2413),
    credit = Color(0xFF5CBB84),
    
    tagClay = Color(0xFF7A6A4F),
    tagMoss = Color(0xFF4F6A5E),
    tagSlate = Color(0xFF5B6480),
    tagPlum = Color(0xFF7A5560),
    tagOlive = Color(0xFF6B6A4F),
    tagSteel = Color(0xFF55677A),
)

val LocalSpendColors = staticCompositionLocalOf { LightSpendColors }
