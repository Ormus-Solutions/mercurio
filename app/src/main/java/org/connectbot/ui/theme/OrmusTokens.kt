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

package org.connectbot.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color

/** Ormus brand tokens (ormus-brand tokens.json version 0.2.0). */
object OrmusTokens {
    const val VERSION = "0.2.0"

    val Midnight = Color(0xFF14213D)
    val Deep = Color(0xFF0E1830)
    val Gold = Color(0xFFD29E3D)
    val GoldBright = Color(0xFFE0B258)
    val Ink = Color(0xFFECEAE3)
    val InkMuted = Color(0xFFA5B0C4)
    val Dim = Color(0xFF6D7891)
    val Hairline = Color(0x1FECEAE3)
    val OnGold = Color(0xFF1A1407)
    val CodeBg = Color(0xFF0B1226)
    val Danger = Color(0xFFD44F4F)

    object Canvas {
        val Background = Color(0xFF0E1830)
        val NodePrimary = Color(0xFFD29E3D)
        val NodeSecondary = Color(0xFFECEAE3)
        val NodeTertiary = Color(0xFFA5B0C4)
        val NodeQuiet = Color(0xFF6D7891)
        val Label = Color(0xFFECEAE3)
        val NodeBorder = Color(0x33ECEAE3)
        val EdgeGold = Color(0x4DD29E3D)
        val EdgeGoldSoft = Color(0x33D29E3D)
        val EdgeMuted = Color(0x1FA5B0C4)
        val EdgeDefault = Color(0x14ECEAE3)
    }

    object Print {
        val Paper = Color(0xFFFFFFFF)
        val Gold = Color(0xFF8A6519)
        val GoldBright = Color(0xFF8A6519)
        val Ink = Color(0xFF1A1407)
        val InkMuted = Color(0xFF14213D)
        val Dim = Color(0xFF14213D)
        val OnGold = Color(0xFFFFFFFF)
        val Hairline = Color(0x331A1407)
    }

    /** The brand's single corner radius, in dp (CSS px). */
    const val RADIUS_DP = 3f

    /** cubic-bezier reveal curve used for every transition. */
    val EaseReveal = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f)

    /** Brand type scale in sp (1rem = 16sp); line heights resolved to sp. */
    object TypeScale {
        val H1 = OrmusTypeToken(font = "display", sizeSp = 36.0f, lineHeightSp = 40.0f, weight = 300, trackingEm = -0.01f)
        val H2 = OrmusTypeToken(font = "display", sizeSp = 30.0f, lineHeightSp = 36.0f, weight = 400, trackingEm = -0.025f)
        val H3 = OrmusTypeToken(font = "display", sizeSp = 24.0f, lineHeightSp = 32.0f, weight = 400, trackingEm = 0.0f)
        val Body = OrmusTypeToken(font = "sans", sizeSp = 16.0f, lineHeightSp = 28.8f, weight = 400, trackingEm = 0.0f)
        val Label = OrmusTypeToken(font = "mono", sizeSp = 11.52f, lineHeightSp = 17.28f, weight = 400, trackingEm = 0.08f)
    }
}

/** One brand type-scale entry; [font] names a family in tokens.json (display, sans, mono). */
data class OrmusTypeToken(
    val font: String,
    val sizeSp: Float,
    val lineHeightSp: Float,
    val weight: Int,
    val trackingEm: Float,
)
