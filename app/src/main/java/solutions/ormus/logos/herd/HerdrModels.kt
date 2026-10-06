/*
 * Copied from Termish, https://github.com/farlume/termish (commit ee9e460),
 * composeApp/src/commonMain/kotlin/dev/termish/herdr/HerdrModels.kt.
 * Mercurio changes: focused ids, tab and workspace number/status fields added;
 * comments translated to English.
 *
 * MIT License
 *
 * Copyright (c) 2026 Termish Project Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package solutions.ormus.logos.herd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Herdr (the runtime your coding agents live on) agent state model.
 *
 * This file only describes the minimal field set the poller needs (a subset of
 * `herdr api snapshot` output). Parsing uses ignoreUnknownKeys so a Herdr
 * upgrade that adds fields does not break the client. The full protocol lives
 * in the Herdr repo, docs/socket-api.mdx (session.snapshot / events.subscribe).
 *
 * Herdr's semantic agent state (pane.agent_status).
 */

@Serializable
enum class HerdrAgentStatus {
    @SerialName("idle")
    IDLE,

    @SerialName("working")
    WORKING,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("done")
    DONE,

    @SerialName("unknown")
    UNKNOWN,
}

/** One pane (terminal cell) in the session snapshot. */
@Serializable
data class HerdrPaneInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    /** Detected agent name (pi / codex / claude ...); null for a non-agent pane. */
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.UNKNOWN,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    val cwd: String? = null,
    val focused: Boolean = false,
    val revision: Long = 0,
    // Mercurio: how far Herdr itself can scroll this pane back (copy mode).
    val scroll: HerdrPaneScroll? = null,
    // Mercurio: the agent session Herdr tracks for the pane (herdr 0.9 puts it here).
    @SerialName("agent_session") val agentSession: HerdrAgentSession? = null,
)

/**
 * The agent session behind a pane: [agent] (claude, codex ...), and for kind "id" the
 * agent's own session id in [value] (Claude Code names its transcript after it).
 */
@Serializable
data class HerdrAgentSession(
    val agent: String? = null,
    val kind: String? = null,
    val value: String? = null,
)

/**
 * A pane's scroll state. [maxOffsetFromBottom] is how many rows of scrollback Herdr
 * holds for the pane: 0 for a full-screen app or agent TUI (Herdr keeps no history
 * for those, so copy mode has nothing to page), more for shell output.
 */
@Serializable
data class HerdrPaneScroll(
    @SerialName("max_offset_from_bottom") val maxOffsetFromBottom: Long = 0,
    @SerialName("offset_from_bottom") val offsetFromBottom: Long = 0,
    @SerialName("viewport_rows") val viewportRows: Int = 0,
)

/** A detected agent in the session snapshot (the agents array, parallel to panes). */
@Serializable
data class HerdrAgentInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.UNKNOWN,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    val cwd: String? = null,
    val focused: Boolean = false,
    @SerialName("state_change_seq") val stateChangeSeq: Long = 0,
    val revision: Long = 0,
)

/** The top-level session snapshot from `herdr api snapshot`. */
@Serializable
data class HerdrSessionSnapshot(
    val version: String = "",
    val protocol: Long = 0,
    val workspaces: List<HerdrWorkspaceInfo> = emptyList(),
    val tabs: List<HerdrTabInfo> = emptyList(),
    val panes: List<HerdrPaneInfo> = emptyList(),
    val agents: List<HerdrAgentInfo> = emptyList(),
    // Mercurio: what has focus, so commands can target it without keybindings.
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
)

@Serializable
data class HerdrWorkspaceInfo(
    @SerialName("workspace_id") val workspaceId: String,
    val label: String = "",
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
    // Mercurio: shown on the jump list in the command sheet.
    val number: Int = 0,
    @SerialName("active_tab_id") val activeTabId: String? = null,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.UNKNOWN,
)

@Serializable
data class HerdrTabInfo(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    val label: String = "",
    @SerialName("pane_count") val paneCount: Int = 0,
    // Mercurio: shown on the jump list in the command sheet.
    val number: Int = 0,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.UNKNOWN,
)

/**
 * Whether Herdr holds scrollback for the focused pane, so its copy mode can page it.
 * False when the pane has none (a full-screen app) and when the snapshot does not say.
 */
fun HerdrSessionSnapshot.focusedPaneHasScrollback(): Boolean {
    val pane = panes.firstOrNull { it.paneId == focusedPaneId } ?: panes.firstOrNull { it.focused }
    return (pane?.scroll?.maxOffsetFromBottom ?: 0) > 0
}

/** A herdr api error (CLI output compatible). */
@Serializable
data class HerdrApiError(
    val code: String = "",
    val message: String = "",
)

/** `herdr api snapshot` CLI output: `{"id","result":{"snapshot":...}}` (same `result` shape as the raw socket reply). */
@Serializable
data class HerdrCliResponse(
    val id: String = "",
    val result: HerdrCliResult? = null,
    val error: HerdrApiError? = null,
)

@Serializable
data class HerdrCliResult(
    val snapshot: HerdrSessionSnapshot? = null,
    /** The raw socket reply's type field (such as session_snapshot); the CLI output has none. */
    val type: String? = null,
)

private val json =
    Json {
        ignoreUnknownKeys = true // a Herdr upgrade that adds fields does not break the client
        coerceInputValues = true // empty or unknown enum values fall back to the default
    }

/**
 * Parse `herdr api snapshot` output. The CLI output and the raw socket reply
 * share the same `result` shape (the CLI is a socket client), so both are read
 * from `result.snapshot`; a parse failure returns null (never throws).
 */
fun parseHerdrSnapshot(raw: String): HerdrSessionSnapshot? = try {
    json.decodeFromString<HerdrCliResponse>(raw).result?.snapshot
} catch (_: Exception) {
    null
}
