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

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptBufferTest {
    private val esc = 27.toChar().toString()
    private val cr = 13.toChar().toString()
    private val lf = 10.toChar().toString()

    @Test
    fun keepsOnlyTheNewestLines() {
        val buffer = TranscriptBuffer(maxLines = 3)
        (1..10).forEach { buffer.append("line $it$lf") }

        assertEquals(3, buffer.lineCount())
        assertEquals(listOf("line 8", "line 9", "line 10").joinToString(lf), buffer.text())
    }

    @Test
    fun defaultBoundIs2000Lines() {
        val buffer = TranscriptBuffer()
        repeat(2500) { buffer.append("row $it$lf") }

        assertEquals(2000, buffer.lineCount())
        assertEquals("row 500", buffer.text().substringBefore(lf))
    }

    @Test
    fun characterBudgetEvictsOldLines() {
        val buffer = TranscriptBuffer(maxLines = 100, maxChars = 10)
        buffer.append("aaaa${lf}bbbb${lf}cccc$lf")

        assertEquals("bbbb${lf}cccc", buffer.text())
    }

    @Test
    fun joinsChunksSplitMidLineAndMidEscape() {
        val buffer = TranscriptBuffer()
        buffer.append("hel")
        buffer.append("lo $esc[3")
        buffer.append("2mworld$esc[0m$cr$lf")
        buffer.append("partial")

        assertEquals("hello world${lf}partial", buffer.text())
    }

    @Test
    fun clearStartsOver() {
        val buffer = TranscriptBuffer(maxLines = 100, maxChars = 10)
        buffer.append("aaaa${lf}bb")
        buffer.clear()
        buffer.append("cccc$lf")

        assertEquals(1, buffer.lineCount())
        assertEquals("cccc", buffer.text())
    }
}
