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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * The connect picker's decision (offer it or attach at once), the per-workspace
 * urgency roll-up, and the connect flow against fake exec channels.
 *
 * snapshot-picker-test.json is `herdr api snapshot` recorded verbatim on Sun
 * (herdr 0.9.0) from a throwaway session made for this test: three workspaces
 * (tmp, beta, gamma), w1 focused, no agents.
 */
class HerdrWorkspacePickerTest {
    private fun snapshot(name: String): HerdrSessionSnapshot = checkNotNull(parseHerdrSnapshot(herdrFixture(name)))

    private fun ok(name: String) = CommandOutput(stdout = herdrFixture(name), stderr = "", exitCode = 0)

    // Recorded from `herdr --session <stopped> api snapshot` on Sun: exit 1, error JSON on stderr.
    private val serverNotRunning = CommandOutput(
        stdout = "",
        stderr = """{"id":"cli:api:snapshot","error":{"code":"server_not_running","message":"no herdr server is running"}}""",
        exitCode = 1,
    )

    @Test
    fun offers_onlyWhenThePostLoginAttachesTheDefaultSession() {
        assertTrue(HerdrWorkspacePicker.offers("herdr"))
        assertTrue(HerdrWorkspacePicker.offers("herdr\n"))
        assertFalse(HerdrWorkspacePicker.offers(null))
        assertFalse(HerdrWorkspacePicker.offers("tmux attach"))
        assertFalse(HerdrWorkspacePicker.offers("herdr --session work"))
        assertFalse(HerdrWorkspacePicker.offers("herdr session attach work"))
        assertFalse(HerdrWorkspacePicker.offers("herdr --remote sun"))
    }

    @Test
    fun choices_noneWithoutASnapshotOrWithOneWorkspace() {
        assertNull(HerdrWorkspacePicker.choices(null))
        val one = snapshot("snapshot-picker-test.json").let { it.copy(workspaces = it.workspaces.take(1)) }
        assertNull(HerdrWorkspacePicker.choices(one))
    }

    @Test
    fun choices_realSessionWithThreeWorkspaces() {
        val choices = checkNotNull(HerdrWorkspacePicker.choices(snapshot("snapshot-picker-test.json")))
        assertEquals(listOf("tmp", "beta", "gamma"), choices.map { it.label })
        assertEquals(listOf(1, 2, 3), choices.map { it.number })
        assertEquals(listOf("w1"), choices.filter { it.focused }.map { it.workspaceId })
        assertTrue(choices.all { it.state == HerdrAgentStatus.UNKNOWN })
    }

    @Test
    fun choices_rollUpTheMostUrgentAgentPerWorkspace() {
        // w1 holds blocked, done, working and idle agents; the other three hold none.
        val choices = checkNotNull(HerdrWorkspacePicker.choices(snapshot("snapshot-sun-mixed.json")))
        assertEquals(4, choices.size)
        assertEquals(HerdrAgentStatus.BLOCKED, choices.single { it.workspaceId == "w1" }.state)
        assertTrue(choices.filter { it.workspaceId != "w1" }.all { it.state == HerdrAgentStatus.UNKNOWN })
        assertTrue(choices.single { it.workspaceId == "w1" }.focused)
    }

    @Test
    fun choices_fallBackOnTheWorkspaceStatusWhenNoAgentSays() {
        val base = snapshot("snapshot-picker-test.json")
        val marked = base.copy(workspaces = base.workspaces.map { if (it.workspaceId == "w2") it.copy(agentStatus = HerdrAgentStatus.DONE) else it })
        assertEquals(HerdrAgentStatus.DONE, checkNotNull(HerdrWorkspacePicker.choices(marked)).single { it.workspaceId == "w2" }.state)
    }

