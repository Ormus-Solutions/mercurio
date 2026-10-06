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

package org.connectbot.data

import androidx.compose.ui.graphics.toArgb
import org.connectbot.ui.theme.OrmusTokens

/**
 * Built-in color scheme presets for terminal emulation.
 * Each preset defines 16 colors in the terminal palette.
 */
object ColorSchemePresets {

    /**
     * Default terminal colors: "Ormus Fusion", gold and ink on the brand's code
     * panel. Slots that have a brand token use it (black = codeBg, red = danger,
     * yellow = gold, white = ink muted, bright black = dim, bright yellow = gold
     * bright, bright white = ink). The other hues are muted to sit on midnight;
     * they are proposed for a `terminal` group in ormus-brand and are hand-set here
     * until it lands. Every index reads 4.2:1 or better on the background
     * (ColorSchemePresetsTest). Also the runtime fallback palette.
     */
    val default = PresetScheme(
        name = "Ormus Fusion",
        description = "Gold and ink on midnight lacquer",
        defaultFg = 15, // ink
        defaultBg = 0, // codeBg
        colors = intArrayOf(
            OrmusTokens.CodeBg.toArgb(), // black
            OrmusTokens.Danger.toArgb(), // red
            0xff8fb08a.toInt(), // green: sage
            OrmusTokens.Gold.toArgb(), // yellow
            0xff6f93cf.toInt(), // blue: lapis
            0xffa98bc2.toInt(), // magenta: amethyst
            0xff6fa7a8.toInt(), // cyan: verdigris
            OrmusTokens.InkMuted.toArgb(), // white
            OrmusTokens.Dim.toArgb(), // bright black (zsh autosuggestions stay readable)
            0xffe27a72.toInt(), // bright red
            0xffaecba7.toInt(), // bright green
            OrmusTokens.GoldBright.toArgb(), // bright yellow
            0xff93b2e2.toInt(), // bright blue
            0xffc4a9da.toInt(), // bright magenta
            0xff93c6c4.toInt(), // bright cyan
            OrmusTokens.Ink.toArgb(), // bright white
        ),
    )

    /**
     * Aurum: the earlier Ormus fleet palette, gold foreground on obsidian. Kept
     * selectable for continuity; appended last so preset IDs stay stable.
     */
    val aurum = PresetScheme(
        name = "Aurum",
        description = "Gold on obsidian, the earlier Ormus fleet palette",
        defaultFg = 7, // gold
        defaultBg = 0, // obsidian
        colors = intArrayOf(
            0xff090a16.toInt(), // black: obsidian
            0xffcc6633.toInt(), // red
            0xff5a9a7a.toInt(), // green
            0xffc8b583.toInt(), // yellow: gold
            0xff6488b4.toInt(), // blue
            0xff8a6aaa.toInt(), // magenta
            0xff5a9a8a.toInt(), // cyan
            0xffc8b583.toInt(), // white: gold foreground
            0xff2a2a32.toInt(), // bright black
            0xffe08050.toInt(), // bright red
            0xff7abb9a.toInt(), // bright green
            0xffecdba6.toInt(), // bright yellow
            0xff8facd6.toInt(), // bright blue
            0xffaa8acc.toInt(), // bright magenta
            0xff7abbaa.toInt(), // bright cyan
            0xfff5e6c8.toInt(), // bright white
        ),
    )

    /**
     * Solarized Dark - Popular low-contrast color scheme
     * Official colors from https://github.com/altercation/solarized
     */
    val solarizedDark = PresetScheme(
        name = "Solarized Dark",
        description = "Precision colors for machines and people",
        defaultFg = 12, // base0 (#839496)
        defaultBg = 8, // base03 (#002b36)
        colors = intArrayOf(
            // ANSI colors (0-15) - Official Solarized Dark mapping
            0xff073642.toInt(), // black: base02
            0xffdc322f.toInt(), // red
            0xff859900.toInt(), // green
            0xffb58900.toInt(), // yellow
            0xff268bd2.toInt(), // blue
            0xffd33682.toInt(), // magenta
            0xff2aa198.toInt(), // cyan
            0xffeee8d5.toInt(), // white: base2
            0xff002b36.toInt(), // bright black: base03
            0xffcb4b16.toInt(), // bright red: orange
            0xff586e75.toInt(), // bright green: base01
            0xff657b83.toInt(), // bright yellow: base00
            0xff839496.toInt(), // bright blue: base0
            0xff6c71c4.toInt(), // bright magenta: violet
            0xff93a1a1.toInt(), // bright cyan: base1
            0xfffdf6e3.toInt(), // bright white: base3
        ),
    )

