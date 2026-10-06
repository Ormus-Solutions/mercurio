/*
 * Copied from Termish, https://github.com/farlume/termish (commit ee9e460),
 * composeApp/src/androidUnitTest/kotlin/dev/termish/herdr/HerdrMonitorTest.kt.
 * Mercurio changes: reads the snapshot recorded on Sun, a test for the
 * notifiedBlocked fix; comments translated.
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
import kotlin.test.assertTrue

/**
 * Real Herdr snapshot parsing: the fixture is `herdr api snapshot` recorded verbatim
 * from herdr 0.9.0 on Sun (resources/herdr/snapshot-sun.json), so the model stays
 * aligned with the real protocol (not a self-consistent hand-made fixture).
 */
class HerdrSnapshotRealDataTest {
    private fun realSnapshot(): HerdrSessionSnapshot {
        val raw =
            HerdrSnapshotRealDataTest::class.java
                .getResourceAsStream("/herdr/snapshot-sun.json")
                ?.readBytes()
                ?.decodeToString()
                ?: error("missing fixture: test resources should hold herdr/snapshot-sun.json (verbatim herdr api snapshot)")
        return assertNotNull(parseHerdrSnapshot(raw), "a real Herdr snapshot should parse")
    }

    @Test
    fun realSnapshotParses() {
        val s = realSnapshot()
        assertTrue(s.protocol > 0, "protocol should be non-zero (the real protocol version)")
        assertTrue(s.panes.isNotEmpty())
        assertEquals(
            s.agents.size,
            s.agents
                .map { it.paneId }
                .toSet()
                .size,
            "agent pane_id should be unique",
        )
    }

    @Test
    fun realSnapshotAgentsHaveExpectedShape() {
        val s = realSnapshot()
        // The state when recorded on Sun: six grok agents, all idle
        assertTrue(s.agents.isNotEmpty())
        for (a in s.agents) {
            assertNotNull(a.paneId)
            assertTrue(a.agentStatus != HerdrAgentStatus.UNKNOWN || a.agent == null)
        }
        println("real agents: ${s.agents.map { "${it.agent}/${it.agentStatus}/${it.paneId}" }}")
    }

    @Test
    fun stateMachineRunsOnRealData() {
        val s = realSnapshot()
        val m = HerdrAgentStateMachine()
        val events = m.update(s)
        assertEquals(s.agents.size, events.size, "the first round should emit each first state")
        // the same snapshot again: no change
        assertTrue(m.update(s).isEmpty())
        println("real events: ${events.map { it::class.simpleName }}")
    }
}

/**
 * HerdrMonitor polling: kotlinx-coroutines-test virtual time drives a fake
 * runCommand that returns a scripted snapshot sequence; checks the poll calls,
 * the event callback, the blocked confirmation text and stop semantics.
 */
class HerdrMonitorTest {
    private fun snapshotWith(vararg statuses: Pair<String, HerdrAgentStatus>): String {
        val agents =
            statuses.joinToString(",") { (pane, st) ->
                """{"pane_id":"$pane","agent":"pi","agent_status":"${st.name.lowercase()}","terminal_title":"t-$pane"}"""
            }
        return """{"id":"cli:api:snapshot","result":{"snapshot":{"version":"0.8.0","protocol":19,"workspaces":[],"tabs":[],"panes":[],"agents":[$agents],"layouts":[]}}}"""
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pollsAndEmitsEvents() = kotlinx.coroutines.test.runTest {
        val commands = mutableListOf<String>()
        val received = mutableListOf<List<HerdrAgentEvent>>()
        var round = 0
        val monitor =
            HerdrMonitor(
                hostName = "nas",
                runCommand = { cmd ->
                    commands += cmd
                    round++
                    // round 1: working; from round 2: blocked
                    if (round == 1) {
                        snapshotWith("w1:p1" to HerdrAgentStatus.WORKING)
                    } else {
                        snapshotWith("w1:p1" to HerdrAgentStatus.BLOCKED)
                    }
                },
                scope = this,
                pollIntervalMs = 1_000,
                onEvents = { received += it },
            )
        monitor.start()
        testScheduler.advanceTimeBy(10_000)
        // assert before stop: stop() resets the state machine
        assertEquals(HerdrAgentStatus.BLOCKED, monitor.currentStatus()["w1:p1"])
        monitor.stop()

        assertTrue(commands.isNotEmpty(), "polling should run herdr api snapshot")
        // Mercurio: the first candidate runs herdr through a login shell.
        assertTrue(commands.all { it == HerdrApi.SNAPSHOT_CMD_CANDIDATES.first() })
        // event stream: Working first, then one Blocked (no repeats once the state is stable)
        val all = received.flatten()
        assertIs<HerdrAgentEvent.Working>(all.first())
        assertIs<HerdrAgentEvent.Blocked>(all[1])
        assertEquals(2, all.size, "no repeated events once the state is stable")
    }

    @Test
    fun blockedNotificationText() {
        val monitor =
            HerdrMonitor(
                "nas",
                { null },
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            )
        val a =
            HerdrAgentInfo(
                paneId = "w1:p1",
                agent = "codex",
                agentStatus = HerdrAgentStatus.BLOCKED,
                terminalTitleStripped = "codex - refactor auth",
                cwd = "/repo",
            )
        assertEquals("codex on nas is waiting for you: codex - refactor auth", monitor.blockedNotificationText("nas", a))
        // fallback with no title and no cwd
        val bare = HerdrAgentInfo(paneId = "w1:p1", agentStatus = HerdrAgentStatus.BLOCKED)
        assertEquals("agent on nas is waiting for you: open the session", monitor.blockedNotificationText("nas", bare))
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun failureBackoffThenPause() = kotlinx.coroutines.test.runTest {
        val calls = mutableListOf<String>()
        val monitor =
            HerdrMonitor(
                hostName = "nas",
                runCommand = { cmd ->
                    calls += cmd
                    null
                }, // always fails (herdr not installed)
                scope = this,
                pollIntervalMs = 1_000,
            )
        monitor.start()
        testScheduler.advanceTimeBy(120_000) // 2 minutes: 3 failures -> pause -> retry after resuming
        monitor.stop()
        // The first 3 backoffs finish within 3+6+12=21s, then each 60s pause + 3 retries (3+6+12);
        // about 12 calls in 120s, never 120 calls at one per second (no hammering)
        assertTrue(calls.size < 30, "repeated failures must back off and pause, got ${calls.size} calls")
        assertTrue(calls.size >= 6, "polling should resume after the pause, got ${calls.size} calls")
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun secondBlockAfterUnblockReportsAgain() = kotlinx.coroutines.test.runTest {
        // Mercurio fix: notifiedBlocked now clears when the pane leaves blocked.
        // Termish only cleared it on stop(), so the same agent's second question
        // never reached the phone.
        val script =
            listOf(
                HerdrAgentStatus.BLOCKED,
                HerdrAgentStatus.BLOCKED,
                HerdrAgentStatus.WORKING,
                HerdrAgentStatus.BLOCKED,
                HerdrAgentStatus.BLOCKED,
            )
        var round = 0
        val reported = mutableListOf<String>()
        val monitor =
            HerdrMonitor(
                hostName = "nas",
                runCommand = { snapshotWith("w1:p1" to script[minOf(round++, script.lastIndex)]) },
                scope = this,
                pollIntervalMs = 1_000,
                onBlocked = { reported += it.paneId },
            )
        monitor.start()
        testScheduler.advanceTimeBy(5_500)
        monitor.stop()

        assertEquals(listOf("w1:p1", "w1:p1"), reported)
    }
}
