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
 * One pane on the Jump to list (one row per pane; a tab with one pane is one row): where it
 * lives ([session], workspace, tab) and its agent ([agent] kind and [state]; null kind for a
 * pane without one).
 */
data class JumpTarget(
    val session: String,
    val tabId: String,
    val tabLabel: String,
    val tabNumber: Int,
    val workspaceLabel: String,
    val workspaceNumber: Int,
    val agent: String?,
    val state: HerdrAgentStatus,
    /** Herdr's focused tab in its session. */
    val focused: Boolean,
    /** The pane this row stands for, so a pick lands on it; null for a tab Herdr lists no pane of. */
    val paneId: String? = null,
    /** The pane's terminal title (an agent's is what it works on); empty when Herdr has none. */
    val title: String = "",
    /** The last part of the pane's working directory; empty when Herdr has none. */
    val folder: String = "",
) {
    /** The title when the tab's label does not already carry it (Herdr's title sync puts it there). */
    val titleLine: String? get() = title.takeIf { it.isNotBlank() && !tabLabel.contains(it) }

    internal fun toAgent(address: String): Agent = Agent(
        address = address,
        engine = AgentEngine.TUI,
        space = workspaceLabel,
        tab = tabLabel,
        title = title.ifBlank { null },
        kind = agent,
        state = AgentState.of(state),
    )
}

/**
 * The Jump to list: every pane of every running Herdr session, the ones that need the user
 * first, so a pane is one search away without cycling through the tabs (Moshi's "Jump to").
 */
object HerdrJump {
    /**
     * Every pane in [snapshots] (by session name) in the Inbox's order ([Agent.rank]): blocked
     * first, then done, working, idle and the rest; inside each, the default session first, then
     * by session name, workspace number, tab number and Herdr's pane order. The TUI gives no
     * time in state, so that part of the rank does not apply here.
     */
    fun targets(snapshots: Map<String, HerdrSessionSnapshot>): List<JumpTarget> = snapshots.flatMap { (session, snapshot) ->
        snapshot.tabs.flatMap { tab ->
            val workspace = snapshot.workspaces.firstOrNull { it.workspaceId == tab.workspaceId }
            val agents = snapshot.agents.filter { it.tabId == tab.tabId }
            val panes = snapshot.panes.filter { it.tabId == tab.tabId }
            val tabState = HerdrWorkspacePicker.mostUrgent(agents.map { it.agentStatus } + tab.agentStatus)
            val tabAgent = (agents.firstOrNull { it.agentStatus == tabState } ?: agents.firstOrNull())?.agent?.ifBlank { null }
            fun target(pane: HerdrPaneInfo?, state: HerdrAgentStatus, agent: String?) = JumpTarget(
                session = session,
                tabId = tab.tabId,
                tabLabel = tab.label,
                tabNumber = tab.number,
                workspaceLabel = workspace?.label.orEmpty(),
                workspaceNumber = workspace?.number ?: 0,
                agent = agent,
                state = state,
                focused = tab.tabId == snapshot.focusedTabId,
                paneId = pane?.paneId,
                title = (pane?.terminalTitleStripped ?: pane?.terminalTitle).orEmpty(),
                folder = pane?.cwd?.trimEnd('/')?.substringAfterLast('/').orEmpty(),
            )
            when {
                panes.isEmpty() -> listOf(target(null, tabState, tabAgent))

                // One pane, the usual tab: the tab's own agent and state, as before.
                panes.size == 1 -> listOf(target(panes.single(), tabState, tabAgent))

                // A split tab: one row per pane, each with its own agent and state.
                else -> panes.map { pane ->
                    val agent = agents.firstOrNull { it.paneId == pane.paneId }
                    target(pane, agent?.agentStatus ?: pane.agentStatus, (agent?.agent ?: pane.agent)?.ifBlank { null })
                }
            }
        }
    }.let(::inboxOrder)

    // Rows in the old tie order (default session first, then session, workspace and tab number; the
    // sort is stable, so Herdr's pane order stays), then the Inbox's rank over each row as an Agent.
    // The Agent's address is the row's place in that order, so a tie keeps it and no two rows collide.
    private fun inboxOrder(rows: List<JumpTarget>): List<JumpTarget> {
        val placed = rows.sortedWith(
            compareBy<JumpTarget> { !HerdrSessions.isDefault(it.session) }
                .thenBy { it.session }
                .thenBy { it.workspaceNumber }
                .thenBy { it.tabNumber },
        )
        val agents = placed.mapIndexed { i, row -> row.toAgent("%09d".format(i)) }
        return Agent.rank(agents).map { placed[it.address.toInt()] }
    }

    /** The [targets] matching every word of [query] in their title, folder, tab, workspace, session or agent, ignoring case. */
    fun filter(targets: List<JumpTarget>, query: String): List<JumpTarget> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return targets
        return targets.filter { target ->
            val text = listOfNotNull(target.title, target.folder, target.tabLabel, target.workspaceLabel, target.session, target.agent).joinToString(" ").lowercase()
            words.all { it in text }
        }
    }

    /**
     * Whether the terminal can leave its Herdr session for another: Herdr's detach must drop
     * back to a shell, which an `exec herdr` post-login does not have (the session would end).
     */
    fun canReattach(postLogin: String?): Boolean = postLogin == null || !EXEC_HERDR.containsMatchIn(postLogin)

    /**
     * Whether [output] shows the Herdr client leaving the screen. Measured on Sun (herdr
     * 0.9.0, a client in a pty): on detach it resets its modes and leaves the alternate
     * screen (`ESC[?1049l`), then the shell prompt returns. Herdr draws its panes itself, so
     * a program inside a pane never puts that sequence on the client's own output.
     */
    fun clientLeft(output: CharSequence): Boolean = output.contains(LEAVE_ALT_SCREEN)

    // How much of the previous output to keep, so the sequence split across reads is still seen.
    internal const val CARRY_CHARS = 16

    private const val LEAVE_ALT_SCREEN = "\u001b[?1049l"

    private val EXEC_HERDR = Regex("""\bexec\s+(\S*/)?herdr\b""")
}

/**
 * A jump to another Herdr session, between the detach key and the attach: [expect] it, feed
 * the terminal's output to [onOutput], which names the session to attach once the client
 * has left ([HerdrJump.clientLeft]) and never before, so nothing is typed into a pane.
 */
class HerdrReattach {
    @Volatile
    private var next: String? = null
    private var carry = ""

    /** The detach key went out for a jump to [session]. */
    fun expect(session: String) {
        carry = ""
        next = session
    }

    /** The client did not leave in time: attach nothing later. */
    fun giveUp(session: String) {
        if (next == session) next = null
    }

    /** The session to attach now that [text] showed the client leave; null otherwise. */
    fun onOutput(text: CharSequence): String? {
        val session = next ?: return null
        val window = carry + text
        carry = window.takeLast(HerdrJump.CARRY_CHARS)
        if (!HerdrJump.clientLeft(window)) return null
        next = null
        return session
    }
}
