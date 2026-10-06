/*
 * Copied from Termish, https://github.com/farlume/termish (commit ee9e460),
 * composeApp/src/commonTest/kotlin/dev/termish/herdr/HerdrModelsTest.kt.
 * Mercurio changes: tests for the blocked-to-done/unknown fix; comments
 * translated.
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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A simplified fixture with the same structure as real `herdr api snapshot` output (see the recorded snapshots in test resources). */
private val SNAPSHOT_JSON =
    """
{"id":"cli:api:snapshot","result":{"snapshot":{
  "version":"0.8.0","protocol":19,
  "workspaces":[{"workspace_id":"w1","label":"dev","pane_count":2,"tab_count":2,"focused":true,"agent_status":"working","number":1,"active_tab_id":"w1:t1"}],
  "tabs":[{"tab_id":"w1:t1","workspace_id":"w1","label":"main","pane_count":1,"number":1,"focused":true,"agent_status":"idle"},
          {"tab_id":"w1:t2","workspace_id":"w1","label":"logs","pane_count":1,"number":2,"focused":false,"agent_status":"blocked"}],
  "panes":[{"pane_id":"w1:p1","terminal_id":"term_a","workspace_id":"w1","tab_id":"w1:t1","focused":true,"agent":"pi","agent_status":"idle",
            "terminal_title":"pi - dev","terminal_title_stripped":"pi - dev","cwd":"/repo","revision":3},
           {"pane_id":"w1:p2","terminal_id":"term_b","workspace_id":"w1","tab_id":"w1:t2","focused":false,"agent":"codex","agent_status":"blocked",
            "terminal_title":"codex - refactor auth","terminal_title_stripped":"codex - refactor auth","cwd":"/repo","revision":9}],
  "agents":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"pi","agent_status":"idle",
             "terminal_title":"pi - dev","terminal_title_stripped":"pi - dev","cwd":"/repo","focused":true,"state_change_seq":10,"revision":3},
            {"pane_id":"w1:p2","workspace_id":"w1","tab_id":"w1:t2","agent":"codex","agent_status":"blocked",
             "terminal_title":"codex - refactor auth","terminal_title_stripped":"codex - refactor auth","cwd":"/repo","focused":false,"state_change_seq":42,"revision":9}],
  "layouts":[],"focused_workspace_id":"w1","focused_tab_id":"w1:t1","focused_pane_id":"w1:p1"
}}}
    """.trimIndent()

class HerdrModelsTest {
    @Test
    fun parseSnapshotExtractsAgents() {
        val s = parseHerdrSnapshot(SNAPSHOT_JSON)
        assertNotNull(s, "a valid snapshot should parse")
        assertEquals(19L, s.protocol)
        assertEquals(2, s.agents.size)

        val blocked = s.agents.first { it.paneId == "w1:p2" }
        assertEquals(HerdrAgentStatus.BLOCKED, blocked.agentStatus)
        assertEquals("codex", blocked.agent)
        assertEquals("codex - refactor auth", blocked.terminalTitleStripped)
        assertEquals("/repo", blocked.cwd)
        assertEquals(42L, blocked.stateChangeSeq)
    }

    @Test
    fun parseSnapshotToleratesUnknownFields() {
        // a Herdr upgrade may add fields: with ignoreUnknownKeys parsing must not fail
        val withExtra =
            SNAPSHOT_JSON.replace(
                "\"pane_id\":\"w1:p2\"",
                "\"pane_id\":\"w1:p2\",\"future_field\":{\"nested\":[1,2,3]}",
            )
        assertNotNull(parseHerdrSnapshot(withExtra))
    }

    @Test
    fun parseSnapshotReturnsNullOnGarbage() {
        assertNull(parseHerdrSnapshot("not json at all"))
        assertNull(parseHerdrSnapshot(""))
        assertNull(parseHerdrSnapshot("{\"id\":\"x\",\"error\":{\"code\":\"not_found\"}}"))
    }

