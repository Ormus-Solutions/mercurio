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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sessions feed, read into the home's boards. */
class HomeBoardsTest {
    private val fixture = checkNotNull(javaClass.getResourceAsStream("/home/sessions.json")).readBytes().decodeToString()

    @Test
    fun parse_readsEveryBoard_withItsMachineSessionCountsAndWorkspaces() {
        val boards = HomeBoards.parse(fixture)

        assertEquals(1_700_000_000L, boards.generated)
        assertEquals(listOf("work", "lab", "far"), boards.boards.map { it.name })
        val work = boards.boards[0]
        assertEquals("box", work.machine)
        assertEquals("work", work.session)
        assertEquals(HomeAgents(blocked = 1, working = 2, done = 3), work.agents)
        assertEquals(6, work.agents.total)
        assertEquals(2, work.waiting)
        assertEquals(listOf("review", "ops"), work.workspaces.map { it.name })
        assertEquals(HomeAgents(blocked = 1, done = 1), work.workspaces[0].agents)
        assertEquals("work", work.workspaces[1].session)
        assertEquals(1, boards.blocked)
    }

    @Test
    fun parse_aBoardWithNoMachineAndNoAgents_isStillListed() {
        val lab = HomeBoards.parse(fixture).boards[1]

        assertNull(lab.machine)
        assertNull(lab.session)
        assertEquals(HomeAgents(), lab.agents)
        assertEquals(0, lab.waiting)
        assertEquals(listOf("bench"), lab.workspaces.map { it.name })
        assertNull(lab.workspaces[0].machine)
        assertEquals(emptyList<HomeWorkspace>(), HomeBoards.parse(fixture).boards[2].workspaces)
    }

    @Test
    fun parse_readsTheMachinesNames_andTheirLinks() {
        val boards = HomeBoards.parse(fixture)

        assertEquals(listOf("box", "box.example", "192.0.2.10"), boards.hosts["box"])
        assertEquals(setOf("box", "far"), boards.hosts.keys)
        assertEquals("ssh://me@far.example:22", boards.links["far"])
    }

    @Test
    fun parse_leavesWhatTheHomeDoesNotRead_andEncodeDropsIt() {
        val boards = HomeBoards.parse(fixture)

        val kept = HomeBoards.encode(boards)
        assertFalse(kept, kept.contains("chats"))
        assertFalse(kept, kept.contains("a line nobody reads here"))
        assertFalse(kept, kept.contains("\"top\""))
        assertFalse(kept, kept.contains("game"))
        assertTrue(kept, kept.contains("\"projects\""))
        assertEquals(boards, HomeBoards.parse(kept))
    }

    @Test
    fun parse_anEmptyDocument_hasNoBoards() {
        assertEquals(HomeBoards(), HomeBoards.parse("{}"))
        assertEquals(HomeBoards(), HomeBoards.parse("""{"boards": []}"""))
    }

    @Test
    fun parse_aNullWhereTheFeedHasADefault_takesTheDefault() {
        val boards = HomeBoards.parse("""{"generated": null, "boards": [{"name": "work", "machine": null, "agents": null, "waiting": null, "projects": null}]}""")

        assertEquals(0L, boards.generated)
        val work = boards.boards.single()
        assertNull(work.machine)
        assertEquals(HomeAgents(), work.agents)
        assertEquals(0, work.waiting)
        assertEquals(emptyList<HomeWorkspace>(), work.workspaces)
    }

    @Test
    fun parse_aFeedCutShort_isRefused_withNoneOfItsTextInTheReasonOrACause() {
        val cut = fixture.substringBefore("nobody")

        val e = assertThrows(IllegalArgumentException::class.java) { HomeBoards.parse(cut) }

        assertNull(e.cause)
        assertFalse(e.message.orEmpty(), e.message.orEmpty().contains("a line"))
        assertTrue(e.message.orEmpty(), e.message.orEmpty().startsWith("not a sessions feed"))
    }

    @Test
    fun parse_refusesWhatIsNotAFeed() {
        val notJson = assertThrows(IllegalArgumentException::class.java) { HomeBoards.parse("not json") }
        assertTrue(notJson.message.orEmpty(), notJson.message.orEmpty().startsWith("not a sessions feed"))
        assertFalse(notJson.message.orEmpty(), notJson.message.orEmpty().contains("JSON input"))
        assertThrows(IllegalArgumentException::class.java) { HomeBoards.parse("") }
        assertThrows(IllegalArgumentException::class.java) { HomeBoards.parse("[]") }
        assertThrows(IllegalArgumentException::class.java) { HomeBoards.parse("""{"boards": "x"}""") }
        // A board is named or it is not a board.
        assertThrows(IllegalArgumentException::class.java) { HomeBoards.parse("""{"boards": [{"machine": "box"}]}""") }
        assertThrows(IllegalArgumentException::class.java) { HomeBoards.parse("""{"boards": [{"name": "work", "projects": [{}]}]}""") }
    }
}
