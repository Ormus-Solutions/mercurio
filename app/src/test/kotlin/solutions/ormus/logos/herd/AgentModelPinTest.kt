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
import org.junit.Test

/**
 * Pins what the board shows today, before the board reads one agent model: the section of
 * every Herdr state, and each section's rows (card key and state) on one synthetic host.
 */
class AgentModelPinTest {
    private val main = boardSnapshot(
        workspaces = listOf("w1" to "alpha", "w2" to "beta"),
        tabs = listOf("w1:t1" to "build", "w1:t2" to "docs", "w2:t1" to "tests", "w2:t2" to "shell"),
        agents = listOf(
            boardAgent("w2:p1", "w2:t1", "codex", HerdrAgentStatus.BLOCKED),
            boardAgent("w1:p2", "w1:t2", "claude", HerdrAgentStatus.WORKING),
            boardAgent("w1:p1", "w1:t1", "claude", HerdrAgentStatus.BLOCKED),
            boardAgent("w1:p3", "w1:t2", "grok", HerdrAgentStatus.DONE),
            boardAgent("w2:p2", "w2:t2", "pi", HerdrAgentStatus.UNKNOWN),
            boardAgent("w2:p3", "w2:t2", "pi", HerdrAgentStatus.IDLE),
        ),
    )

    @Test
    fun column_everyStateHasItsSection() {
        assertEquals(BoardColumn.NEEDS_YOU, AgentsBoard.column(HerdrAgentStatus.BLOCKED))
        assertEquals(BoardColumn.WORKING, AgentsBoard.column(HerdrAgentStatus.WORKING))
        assertEquals(BoardColumn.DONE, AgentsBoard.column(HerdrAgentStatus.DONE))
        assertEquals(BoardColumn.IDLE, AgentsBoard.column(HerdrAgentStatus.IDLE))
        assertEquals(BoardColumn.IDLE, AgentsBoard.column(HerdrAgentStatus.UNKNOWN))
    }

    @Test
    fun sections_rowsOfEachSection_inPlaceOrder() {
        val rows = AgentsBoard.sections(AgentsBoard.cards(mapOf("default" to main)))
            .mapValues { (_, cards) -> cards.map { "${it.key}:${it.state}" } }
        assertEquals(
            mapOf(
                BoardColumn.NEEDS_YOU to listOf("default/w1:p1:BLOCKED", "default/w2:p1:BLOCKED"),
                BoardColumn.WORKING to listOf("default/w1:p2:WORKING"),
                BoardColumn.DONE to listOf("default/w1:p3:DONE"),
                BoardColumn.IDLE to listOf("default/w2:p2:UNKNOWN", "default/w2:p3:IDLE"),
            ),
            rows,
        )
    }

    @Test
    fun jumpOrder_likeTheInbox_needsYouDoneWorkingIdleUnknown_aSplitTabIsOneRowPerPane() {
        assertEquals(
            listOf("build:BLOCKED", "tests:BLOCKED", "docs:DONE", "docs:WORKING", "shell:IDLE", "shell:UNKNOWN"),
            HerdrJump.targets(mapOf("default" to main)).map { "${it.tabLabel}:${it.state}" },
        )
    }
}
