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

package org.connectbot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TerminalTextTest {
    // Control characters from code points, never as escapes in literals.
    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()
    private val cr = 13.toChar().toString()
    private val lf = 10.toChar().toString()
    private val tab = 9.toChar().toString()

    @Test
    fun stripsColorAndCursorSequences() {
        val raw = esc + "[1;32mgreen" + esc + "[0m " + esc + "[2K" + esc + "[10;4Hmoved"
        assertEquals("green moved", TerminalText.toPlainText(raw))
    }

    @Test
    fun stripsPrivateParameterCsi() {
        // ESC [ > 4 ; 1 m (modifyOtherKeys) and ESC [ ? 2026 h (synchronized output).
        val raw = esc + "[>4;1m" + esc + "[?2026hready"
        assertEquals("ready", TerminalText.toPlainText(raw))
    }

    @Test
    fun stripsOscTitleEndedByBelOrSt() {
        val raw = esc + "]0;window title" + bel + "a" + esc + "]2;other" + esc + "\\b"
        assertEquals("ab", TerminalText.toPlainText(raw))
    }

    @Test
    fun resolvesCarriageReturnRedrawsAndCrlf() {
        val raw = "progress 10%" + cr + "progress 100%" + cr + lf + "next line" + cr + lf
        assertEquals("progress 100%" + lf + "next line" + lf, TerminalText.toPlainText(raw))
    }

    @Test
    fun dropsOtherControlsKeepsTabsAndTrimsTrailingSpace() {
        val raw = "a" + bel + tab + "b   "
        assertEquals("a" + tab + "b", TerminalText.toPlainText(raw))
    }

    @Test
    fun stripsCharsetAndCursorSaveEscapes() {
        // ESC ( B (ASCII charset, from tput sgr0), ESC 7 / ESC 8 (save, restore cursor),
        // ESC = (keypad mode), ESC M (reverse index).
        val raw = esc + "(Bplain" + esc + "7 " + esc + "8text" + esc + "=" + esc + "M"
        assertEquals("plain text", TerminalText.toPlainText(raw))
    }

    @Test
    fun stripsDcsAndApcStrings() {
        // A DCS capability query and a kitty graphics APC, both ended by ST.
        val raw = "a" + esc + "P+q544e" + esc + "\\b" + esc + "_Gf=24,s=1;AAAA" + esc + "\\c"
        assertEquals("abc", TerminalText.toPlainText(raw))
    }

    @Test
    fun stripsASequenceCutAtTheEnd() {
        // The last read ended inside a sequence; the rest comes with the next read.
        assertEquals("working", TerminalText.toPlainText("working" + esc + "[38;5"))
        assertEquals("done", TerminalText.toPlainText("done" + esc + "]0;win"))
        assertEquals("done", TerminalText.toPlainText("done" + esc + "]0;win" + esc))
        assertEquals("x", TerminalText.toPlainText("x" + esc))
        assertEquals(0, TerminalText.unfinishedStart(esc + "[12;3"))
        assertEquals(2, TerminalText.unfinishedStart("ab" + esc + "]0;t" + esc))
        val ended = "ab" + esc + "[1mz" + esc + "]0;t" + esc + "\\"
        assertEquals(ended.length, TerminalText.unfinishedStart(ended))
    }

    @Test
    fun keepsTextThatOnlyLooksLikeASequence() {
        val typed = "move it to 38;27H then [1m and ]0;x"
        assertEquals(typed, TerminalText.toPlainText(typed))
        assertEquals(typed, TerminalText.latestLine(typed, 80))
    }

    @Test
    fun keepLastNeverStartsInsideASequence() {
        // A full-screen redraw: cursor moves and colors, no newline (Herdr, Claude Code).
        val raw = esc + "[12;38Hfirst " + esc + "[38;5;27mblue" + esc + "[0m " + esc + "]0;title" + bel + "end"
        val cuts = (1 until raw.length).map { at ->
            StringBuilder(raw).also { TerminalText.keepLast(it, raw.length - at) }.toString()
        }
        // Wherever the cut lands, what is kept starts with plain text or a whole sequence.
        cuts.forEach { kept ->
            val preview = TerminalText.latestLine(kept, 80).orEmpty()
            listOf("12;38H", "38H", "8H", "5;27m", "27m", "0m", "0;title").forEach { tail ->
                assertFalse("kept '$kept' shows '$preview'", preview.startsWith(tail))
            }
        }
        // A cut in plain text keeps the text from there; a cut inside a sequence skips it.
        assertEquals("rst blue end", TerminalText.latestLine(cutAt(raw, raw.indexOf("rst")), 80))
        assertEquals("first blue end", TerminalText.latestLine(cutAt(raw, 3), 80))
        assertEquals("blue end", TerminalText.latestLine(cutAt(raw, raw.indexOf("5;27m")), 80))
        assertEquals("end", TerminalText.latestLine(cutAt(raw, raw.indexOf("title")), 80))
    }

    @Test
    fun latestLineIsTheLastNonBlankLineClamped() {
        val raw = "old line" + cr + lf + esc + "[32mnew " + esc + "[0mline" + cr + lf + "   " + lf
        assertEquals("new line", TerminalText.latestLine(raw, 80))
        assertEquals("new", TerminalText.latestLine(raw, 3))
        assertEquals(null, TerminalText.latestLine(esc + "[2J" + lf + "  ", 80))
    }

    private fun cutAt(raw: String, at: Int): String = StringBuilder(raw).also { TerminalText.keepLast(it, raw.length - at) }.toString()

    @Test
    fun stripSequencesLeavesCarriageReturns() {
        assertEquals("x" + cr + "y", TerminalText.stripSequences(esc + "[31mx" + cr + "y"))
    }
}
