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

package org.connectbot.ui.components

/** Where a bar modifier stands: off, armed for the next key only, or locked until tapped. */
enum class ModLevel { OFF, ARMED, LOCKED }

enum class BarModifier { CTRL, ALT }

private const val ESC = '\u001b'
private const val CTRL_B = "\u0002"

/**
 * The bar's Ctrl and Alt keys. A tap arms the modifier for the next key (a second tap disarms),
 * a long press locks it, and a tap on a locked one turns it off. [afterKey] is what a sent key
 * leaves behind: armed ones are spent (except by a text macro), locked ones stay.
 *
 * [encode] writes a key's bytes the way the terminal view's own keys do for Ctrl and Alt (the
 * xterm scheme libvterm uses): Ctrl on a letter is its control code, Alt prefixes Escape, and the
 * cursor, tilde and function keys carry the modifier parameter (1 + 2 for Alt + 4 for Ctrl).
 */
data class KeyBarModifiers(val ctrl: ModLevel = ModLevel.OFF, val alt: ModLevel = ModLevel.OFF) {
    fun level(modifier: BarModifier): ModLevel = when (modifier) {
        BarModifier.CTRL -> ctrl
        BarModifier.ALT -> alt
    }

    private fun with(modifier: BarModifier, level: ModLevel): KeyBarModifiers = when (modifier) {
        BarModifier.CTRL -> copy(ctrl = level)
        BarModifier.ALT -> copy(alt = level)
    }

    fun tap(modifier: BarModifier): KeyBarModifiers = with(
        modifier,
        if (level(modifier) == ModLevel.OFF) ModLevel.ARMED else ModLevel.OFF,
    )

    fun longPress(modifier: BarModifier): KeyBarModifiers = with(modifier, ModLevel.LOCKED)

    fun afterKey(): KeyBarModifiers = KeyBarModifiers(spend(ctrl), spend(alt))

    /**
     * What [sequence] leaves behind: any key spends an armed modifier, except a text macro (a
     * several-character string that is not an escape sequence, like `/clear`), which no modifier
     * touches and so leaves armed for the key that follows. Locked ones always stay.
     */
    fun afterKey(sequence: String): KeyBarModifiers = if (isTextMacro(sequence)) this else afterKey()

    private fun isTextMacro(sequence: String) = sequence.length > 1 && sequence[0] != ESC

    private fun spend(level: ModLevel) = if (level == ModLevel.ARMED) ModLevel.OFF else level

    /** The bytes for [sequence] under these modifiers; empty when they would make Herdr's prefix (Ctrl+B), which the bar never sends. */
    fun encode(sequence: String): String = encodeRaw(sequence).takeUnless { it == CTRL_B || it == ESC.toString() + CTRL_B } ?: ""

    private fun encodeRaw(sequence: String): String {
        val ctrlOn = ctrl != ModLevel.OFF
        val altOn = alt != ModLevel.OFF
        if ((!ctrlOn && !altOn) || sequence.isEmpty()) return sequence
        val param = 1 + (if (altOn) 2 else 0) + (if (ctrlOn) 4 else 0)
        val altPrefix = if (altOn) ESC.toString() else ""
        if (sequence.length == 1) {
            val c = sequence[0]
            if (c == ESC) return if (altOn) "$ESC$ESC" else sequence
            return altPrefix + if (ctrlOn) controlCode(c) else c
        }
        // Escape, then "[" or "O", then the key: CSI and SS3 forms.
        if (sequence[0] == ESC && sequence.length >= 3 && (sequence[1] == '[' || sequence[1] == 'O')) {
            val tail = sequence.substring(2)
            val last = tail.last()
            return when {
                last == 'Z' -> if (altOn) "$ESC$sequence" else sequence
                last == '~' -> "$ESC[${tail.dropLast(1)};$param~"
                sequence[1] == 'O' || tail.length == 1 -> "$ESC[1;$param$last"
                else -> sequence
            }
        }
        return sequence
    }

    private fun controlCode(c: Char): Char = when (c) {
        in 'a'..'z', in 'A'..'Z', '@', '[', '\\', ']', '^', '_' -> (c.code and 0x1f).toChar()
        '?' -> 127.toChar()
        ' ' -> '\u0000'
        else -> c
    }
}
