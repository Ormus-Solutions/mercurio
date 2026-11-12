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

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdrConsole

class ComposeDraftStoreTest {
    private val store = ComposeDraftStore()

    @Test
    fun anUnsentDraft_comesBackForTheSameTerminal_withTheCursorAtTheEnd() {
        store.draft("1|a").value = TextFieldValue("fix the header", TextRange(0))

        val reopened = store.draft("1|a")

        assertEquals("fix the header", reopened.text)
        assertEquals(TextRange("fix the header".length), reopened.value.selection)
    }

    @Test
    fun twoTerminals_keepTheirOwnDrafts() {
        store.draft("1|a").value = TextFieldValue("one")
        store.draft("1|b").value = TextFieldValue("two")
        store.draft("2|a").value = TextFieldValue("three")

        assertEquals("one", store.draft("1|a").text)
        assertEquals("two", store.draft("1|b").text)
        assertEquals("three", store.draft("2|a").text)
    }

    @Test
    fun aSentDraft_isGone_whenTheTerminalOpensAgain() {
        val draft = store.draft("1|a")
        draft.value = TextFieldValue("run the tests")

        draft.value = TextFieldValue()

        assertEquals("", store.draft("1|a").text)
    }

    @Test
    fun aDraftEditedAfterItWasRestored_isKeptAgain() {
        store.draft("1|a").value = TextFieldValue("first")
        val restored = store.draft("1|a")

        restored.insertAtCursor("second")

        assertEquals("first second", store.draft("1|a").text)
    }

    @Test
    fun theKey_tellsTerminalsApart() {
        val core = CoreWatchTarget("t7", "work", "main")
        val other = CoreWatchTarget("t8", "work", "main")
        val keys = listOf(
            ComposeDraftStore.key(1, null, null),
            ComposeDraftStore.key(2, null, null),
            ComposeDraftStore.key(1, core, null),
            ComposeDraftStore.key(1, other, null),
            ComposeDraftStore.key(1, null, HerdrConsole("main", workspaceId = "w1")),
            ComposeDraftStore.key(1, null, HerdrConsole("main", workspaceId = "w2")),
            ComposeDraftStore.key(1, null, HerdrConsole("side", workspaceId = "w1")),
        )

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(ComposeDraftStore.key(1, core, null), ComposeDraftStore.key(1, CoreWatchTarget("t7", "renamed", ""), null))
    }

    @Test
    fun anIdAndALabelWithTheSameText_areTwoTerminals() {
        assertNotEquals(
            ComposeDraftStore.key(1, null, HerdrConsole("main", workspaceId = "x")),
            ComposeDraftStore.key(1, null, HerdrConsole("main", workspaceLabel = "x")),
        )
    }

    @Test
    fun theSeparatorInAName_cannotMakeTwoKeysMeetInTheMiddle() {
        assertNotEquals(
            ComposeDraftStore.key(1, null, HerdrConsole("a", workspaceId = "1:b|id:1:c")),
            ComposeDraftStore.key(1, null, HerdrConsole("a", workspaceId = "1:b", workspaceLabel = "c")),
        )
        assertNotEquals(
            ComposeDraftStore.key(1, CoreWatchTarget("t1|host", "", ""), null),
            ComposeDraftStore.key(1, null, null),
        )
    }

    @Test
    fun aConsoleWithNoWorkspace_hasItsOwnKey_apartFromTheHostsFirstConsole() {
        assertNotEquals(
            ComposeDraftStore.key(1, null, HerdrConsole("main")),
            ComposeDraftStore.key(1, null, null),
        )
    }

    @Test
    fun aSupersededDraft_cannotOverwriteTheStore() {
        val old = store.draft("1|a")
        old.value = TextFieldValue("typed")
        val reopened = store.draft("1|a")
        reopened.value = TextFieldValue()

        // Late voice text or a screenshot path lands in the instance the closed console left behind.
        old.insertAtCursor("late words")

        assertEquals("", store.kept("1|a"))
        assertEquals("", store.draft("1|a").text)
    }
}
