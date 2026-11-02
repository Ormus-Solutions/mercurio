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

    // CSI (including private-parameter forms such as ESC [ > 4 m); OSC and the other
    // string controls (DCS ESC P, SOS ESC X, PM ESC ^, APC ESC _) ended by BEL or ST
    // (ESC \); and every other escape: intermediates, then one final byte (ESC 7,
    // ESC ( B, ESC M).
    private val ANSI_SEQUENCE = Regex(
        "$ESC\\[[0-?]*[ -/]*[@-~]|$ESC[\\]PX^_][^$BEL$ESC]*(?:$BEL|$ESC\\\\)|$ESC[ -/]*[0-~]",
    )

    // The same three forms cut before their end, at the end of the text: a read can stop
    // inside a sequence, and the next read brings the rest.
    private val UNFINISHED = Regex(
        "(?:$ESC\\[[0-?]*[ -/]*|$ESC[\\]PX^_][^$BEL$ESC]*$ESC?|$ESC[ -/]*)\\z",
    )

    /**
     * Remove escape sequences only, and one cut short at the end; carriage returns and
     * other controls stay.
     */
    fun stripSequences(raw: CharSequence): String = ANSI_SEQUENCE.replace(raw.subSequence(0, unfinishedStart(raw)), "")

    /** Where an escape sequence that has not ended yet starts at the end of [raw], else its length. */
    fun unfinishedStart(raw: CharSequence): Int {
        // It holds at most two ESCs (a string control cut inside its ST): it starts at one
        // of the last two.
        val last = raw.lastIndexOf(ESC)
        if (last < 0) return raw.length
        val from = if (last == 0) 0 else raw.lastIndexOf(ESC, last - 1).coerceAtLeast(0)
        return UNFINISHED.find(raw, from)?.range?.first ?: raw.length
    }

    /**
     * Trim [buffer] to about its last [maxChars] characters, for a rolling tail of output.
     * A cut that lands inside an escape sequence moves to that sequence's end, so the
     * tail never starts with what is left of one (`12;38H`). Text that only looks like
     * a sequence, with no ESC before it, is never removed.
     */
    fun keepLast(buffer: StringBuilder, maxChars: Int) {
        val overflow = buffer.length - maxChars
        if (overflow > 0) buffer.delete(0, cutOutsideSequence(buffer, overflow))
    }

    // [at], or the end of the sequence that [at] falls inside.
    private fun cutOutsideSequence(raw: CharSequence, at: Int): Int {
        val start = raw.lastIndexOf(ESC, at - 1)
        if (start < 0) return at
        // Not ended inside the text: a terminal shows none of it either.
        if (unfinishedStart(raw) == start) return raw.length
        val whole = ANSI_SEQUENCE.find(raw, start)?.takeIf { it.range.first == start } ?: return at
        return maxOf(at, whole.range.last + 1)
    }

    /**
     * The last non-blank line of [raw] with escape and control sequences removed, at most
     * [maxChars] long, or null: the session drawer's activity preview.
     */
    fun latestLine(raw: CharSequence, maxChars: Int): String? {
        // Treat carriage returns as line breaks so in-place redraws (spinners,
        // progress bars) resolve to their final rendered state.
        val cleaned = stripSequences(raw).replace(CR, LF)
        val line = cleaned.split(LF)
            .lastOrNull { candidate -> candidate.any { !it.isISOControl() && !it.isWhitespace() } }
            ?: return null
        val printable = line.filter { !it.isISOControl() }.trim()
        return printable.ifEmpty { null }?.take(maxChars)
    }

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
