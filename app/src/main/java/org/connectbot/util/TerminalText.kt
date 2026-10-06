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

/**
 * Turns decoded terminal output into plain readable text, for the session
 * drawer's activity preview and the full-screen output reader.
 */
object TerminalText {
    // Control characters built from code points so no raw control bytes live in the source.
    private val ESC: Char = 27.toChar()
    private val BEL: Char = 7.toChar()
    private val TAB: Char = 9.toChar()
    private val CR: Char = 13.toChar()
    val LF: Char = 10.toChar()

    // CSI (including private-parameter forms such as ESC [ > 4 m), OSC ended by BEL
    // or ST (ESC \), and two-character escapes.
    private val ANSI_SEQUENCE = Regex(
        "$ESC\\[[0-?]*[ -/]*[@-~]|$ESC\\][^$BEL$ESC]*(?:$BEL|$ESC\\\\)|$ESC[@-_]",
    )

    /** Remove escape sequences only; carriage returns and other controls stay. */
    fun stripSequences(raw: CharSequence): String = ANSI_SEQUENCE.replace(raw, "")

    /**
     * Remove escape sequences, resolve carriage-return redraws to what was drawn
     * last on each line, drop other control characters (tabs stay), and trim
     * trailing spaces from every line.
     */
    fun toPlainText(raw: CharSequence): String = stripSequences(raw)
        .split(LF)
        .joinToString(LF.toString()) { plainLine(it) }

    private fun plainLine(line: String): String {
        val body = line.trimEnd(CR)
        // A bare CR redraws the line in place (spinners, progress bars).
        val drawn = body.substring(body.lastIndexOf(CR) + 1)
        return drawn.filter { it == TAB || !it.isISOControl() }.trimEnd()
    }
}
