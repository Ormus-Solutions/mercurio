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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.connectbot.data.ColorSchemePresets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The Ormus re-skin's acceptance checks: brand colors come from the vendored
 * ormus-brand tokens.json, the Material 3 dark roles equal those tokens, and the
 * stale Aurum and ConnectBot theme values are gone from the app.
 */
@RunWith(RobolectricTestRunner::class)
class OrmusThemeTest {

    // Gradle runs unit tests with the app module as the working directory.
    private val tokens = JSONObject(File("brand/ormus-tokens.json").readText())

    private fun token(group: String, key: String): Color = parseCss(tokens.getJSONObject(group).getString(key))

    private fun parseCss(value: String): Color {
        val v = value.trim()
        if (v.startsWith("#")) return Color(("FF" + v.drop(1)).toLong(16))
        val parts = v.substringAfter("(").substringBefore(")").split(",").map { it.trim() }
        return Color(
            red = parts[0].toInt(),
            green = parts[1].toInt(),
            blue = parts[2].toInt(),
            alpha = Math.round(parts[3].toDouble() * 255).toInt(),
        )
    }

    @Test
    fun generatedTokens_matchVendoredJson() {
        assertEquals(tokens.getString("version"), OrmusTokens.VERSION)
        val color = mapOf(
            "midnight" to OrmusTokens.Midnight,
            "deep" to OrmusTokens.Deep,
            "gold" to OrmusTokens.Gold,
            "goldBright" to OrmusTokens.GoldBright,
            "ink" to OrmusTokens.Ink,
            "inkMuted" to OrmusTokens.InkMuted,
            "dim" to OrmusTokens.Dim,
            "hairline" to OrmusTokens.Hairline,
            "onGold" to OrmusTokens.OnGold,
            "codeBg" to OrmusTokens.CodeBg,
            "danger" to OrmusTokens.Danger,
        )
        assertEquals(tokens.getJSONObject("color").keys().asSequence().toSet(), color.keys)
        color.forEach { (key, value) -> assertEquals("color.$key", token("color", key).toArgb(), value.toArgb()) }
        mapOf(
            "paper" to OrmusTokens.Print.Paper,
            "gold" to OrmusTokens.Print.Gold,
            "ink" to OrmusTokens.Print.Ink,
            "onGold" to OrmusTokens.Print.OnGold,
        ).forEach { (key, value) -> assertEquals("print.$key", token("print", key).toArgb(), value.toArgb()) }
        assertEquals(tokens.getString("radius").removeSuffix("px").toFloat(), OrmusTokens.RADIUS_DP)
    }

    @Test
    fun darkScheme_brandRolesEqualVendoredTokens() {
        val scheme = OrmusDarkColorScheme
        assertEquals(token("color", "gold"), scheme.primary)
        assertEquals(token("color", "onGold"), scheme.onPrimary)
        assertEquals(token("color", "deep"), scheme.background)
        assertEquals(token("color", "deep"), scheme.surface)
        assertEquals(token("color", "ink"), scheme.onSurface)
        assertEquals(token("color", "danger"), scheme.error)
    }

    @Test
    fun appSources_holdNoStaleThemeColors() {
        val bannedHex = listOf("0xFF03A9F4", "0xFF8A7434", "0xFFC8B583", "0xFF4CAF50")
        // The Aurum terminal preset keeps its own palette on purpose (selectable for
        // continuity); its definition is the one place those values may appear.
        val legacyPreset = "ColorSchemePresets.kt"
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") && it.name != legacyPreset }
            .flatMap { file ->
                val text = file.readText()
                // Hex in either case; Compose's Color.Green exactly (android.graphics.Color.GREEN
                // in ConnectionNotifier is a user's chosen green host color for the LED).
                val hits = bannedHex.filter { text.contains(it, ignoreCase = true) } +
                    listOf("Color.Green").filter { text.contains(it) }
                hits.map { "${file.path}: $it" }
            }
            .toList()
        assertTrue("Stale theme colors found:\n" + offenders.joinToString("\n"), offenders.isEmpty())

        val defaultPalette = ColorSchemePresets.default.colors.map { it.toLong() and 0xFFFFFFFFL }
        listOf(0xFFC8B583L, 0xFF8A7434L, 0xFF03A9F4L, 0xFF4CAF50L).forEach {
            assertTrue("default terminal palette still holds ${it.toString(16)}", it !in defaultPalette)
        }
    }

    @Test
    fun xmlTokens_matchGeneratedKotlin() {
        val xml = File("src/main/res/values/ormus_tokens.xml").readText()
        assertTrue(xml.contains("<color name=\"ormus_gold\">#FFD29E3D</color>"))
        assertTrue(xml.contains("<color name=\"ormus_deep\">#FF0E1830</color>"))
    }
}
