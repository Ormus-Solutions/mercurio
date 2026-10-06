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
class DictationDraft {
    var value by mutableStateOf(TextFieldValue())

    /** Bumped when the field should take focus and raise the keyboard (for dictation). */
    var focusRequests by mutableIntStateOf(0)
        private set

    val text: String get() = value.text

    /** Bumped to ask the compose bar to send the draft (the draft view's Send). */
    var sendRequests by mutableIntStateOf(0)
        private set

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
}
