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

package org.connectbot.ui.screens.console

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What happened that may call for the soft keyboard. */
enum class KeyboardEvent {
    /** A console's session opened. */
    OPEN,

    /** A tap on the terminal. */
    TERMINAL_TAP,

    /** The biometric prompt finished; the console is about to be typed into. */
    BIOMETRIC_DONE,
}

/** The facts the decision reads. [agentConsole]: a Herdr session or a core watch, opened to watch. */
data class KeyboardSetting(val hardwareKeyboard: Boolean, val agentConsole: Boolean)

/**
 * Whether [event] should raise the soft keyboard. A hardware keyboard never does. A console
 * opened to watch an agent opens with the keyboard down (K-20); a plain shell keeps raising it on
 * open. A tap and the end of a biometric prompt raise it everywhere.
 */
fun raisesKeyboard(event: KeyboardEvent, setting: KeyboardSetting): Boolean {
    if (setting.hardwareKeyboard) return false
    return when (event) {
        KeyboardEvent.OPEN -> !setting.agentConsole
        KeyboardEvent.TERMINAL_TAP, KeyboardEvent.BIOMETRIC_DONE -> true
    }
}

/**
 * Whether the console asks the terminal view for the soft keyboard. [wanted] is the level the
 * terminal view reads; [requests] counts each ask, so a second tap tries again even when the
 * first one never brought the keyboard up. The compose field raises the keyboard on its own, and
 * the Compose key and slash insert ask the keyboard controller directly.
 */
class SoftKeyboardAsk {
    var wanted by mutableStateOf(false)
        private set

    var requests by mutableIntStateOf(0)
        private set

    fun on(event: KeyboardEvent, setting: KeyboardSetting) {
        if (!raisesKeyboard(event, setting)) return
        wanted = true
        requests++
    }

    fun onKeyboardDismissed() {
        wanted = false
    }
}
