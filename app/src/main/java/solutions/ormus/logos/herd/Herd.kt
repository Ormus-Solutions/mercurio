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
 * Mercurio's glue around the Herdr package copied from Termish: which sessions
 * run Herdr, the login-shell commands the app sends, the agents ordered by who
 * needs you, and what has focus.
 */
object Herd {
    /** Whether the host's post-login command lands the session in Herdr. */
    fun runsHerdr(host: Host): Boolean = host.postLogin?.contains("herdr", ignoreCase = true) == true

    /** Prints the live workspace/tab/pane/agent state as JSON. */
    val snapshotCommand: String = HerdrApi.loginShell(HerdrApi.SNAPSHOT_CMD)

    /** Prints a pane's recent output as plain text with soft wraps joined. */
    fun readCommand(paneId: String, lines: Int = HerdrApi.READ_LINES): String = HerdrApi.loginShell(HerdrApi.paneReadCmd(paneId, lines))

    /**
     * Agents ordered by who needs you: blocked, done, working, idle, then
     * unknown, and inside each state the newest `state_change_seq` first.
     */
    fun order(agents: List<HerdrAgentInfo>): List<HerdrAgentInfo> = agents.sortedWith(
        compareBy<HerdrAgentInfo> { priority(it.agentStatus) }.thenByDescending { it.stateChangeSeq },
    )

    private fun priority(status: HerdrAgentStatus): Int = when (status) {
        HerdrAgentStatus.BLOCKED -> 0
        HerdrAgentStatus.DONE -> 1
        HerdrAgentStatus.WORKING -> 2
        HerdrAgentStatus.IDLE -> 3
        HerdrAgentStatus.UNKNOWN -> 4
    }
}

/** The focused pane and, when it runs an agent, where it lives. */
data class HerdFocus(
    val paneId: String,
    val agent: String? = null,
    val paneTitle: String? = null,
    val workspaceLabel: String? = null,
    val tabLabel: String? = null,
)

/** What has focus; null when nothing does. */
fun HerdrSessionSnapshot.focus(): HerdFocus? {
    val paneId = focusedPaneId?.ifBlank { null } ?: return null
    val agent = agents.firstOrNull { it.paneId == paneId }
    val workspaceId = agent?.workspaceId?.ifBlank { null } ?: focusedWorkspaceId
    val tabId = agent?.tabId?.ifBlank { null } ?: focusedTabId
    return HerdFocus(
        paneId = paneId,
        agent = agent?.agent?.ifBlank { null },
        paneTitle = agent?.title(),
        workspaceLabel = workspaceLabel(workspaceId),
        tabLabel = tabLabel(tabId),
    )
}

/** A workspace's label, or null when it is unknown or blank. */
fun HerdrSessionSnapshot.workspaceLabel(workspaceId: String?): String? = workspaces.firstOrNull { it.workspaceId == workspaceId }?.label?.ifBlank { null }

/** A tab's label, or null when it is unknown or blank. */
fun HerdrSessionSnapshot.tabLabel(tabId: String?): String? = tabs.firstOrNull { it.tabId == tabId }?.label?.ifBlank { null }

/** The terminal title Herdr shows for an agent, without decorations. */
fun HerdrAgentInfo.title(): String? = (terminalTitleStripped ?: terminalTitle)?.ifBlank { null }
