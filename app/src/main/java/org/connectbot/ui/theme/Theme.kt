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
 * A planet's dark scheme: its ground, ink text, its accent (the palette's gold key) as
 * the only one. Mapping from the brand mapping notes, section 2; see docs/DESIGN.md.
 */
fun ormusColorScheme(p: OrmusPalette): ColorScheme = darkColorScheme(
    primary = p.gold,
    onPrimary = p.onGold,
    primaryContainer = p.primaryContainer,
    onPrimaryContainer = p.goldBright,
    inversePrimary = p.inversePrimary,
    // The brand has no second hue: secondary reads through intensity (ink muted).
    secondary = p.inkMuted,
    onSecondary = p.deep,
    secondaryContainer = p.surfaceContainerHighest,
    onSecondaryContainer = p.ink,
    // Emphasis is gold plus a brightness lift, never a new hue.
    tertiary = p.goldBright,
    onTertiary = p.onGold,
    tertiaryContainer = p.primaryContainer,
    onTertiaryContainer = p.goldBright,
    // The brand danger token itself (4.22 on deep: fine for fills, icons and
    // borders). Error text in Mercurio's own screens uses Ormus.extras.status.error,
    // the lifted tone that reads 4.5 or better on every surface.
    error = p.danger,
    onError = p.onGold,
    errorContainer = p.errorContainer,
    onErrorContainer = p.ink,
    background = p.deep,
    onBackground = p.ink,
    surface = p.deep,
    onSurface = p.ink,
    surfaceVariant = p.surfaceContainerHighest,
    // Never dim for small text: the brand keeps dim for 24px and up.
    onSurfaceVariant = p.inkMuted,
    // Equal to surface so tonal elevation adds no gold wash.
    surfaceTint = p.deep,
    surfaceDim = p.codeBg,
    surfaceBright = p.surfaceContainerHighest,
    surfaceContainerLowest = p.codeBg,
    surfaceContainerLow = p.surfaceContainerLow,
    surfaceContainer = p.midnight,
    surfaceContainerHigh = p.surfaceContainerHigh,
    surfaceContainerHighest = p.surfaceContainerHighest,
    // Dim (3.61 on midnight) rather than the 25% ghost border (2.07), which fails 3:1.
    outline = p.dim,
    outlineVariant = p.outlineVariant,
    inverseSurface = p.ink,
    inverseOnSurface = p.deep,
    scrim = p.deep,
)

/** The Ormus dark scheme: the Sun planet, midnight lacquer with gold. */
val OrmusDarkColorScheme: ColorScheme = Planet.SUN.colorScheme

/**
 * The app theme: the [planet] picked in the settings panel, Sun until one is picked. Every
 * planet is dark: the brand has no light mode, whatever the phone's system setting is.
 */
@Composable
fun ConnectBotTheme(planet: Planet = rememberPlanet(), content: @Composable () -> Unit) {
    val colorScheme = planet.colorScheme

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

    CompositionLocalProvider(LocalOrmusExtras provides planet.extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = OrmusShapes,
            content = content,
        )
    }
}

/**
 * Terminal-specific colors for overlays that appear over the terminal view, from the
 * scheme of the planet in use. The terminal background is always dark, like the rest of the app.
 */
data class TerminalColors(
    val overlayBackground: Color,
    val overlayText: Color,
    val overlayTextSecondary: Color,
)

val ColorScheme.terminal: TerminalColors
    get() = TerminalColors(
        // Panels over live content are solid ground at 90%, never blurred.
        overlayBackground = background.copy(alpha = 0.9f),
        overlayText = onSurface,
        overlayTextSecondary = onSurfaceVariant,
    )