    /**
     * Solarized Light - Light variant of Solarized
     * Official colors from https://github.com/altercation/solarized
     */
    val solarizedLight = PresetScheme(
        name = "Solarized Light",
        description = "Solarized with light background",
        defaultFg = 10, // base1 (#586e75)
        defaultBg = 15, // base3 (#fdf6e3)
        colors = intArrayOf(
            // ANSI colors (0-15) - Official Solarized Light mapping
            // Note: Light variant swaps dark/light base colors
            0xff073642.toInt(), // black: base02
            0xffdc322f.toInt(), // red
            0xff859900.toInt(), // green
            0xffb58900.toInt(), // yellow
            0xff268bd2.toInt(), // blue
            0xffd33682.toInt(), // magenta
            0xff2aa198.toInt(), // cyan
            0xffeee8d5.toInt(), // white: base2
            0xff002b36.toInt(), // bright black: base03
            0xffcb4b16.toInt(), // bright red: orange
            0xff586e75.toInt(), // bright green: base01
            0xff657b83.toInt(), // bright yellow: base00
            0xff839496.toInt(), // bright blue: base0
            0xff6c71c4.toInt(), // bright magenta: violet
            0xff93a1a1.toInt(), // bright cyan: base1
            0xfffdf6e3.toInt(), // bright white: base3
        ),
    )

    /**
     * Dracula - Dark theme with vibrant colors
     */
    val dracula = PresetScheme(
        name = "Dracula",
        description = "A dark theme for the terminal",
        defaultFg = 7,
        defaultBg = 0,
        colors = intArrayOf(
            0xff282a36.toInt(), // background
            0xffff5555.toInt(), // red
            0xff50fa7b.toInt(), // green
            0xfff1fa8c.toInt(), // yellow
            0xffbd93f9.toInt(), // purple
            0xffff79c6.toInt(), // pink
            0xff8be9fd.toInt(), // cyan
            0xfff8f8f2.toInt(), // foreground
            0xff6272a4.toInt(), // selection
            0xffff6e6e.toInt(), // bright red
            0xff69ff94.toInt(), // bright green
            0xfffffa65.toInt(), // bright yellow
            0xffd6acff.toInt(), // bright purple
            0xffff92df.toInt(), // bright pink
            0xffa4ffff.toInt(), // bright cyan
            0xffffffff.toInt(), // bright white
        ),
    )

    /**
     * Nord - Arctic, north-bluish color palette
     */
    val nord = PresetScheme(
        name = "Nord",
        description = "An arctic, north-bluish color palette",
        defaultFg = 7,
        defaultBg = 0,
        colors = intArrayOf(
            0xff2e3440.toInt(), // polar night
            0xffbf616a.toInt(), // red
            0xffa3be8c.toInt(), // green
            0xffebcb8b.toInt(), // yellow
            0xff81a1c1.toInt(), // blue
            0xffb48ead.toInt(), // purple
            0xff88c0d0.toInt(), // cyan
            0xffe5e9f0.toInt(), // snow storm
            0xff4c566a.toInt(), // polar night bright
            0xffbf616a.toInt(), // bright red
            0xffa3be8c.toInt(), // bright green
            0xffebcb8b.toInt(), // bright yellow
            0xff81a1c1.toInt(), // bright blue
            0xffb48ead.toInt(), // bright purple
            0xff8fbcbb.toInt(), // bright cyan
            0xffeceff4.toInt(), // bright white
        ),
    )

