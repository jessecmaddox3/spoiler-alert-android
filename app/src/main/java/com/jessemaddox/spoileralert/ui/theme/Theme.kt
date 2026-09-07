package com.jessemaddox.spoileralert.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Jess Design System theme: single light scheme, bundled fonts, no dynamic color. */
@Composable
fun SpoilerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JessLightColorScheme,
        typography = JessTypography,
        content = content,
    )
}
