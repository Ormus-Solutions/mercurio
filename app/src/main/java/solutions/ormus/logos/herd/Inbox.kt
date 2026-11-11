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

import java.util.Locale

/**
 * One line of the Inbox: an [agent] of either engine, with the host it lives on. [agent]'s address
 * carries the host id and the engine, so the same pane id on two hosts, or in both engines, stays two rows. [console] is the Herdr
 * console a tap opens on the host (a TUI agent's session); null opens the host's own console,
 * which is all a herdr-core agent has until it gets its own view.
 */
data class InboxRow(
    val agent: Agent,
    val hostId: Long,
    val host: String,
    val console: HerdrConsole? = null,
) {
    /** The second line: the agent's title (its task) when it has one, else its tab. */
    val detail: String get() = agent.title?.takeIf { it.isNotBlank() } ?: agent.tab
}

/** One section of the Inbox: [rows] in order, [shown] of them while folded, [hidden] the rest. */
data class InboxSection(
    val section: Inbox.Section,
    val rows: List<InboxRow>,
    val shown: List<InboxRow>,
) {
    val hidden: Int get() = rows.size - shown.size

    /** Whether the section has more rows than the fold, so it can be opened and closed. */
    val canFold: Boolean get() = rows.size > Inbox.FOLD
}

/** The Inbox: every agent of every host in one list, sorted by what it needs. Pure; the screen draws it. */
object Inbox {
    /** A section folds after this many rows. */
    const val FOLD = 3

    /** How many identity accents the space colour picks from. */
    const val ACCENT_COUNT = 6

    private const val GROUP_SEP = " · "

    /** The Inbox's sections, in the order they show. */
    enum class Section {
        NEEDS_YOU,
        DONE,
        WORKING,
        IDLE,
        UNKNOWN,
        ;

        companion object {
            fun of(state: AgentState): Section = when (state) {
                is AgentState.NeedsYou -> NEEDS_YOU
                AgentState.Done -> DONE
                AgentState.Working -> WORKING
                AgentState.Idle -> IDLE
                AgentState.Unknown -> UNKNOWN
            }
        }
    }

    /** What one host's herdr-core read gave: its rows, and a line for the user when it could not be read. */
    data class CoreRows(val rows: List<InboxRow>, val note: String?)

    /** [agent] as a row on host [hostId]; its address is made unique across hosts. */
    fun row(hostId: Long, host: String, agent: Agent, console: HerdrConsole? = null) = InboxRow(
        agent = agent.copy(address = "$hostId/${agent.engine.name.lowercase(Locale.ROOT)}/${agent.address}"),
        hostId = hostId,
        host = host,
        console = console,
    )

    /** The TUI's [cards] of one host: a tap opens the card's session on that host. */
    fun fromCards(hostId: Long, host: String, cards: List<BoardCard>): List<InboxRow> = cards.map { card ->
        row(hostId, host, card.toAgent().copy(machine = host), HerdrConsole(card.session, workspaceLabel = card.workspaceLabel))
    }

    /** One host's herdr-core [result]: its agents as rows; a note only when the core could not be read. */
    fun fromCore(hostId: Long, host: String, result: CoreSource.Result): CoreRows = when (result) {
        CoreSource.Result.Off -> CoreRows(emptyList(), null)
        is CoreSource.Result.Agents -> CoreRows(result.agents.map { row(hostId, host, it) }, null)
        is CoreSource.Result.Unavailable -> CoreRows(emptyList(), "$host: ${result.reason}")
    }

    /**
     * The sections for [rows] narrowed by [query], each ranked as [Agent.rank] does; a section
     * is folded to [FOLD] rows unless it is in [expanded]. Sections with no row are left out.
     */
    fun sections(rows: List<InboxRow>, query: String, expanded: Set<Section>): List<InboxSection> {
        val byAddress = rows.associateBy { it.agent.address }
        val ranked = Agent.rank(rows.filter { matches(it, query) }.map { it.agent }).mapNotNull { byAddress[it.address] }
        val grouped = ranked.groupBy { Section.of(it.agent.state) }
        return Section.entries.mapNotNull { section ->
            val all = grouped[section] ?: return@mapNotNull null
            InboxSection(section, all, if (section in expanded) all else all.take(FOLD))
        }
    }

    /** Whether [row] holds every word of [query] in its space, tab, title, kind or host, case aside. */
    fun matches(row: InboxRow, query: String): Boolean {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val a = row.agent
        val hay = listOfNotNull(a.space, a.tab, a.title, a.kind, row.host).joinToString(" ").lowercase(Locale.ROOT)
        return words.all { it in hay }
    }

    /** The group of a space: the part before " · ", or the whole name. */
    fun group(space: String): String = space.substringBefore(GROUP_SEP)

    /** The tile letter: the group's first character, upper case. */
    fun letter(space: String): String = group(space).firstOrNull { !it.isWhitespace() }?.uppercase(Locale.ROOT) ?: "?"

    /**
     * Which of the [ACCENT_COUNT] accents a group takes. Case-insensitive, and fixed by the name
     * alone (String.hashCode is specified), so a group keeps its colour across runs and phones.
     */
    fun accentIndex(group: String): Int = Math.floorMod(group.trim().lowercase(Locale.ROOT).hashCode(), ACCENT_COUNT)

    /** How long the row has been in its state ("12m"); null when the time is unknown. */
    fun age(row: InboxRow, now: Long): String? = row.agent.since?.let { AgentsBoard.age(now - it) }
}
