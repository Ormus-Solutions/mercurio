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

/** The core terminal a console shows: its address, and the space and tab it sits in. */
data class CoreWatchTarget(val address: String, val space: String, val tab: String) {
    /** The console's name in the drawer: `space · tab`, or the space alone when the tab has no name. */
    val label: String get() = if (space.isBlank()) {
        address
    } else if (tab.isBlank()) {
        space
    } else {
        "$space · $tab"
    }
}

/**
 * Opening a herdr-core terminal in a console, as the Herdr app opens one: the console's shell types
 * `exec hc attach <t>` once. A plain attach makes the phone the active viewer, so the program is
 * reflowed to the phone's grid at the console's own font (the Herdr app's kit: "Opens a terminal and
 * makes the phone the active viewer"). The size goes back to the desktop viewer when the phone leaves,
 * or when the desktop is typed in or focused again (herdr-core: the most recently active typing viewer
 * sets the size). `exec` ends the console with the attach, so no shell is left behind; `hc` itself
 * prints `[<t> finished: ...]` when the terminal ends (measured).
 */
object CoreWatch {
    // An address as `hc ls` gives it (`t7`, `box/t7`): at most one slash, nothing a shell would read as more than a word.
    private val SAFE = Regex("""[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_.-]+)?""")

    /** Whether [address] is a plain word a shell reads as one argument. */
    fun isSafe(address: String): Boolean = SAFE.matches(address)

    /** The terminal [agent] sits in, or null when its address is not a plain word. */
    fun target(agent: Agent): CoreWatchTarget? = agent.address.takeIf { SAFE.matches(it) }
        ?.let { CoreWatchTarget(it, agent.space, agent.tab) }

    /** What the console's shell is given to open [address]; null for an address that is not a plain word. */
    fun attachCommand(address: String): String? = address.takeIf { SAFE.matches(it) }?.let { "exec hc attach '$it'\n" }
}
