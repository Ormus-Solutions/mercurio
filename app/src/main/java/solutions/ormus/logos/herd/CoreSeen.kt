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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.connectbot.transport.CommandOutput

/**
 * A watch console marks its terminal read: while the terminal is on screen and the app is in front,
 * `herdr.seen` is raised to the terminal's `turn`, once per turn (herdr-app docs/labels.md). Write only
 * to raise it, and also when it is missing; a turn already handled is not written again, so a "mark
 * unread" from another client is not overruled by a screen that stays on. One instance per showing:
 * a new showing writes again.
 *
 * [terminal] is a watch target's address: a terminal (`t7`) or a remote one (`box/t7`). The write goes to
 * the terminal on its host, never to a local one of the same number.
 */
class CoreSeen(private val terminal: String) {

    // The last turn this showing decided on; no turn is -1.
    private var handled = -1

    /**
     * One look at the terminal while it is on screen. [run] runs one command on the host's connection and
     * is null when it never ran; [stillShown] is asked before a write. A read that fails changes nothing: the next look tries again.
     */
    fun look(run: (String) -> CommandOutput?, stillShown: () -> Boolean = { true }) {
        val get = CoreSource.getCommand(terminal) ?: return
        val out = try {
            run(get)
        } catch (_: Exception) {
            null
        }?.takeIf { it.exitCode == 0 } ?: return
        val start = out.stdout.indexOf('{').takeIf { it >= 0 } ?: return
        val root = try {
            Json.parseToJsonElement(out.stdout.substring(start)) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return
        val turn = (root["turn"] as? JsonPrimitive)?.intOrNull ?: return
        // A turn that went back (the core restarted, an id was reused) starts over.
        if (turn < handled) handled = -1
        if (turn <= handled) return
        val mark = ((root["labels"] as? JsonObject)?.get(LABEL) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        if (mark == null || mark < turn) {
            // The read can take a while on a slow link: a showing that ended meanwhile writes nothing.
            if (!stillShown() || !CoreSource.write(RowAction.MARK_READ, terminal, turn, run)) return
        }
        handled = turn
    }

    companion object {
        /** How long a showing waits between two looks. */
        const val PERIOD_MS = 5_000L

        private const val LABEL = "herdr.seen"
    }
}
