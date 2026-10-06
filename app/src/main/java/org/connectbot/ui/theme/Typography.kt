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

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.connectbot.R

// Brand families, bundled under res/font (SIL OFL 1.1, unmodified upstream static
// builds; license texts in assets/licenses/fonts). Static files, not variable fonts:
// minSdk 24 predates font-variation axes.

/** Display: Cormorant Garamond, light and regular only. Never bold the serif. */
val OrmusDisplay = FontFamily(
    Font(R.font.cormorant_garamond_light, FontWeight.Light),
    Font(R.font.cormorant_garamond_regular, FontWeight.Normal),
)

/** Body and UI: Inter. */
val OrmusSans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Labels, names, endpoints and terminal-like text: JetBrains Mono. */
val OrmusMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

private val scale = OrmusTokens.TypeScale

private fun display(sizeSp: Float, lineSp: Float, weight: FontWeight, trackingEm: Float = 0f) = TextStyle(
    fontFamily = OrmusDisplay,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = lineSp.sp,
    letterSpacing = trackingEm.em,
)

private fun sans(sizeSp: Float, lineSp: Float, weight: FontWeight = FontWeight.Normal, trackingEm: Float = 0f) = TextStyle(
    fontFamily = OrmusSans,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = lineSp.sp,
    letterSpacing = trackingEm.em,
)

private fun mono(sizeSp: Float, lineSp: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = OrmusMono,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = lineSp.sp,
    letterSpacing = scale.Label.trackingEm.em,
)

/**
 * Type scale. Headlines take the brand h1/h2/h3 tokens; the serif runs optically
 * small, so titleLarge (top bars) is bumped a step over the 22sp sans default.
 * Body line heights stay at Material's phone values: the brand's 1.8 body leading is
 * a web prose value and would make list rows and fields loose. The reader sets its
 * own leading for long output.
 */
val AppTypography = Typography(
    displayLarge = display(57f, 64f, FontWeight.Light),
    displayMedium = display(45f, 52f, FontWeight.Light),
    displaySmall = display(36f, 44f, FontWeight.Light),
    headlineLarge = display(scale.H1.sizeSp, scale.H1.lineHeightSp, FontWeight.Light, scale.H1.trackingEm),
    headlineMedium = display(scale.H2.sizeSp, scale.H2.lineHeightSp, FontWeight.Normal, scale.H2.trackingEm),
    headlineSmall = display(scale.H3.sizeSp, scale.H3.lineHeightSp, FontWeight.Normal, scale.H3.trackingEm),
    titleLarge = display(26f, 32f, FontWeight.Normal),
    titleMedium = sans(16f, 24f, FontWeight.SemiBold),
    titleSmall = sans(14f, 20f, FontWeight.Medium),
    bodyLarge = sans(scale.Body.sizeSp, 24f),
    bodyMedium = sans(14f, 20f),
    bodySmall = sans(12f, 16f),
    labelLarge = sans(14f, 20f, FontWeight.Medium),
    labelMedium = mono(12f, 16f, FontWeight.Medium),
    labelSmall = mono(scale.Label.sizeSp, scale.Label.lineHeightSp),
)
