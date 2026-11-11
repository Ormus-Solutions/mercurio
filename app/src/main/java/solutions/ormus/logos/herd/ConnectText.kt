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

package solutions.ormus.logos.herd

/**
 * The sign-in text of a console. A console prints a line for each step of its login
 * ("Verified host key", "Trying to authenticate"); the Herdr app opens a terminal with nothing
 * before it. A [quiet] console (one that shows a core terminal) holds those lines, and shows them
 * only when the user must read them: a prompt asks something, or the login fails ([reveal]).
 * Once the session opens ([opened]) the held lines are dropped. Every line also goes to the
 * connection log as before, so "Copy details" still has them.
 */
class ConnectText(private val quiet: Boolean) {
    private val held = mutableListOf<String>()
    private var showing = !quiet

    /** A login starts: a quiet console holds its text again. */
    @Synchronized
    fun restart() {
        held.clear()
        showing = !quiet
    }

    /** The lines to write on the screen now for [line]: the line itself when the text shows, none while it is held. */
    @Synchronized
    fun line(line: String): List<String> = if (showing) {
        listOf(line)
    } else {
        held += line
        emptyList()
    }

    /** The user must read the text: the held lines, once, and every later line shows at once. */
    @Synchronized
    fun reveal(): List<String> {
        if (showing) return emptyList()
        showing = true
        return held.toList().also { held.clear() }
    }

    /** The session opened: the held lines are dropped, and a later [reveal] shows nothing. */
    @Synchronized
    fun opened() {
        held.clear()
        showing = true
    }
}
