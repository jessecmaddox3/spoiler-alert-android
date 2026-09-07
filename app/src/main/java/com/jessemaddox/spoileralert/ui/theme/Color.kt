package com.jessemaddox.spoileralert.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Jess Design System tokens (single source of truth for the app's palette).
val WoltBlue = Color(0xFF00C2E8)      // --jds-blue: primary surfaces & buttons
val WoltBlueHover = Color(0xFF00A6C7) // --jds-blue-hover
val Blue50 = Color(0xFFE0F7FC)        // --jds-blue-50: soft secondary surface
val Blueberry = Color(0xFF021738)     // --jds-blueberry: ink; text on Wolt Blue is ALWAYS Blueberry
val Milk = Color(0xFFF8F8F8)          // --jds-milk: screen background behind cards
val Amber = Color(0xFFFFC107)         // --jds-amber: warnings only
val AccentInk = Color(0xFF007A94)     // --accent-ink: small blue text/labels on light surfaces
val ProtectionRed = Color(0xFFC92837) // unmistakable active hiding state
val ProtectionRed50 = Color(0xFFFFECEE)
val RevealGreen = Color(0xFF157A52)   // deliberate stop-and-reveal action

/** Semantic values that don't map onto the M3 scheme. */
object JessColors {
    val accentInk = AccentInk
    /** rgba(2,23,56,.10) — hairline borders on de-emphasized cards & idle hero. */
    val hairline = Color(0x1A021738)
    /** rgba(2,23,56,.62) — subtle/secondary text. */
    val subtle = Color(0x9E021738)
    /** rgba(2,23,56,.35) — ghost button stroke. */
    val ghostStroke = Color(0x59021738)
    /** rgba(255,255,255,.85) — status chips on the blue hero. */
    val chipOnBlue = Color(0xD9FFFFFF)
    /** rgba(2,23,56,.45) — bottom-sheet scrim. */
    val scrim = Color(0x73021738)
    /** rgba(2,23,56,.70) — secondary ink on Wolt Blue surfaces. */
    val subtleOnBlue = Color(0xB3021738)
    val amber = Amber
    val protectionRed = ProtectionRed
    val protectionRed50 = ProtectionRed50
    val revealGreen = RevealGreen
}

/**
 * Hand-built light scheme from the Jess tokens. No dynamic color, no dark scheme —
 * the design system is a single light theme.
 *
 * error → amber: warning banners use errorContainer = Amber with Blueberry ink.
 * ProtectionRed is deliberately reserved for the unmistakable active-hiding state.
 */
val JessLightColorScheme = lightColorScheme(
    primary = WoltBlue,
    onPrimary = Blueberry,
    primaryContainer = Blue50,
    onPrimaryContainer = Blueberry,
    inversePrimary = Blue50,
    secondary = AccentInk,
    onSecondary = Color.White,
    secondaryContainer = Blue50,
    onSecondaryContainer = AccentInk,
    tertiary = AccentInk,
    onTertiary = Color.White,
    tertiaryContainer = Blue50,
    onTertiaryContainer = AccentInk,
    background = Milk,
    onBackground = Blueberry,
    surface = Color.White,
    onSurface = Blueberry,
    surfaceVariant = Blue50,
    onSurfaceVariant = JessColors.subtle,
    surfaceTint = Color.White, // no elevation tinting; shadows carry elevation
    inverseSurface = Blueberry,
    inverseOnSurface = Color.White,
    error = Amber,
    onError = Blueberry,
    errorContainer = Amber,
    onErrorContainer = Blueberry,
    outline = JessColors.ghostStroke,
    outlineVariant = JessColors.hairline,
    scrim = JessColors.scrim,
)
