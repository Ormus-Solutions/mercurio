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

package org.connectbot.service

import org.connectbot.util.TerminalText

/**
 * Bounded plain-text transcript of a session's recent output, oldest line
 * first. Fed the same decoded chunks as the activity preview; a line is
 * cleaned once it completes, so escape sequences split across chunks still
 * strip. Thread-safe: written from the relay thread, read by the reader.
 */
class TranscriptBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    private val lines = ArrayDeque<String>()
    private var storedChars = 0

    // Raw text after the last newline, waiting for its line to complete.
    private val pending = StringBuilder()

    @Synchronized
    fun append(text: CharSequence) {
        pending.append(text)
        var start = 0
        while (true) {
            val end = pending.indexOf(LF, start)
            if (end < 0) break
            addLine(pending.substring(start, end))
            start = end + 1
        }
        pending.delete(0, start)
        // A full-screen TUI can redraw for a long time without a newline; cap it.
        if (pending.length > MAX_PENDING_CHARS) {
            addLine(pending.toString())
            pending.setLength(0)
        }
    }

    /** The transcript as plain text, including the line still being written. */
    @Synchronized
    fun text(): String {
        val tail = TerminalText.toPlainText(pending)
        return if (tail.isBlank()) {
            lines.joinToString(LF)
        } else {
            (lines + tail).joinToString(LF)
        }
    }

    @Synchronized
    fun lineCount(): Int = lines.size

    /** Drop everything, so the buffer can be reused for a fresh attempt. */
    @Synchronized
    fun clear() {
        lines.clear()
        storedChars = 0
        pending.setLength(0)
    }

    private fun addLine(raw: String) {
        val line = TerminalText.toPlainText(raw)
        lines.addLast(line)
        storedChars += line.length
        while (lines.size > maxLines || (storedChars > maxChars && lines.size > 1)) {
            storedChars -= lines.removeFirst().length
        }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 2000

        // About 1 MB of UTF-16 per session, so long lines cannot grow it unbounded.
        const val DEFAULT_MAX_CHARS = 512 * 1024
        private const val MAX_PENDING_CHARS = 8192
        private val LF: String = TerminalText.LF.toString()
    }
}
