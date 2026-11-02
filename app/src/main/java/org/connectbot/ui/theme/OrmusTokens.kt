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

/** Ormus brand tokens (ormus-brand tokens.json version 0.3.0). */
object OrmusTokens {
    const val VERSION = "0.3.0"

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

    /** One dark theme per classical planet (tokens.json planets). [Planets.Sun] equals the color group. */
    object Planets {
        val Sun = OrmusPalette(midnight = Color(0xFF14213D), deep = Color(0xFF0E1830), gold = Color(0xFFD29E3D), goldBright = Color(0xFFE0B258), ink = Color(0xFFECEAE3), inkMuted = Color(0xFFA5B0C4), dim = Color(0xFF6D7891), hairline = Color(0x1FECEAE3), onGold = Color(0xFF1A1407), codeBg = Color(0xFF0B1226), danger = Color(0xFFD44F4F))
        val Moon = OrmusPalette(midnight = Color(0xFF182038), deep = Color(0xFF0C1226), gold = Color(0xFF9CC0F0), goldBright = Color(0xFFC4DBF8), ink = Color(0xFFECEFF4), inkMuted = Color(0xFFA7B1C5), dim = Color(0xFF6F7A94), hairline = Color(0x1FECEFF4), onGold = Color(0xFF0B1424), codeBg = Color(0xFF090E1E), danger = Color(0xFFD44F4F))
        val Mercury = OrmusPalette(midnight = Color(0xFF12252B), deep = Color(0xFF0A171B), gold = Color(0xFF6FC9D6), goldBright = Color(0xFF9CDCE6), ink = Color(0xFFE8EFF0), inkMuted = Color(0xFFA0B3B8), dim = Color(0xFF66808A), hairline = Color(0x1FE8EFF0), onGold = Color(0xFF06181C), codeBg = Color(0xFF071216), danger = Color(0xFFD44F4F))
        val Venus = OrmusPalette(midnight = Color(0xFF132620), deep = Color(0xFF0B1814), gold = Color(0xFF5FC497), goldBright = Color(0xFF8ED8B5), ink = Color(0xFFEAEFEA), inkMuted = Color(0xFFA5B8AE), dim = Color(0xFF6A8278), hairline = Color(0x1FEAEFEA), onGold = Color(0xFF06180F), codeBg = Color(0xFF08130F), danger = Color(0xFFD44F4F))
        val Mars = OrmusPalette(midnight = Color(0xFF2A1816), deep = Color(0xFF1A0F0E), gold = Color(0xFFE07A48), goldBright = Color(0xFFEE9B6E), ink = Color(0xFFF0E9E5), inkMuted = Color(0xFFC2AEA6), dim = Color(0xFF8C766E), hairline = Color(0x1FF0E9E5), onGold = Color(0xFF1E0D06), codeBg = Color(0xFF140B0A), danger = Color(0xFFD44F4F))
        val Jupiter = OrmusPalette(midnight = Color(0xFF241838), deep = Color(0xFF150D24), gold = Color(0xFFC08AE8), goldBright = Color(0xFFD8B4F4), ink = Color(0xFFEEEBF4), inkMuted = Color(0xFFB5A9C8), dim = Color(0xFF7E7298), hairline = Color(0x1FEEEBF4), onGold = Color(0xFF1A0C26), codeBg = Color(0xFF0F091C), danger = Color(0xFFD44F4F))
        val Saturn = OrmusPalette(midnight = Color(0xFF1A1C24), deep = Color(0xFF0D0E12), gold = Color(0xFF8A97EE), goldBright = Color(0xFFB3BCF6), ink = Color(0xFFECECEE), inkMuted = Color(0xFFA9ABB3), dim = Color(0xFF757884), hairline = Color(0x1FECECEE), onGold = Color(0xFF0E1028), codeBg = Color(0xFF090A0D), danger = Color(0xFFD44F4F))
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

/** One planet's palette: every key of the color group, in the same roles; [gold] is the planet's one accent. */
data class OrmusPalette(
    val midnight: Color,
    val deep: Color,
    val gold: Color,
    val goldBright: Color,
    val ink: Color,
    val inkMuted: Color,
    val dim: Color,
    val hairline: Color,
    val onGold: Color,
    val codeBg: Color,
    val danger: Color,
)