    @Test
    fun parseSnapshotToleratesMissingOptionalFields() {
        // a minimal agent with only pane_id/agent_status (when Herdr omits fields)
        val minimal =
            """
            {"id":"cli:api:snapshot","result":{"snapshot":{"version":"0.8.0","protocol":19,
              "workspaces":[],"tabs":[],"panes":[],"agents":[{"pane_id":"w1:p1","agent_status":"working"}],"layouts":[]}}}
            """.trimIndent()
        val s = parseHerdrSnapshot(minimal)
        assertNotNull(s)
        assertEquals(1, s.agents.size)
        assertEquals(HerdrAgentStatus.WORKING, s.agents[0].agentStatus)
        assertEquals("", s.agents[0].workspaceId)
    }
}

class HerdrAgentStateMachineTest {
    private fun snapshot(vararg statuses: Pair<String, HerdrAgentStatus>): HerdrSessionSnapshot = HerdrSessionSnapshot(
        agents =
            statuses.map { (pane, st) ->
                HerdrAgentInfo(paneId = pane, agent = "pi", agentStatus = st)
            },
    )

    @Test
    fun firstUpdateEmitsCurrentStatuses() {
        val m = HerdrAgentStateMachine()
        val events = m.update(snapshot("w1:p1" to HerdrAgentStatus.IDLE, "w1:p2" to HerdrAgentStatus.WORKING))
        assertEquals(2, events.size)
        assertIs<HerdrAgentEvent.Idle>(events[0])
        assertIs<HerdrAgentEvent.Working>(events[1])
        assertEquals(HerdrAgentStatus.WORKING, m.current()["w1:p2"])
    }

    @Test
    fun unchangedStatusEmitsNothing() {
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.WORKING))
        assertTrue(m.update(snapshot("w1:p1" to HerdrAgentStatus.WORKING)).isEmpty(), "an unchanged state should produce no event")
    }

    @Test
    fun blockedTransitionEmitsBlockedThenUnblocked() {
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.WORKING))

        val blocked = m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))
        assertEquals(1, blocked.size)
        val b = assertIs<HerdrAgentEvent.Blocked>(blocked[0])
        assertEquals("w1:p1", b.paneId)

        val unblocked = m.update(snapshot("w1:p1" to HerdrAgentStatus.WORKING))
        assertIs<HerdrAgentEvent.Working>(unblocked[0])
        assertIs<HerdrAgentEvent.Unblocked>(unblocked[1])
    }

    @Test
    fun paneDisappearingWhileBlockedEmitsUnblocked() {
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))
        val events = m.update(snapshot()) // the pane disappears from the snapshot (session closed)
        assertEquals(1, events.size)
        val u = assertIs<HerdrAgentEvent.Unblocked>(events[0])
        assertEquals("w1:p1", u.paneId)
        assertTrue(m.current().isEmpty())
    }

    @Test
    fun resetClearsState() {
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))
        m.reset()
        // after a reset the same state counts as new (emits Blocked), not debounced by the old state
        val events = m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))
        assertIs<HerdrAgentEvent.Blocked>(events.single())
    }

    // ---- Mercurio fixes ----

    @Test
    fun blockedToDoneEmitsDoneThenUnblocked() {
        // Termish emitted nothing here: the done state had no event, and the
        // `?: continue` skipped the Unblocked too, so the phone kept the agent
        // marked as waiting on you.
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))

        val events = m.update(snapshot("w1:p1" to HerdrAgentStatus.DONE))
        assertEquals(2, events.size)
        assertIs<HerdrAgentEvent.Done>(events[0])
        assertIs<HerdrAgentEvent.Unblocked>(events[1])
    }

    @Test
    fun blockedToUnknownEmitsUnblocked() {
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))

        val events = m.update(snapshot("w1:p1" to HerdrAgentStatus.UNKNOWN))
        val u = assertIs<HerdrAgentEvent.Unblocked>(events.single())
        assertEquals("w1:p1", u.paneId)
    }
}
