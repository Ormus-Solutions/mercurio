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
 * One line of the Inbox: an [agent] of either engine, with the host it lives on. [agent]'s address is
 * exactly what the engine gave (a core terminal is watched and written by it). [key] is the row's own
 * identity, with the host id and the engine, so the same pane id on two hosts, or in both engines, stays two rows. [console] is the Herdr
 * console a tap opens on the host (a TUI agent's session); null opens the host's own console,
 * which is all a herdr-core agent has until it gets its own view. [helperRows] are the helper terminals
 * ([Agent.parent]) [Inbox.sections] folded under this row, ranked, each a row of its own to open.
 */
data class InboxRow(
    val agent: Agent,
    val hostId: Long,
    val host: String,
    val console: HerdrConsole? = null,
    val key: String = Inbox.keyOf(hostId, agent),
    val helperRows: List<InboxRow> = emptyList(),
) {
    /** How many helpers are folded under this row. */
    val helpers: Int get() = helperRows.size

    /** Whether a folded helper needs the user. */
    val helpersNeedYou: Boolean get() = helperRows.any { it.agent.state is AgentState.NeedsYou }

    /** Whether a folded helper has output the user has not seen. */
    val helpersUnread: Boolean get() = helperRows.any { it.agent.unread == true }

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

/** What a swipe on a herdr-core row does; each is one `hc` write ([CoreSource.write]). */
enum class RowAction { MARK_READ, PIN, UNPIN, INTERRUPT }

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

    /** The key of [agent]'s row on host [hostId]: unique across hosts and engines. */
    fun keyOf(hostId: Long, agent: Agent): String = "$hostId/${agent.engine.name.lowercase(Locale.ROOT)}/${agent.address}"

    /** [agent] as a row on host [hostId]; the agent is left as it is, the row's [InboxRow.key] is what is unique. */
    fun row(hostId: Long, host: String, agent: Agent, console: HerdrConsole? = null) = InboxRow(agent, hostId, host, console)

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
     * With no query a helper whose parent is in [rows] is not a row: its parent counts it
     * ([InboxRow.helpers]) and, if the helper needs the user, sits in Needs you. A search lists the helpers it hits.
     */
    fun sections(rows: List<InboxRow>, query: String, expanded: Set<Section>): List<InboxSection> {
        val visible = if (query.isBlank()) foldHelpers(rows) else rows.filter { matches(it, query) }
        val ranked = visible.sortedWith(compareBy<InboxRow, Agent>(Agent.RANKING) { effective(it) }.thenBy { it.key })
        val grouped = ranked.groupBy { Section.of(effective(it).state) }
        return Section.entries.mapNotNull { section ->
            val all = grouped[section] ?: return@mapNotNull null
            InboxSection(section, all, if (section in expanded) all else all.take(FOLD))
        }
    }

    /**
     * The agent the row is ranked, sectioned and aged by: a parent whose helper needs the user reads as
     * needing the user, with the state (so its kind) and the wait of the helper that has waited longest.
     */
    fun effective(row: InboxRow): Agent {
        if (row.agent.state is AgentState.NeedsYou) return row.agent
        val waiting = row.helperRows.map { it.agent }.filter { it.state is AgentState.NeedsYou }
            .minWithOrNull(compareBy<Agent> { it.since ?: Long.MAX_VALUE }.thenBy { it.address }) ?: return row.agent
        return row.agent.copy(state = waiting.state, since = waiting.since)
    }

    /**
     * [rows] without the helpers whose parent (on the same host) is a row: the top parent counts them.
     * A parent loop leaves its rows as they are, so no row is lost.
     */
    private fun foldHelpers(rows: List<InboxRow>): List<InboxRow> {
        val byAddress = rows.associateBy { Triple(it.hostId, it.agent.engine, it.agent.address) }
        fun top(row: InboxRow): InboxRow? {
            val seen = HashSet<String>()
            var cur = row
            while (true) {
                if (!seen.add(cur.key)) return null
                cur = cur.agent.parent?.let { byAddress[Triple(cur.hostId, cur.agent.engine, it)] } ?: return cur
            }
        }
        val tops = rows.associate { it.key to (top(it) ?: it) }
        val folded = HashMap<String, MutableList<InboxRow>>()
        rows.forEach { row ->
            val parent = tops.getValue(row.key)
            if (parent.key != row.key) folded.getOrPut(parent.key) { ArrayList() } += row
        }
        val order = compareBy<InboxRow, Agent>(Agent.RANKING) { it.agent }.thenBy { it.key }
        return rows.filter { tops.getValue(it.key).key == it.key }
            .map { row -> folded[row.key]?.let { row.copy(helperRows = it.sortedWith(order)) } ?: row }
    }

    /** Whether [row] holds every word of [query] in its space, tab, title, kind or host, case aside. */
    fun matches(row: InboxRow, query: String): Boolean {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val a = row.agent
        val hay = listOfNotNull(a.space, a.tab, a.title, a.kind, row.host).joinToString(" ").lowercase(Locale.ROOT)
        return words.all { it in hay }
    }

    /** What a swipe right does to [agent]: mark an unread one read, else toggle its pin. Null for a TUI agent, which has no labels. */
    fun rightSwipe(agent: Agent): RowAction? = when {
        agent.engine != AgentEngine.CORE -> null
        agent.unread == true -> RowAction.MARK_READ
        agent.pinned -> RowAction.UNPIN
        else -> RowAction.PIN
    }

    /** Whether a swipe left may send Esc to [agent]: herdr-core terminals only. */
    fun canInterrupt(agent: Agent): Boolean = agent.engine == AgentEngine.CORE

    /** [agent] as it will read once [action] has been written, so the row moves before the next poll. */
    fun applied(agent: Agent, action: RowAction): Agent = when (action) {
        RowAction.MARK_READ -> agent.copy(state = if (agent.state == AgentState.Done) AgentState.Idle else agent.state, unread = false)
        RowAction.PIN -> agent.copy(pinned = true)
        RowAction.UNPIN -> agent.copy(pinned = false)
        RowAction.INTERRUPT -> agent
    }

    /** [rows] after [action] was written on [acted]: only that row, and only if it is still at the turn it was acted on. */
    fun patched(rows: List<InboxRow>, acted: Agent, action: RowAction): List<InboxRow> = rows.map {
        if (it.agent.address == acted.address && it.agent.turn == acted.turn) it.copy(agent = applied(it.agent, action)) else it
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
    fun age(row: InboxRow, now: Long): String? = effective(row).since?.let { AgentsBoard.age(now - it) }
}