    @Test
    fun mostUrgent_blockedThenWorkingThenDoneThenIdle() {
        val s = HerdrAgentStatus.entries
        assertEquals(HerdrAgentStatus.BLOCKED, HerdrWorkspacePicker.mostUrgent(s))
        // Working beats done here (a workspace still busy), unlike the Herd's who-needs-you order.
        assertEquals(HerdrAgentStatus.WORKING, HerdrWorkspacePicker.mostUrgent(listOf(HerdrAgentStatus.DONE, HerdrAgentStatus.WORKING, HerdrAgentStatus.IDLE)))
        assertEquals(HerdrAgentStatus.DONE, HerdrWorkspacePicker.mostUrgent(listOf(HerdrAgentStatus.IDLE, HerdrAgentStatus.DONE)))
        assertEquals(HerdrAgentStatus.IDLE, HerdrWorkspacePicker.mostUrgent(listOf(HerdrAgentStatus.UNKNOWN, HerdrAgentStatus.IDLE)))
        assertEquals(HerdrAgentStatus.UNKNOWN, HerdrWorkspacePicker.mostUrgent(emptyList()))
    }

    /** Runs [offer] with a real IO pool, recording each command and each time the picker asked. */
    private class Flow(private val answer: WorkspacePick = WorkspacePick.Attach, private val run: (String) -> CommandOutput?) {
        val commands = mutableListOf<String>()
        var asked: List<WorkspaceChoice>? = null

        fun offer(): WorkspacePick? = runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                HerdrWorkspacePicker.offer(
                    scope = scope,
                    io = Dispatchers.IO,
                    run = { command, _ ->
                        synchronized(commands) { commands += command }
                        run(command)
                    },
                    ask = { choices ->
                        asked = choices
                        answer
                    },
                )
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun offer_attachesAtOnceWhenTheServerIsNotRunning() {
        val flow = Flow { serverNotRunning }
        assertNull(flow.offer())
        assertNull(flow.asked)
        assertEquals(listOf(Herd.snapshotCommand), flow.commands)
    }

    @Test
    fun offer_attachesAtOnceWhenTheSnapshotFails() {
        val flow = Flow { throw IllegalStateException("Not connected") }
        assertNull(flow.offer())
        assertNull(flow.asked)
    }

    @Test
    fun offer_attachesAtOnceWithOneWorkspace() {
        val one = snapshot("snapshot-picker-test.json").let { it.copy(workspaces = it.workspaces.take(1)) }
        val raw = Json.encodeToString(HerdrCliResponse(result = HerdrCliResult(snapshot = one)))
        val flow = Flow { CommandOutput(stdout = raw, stderr = "", exitCode = 0) }
        assertNull(flow.offer())
        assertNull(flow.asked)
    }

    @Test
    fun offer_givesUpOnASlowSnapshotWithinTheWait() {
        val release = CountDownLatch(1)
        val flow = Flow {
            release.await()
            ok("snapshot-picker-test.json")
        }
        val started = System.nanoTime()
        val pick = flow.offer()
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertNull(pick)
        assertNull(flow.asked)
        assertTrue("waited $waitedMs ms", waitedMs >= HerdrWorkspacePicker.SNAPSHOT_WAIT_MS && waitedMs < HerdrWorkspacePicker.SNAPSHOT_WAIT_MS + 1_000)
    }

    @Test
    fun offer_aRowFocusesItsWorkspaceBeforeReturning() {
        val flow = Flow(WorkspacePick.Focus("w3")) { command ->
            if (command == Herd.snapshotCommand) ok("snapshot-picker-test.json") else CommandOutput("", "", 0)
        }
        assertEquals(WorkspacePick.Focus("w3"), flow.offer())
        assertEquals(3, flow.asked?.size)
        assertEquals(2, flow.commands.size)
        assertTrue(flow.commands[1], flow.commands[1].contains("herdr workspace focus"))
        assertTrue(flow.commands[1], flow.commands[1].contains("w3"))
    }

    @Test
    fun offer_attachRunsNothingMore() {
        val flow = Flow(WorkspacePick.Attach) { ok("snapshot-picker-test.json") }
        assertEquals(WorkspacePick.Attach, flow.offer())
        assertEquals(listOf(Herd.snapshotCommand), flow.commands)
    }

    @Test
    fun offer_aFocusThatFailsStillReturnsThePick() {
        val flow = Flow(WorkspacePick.Focus("w2")) { command ->
            if (command == Herd.snapshotCommand) ok("snapshot-picker-test.json") else throw IllegalStateException("dropped")
        }
        assertEquals(WorkspacePick.Focus("w2"), flow.offer())
    }
}
