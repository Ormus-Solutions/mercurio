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
 * What a host URI intent asks the console to open over the terminal of a Herdr host
 * (MainActivity's `EXTRA_HERD_VIEW`): the agent chat or the Agents board. A launcher's
 * widget sends it beside the pane and session extras a Herdr notification carries.
 */
sealed interface HerdView {
    /** The chat on [pane] in Herdr [session]; on that session's focused pane when [pane] is null. */
    data class Chat(val session: String, val pane: String?) : HerdView

    /** The Agents board: every session's agents on the host. */
    data object Board : HerdView

    companion object {
        /** The extra's value for [Chat]. */
        const val CHAT = "chat"

        /** The extra's value for [Board]. */
        const val BOARD = "board"

        /**
         * The view the extra's [value] asks for, with the intent's [pane] and [session]; null for
         * a missing or unknown value, so the console opens on the terminal as before.
         */
        fun parse(value: String?, pane: String?, session: String): HerdView? = when (value) {
            CHAT -> Chat(session, pane?.takeIf { it.isNotBlank() })
            BOARD -> Board
            else -> null
        }
    }
}
