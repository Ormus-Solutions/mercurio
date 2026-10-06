/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
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

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package org.connectbot.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The brand's one corner radius. No pills: buttons, keys, cards and sheets all use it. */
val OrmusCornerShape = RoundedCornerShape(OrmusTokens.RADIUS_DP.dp)

/** Every Material size maps to the brand radius. Status dots and switch thumbs stay round. */
val OrmusShapes = Shapes(
    extraSmall = OrmusCornerShape,
    small = OrmusCornerShape,
    medium = OrmusCornerShape,
    large = OrmusCornerShape,
    extraLarge = OrmusCornerShape,
)

/**
 * Agent and session state colors. The brand reads state through intensity (gold,
 * ink, muted, dim), so the Herd sort order blocked, done, working, idle is also the
 * intensity order. Pair each color with a shape (filled, ring, hollow) so state never
 * rests on color alone. [error] is for failures and dropped connections only.
 */
@Immutable
data class OrmusStatusColors(
    val blocked: Color,
    val done: Color,
    val working: Color,
    val idle: Color,
    val connecting: Color,
    val error: Color,
)

/** Brand surfaces and status colors that Material 3's ColorScheme has no slot for. */
@Immutable
data class OrmusExtras(
    val status: OrmusStatusColors,
    /** Borders and dividers drawn over any surface. */
    val hairline: Color,
    /** Ghost button and idle key border. */
    val ghostBorder: Color,
    /** The gold top hairline on cards (gold at 70%). */
    val cardAccent: Color,
    /** Solid panel over live content such as the terminal (deep at 90%, never blurred). */
    val panelOverLive: Color,
)

val OrmusDarkExtras = OrmusExtras(
    status = OrmusStatusColors(
        blocked = OrmusTokens.Gold,
        done = OrmusTokens.Ink,
        working = OrmusTokens.InkMuted,
        idle = OrmusTokens.Dim,
        connecting = OrmusTokens.GoldBright,
        error = DarkError,
    ),
    hairline = OrmusTokens.Hairline,
    ghostBorder = OrmusTokens.Ink.copy(alpha = 0.25f),
    cardAccent = OrmusTokens.Gold.copy(alpha = 0.7f),
    panelOverLive = OrmusTokens.Deep.copy(alpha = 0.9f),
)

val LocalOrmusExtras = staticCompositionLocalOf { OrmusDarkExtras }

/** Spacing scale on a 4dp grid. */
@Immutable
object OrmusSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** Smallest touch target. The brief asks 44dp and up; Android's guideline is 48dp. */
    val touchTarget: Dp = 48.dp
}

/**
 * Elevation. The brand is flat: depth comes from the surface-container family and
 * hairlines, so cards sit at 0dp and lift 2dp only when pressed.
 */
@Immutable
object OrmusElevation {
    val flat: Dp = 0.dp
    val pressed: Dp = 2.dp
    val sheet: Dp = 1.dp
}

/** Motion: the brand reveal curve. Phone transitions run 150-200ms; first reveals 1100ms. */
@Immutable
object OrmusMotion {
    val easing: Easing = OrmusTokens.EaseReveal
    const val FAST_MS = 150
    const val STANDARD_MS = 200
    const val REVEAL_MS = 1100
}

/** Entry point for brand values Material does not carry: `Ormus.extras.status.blocked`. */
object Ormus {
    val extras: OrmusExtras
        @Composable
        @ReadOnlyComposable
        get() = LocalOrmusExtras.current

    val spacing = OrmusSpacing
    val elevation = OrmusElevation
    val motion = OrmusMotion
}
