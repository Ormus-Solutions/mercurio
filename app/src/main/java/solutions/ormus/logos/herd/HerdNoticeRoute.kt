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

import org.connectbot.data.entity.Host

/**
 * Which console posts a Herd notice. Several host rows can name one machine (`Sun`, `Sun (copy)`),
 * and every console of a machine reads the same default session, so one agent would otherwise
 * notify once per console: the first open console of the machine posts the default session's
 * notices, and the first open console attached to a named session posts that session's.
 */
object HerdNoticeRoute {
    /** Whether two host rows reach the same account on the same machine. */
    fun sameMachine(a: Host, b: Host): Boolean = a.username == b.username && a.hostname.equals(b.hostname, ignoreCase = true) && a.port == b.port

    /**
     * The console among [consoles] that posts a notice about [session] raised by [from]: the one on
     * screen when one is (its rules know which pane he looks at), else the first open one of the machine
     * on that session, else [from] itself (it moved sessions while the notice was in flight). [host],
     * [attached], [open] and [onScreen] read a console's host row, the Herdr session it attached, whether
     * its SSH session is up and whether he looks at it.
     */
    fun <T> poster(
        consoles: List<T>,
        from: T,
        session: String,
        host: (T) -> Host,
        attached: (T) -> String,
        open: (T) -> Boolean,
        onScreen: (T) -> Boolean,
    ): T {
        val there = consoles.filter { open(it) && sameMachine(host(it), host(from)) && (HerdrSessions.isDefault(session) || attached(it) == session) }
        return there.firstOrNull(onScreen) ?: there.firstOrNull() ?: from
    }
}
