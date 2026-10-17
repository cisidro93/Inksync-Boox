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
    monochrome: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (monochrome) {
        BooxEInkColorScheme.copy(
            secondary = EInkPureBlack,
            secondaryContainer = EInkSurfaceGray,
            tertiary = EInkPureBlack
        )
    } else {
        BooxEInkColorScheme
    }

    // No stretch/glow overscroll: animated overscroll causes ghosting on e-ink
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
