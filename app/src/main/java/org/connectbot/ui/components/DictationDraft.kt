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

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The compose bar's dictation field, held outside the bar so other controls
 * (the slash menu) can put text in it. The bar owns editing; [insertCommand]
 * is the one way in from outside.
 */
@Stable
class DictationDraft(
    initial: TextFieldValue = TextFieldValue(),
    // Told the new text whenever it changes: ComposeDraftStore keeps it for the terminal.
    private val onTextChange: (String) -> Unit = {},
) {
    private var held by mutableStateOf(initial)

    var value: TextFieldValue
        get() = held
        set(new) {
            val changed = new.text != held.text
            held = new
            if (changed) onTextChange(new.text)
        }

    /** Bumped when the field should take focus and raise the keyboard (for dictation). */
    var focusRequests by mutableIntStateOf(0)
        private set

    val text: String get() = value.text

    /** Bumped to ask the compose bar to send the draft (the draft view's Send). */
    var sendRequests by mutableIntStateOf(0)
        private set

    /** Ask for focus and the keyboard on the field, with the text as it is (the key bar's Compose key). */
    fun requestFocus() {
        focusRequests++
    }

    fun requestSend() {
        sendRequests++
    }

    /**
     * Put [command] ("/name ") at the start of the field, keep what was there
     * after it (replacing a slash command already at the start), place the
     * cursor at the end, and ask for focus so the rest can be dictated before Send.
     */
    fun insertCommand(command: String) {
        var rest = value.text.trimStart()
        if (rest.startsWith("/")) rest = rest.substringAfter(' ', "").trimStart()
        val text = if (rest.isEmpty()) command else command + rest
        value = TextFieldValue(text, TextRange(text.length))
        focusRequests++
    }

    /**
     * Put dictated [text] at the cursor (in place of a selection), with one space where it would
     * otherwise touch a word, and leave the cursor after it. The keyboard is not raised and nothing is sent.
     */
    fun insertAtCursor(text: String) {
        val dictated = text.trim()
        if (dictated.isEmpty()) return
        val current = value.text
        val start = value.selection.min.coerceIn(0, current.length)
        val end = value.selection.max.coerceIn(start, current.length)
        val before = current.substring(0, start)
        val after = current.substring(end)
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val trail = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
        val inserted = lead + dictated + trail
        // The cursor goes after the words, before the space that keeps them off the next word.
        val cursor = before.length + lead.length + dictated.length
        value = TextFieldValue(before + inserted + after, TextRange(cursor))
    }
}
