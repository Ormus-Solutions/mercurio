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

/**
 * Notices and Approve or Deny for herdr-core agents, on the machinery the TUI's agents use.
 *
 * A core [Agent] is shown to [HerdAttention] as a [HerdrAgentInfo] in a one-workspace-per-space
 * snapshot, so the hold, the repeat window and the Finished rule are the same code. The notice
 * carries [SESSION] where a TUI notice carries a Herdr session name, and that is how the answer
 * is routed to `hc` instead of `herdr`.
 */
object CoreNotices {
    /**
     * The session a core notice is in. Not a name Herdr could give a session ([HerdrSessions.isName]),
     * so it never meets a real one.
     */
    const val SESSION = "@core"

    /** Agent kinds whose permission prompt Enter and Esc answer. INFERRED from the TUI path ([HerdApproval.PROVEN_KINDS]): same agent, same prompt; not measured on herdr-core. */
    val PROVEN_KINDS = setOf("claude", "claude-code")

    // herdr-core terminal addresses ("t15", "box/t7"); anything else is refused before it reaches a shell.
    // The first character is a letter or digit, so an address is never read as an option of `hc`.
    private val ADDRESS = Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]*")

    /** Whether [address] is one `hc` could have given a terminal. The host's answer is data: check before use. */
    fun isAddress(address: String): Boolean = ADDRESS.matches(address)

    fun isCore(session: String): Boolean = session == SESSION

    /**
     * [agents] as the snapshot [HerdAttention] reads. A helper (a terminal with a parent) is left out,
     * so it never notifies: not for a block, not for finishing.
     */
    fun snapshot(agents: List<Agent>): HerdrSessionSnapshot {
        val own = agents.filter { it.parent == null && isAddress(it.address) }
        val spaces = own.map { it.space }.distinct()
        val tabs = own.map { it.space to it.tab }.distinct()
        return HerdrSessionSnapshot(
            workspaces = spaces.map { HerdrWorkspaceInfo(workspaceId = it, label = it) },
            tabs = tabs.map { (space, tab) -> HerdrTabInfo(tabId = tabId(space, tab), workspaceId = space, label = tab) },
            agents = own.map { a ->
                HerdrAgentInfo(
                    paneId = a.address,
                    workspaceId = a.space,
                    tabId = tabId(a.space, a.tab),
                    agent = a.kind,
                    agentStatus = statusOf(a.state),
                    terminalTitle = a.title,
                    core = CoreFacts((a.state as? AgentState.NeedsYou)?.kind, a.turn),
                )
            },
        )
    }

    private fun tabId(space: String, tab: String) = "$space / $tab"

    private fun statusOf(state: AgentState): HerdrAgentStatus = when (state) {
        is AgentState.NeedsYou -> HerdrAgentStatus.BLOCKED
        AgentState.Working -> HerdrAgentStatus.WORKING
        AgentState.Done -> HerdrAgentStatus.DONE
        AgentState.Idle -> HerdrAgentStatus.IDLE
        AgentState.Unknown -> HerdrAgentStatus.UNKNOWN
    }

    /** Whether Approve and Deny may show for [agent]: a core permission block of a proven kind, with a turn to guard on. */
    fun offersAnswers(agent: HerdrAgentInfo): Boolean {
        val core = agent.core ?: return false
        return agent.agentStatus == HerdrAgentStatus.BLOCKED &&
            core.needs == NeedsYouKind.PERMISSION &&
            core.turn != null &&
            agent.agent in PROVEN_KINDS
    }

    /**
     * What identifies the prompt a notice showed: a hash of the permission prompt on its screen (the question,
     * the command and the options, [HerdApproval.promptRegion]), not of the footer or status lines under it,
     * which change while the prompt stays. Null when the screen shows no such prompt. Whether `turn` changes on
     * every block (two permission asks in one turn) is not measured, so the answer also checks the screen still
     * shows the prompt he saw. The same hash serves a plain Herdr notice.
     */
    fun fingerprint(screen: String): Int? = HerdApproval.promptRegion(screen)?.hashCode()

    /**
     * Whether an answer to a notice raised at [turn] showing the prompt [prompt] may go out, judged on
     * [fresh], the terminal re-read just now, and its [screen]: null when it may, else why not. A different
     * turn, or a screen other than the one the notice showed, means the agent moved on and may be asking
     * something else. A notice that carried no [prompt] cannot prove it, so its answer is refused as
     * [HerdAnswerOutcome.Unproven] (it is not offered either, see `ConnectionNotifier`).
     */
    fun guard(fresh: Agent?, turn: Int?, screen: String?, prompt: Int?): HerdAnswerOutcome? {
        val state = fresh?.state
        return when {
            fresh == null || state !is AgentState.NeedsYou || state.kind != NeedsYouKind.PERMISSION || fresh.kind !in PROVEN_KINDS -> HerdAnswerOutcome.NotBlocked
            turn == null || fresh.turn != turn -> HerdAnswerOutcome.PromptChanged
            prompt == null -> HerdAnswerOutcome.Unproven
            screen == null || fingerprint(screen) != prompt -> HerdAnswerOutcome.PromptChanged
            else -> null
        }
    }

    /** `hc get <address> --json` through the login shell: ssh exec has no `~/.local/bin` on its PATH. */
    fun getCommand(address: String): String = HerdrApi.loginShell("hc get ${checked(address)} --json")

    /** `hc keys <address> <key>` through the login shell: Enter approves, Esc denies (inferred, see [PROVEN_KINDS]). */
    fun keysCommand(address: String, answer: HerdAnswer): String = HerdrApi.loginShell("hc keys ${checked(address)} ${answer.key}")

    private fun checked(address: String): String {
        require(ADDRESS.matches(address)) { "Unexpected herdr-core address: $address" }
        return address
    }

    /** The `screen` of an `hc get --json` answer, or null when there is none. */
    fun screenOf(getOutput: String): String? {
        val start = getOutput.indexOf('{')
        if (start < 0) return null
        val root = try {
            Json.parseToJsonElement(getOutput.substring(start))
        } catch (_: SerializationException) {
            return null
        }
        return ((root as? JsonObject)?.get("screen") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
