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

package org.connectbot.ui.commands

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.connectbot.ui.components.DictationDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import solutions.ormus.logos.commands.LineSender
import solutions.ormus.logos.commands.SlashCommand
import solutions.ormus.logos.commands.SlashSource

/** Send vs insert: the exact bytes a tap writes to the PTY, or the text it puts in the field. */
@OptIn(ExperimentalCoroutinesApi::class)
class SlashSendTest {
    private val pty = mutableListOf<String>()
    private val field = mutableListOf<String>()

    private fun bytes(chunk: String): List<Int> = chunk.map { it.code }

    @Test
    fun bareBuiltIn_sendsNameThenADiscreteEnter() = runTest {
        val sent = runSlashCommand(SlashCommand("compact", "Summarize"), { pty += it }, { field += it }) { send -> launch { send() } }
        assertTrue(sent)

        runCurrent()
        // The name goes out at once, as one burst, with no newline folded in.
        assertEquals(listOf("/compact"), pty)
        assertEquals(listOf(0x2f, 0x63, 0x6f, 0x6d, 0x70, 0x61, 0x63, 0x74), bytes(pty[0]))

        advanceTimeBy(LineSender.ENTER_DELAY_MS - 1)
        runCurrent()
        assertEquals(1, pty.size)

        advanceTimeBy(1)
        runCurrent()
        // Then Enter on its own: one carriage return, 0x0d.
        assertEquals(listOf("/compact", "\r"), pty)
        assertEquals(listOf(0x0d), bytes(pty[1]))
        assertTrue(field.isEmpty())
    }

    @Test
    fun commandWithAnArgumentHint_goesToTheField() = runTest {
        val sent = runSlashCommand(SlashCommand("add-dir", "Add a dir", "<path>"), { pty += it }, { field += it }) { send -> launch { send() } }
        runCurrent()
        advanceTimeBy(1_000)

        assertFalse(sent)
        assertEquals(listOf("/add-dir "), field)
        assertTrue(pty.isEmpty())
    }

    @Test
    fun ownCommand_goesToTheField() = runTest {
        runSlashCommand(SlashCommand("mplan", "Plan", source = SlashSource.COMMAND), { pty += it }, { field += it }) { send -> launch { send() } }
        advanceTimeBy(1_000)

        assertEquals(listOf("/mplan "), field)
        assertTrue(pty.isEmpty())
    }

    @Test
    fun longPress_putsEvenABareCommandInTheField() = runTest {
        val sent = runSlashCommand(SlashCommand("clear", "New"), { pty += it }, { field += it }, insert = true) { send -> launch { send() } }
        advanceTimeBy(1_000)

        assertFalse(sent)
        assertEquals(listOf("/clear "), field)
        assertTrue(pty.isEmpty())
    }

    @Test
    fun draft_insertPutsTheCommandFirstWithTheCursorAtTheEnd() {
        val draft = DictationDraft()
        draft.insertCommand("/mplan ")
        assertEquals("/mplan ", draft.text)
        assertEquals(TextRange(7), draft.value.selection)
        assertEquals(1, draft.focusRequests)

        // Words already dictated stay after the command.
        draft.value = draft.value.copy(text = "fix the login bug")
        draft.insertCommand("/mplan ")
        assertEquals("/mplan fix the login bug", draft.text)
        assertEquals(TextRange(draft.text.length), draft.value.selection)

        // A second pick replaces the first command instead of stacking.
        draft.insertCommand("/e0 ")
        assertEquals("/e0 fix the login bug", draft.text)
        assertEquals(3, draft.focusRequests)
    }
}
