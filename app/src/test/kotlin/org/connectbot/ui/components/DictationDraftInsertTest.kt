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
import org.junit.Test

class DictationDraftInsertTest {
    private fun draft(text: String, cursor: Int, end: Int = cursor) = DictationDraft().also { it.value = TextFieldValue(text, TextRange(cursor, end)) }

    @Test
    fun intoAnEmptyField_isJustTheText_cursorAtTheEnd() {
        val d = DictationDraft()
        d.insertAtCursor("hello world")
        assertEquals("hello world", d.text)
        assertEquals(TextRange(11), d.value.selection)
    }

    @Test
    fun atTheEnd_afterAWord_getsOneSpaceBetween() {
        val d = draft("fix the", 7)
        d.insertAtCursor("login bug")
        assertEquals("fix the login bug", d.text)
        assertEquals(TextRange(17), d.value.selection)
    }

    @Test
    fun atTheEnd_afterASpace_getsNoSecondSpace() {
        val d = draft("fix the ", 8)
        d.insertAtCursor("login bug")
        assertEquals("fix the login bug", d.text)
    }

    @Test
    fun inTheMiddle_keepsBothSidesApart_andTheCursorAfterTheInsert() {
        val d = draft("fix  bug", 4)
        d.insertAtCursor("the login")
        assertEquals("fix the login bug", d.text)
        assertEquals(TextRange(13), d.value.selection)
    }

    @Test
    fun betweenTwoWordsWithNoSpace_getsASpaceEachSide() {
        val d = draft("ab", 1)
        d.insertAtCursor("X")
        assertEquals("a X b", d.text)
        assertEquals(TextRange(3), d.value.selection)
    }

    @Test
    fun aSelection_isReplaced() {
        val d = draft("fix the old bug", 8, 11)
        d.insertAtCursor("new")
        assertEquals("fix the new bug", d.text)
    }

    @Test
    fun afterANewLine_getsNoLeadingSpace() {
        val d = draft("one\n", 4)
        d.insertAtCursor("two")
        assertEquals("one\ntwo", d.text)
    }

    @Test
    fun blankText_changesNothing() {
        val d = draft("keep", 2)
        d.insertAtCursor("   ")
        assertEquals("keep", d.text)
        assertEquals(TextRange(2), d.value.selection)
    }

    @Test
    fun doesNotAskForTheKeyboard() {
        val d = DictationDraft()
        d.insertAtCursor("hi")
        assertEquals(0, d.focusRequests)
    }
}
