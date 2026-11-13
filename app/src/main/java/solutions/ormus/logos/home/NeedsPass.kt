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

package solutions.ormus.logos.home

/**
 * One pass over the open asks, as the store's own cards page walks them: the store's order
 * (oldest first), one on screen, Later sends it to the end of the pass, and a read that finds
 * an ask closed elsewhere takes it out without moving the one on screen. The pass is over once
 * every ask was seen once. An ask that takes several answers (`multi`) is not in the pass: a tap
 * would answer it with one label and close it, so it is only counted, for the view to point at the page.
 */
data class NeedsPass(
    /** The asks still to show; the first is on screen. */
    val queue: List<Ask> = emptyList(),
    /** The asks put off with Later, in the order they were. */
    val later: List<Ask> = emptyList(),
    /** How many were answered in this pass. */
    val answered: Int = 0,
    /** The ids this pass is done with (answered or closed): a read that still lists one does not bring it back. */
    val done: Set<String> = emptySet(),
    /** The open asks of the last read that take several answers: left out of the pass, answered on the page. */
    val onPage: Int = 0,
) {
    /** The ask on screen; null once the pass is over. */
    val current: Ask?
        get() = queue.firstOrNull()

    /** Where the one on screen stands: k of n, n counting what was shown and what is left. */
    val position: Int
        get() = answered + closedCount + later.size + 1

    val total: Int
        get() = answered + closedCount + later.size + queue.size

    private val closedCount: Int
        get() = done.size - answered

    /** The ask on screen was answered. */
    fun answer(): NeedsPass = current?.let { copy(queue = queue.drop(1), answered = answered + 1, done = done + it.id) } ?: this

    /** The ask on screen was closed before its answer (a 409). */
    fun closed(): NeedsPass = current?.let { copy(queue = queue.drop(1), done = done + it.id) } ?: this

    /** Later: the ask on screen goes to the end of the pass; the store is not told. */
    fun putOff(): NeedsPass = current?.let { copy(queue = queue.drop(1), later = later + it) } ?: this

    /**
     * A new read of the store: an ask that closed elsewhere leaves the pass, a new one joins its
     * end, the rest keep their place. The one on screen stays as it is.
     */
    fun read(asks: List<Ask>): NeedsPass {
        val (several, single) = asks.filter { it.open && it.id !in done }.partition { it.multi }
        val open = single.associateBy { it.id }
        val onScreen = current
        val kept = queue.mapIndexedNotNull { i, ask -> if (i == 0) ask else open[ask.id] }
        val putOff = later.mapNotNull { open[it.id] }
        val known = (queue + later).map { it.id }.toSet()
        val fresh = open.values.filter { it.id !in known }
        // The one on screen stays where it is even when it closed elsewhere: its answer then reads the 409.
        return copy(queue = if (onScreen == null) fresh else kept + fresh, later = putOff, onPage = several.size)
    }
}
