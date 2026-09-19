package com.jessemaddox.spoileralert.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jessemaddox.spoileralert.R

// Bundled DM Sans 4.004 (OFL) and Roboto Condensed 3.008 (Apache 2.0).
// Licenses ship in assets/licenses; fonts never require a network request.
val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_bold, FontWeight.Bold),
)

val RobotoCondensed = FontFamily(
    Font(R.font.roboto_condensed_bold, FontWeight.Bold),
    Font(R.font.roboto_condensed_black, FontWeight.Black),
)

/**
 * Jess type ramp.
 * Display = Roboto Condensed 900, tracking -0.03em, leading ~90% — apply `.uppercase()`
 * at call sites (Compose has no text-transform).
 * Eyebrows = labelLarge: Roboto Condensed 700, +0.08em, uppercase at call sites.
 * Body = DM Sans, tracking -0.02em.
 */
val JessTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = RobotoCondensed, fontWeight = FontWeight.Black,
        fontSize = 44.sp, lineHeight = 40.sp, letterSpacing = (-0.03).em,
    ),
    displayMedium = TextStyle(
        fontFamily = RobotoCondensed, fontWeight = FontWeight.Black,
        fontSize = 34.sp, lineHeight = 31.sp, letterSpacing = (-0.03).em,
    ),
    displaySmall = TextStyle(
        fontFamily = RobotoCondensed, fontWeight = FontWeight.Black,
        fontSize = 26.sp, lineHeight = 24.sp, letterSpacing = (-0.03).em,
    ),
    // Wordmark / step numerals share the display voice at smaller sizes.
    headlineLarge = TextStyle(
        fontFamily = RobotoCondensed, fontWeight = FontWeight.Black,
        fontSize = 26.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em,
    ),
    headlineMedium = TextStyle(
        fontFamily = RobotoCondensed, fontWeight = FontWeight.Black,
        fontSize = 20.sp, lineHeight = 20.sp, letterSpacing = (-0.02).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.02).em,
    ),
    titleLarge = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Bold,
        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.02).em,
    ),
    titleMedium = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.02).em,
    ),
    titleSmall = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = (-0.02).em,
    ),
    bodyLarge = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = (-0.02).em,
    ),
    bodyMedium = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.02).em,
    ),
    bodySmall = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.02).em,
    ),
    // Eyebrows / section labels — uppercase at call sites, color = accent-ink at call sites.
    labelLarge = TextStyle(
        fontFamily = RobotoCondensed, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.08.em,
    ),
    // 12sp (not 11) so accent-ink (#007A94, ~4.6:1 on white) stays acceptable at this size;
    // still inside the design's 11-13px eyebrow range.
    labelMedium = TextStyle(
        fontFamily = RobotoCondensed, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.08.em,
    ),
    labelSmall = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = (-0.01).em,
    ),
)
