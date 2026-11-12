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
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdrConsole
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The unsent compose text of each terminal, kept outside composition and outside the bridge. Leaving
 * the console (Back, Settings, another session) destroys both, so the text lives here, keyed by the
 * terminal ([key]) and not by the bridge object a new visit creates. Held in memory only: a process
 * death loses it. Text is never logged and never written to disk.
 */
@Singleton
class ComposeDraftStore @Inject constructor() {
    private val texts = ConcurrentHashMap<String, String>()

    // The newest draft handed out per key: a draft a later visit replaced no longer writes (late voice text, a screenshot path).
    private val owners = ConcurrentHashMap<String, DictationDraft>()

    /** The text kept for [key] now; empty when none. */
    fun kept(key: String): String = texts[key].orEmpty()

    /**
     * The draft for [key], holding the text kept for that terminal with the cursor at its end.
     * Every edit is written back; an emptied draft (sent or cleared) is forgotten. A draft handed
     * out earlier for the same key stops writing.
     */
    fun draft(key: String): DictationDraft {
        val kept = kept(key)
        lateinit var created: DictationDraft
        created = DictationDraft(TextFieldValue(kept, TextRange(kept.length))) { text ->
            if (owners[key] === created) {
                if (text.isEmpty()) texts.remove(key) else texts[key] = text
            }
        }
        owners[key] = created
        return created
    }

    companion object {
        /**
         * Which terminal a console shows, in a form that survives the bridge being rebuilt: the host
         * id plus the core terminal address, or the Herdr console's session and workspace, or just the
         * host for its own first console. The console id is left out on purpose: it changes every
         * time a console is rebuilt, which is the case this key exists for. The manager opens one
         * console per host and [HerdrConsole], so no two live consoles share a key. Parts are
         * length-prefixed, so no text in a name can make two keys meet, and an id and a label with the
         * same text stay apart. The Herdr TUI switches workspace inside one console: that console
         * keeps one draft across its workspaces.
         */
        fun key(hostId: Long, watch: CoreWatchTarget?, console: HerdrConsole?): String = when {
            watch != null -> "$hostId|watch|${part(watch.address)}"

            console != null -> {
                val workspace = console.workspaceId?.let { "id:" + part(it) } ?: ("label:" + part(console.workspaceLabel.orEmpty()))
                "$hostId|console|${part(console.session)}|$workspace"
            }

            else -> "$hostId|host"
        }

        private fun part(text: String) = "${text.length}:$text"
    }
}
