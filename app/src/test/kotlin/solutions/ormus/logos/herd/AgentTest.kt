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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The one agent model: what the TUI fills, the pure rank, and the board's section read from it. */
class AgentTest {
    private fun agent(address: String, state: AgentState, since: Long? = null, pinned: Boolean = false) = Agent(
        address = address,
        engine = AgentEngine.TUI,
        space = "alpha · ONE",
        tab = "work",
        state = state,
        since = since,
        pinned = pinned,
    )

    @Test
    fun rank_pinnedFirst_thenNeedsYouDoneWorkingIdleUnknown() {
        val ranked = Agent.rank(
            listOf(
                agent("unknown", AgentState.Unknown),
                agent("idle", AgentState.Idle),
                agent("working", AgentState.Working),
                agent("done", AgentState.Done),
                agent("needs", AgentState.NeedsYou()),
                agent("pinnedIdle", AgentState.Idle, pinned = true),
            ),
        )
        assertEquals(listOf("pinnedIdle", "needs", "done", "working", "idle", "unknown"), ranked.map { it.address })
    }

    @Test
    fun rank_sameState_longestInStateFirst_unknownTimeLast_thenAddress() {
        val ranked = Agent.rank(
            listOf(
                agent("c", AgentState.Working, since = null),
                agent("b", AgentState.Working, since = 200),
                agent("a2", AgentState.Working, since = 100),
                agent("a1", AgentState.Working, since = 100),
            ),
        )
        assertEquals(listOf("a1", "a2", "b", "c"), ranked.map { it.address })
    }

    @Test
    fun tuiCard_fillsOnlyWhatTheTuiKnows() {
        val snapshot = boardSnapshot(
            workspaces = listOf("w1" to "alpha"),
            tabs = listOf("w1:t1" to "build"),
            agents = listOf(boardAgent("w1:p1", "w1:t1", "claude", HerdrAgentStatus.BLOCKED)),
        )
        val agent = AgentsBoard.cards(mapOf("work" to snapshot), since = { _, _ -> 42L }).single().toAgent()
        assertEquals("work/w1:p1", agent.address)
        assertEquals(AgentEngine.TUI, agent.engine)
        assertEquals("alpha", agent.space)
        assertEquals("build", agent.tab)
        assertEquals("claude", agent.kind)
        assertEquals("task w1:p1", agent.title)
        assertEquals(AgentState.NeedsYou(), agent.state)
        assertEquals(42L, agent.since)
        assertNull(agent.machine)
        assertNull(agent.turn)
        assertNull(agent.unread)
        assertNull(agent.parent)
        assertFalse(agent.pinned)
    }

    @Test
    fun state_everyHerdrStatusMapsToOneState() {
        assertEquals(AgentState.NeedsYou(), AgentState.of(HerdrAgentStatus.BLOCKED))
        assertEquals(AgentState.Working, AgentState.of(HerdrAgentStatus.WORKING))
        assertEquals(AgentState.Done, AgentState.of(HerdrAgentStatus.DONE))
        assertEquals(AgentState.Idle, AgentState.of(HerdrAgentStatus.IDLE))
        assertEquals(AgentState.Unknown, AgentState.of(HerdrAgentStatus.UNKNOWN))
    }

    @Test
    fun rank_severalPinned_keepStateOrderAmongThemselves_andStateBeatsSince() {
        val ranked = Agent.rank(
            listOf(
                agent("workingOld", AgentState.Working, since = 1),
                agent("pinWorking", AgentState.Working, since = 5, pinned = true),
                agent("pinNeeds", AgentState.NeedsYou(NeedsYouKind.AUTH), since = null, pinned = true),
                agent("doneNew", AgentState.Done, since = 900),
            ),
        )
        assertEquals(listOf("pinNeeds", "pinWorking", "doneNew", "workingOld"), ranked.map { it.address })
    }

    @Test
    fun column_aNeedsYouOfAnyKindIsInNeedsYou() {
        assertEquals(BoardColumn.NEEDS_YOU, AgentState.NeedsYou(NeedsYouKind.PERMISSION).column)
        assertEquals(BoardColumn.IDLE, AgentState.Unknown.column)
    }
}
