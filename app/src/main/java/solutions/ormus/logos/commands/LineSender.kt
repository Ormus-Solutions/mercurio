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

package solutions.ormus.logos.commands

import kotlinx.coroutines.delay

/**
 * Sends a line to the PTY the way the compose bar's Send does: the text as one
 * burst, then, after a short pause, a separate carriage return, so an agent TUI
 * such as Claude Code reads a discrete Enter keypress and runs the line instead
 * of folding a newline into pasted text.
 */
object LineSender {
    /** Same pause as the compose bar's Send. */
    const val ENTER_DELAY_MS = 50L

    /** Carriage return, the byte the Enter key sends. */
    val ENTER: String = 13.toChar().toString()

    suspend fun send(inject: (String) -> Unit, line: String) {
        inject(line)
        delay(ENTER_DELAY_MS)
        inject(ENTER)
    }
}
