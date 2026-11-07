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

package solutions.ormus.logos.home

import org.connectbot.data.entity.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.HerdrWorkspace

/** The feed's workboards joined with the saved hosts: which key opens where, and why one cannot. */
class HomeWorkboardsTest {
    private val names = mapOf("box" to listOf("box", "box.example", "192.0.2.10"), "far" to listOf("far", "far.example"), "bare" to emptyList())
    private val plain = Host(id = 1L, nickname = "Box", protocol = "ssh", username = "me", hostname = "192.0.2.10", postLogin = "herdr\n", lastConnect = 10L)
    private val copy = Host(id = 2L, nickname = "Box (copy)", protocol = "ssh", username = "me", hostname = "box.example", postLogin = "herdr session attach work\n", lastConnect = 5L)
    private val elsewhere = Host(id = 3L, nickname = "Other", protocol = "ssh", username = "me", hostname = "other.example", postLogin = "herdr\n")
    private val plainOld = Host(id = 4L, nickname = "Box LAN", protocol = "ssh", username = "me", hostname = "box.example", postLogin = "herdr\n", lastConnect = 1L)

    private fun board(name: String, machine: String? = "box", session: String? = name, vararg workspaces: String, blocked: Int = 0) = HomeBoard(
        name = name,
        machine = machine,
        session = session,
        agents = HomeAgents(blocked = blocked),
        workspaces = workspaces.map { HomeWorkspace(it) },
    )

    private fun feed(vararg boards: HomeBoard) = HomeBoards(boards = boards.toList(), hosts = names)

    private fun only(boards: HomeBoards, hosts: List<Host>, connected: Set<Long> = emptySet()) = HomeWorkboards.blocks(boards, hosts, connected).single().keys.single()

    @Test
    fun blocks_followTheFeedsOrder_oneKeyPerNamedWorkspace() {
        val blocks = HomeWorkboards.blocks(feed(board("work", workspaces = arrayOf("review", " ops ", " ")), board("lab", machine = null, session = null, "bench")), listOf(plain))

        assertEquals(listOf("work", "lab"), blocks.map { it.board.name })
        // Names are trimmed; a blank name is no key.
        assertEquals(listOf("review", "ops"), blocks[0].keys.map { it.name })
        assertEquals(listOf("bench"), blocks[1].keys.map { it.name })
    }

    @Test
    fun aKey_opensTheSessionOnTheSavedHostTheMachinesNamesPick_landedOnTheWorkspace() {
        val key = only(feed(board("work", workspaces = arrayOf(" review "))), listOf(elsewhere, plain))

        assertTrue(key.opens)
        assertNull(key.off)
        assertEquals(WorkboardTarget(plain, HerdrConsole("work", workspaceLabel = "review")), key.target)
        assertEquals("box", key.machine)
        assertEquals("work", key.session)
    }

    @Test
    fun aKey_prefersTheCopyWhosePostLoginAttachesTheSession() {
        val key = only(feed(board("work", workspaces = arrayOf("review"))), listOf(plain, copy))

        assertEquals(copy, key.target?.host)
        assertEquals(HerdrConsole("work", workspaceLabel = "review"), key.target?.console)
    }

    @Test
    fun aKey_prefersAConnectedHost_asALaunchersTapDoes() {
        val lastConnected = only(feed(board("work", workspaces = arrayOf("review"))), listOf(plainOld, plain))
        assertEquals(plain, lastConnected.target?.host)

        val connected = only(feed(board("work", workspaces = arrayOf("review"))), listOf(plainOld, plain), connected = setOf(plainOld.id))
        assertEquals(plainOld, connected.target?.host)
    }

    @Test
    fun aKey_withNoSavedHostOnItsMachine_isOff() {
        val key = only(feed(board("far", machine = "far", workspaces = arrayOf("edge"))), listOf(plain, elsewhere))

        assertFalse(key.opens)
        assertNull(key.target)
        assertEquals(WorkboardOff.NO_HOST, key.off)
        assertEquals("far", key.machine)
    }

    @Test
    fun aKey_saysWhyItIsOff() {
        assertEquals(WorkboardOff.NO_SESSION, only(feed(board("lab", session = null, workspaces = arrayOf("bench"))), listOf(plain)).off)
        assertEquals(WorkboardOff.BAD_SESSION, only(feed(board("work", session = "no way", workspaces = arrayOf("review"))), listOf(plain)).off)
        assertEquals(WorkboardOff.NO_MACHINE, only(feed(board("lab", machine = null, workspaces = arrayOf("bench"))), listOf(plain)).off)
        assertEquals(WorkboardOff.UNNAMED_MACHINE, only(feed(board("work", machine = "nowhere", workspaces = arrayOf("review"))), listOf(plain)).off)
        assertEquals(WorkboardOff.UNNAMED_MACHINE, only(feed(board("work", machine = "bare", workspaces = arrayOf("review"))), listOf(plain)).off)
        // A session name Herdr could not have given is off, not a throw, even with a host there.
        val bad = only(feed(board("work", session = "no way", workspaces = arrayOf("review"))), listOf(plain))
        assertFalse(bad.opens)
        assertEquals("no way", bad.session)
    }

    @Test
    fun aWorkspace_withItsOwnMachineAndSession_usesThem() {
        val board = HomeBoard("work", machine = "far", session = "work", workspaces = listOf(HomeWorkspace("review", machine = "box", session = "ops")))
        val key = only(feed(board), listOf(plain))

        assertEquals("box", key.machine)
        assertEquals("ops", key.session)
        assertEquals(WorkboardTarget(plain, HerdrConsole("ops", workspaceLabel = "review")), key.target)
    }

    @Test
    fun uncovered_keepsTheKeptRowsNoOpeningKeyStandsFor() {
        val blocks = HomeWorkboards.blocks(feed(board("work", workspaces = arrayOf("review")), board("far", machine = "far", workspaces = arrayOf("edge"))), listOf(plain, elsewhere))
        val kept = listOf(
            HerdrWorkspace(plain, HerdrConsole("work", workspaceLabel = "main")),
            HerdrWorkspace(plain, HerdrConsole("review")),
            HerdrWorkspace(elsewhere, HerdrConsole("work")),
        )

        // The feed opens work · review on Box: the main row of work and the review session stay, as does work elsewhere.
        assertEquals(kept, HomeWorkboards.uncovered(kept, blocks))
        // A row for the workspace the feed opens goes; a row with no workspace (the session as it is) goes too.
        val same = listOf(HerdrWorkspace(plain, HerdrConsole("work", workspaceLabel = "review")), HerdrWorkspace(plain, HerdrConsole("work")))
        assertEquals(emptyList<HerdrWorkspace>(), HomeWorkboards.uncovered(same, blocks))
        // A feed whose keys are all off covers nothing.
        assertEquals(kept, HomeWorkboards.uncovered(kept, HomeWorkboards.blocks(feed(board("work", workspaces = arrayOf("review"))), listOf(elsewhere))))
        assertEquals(kept, HomeWorkboards.uncovered(kept, emptyList()))
    }

    @Test
    fun noFeed_noBlocks() {
        assertEquals(emptyList<WorkboardBlock>(), HomeWorkboards.blocks(null, listOf(plain)))
        assertEquals(emptyList<WorkboardBlock>(), HomeWorkboards.blocks(HomeBoards(), listOf(plain)))
    }
}