    /**
     * Gruvbox Dark - Retro groove color scheme
     */
    val gruvboxDark = PresetScheme(
        name = "Gruvbox Dark",
        description = "Retro groove color scheme",
        defaultFg = 15,
        defaultBg = 0,
        colors = intArrayOf(
            0xff282828.toInt(), // dark0
            0xffcc241d.toInt(), // red
            0xff98971a.toInt(), // green
            0xffd79921.toInt(), // yellow
            0xff458588.toInt(), // blue
            0xffb16286.toInt(), // purple
            0xff689d6a.toInt(), // aqua
            0xffa89984.toInt(), // gray
            0xff928374.toInt(), // dark gray
            0xfffb4934.toInt(), // bright red
            0xffb8bb26.toInt(), // bright green
            0xfffabd2f.toInt(), // bright yellow
            0xff83a598.toInt(), // bright blue
            0xffd3869b.toInt(), // bright purple
            0xff8ec07c.toInt(), // bright aqua
            0xffebdbb2.toInt(), // light
        ),
    )

    /**
     * Monokai - Sublime Text default color scheme
     */
    val monokai = PresetScheme(
        name = "Monokai",
        description = "Sublime Text inspired theme",
        defaultFg = 7,
        defaultBg = 0,
        colors = intArrayOf(
            0xff272822.toInt(), // background
            0xfff92672.toInt(), // red
            0xffa6e22e.toInt(), // green
            0xfff4bf75.toInt(), // yellow
            0xff66d9ef.toInt(), // blue
            0xffae81ff.toInt(), // purple
            0xffa1efe4.toInt(), // cyan
            0xfff8f8f2.toInt(), // foreground
            0xff75715e.toInt(), // comment
            0xfffd971f.toInt(), // orange
            0xffa6e22e.toInt(), // bright green
            0xffe6db74.toInt(), // bright yellow
            0xff66d9ef.toInt(), // bright blue
            0xffae81ff.toInt(), // bright purple
            0xffa1efe4.toInt(), // bright cyan
            0xfff9f8f5.toInt(), // bright white
        ),
    )

    /**
     * Tomorrow Night - Clean, comfortable theme
     */
    val tomorrowNight = PresetScheme(
        name = "Tomorrow Night",
        description = "A clean and comfortable dark theme",
        defaultFg = 7,
        defaultBg = 0,
        colors = intArrayOf(
            0xff1d1f21.toInt(), // background
            0xffcc6666.toInt(), // red
            0xffb5bd68.toInt(), // green
            0xfff0c674.toInt(), // yellow
            0xff81a2be.toInt(), // blue
            0xffb294bb.toInt(), // purple
            0xff8abeb7.toInt(), // cyan
            0xffc5c8c6.toInt(), // foreground
            0xff969896.toInt(), // bright black
            0xffcc6666.toInt(), // bright red
            0xffb5bd68.toInt(), // bright green
            0xfff0c674.toInt(), // bright yellow
            0xff81a2be.toInt(), // bright blue
            0xffb294bb.toInt(), // bright purple
            0xff8abeb7.toInt(), // bright cyan
            0xffffffff.toInt(), // bright white
        ),
    )

    /**
     * List of all built-in color scheme presets.
     */
    val builtInSchemes = listOf(
        default,
        solarizedDark,
        solarizedLight,
        dracula,
        nord,
        gruvboxDark,
        monokai,
        tomorrowNight,
        aurum,
    )

    /**
     * Represents a preset color scheme definition.
     */
    data class PresetScheme(
        val name: String,
        val description: String,
        val defaultFg: Int,
        val defaultBg: Int,
        val colors: IntArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PresetScheme

            if (defaultFg != other.defaultFg) return false
            if (defaultBg != other.defaultBg) return false
            if (name != other.name) return false
            if (description != other.description) return false
            if (!colors.contentEquals(other.colors)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = defaultFg
            result = 31 * result + defaultBg
            result = 31 * result + name.hashCode()
            result = 31 * result + description.hashCode()
            result = 31 * result + colors.contentHashCode()
            return result
        }
    }
}
