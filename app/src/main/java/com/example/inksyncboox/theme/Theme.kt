package com.example.inksyncboox.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// Strict High-Contrast E-Ink Color Scheme (Never use dark grey mud or Android dynamic colors)
private val BooxEInkColorScheme = lightColorScheme(
    primary = EInkPureBlack,
    onPrimary = EInkPaperWhite,
    primaryContainer = EInkSurfaceGray,
    onPrimaryContainer = EInkPureBlack,
    secondary = KaleidoCobaltInk,
    onSecondary = EInkPaperWhite,
    secondaryContainer = KaleidoCyanHighlight,
    onSecondaryContainer = EInkPureBlack,
    tertiary = KaleidoEmeraldInk,
    onTertiary = EInkPaperWhite,
    background = EInkPaperWhite,
    onBackground = EInkPureBlack,
    surface = EInkPaperWhite,
    onSurface = EInkPureBlack,
    surfaceVariant = EInkSurfaceGray,
    onSurfaceVariant = EInkPureBlack,
    outline = EInkPureBlack,
    outlineVariant = EInkBorderGray,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InkSyncBooxTheme(
    highContrastBAndW: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (highContrastBAndW) {
        BooxEInkColorScheme.copy(
            secondary = EInkPureBlack,
            secondaryContainer = EInkSurfaceGray,
            tertiary = EInkPureBlack
        )
    } else {
        BooxEInkColorScheme
    }

    // Disable stretchy overscroll glow/bounce which causes severe E-Ink A2 refresh ghosting
    CompositionLocalProvider(
        LocalOverscrollConfiguration provides null
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
            content = content
        )
    }
}
