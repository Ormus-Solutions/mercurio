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

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast for the dark scheme (the app has no other): text pairs at 4.5:1, outlines and status
 * marks at 3:1. One documented exception: the dark `error` role is the brand
 * danger token itself (4.22 on deep), so error text uses status.error instead.
 */
class OrmusContrastTest {

    private fun ratio(fg: Color, bg: Color): Double {
        val f = fg.compositeOver(bg).luminance() + 0.05
        val b = bg.luminance() + 0.05
        return maxOf(f, b) / minOf(f, b)
    }

    private fun assertContrast(label: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertTrue("$label reads %.2f:1, needs %.1f".format(r, min), r >= min)
    }

    private fun ColorScheme.surfaces() = mapOf(
        "surface" to surface,
        "containerLowest" to surfaceContainerLowest,
        "containerLow" to surfaceContainerLow,
        "container" to surfaceContainer,
        "containerHigh" to surfaceContainerHigh,
        "containerHighest" to surfaceContainerHighest,
    )

    private fun checkScheme(name: String, scheme: ColorScheme, extras: OrmusExtras) {
        scheme.surfaces().forEach { (s, bg) ->
            assertContrast("$name onSurface on $s", scheme.onSurface, bg, 4.5)
            assertContrast("$name onSurfaceVariant on $s", scheme.onSurfaceVariant, bg, 4.5)
            assertContrast("$name primary text on $s", scheme.primary, bg, 4.5)
            assertContrast("$name status.error text on $s", extras.status.error, bg, 4.5)
            assertContrast("$name outline on $s", scheme.outline, bg, 3.0)
            assertContrast("$name blocked mark on $s", extras.status.blocked, bg, 3.0)
            assertContrast("$name done mark on $s", extras.status.done, bg, 3.0)
            assertContrast("$name working mark on $s", extras.status.working, bg, 3.0)
        }
        assertContrast("$name onPrimary on primary", scheme.onPrimary, scheme.primary, 4.5)
        assertContrast("$name onPrimaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer, 4.5)
        assertContrast("$name onSecondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer, 4.5)
        assertContrast("$name onErrorContainer", scheme.onErrorContainer, scheme.errorContainer, 4.5)
        assertContrast("$name onBackground", scheme.onBackground, scheme.background, 4.5)
    }

    @Test
    fun darkScheme_meetsContrast() {
        checkScheme("dark", OrmusDarkColorScheme, OrmusDarkExtras)
        // The exception, pinned so it cannot slip further: danger on deep.
        assertContrast("dark error role on surface", OrmusDarkColorScheme.error, OrmusDarkColorScheme.surface, 4.2)
    }

    @Test
    fun terminalOverlay_textReadsOverTheTerminal() {
        val terminal = OrmusTokens.CodeBg
        val panel = TerminalOverlayBackground.compositeOver(terminal)
        assertContrast("overlay text", TerminalOverlayText, panel, 4.5)
        assertContrast("overlay secondary text", TerminalOverlayTextSecondary, panel, 4.5)
    }
}
