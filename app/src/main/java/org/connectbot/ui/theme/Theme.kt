/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.ui.theme

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Ormus dark scheme: midnight lacquer, ink text, gold as the only accent.
 * Mapping from ~/projects/logos-notes/brand-mapping.md section 2; see docs/DESIGN.md.
 */
val OrmusDarkColorScheme = darkColorScheme(
    primary = OrmusTokens.Gold,
    onPrimary = OrmusTokens.OnGold,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = OrmusTokens.GoldBright,
    inversePrimary = OrmusTokens.Print.Gold,
    // The brand has no second hue: secondary reads through intensity (ink muted).
    secondary = OrmusTokens.InkMuted,
    onSecondary = OrmusTokens.Deep,
    secondaryContainer = DarkSurfaceContainerHighest,
    onSecondaryContainer = OrmusTokens.Ink,
    // Emphasis is gold plus a brightness lift, never a new hue.
    tertiary = OrmusTokens.GoldBright,
    onTertiary = OrmusTokens.OnGold,
    tertiaryContainer = DarkPrimaryContainer,
    onTertiaryContainer = OrmusTokens.GoldBright,
    // The brand danger token itself (4.22 on deep: fine for fills, icons and
    // borders). Error text in Mercurio's own screens uses Ormus.extras.status.error,
    // the lifted tone that reads 4.5 or better on every surface.
    error = OrmusTokens.Danger,
    onError = OrmusTokens.OnGold,
    errorContainer = DarkErrorContainer,
    onErrorContainer = OrmusTokens.Ink,
    background = OrmusTokens.Deep,
    onBackground = OrmusTokens.Ink,
    surface = OrmusTokens.Deep,
    onSurface = OrmusTokens.Ink,
    surfaceVariant = DarkSurfaceContainerHighest,
    // Never dim for small text: the brand keeps dim for 24px and up.
    onSurfaceVariant = OrmusTokens.InkMuted,
    // Equal to surface so tonal elevation adds no gold wash.
    surfaceTint = OrmusTokens.Deep,
    surfaceDim = OrmusTokens.CodeBg,
    surfaceBright = DarkSurfaceContainerHighest,
    surfaceContainerLowest = OrmusTokens.CodeBg,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = OrmusTokens.Midnight,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    // Dim (3.61 on midnight) rather than the 25% ghost border (2.07), which fails 3:1.
    outline = OrmusTokens.Dim,
    outlineVariant = DarkOutlineVariant,
    inverseSurface = OrmusTokens.Ink,
    inverseOnSurface = OrmusTokens.Deep,
    scrim = OrmusTokens.Deep,
)

/**
 * The app theme. The brand has no light mode, so this always renders the dark Ormus
 * scheme, whatever the phone's system setting is.
 */
@Composable
fun ConnectBotTheme(content: @Composable () -> Unit) {
    val colorScheme = OrmusDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(LocalOrmusExtras provides OrmusDarkExtras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = OrmusShapes,
            content = content,
        )
    }
}

/**
 * Terminal-specific colors for overlays that appear over the terminal view.
 * The terminal background is always dark, like the rest of the app.
 */
data class TerminalColors(
    val overlayBackground: Color,
    val overlayText: Color,
    val overlayTextSecondary: Color,
)

private val terminalColors = TerminalColors(
    overlayBackground = TerminalOverlayBackground,
    overlayText = TerminalOverlayText,
    overlayTextSecondary = TerminalOverlayTextSecondary,
)

/**
 * Access terminal-specific colors via MaterialTheme.colorScheme.terminal
 */
val ColorScheme.terminal: TerminalColors
    @Composable
    @ReadOnlyComposable
    get() = terminalColors
