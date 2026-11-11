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

import org.connectbot.service.TerminalBridge
import org.connectbot.ui.components.KeyBarCatalog
import org.connectbot.ui.components.KeyBarLayout
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdrKey
import solutions.ormus.logos.herd.HerdrKeys

/**
 * What a console screen opened for a core terminal (a watch target) shows and sends: that terminal
 * alone, a header that names it, and a key bar without the Herdr TUI keys.
 */
internal object CoreView {
    /** The consoles the screen pages across: the watch console of [watch] on host [hostId], and no other. */
    fun consolesOf(bridges: List<TerminalBridge>, hostId: Long, watch: CoreWatchTarget): List<TerminalBridge> {
        val same = bridges.filter { it.host.id == hostId && it.watch?.address == watch.address }
        // An ended console (its overlay) still listed beside the fresh one that replaces it is not shown.
        return same.filter { !it.isDisconnected }.ifEmpty { same }
    }

    // How many screens show each core terminal now: a screen going away closes the console only when it is the last.
    private val viewers = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun viewerKey(hostId: Long, watch: CoreWatchTarget) = "$hostId/${watch.address}"

    /** A screen starts showing [watch] on host [hostId]. */
    fun viewerJoined(hostId: Long, watch: CoreWatchTarget) {
        viewers.merge(viewerKey(hostId, watch), 1, Int::plus)
    }

    /** A screen stops showing it; true when no other screen still does. */
    fun viewerLeft(hostId: Long, watch: CoreWatchTarget): Boolean = viewers.compute(viewerKey(hostId, watch)) { _, n -> if (n == null || n <= 1) null else n - 1 } == null

    /** Test hook: forget every screen. */
    fun resetViewers() = viewers.clear()

    /** The header's title: the tab's name, or the space when the tab has none. */
    fun title(watch: CoreWatchTarget): String = watch.tab.ifBlank { watch.space.ifBlank { watch.address } }

    /** The header's muted second line: `space · host`, or the host alone when the terminal has no space. */
    fun subtitle(watch: CoreWatchTarget, hostNickname: String): String = if (watch.space.isBlank()) hostNickname else "${watch.space} \u00b7 $hostNickname"

    /**
     * [layout] without the keys that write the Herdr TUI prefix or any Herdr binding: in a core terminal
     * those bytes would be typed into the program. A page left empty stays, so the bar keeps its pages.
     */
    fun keyBar(layout: KeyBarLayout, keys: HerdrKeys): KeyBarLayout {
        val herdr = HerdrKey.entries.mapNotNull { keys.bytes(it) }.toSet()
        val prefix = keys.prefix.bytes
        fun writesHerdr(id: String): Boolean {
            val sequence = KeyBarCatalog.byId(id)?.sequence.orEmpty()
            return sequence.isNotEmpty() && (sequence in herdr || (prefix.isNotEmpty() && sequence.startsWith(prefix)))
        }
        return KeyBarLayout(layout.pages.map { page -> page.filterNot(::writesHerdr) })
    }
}
