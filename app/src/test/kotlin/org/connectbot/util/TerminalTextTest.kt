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
    fun stripSequencesLeavesCarriageReturns() {
        assertEquals("x" + cr + "y", TerminalText.stripSequences(esc + "[31mx" + cr + "y"))
    }
}
