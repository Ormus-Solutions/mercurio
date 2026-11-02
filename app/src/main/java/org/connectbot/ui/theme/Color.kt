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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp

// Every color here is a brand token from OrmusTokens (generated from the vendored
// ormus-brand tokens.json) or a tone derived from tokens by compositing, the way
// the web renders the brand's alpha tokens. No brand hex is typed by hand.
// Each tone is derived from a planet's palette (OrmusTokens.Planets): the contrast
// ratios in comments are WCAG 2.x, measured on Sun; OrmusContrastTest enforces
// the text pairs at 4.5:1 and the outline at 3:1 for every planet.

private val White = Color.White

// Midnight lacquer, or the planet's own ground: the brand is dark only.

/** bg-elevated (white 1.5%) over deep: drawers and sheets. */
val OrmusPalette.surfaceContainerLow: Color get() = White.copy(alpha = 0.015f).compositeOver(deep)

/** bg-elevated (white 1.5%) over midnight: dialogs. */
val OrmusPalette.surfaceContainerHigh: Color get() = White.copy(alpha = 0.015f).compositeOver(midnight)

/** bg-muted (white 4%) over midnight: text fields, filled cards, keys. */
val OrmusPalette.surfaceContainerHighest: Color get() = White.copy(alpha = 0.04f).compositeOver(midnight)

/** Gold 15% over midnight: selected rows, primary containers. goldBright on it 6.35. */
val OrmusPalette.primaryContainer: Color get() = gold.copy(alpha = 0.15f).compositeOver(midnight)

/** Hairline over midnight, opaque, for dividers. */
val OrmusPalette.outlineVariant: Color get() = hairline.compositeOver(midnight)

/**
 * Danger lifted a quarter toward ink, for error text. Raw danger is 4.22 on deep
 * and 3.43 on the highest container, under the 4.5 text minimum; this tone reads
 * 5.72 and 4.64. Exposed as Ormus.extras.status.error.
 */
val OrmusPalette.errorText: Color get() = lerp(danger, ink, 0.25f)

/** Danger 16% over midnight. Ink on it 11.5. */
val OrmusPalette.errorContainer: Color get() = danger.copy(alpha = 0.16f).compositeOver(midnight)

/**
 * The accent on a light surface: snackbar actions over the inverse surface (ink). Sun keeps
 * the brand's print gold (4.41 on ink); another planet's accent goes halfway to its
 * on-accent color, which reads 4.75 or better on its ink.
 */
val OrmusPalette.inversePrimary: Color
    get() = if (this == OrmusTokens.Planets.Sun) OrmusTokens.Print.Gold else lerp(gold, onGold, 0.5f)

// Key rows over the terminal (TerminalKeyboard): hairline keys, gold press.
val KeyBackgroundNormal = OrmusTokens.Hairline
val KeyBackgroundPressed = OrmusTokens.Canvas.EdgeGold
val KeyBackgroundLayout = OrmusTokens.Deep.copy(alpha = 0.33f)
val KeyboardBackground = OrmusTokens.Deep.copy(alpha = 0.9f)
