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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads a snapshot fixture recorded from Herdr on Sun. */
internal fun herdrFixture(name: String): String = checkNotNull(HerdTest::class.java.getResourceAsStream("/herdr/$name")) { "missing fixture herdr/$name" }
    .readBytes()
    .decodeToString()

/**
 * Mercurio's Herdr glue, against `herdr api snapshot` recorded on Sun (herdr 0.9.0).
 *
 * snapshot-sun.json is the recording as Herdr printed it (six grok agents, all
 * idle). snapshot-sun-mixed.json is the same recording with only the
 * agent_status of five agents changed (two blocked, one done, two working) so
 * one fixture covers every state; nothing else in it was touched.
 */
class HerdTest {
    private val sun = assertNotNull(parseHerdrSnapshot(herdrFixture("snapshot-sun.json")))
    private val mixed = assertNotNull(parseHerdrSnapshot(herdrFixture("snapshot-sun-mixed.json")))

    private fun <T : Any> assertNotNull(value: T?): T {
        assertNotNull("fixture should parse", value)
        return value!!
    }

    @Test
    fun order_blockedDoneWorkingIdle_newestFirstInEachState() {
        val ordered = Herd.order(mixed.agents)

        assertEquals(listOf("w1:pJ", "w1:pC", "w1:pH", "w1:pK", "w1:pB", "w1:pF"), ordered.map { it.paneId })
        assertEquals(
            listOf(
                HerdrAgentStatus.BLOCKED,
                HerdrAgentStatus.BLOCKED,
                HerdrAgentStatus.DONE,
                HerdrAgentStatus.WORKING,
                HerdrAgentStatus.WORKING,
                HerdrAgentStatus.IDLE,
            ),
            ordered.map { it.agentStatus },
        )
        assertEquals(listOf(10L, 9L, 11L, 13L, 12L, 14L), ordered.map { it.stateChangeSeq })
    }

    @Test
    fun order_verbatimRecording_allIdleNewestFirst() {
        assertEquals("0.9.0", sun.version)
        assertEquals(22L, sun.protocol)
        assertEquals(
            listOf("w1:pF", "w1:pK", "w1:pB", "w1:pH", "w1:pJ", "w1:pC"),
            Herd.order(sun.agents).map { it.paneId },
        )
    }

    @Test
    fun order_unknownGoesLast() {
        val agents = listOf(
            HerdrAgentInfo(paneId = "u", agentStatus = HerdrAgentStatus.UNKNOWN, stateChangeSeq = 99),
            HerdrAgentInfo(paneId = "i", agentStatus = HerdrAgentStatus.IDLE, stateChangeSeq = 1),
        )
        assertEquals(listOf("i", "u"), Herd.order(agents).map { it.paneId })
    }

    @Test
    fun snapshot_readsFocusTabsAndWorkspaces() {
        assertEquals("w1:p1", sun.focusedPaneId)
        assertEquals("w1:t1", sun.focusedTabId)
        assertEquals("w1", sun.focusedWorkspaceId)
        val demo = sun.workspaces.first()
        assertEquals("~Demo", demo.label)
        assertEquals(1, demo.number)
        assertEquals("w1:t1", demo.activeTabId)
        assertEquals(7, sun.tabs.count { it.workspaceId == "w1" })
    }

    @Test
    fun focus_plainShellPaneUsesFocusedIds() {
        // Sun's focused pane (w1:p1) runs no detected agent.
        assertEquals(HerdFocus(paneId = "w1:p1", workspaceLabel = "~Demo", tabLabel = "1"), sun.focus())
    }

    @Test
    fun focus_findsFocusedAgentAndLabels() {
        val focusedOnAgent = sun.copy(focusedPaneId = "w1:pB", focusedTabId = "w1:tB")

        assertEquals(
            HerdFocus(
                paneId = "w1:pB",
                agent = "grok",
                paneTitle = "Docs site build, broken links in the nav… - grok",
                workspaceLabel = "~Demo",
                tabLabel = "2",
            ),
            focusedOnAgent.focus(),
        )
    }

    @Test
    fun focus_nullWhenNothingFocusedOrMalformed() {
        assertNull(parseHerdrSnapshot("""{"result":{"snapshot":{"focused_pane_id":null}}}""")?.focus())
        assertNull(parseHerdrSnapshot("""{"error":{"code":"no_server"}}"""))
        assertNull(parseHerdrSnapshot("herdr: command not found"))
    }

    @Test
    fun commands_runThroughLoginShellWithQuotedPane() {
        assertEquals(
            """bash -lc 'herdr pane read '\''w1:pB'\'' --source recent-unwrapped --lines 1000'""",
            Herd.readCommand("w1:pB"),
        )
        assertEquals("bash -lc 'herdr api snapshot'", Herd.snapshotCommand)
    }

    @Test
    fun shellQuote_escapesSingleQuotes() {
        assertEquals("""'it'\''s'""", HerdrApi.shQuote("it's"))
    }

    @Test
    fun runsHerdr_matchesPostLogin() {
        assertTrue(Herd.runsHerdr(Host(nickname = "Sun", postLogin = "herdr\n")))
        assertFalse(Herd.runsHerdr(Host(nickname = "Sun", postLogin = null)))
    }
}
