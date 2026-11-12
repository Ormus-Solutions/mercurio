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

/** Which Herdr engine an agent lives in. */
enum class AgentEngine { TUI, CORE }

/** Why an agent needs the user; only herdr-core says, so the TUI leaves it null. */
enum class NeedsYouKind { PERMISSION, QUESTION, AUTH }

/** What an agent is doing, whatever engine reports it. */
sealed interface AgentState {
    data class NeedsYou(val kind: NeedsYouKind? = null) : AgentState

    data object Done : AgentState

    data object Working : AgentState

    data object Idle : AgentState

    data object Unknown : AgentState

    /** The board section this state belongs to; an unknown state sits with the idle ones. */
    val column: BoardColumn
        get() = when (this) {
            is NeedsYou -> BoardColumn.NEEDS_YOU
            Working -> BoardColumn.WORKING
            Done -> BoardColumn.DONE
            Idle, Unknown -> BoardColumn.IDLE
        }

    companion object {
        /** The TUI's state for [status]. */
        fun of(status: HerdrAgentStatus): AgentState = when (status) {
            HerdrAgentStatus.BLOCKED -> NeedsYou()
            HerdrAgentStatus.WORKING -> Working
            HerdrAgentStatus.DONE -> Done
            HerdrAgentStatus.IDLE -> Idle
            HerdrAgentStatus.UNKNOWN -> Unknown
        }
    }
}

/**
 * One agent, engine-neutral: the board, Jump to and the notices read this, never an engine's
 * own record. A field only one engine fills ([machine], [turn], [unread], [parent]) is null
 * for the other. [since] is when the agent entered [state], epoch milliseconds.
 */
data class Agent(
    val address: String,
    val engine: AgentEngine,
    val machine: String? = null,
    val space: String,
    val tab: String,
    val title: String? = null,
    val kind: String? = null,
    val state: AgentState,
    val since: Long? = null,
    val turn: Int? = null,
    val unread: Boolean? = null,
    val pinned: Boolean = false,
    val parent: String? = null,
) {
    companion object {
        /**
         * [agents] in inbox order: pinned first, then needs you, done, working, idle, unknown;
         * inside a state the one waiting longest first (an unknown time last), then by address.
         */
        fun rank(agents: List<Agent>): List<Agent> = agents.sortedWith(RANKING)

        /** The inbox order of [rank], for a list that carries more than the agent. */
        val RANKING: Comparator<Agent> = compareBy<Agent> { !it.pinned }
            .thenBy { urgency(it.state) }
            .thenBy { it.since ?: Long.MAX_VALUE }
            .thenBy { it.address }

        /** How urgent [state] is: lower is more urgent; the order [RANKING] uses. */
        fun urgency(state: AgentState): Int = when (state) {
            is AgentState.NeedsYou -> 0
            AgentState.Done -> 1
            AgentState.Working -> 2
            AgentState.Idle -> 3
            AgentState.Unknown -> 4
        }
    }
}
