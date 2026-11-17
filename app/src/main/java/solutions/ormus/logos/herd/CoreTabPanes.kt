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
 * The panes of the tab a watched herdr-core terminal sits in, for a console that watches one terminal and wants to show the
 * tab around it (a small picture of the panes, the watched one marked, a tap on another opening that terminal's watch).
 * Read from the same `herdr-app` document the Spaces list reads ([CoreSpaces]); [HerdSession.tabPanes] keeps it fresh.
 * Layout only: no screen text enters the model, and of the document only the space's and tab's names and the terminals' ids do.
 */
object CoreTabPanes {
    /** One pane of the tab: its terminal (null for a mod), its place as shares of the tab, and whether it is the watched one. */
    data class Pane(val terminal: String?, val rect: CoreSpaces.BoxRect, val watched: Boolean)

    /**
     * The tab around the watched terminal: the [space] and [tab] names, and its [panes] in drawing order. No pane is [watched] when
     * the tab does not draw the watched terminal now (a helper swapped into a pane on demand, or the inactive child of a stack).
     */
    data class TabPanes(val space: String, val tab: String, val panes: List<Pane>) {
        /** The watched pane; null when the tab does not draw the watched terminal. */
        val watched: Pane? get() = panes.firstOrNull { it.watched }

        /** The watch that opens [pane]'s terminal, named as a watch of it is; null for a mod, or an address that is not a plain word. */
        fun target(pane: Pane): CoreWatchTarget? = pane.terminal?.takeIf(CoreWatch::isSafe)?.let { CoreWatchTarget(it, space, tab) }
    }

    /**
     * The tab of [spaces] around the watch [watch]. The tab that draws the terminal's address is the one, and that pane is the
     * watched one. A terminal that `hc ls` puts in a tab but the tab's layout does not draw (measured on a real host: a third of the
     * terminals, most of them helpers) is not found by address: then the tab is the one the watch names, by its space name and tab
     * name, and no pane is watched. The names are a label the Herdr app last wrote (the terminal's own tab, or its nearest drawn
     * ancestor's), so they can be stale: a terminal that no layout draws any more keeps its last place (measured on one host: 9 of
     * 90 by-name answers were an old tab or had no drawn ancestor). Limits of that name match:
     * - `hc ls` gives no tab id, so two (space, tab) pairs with the same names are ambiguous and give null.
     * - A tab the document names differently from `hc ls`, or leaves blank where `hc ls` names it, is not matched; a watch with a
     *   blank tab name names nothing (the Herdr app never writes one).
     * - A terminal of another machine (an address with a machine part, `box/t7`) is never matched by name: this host's document
     *   holds only this host's spaces, so a tab of the same names would be a local one. Such a terminal is found only if a layout
     *   of this host draws its address. Reading that machine's document is a later change.
     * - A bare address could still be given another machine's names, if a Herdr app draws a terminal of this host in a space of
     *   another machine (not proven that it allows that).
     *
     * Null when no tab draws the terminal and no single tab is named, or the named tab has no layout.
     */
    fun of(spaces: List<CoreSpaces.CoreSpace>, watch: CoreWatchTarget): TabPanes? {
        for (space in spaces) {
            for (tab in space.tabs) {
                val panes = CoreSpaces.panes(tab)
                if (panes.none { it.terminal == watch.address }) continue
                return TabPanes(space.name, tab.name, panes.map { Pane(it.terminal, it.rect, it.terminal == watch.address) })
            }
        }
        // No name match for another machine's terminal (this host's tabs are not its tabs) or for a watch with no tab name.
        if ('/' in watch.address || watch.tab.isBlank()) return null
        val named = spaces.filter { it.name == watch.space }.flatMap { space -> space.tabs.filter { it.name == watch.tab }.map { space to it } }
        val (space, tab) = named.singleOrNull() ?: return null
        val panes = CoreSpaces.panes(tab).ifEmpty { return null }
        return TabPanes(space.name, tab.name, panes.map { Pane(it.terminal, it.rect, false) })
    }
}
