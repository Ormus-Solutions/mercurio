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
// Contrast ratios in comments are WCAG 2.x, measured; OrmusContrastTest enforces
// the text pairs at 4.5:1 and the outline at 3:1.

private val White = Color.White

// Midnight lacquer: the brand's only mode.

/** bg-elevated (white 1.5%) over deep: drawers and sheets. */
val DarkSurfaceContainerLow = White.copy(alpha = 0.015f).compositeOver(OrmusTokens.Deep)

/** bg-elevated (white 1.5%) over midnight: dialogs. */
val DarkSurfaceContainerHigh = White.copy(alpha = 0.015f).compositeOver(OrmusTokens.Midnight)

/** bg-muted (white 4%) over midnight: text fields, filled cards, keys. */
val DarkSurfaceContainerHighest = White.copy(alpha = 0.04f).compositeOver(OrmusTokens.Midnight)

/** Gold 15% over midnight: selected rows, primary containers. goldBright on it 6.35. */
val DarkPrimaryContainer = OrmusTokens.Gold.copy(alpha = 0.15f).compositeOver(OrmusTokens.Midnight)

/** Hairline over midnight, opaque, for dividers. */
val DarkOutlineVariant = OrmusTokens.Hairline.compositeOver(OrmusTokens.Midnight)

/**
 * Danger lifted a quarter toward ink, for error text. Raw danger is 4.22 on deep
 * and 3.43 on the highest container, under the 4.5 text minimum; this tone reads
 * 5.72 and 4.64. Exposed as Ormus.extras.status.error.
 */
val DarkError = lerp(OrmusTokens.Danger, OrmusTokens.Ink, 0.25f)

/** Danger 16% over midnight. Ink on it 11.5. */
val DarkErrorContainer = OrmusTokens.Danger.copy(alpha = 0.16f).compositeOver(OrmusTokens.Midnight)

// Key rows over the terminal (TerminalKeyboard): hairline keys, gold press.
val KeyBackgroundNormal = OrmusTokens.Hairline
val KeyBackgroundPressed = OrmusTokens.Canvas.EdgeGold
val KeyBackgroundLayout = OrmusTokens.Deep.copy(alpha = 0.33f)
val KeyboardBackground = OrmusTokens.Deep.copy(alpha = 0.9f)

// Overlays drawn over the live terminal. Panels over live content are solid deep at 90%,
// never blurred.
val TerminalOverlayBackground = OrmusTokens.Deep.copy(alpha = 0.9f)
val TerminalOverlayText = OrmusTokens.Ink
val TerminalOverlayTextSecondary = OrmusTokens.InkMuted
