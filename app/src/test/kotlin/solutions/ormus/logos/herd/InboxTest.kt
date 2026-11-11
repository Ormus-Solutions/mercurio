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

import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Inbox's pure part: rows from both engines, sections, folding, search and the group colour. */
class InboxTest {
    private fun agent(
        address: String,
        state: AgentState,
        space: String = "alpha · ONE",
        tab: String = "work",
        since: Long? = null,
        title: String? = null,
        kind: String? = null,
        pinned: Boolean = false,
    ) = Agent(address, AgentEngine.TUI, null, space, tab, title, kind, state, since, pinned = pinned)

    private fun rows(vararg agents: Agent) = agents.map { Inbox.row(1L, "box", it) }

    @Test
    fun sections_followTheStateOrder_andOmitEmptyOnes() {
        val sections = Inbox.sections(
            rows(
                agent("i", AgentState.Idle),
                agent("u", AgentState.Unknown),
                agent("w", AgentState.Working),
                agent("n", AgentState.NeedsYou()),
            ),
            query = "",
            expanded = emptySet(),
        )
        assertEquals(
            listOf(Inbox.Section.NEEDS_YOU, Inbox.Section.WORKING, Inbox.Section.IDLE, Inbox.Section.UNKNOWN),
            sections.map { it.section },
        )
    }

    @Test
    fun aSection_isRankedLikeTheAgentRank_pinnedFirstThenLongestInState() {
        val s = Inbox.sections(
            rows(
                agent("late", AgentState.Idle, since = 300),
                agent("early", AgentState.Idle, since = 100),
                agent("pin", AgentState.Idle, since = 900, pinned = true),
            ),
            "",
            emptySet(),
        ).single()
        assertEquals(listOf("pin", "early", "late"), s.shown.map { it.agent.address.substringAfterLast('/') })
    }

    @Test
    fun aSectionFoldsAfterThreeRows_andSaysHowManyAreHidden() {
        val many = rows(*(1..7).map { agent("a$it", AgentState.Idle, since = it.toLong()) }.toTypedArray())
        val folded = Inbox.sections(many, "", emptySet()).single()
        assertEquals(3, folded.shown.size)
        assertEquals(4, folded.hidden)
        val open = Inbox.sections(many, "", setOf(Inbox.Section.IDLE)).single()
        assertEquals(7, open.shown.size)
        assertEquals(0, open.hidden)
        assertTrue(open.canFold)
    }

    @Test
    fun threeRowsOrFewer_doNotFold() {
        val s = Inbox.sections(rows(agent("a", AgentState.Done), agent("b", AgentState.Done), agent("c", AgentState.Done)), "", emptySet()).single()
        assertEquals(3, s.shown.size)
        assertEquals(0, s.hidden)
        assertFalse(s.canFold)
    }

    @Test
    fun theSameAddressOnTwoHosts_staysTwoRows() {
        val a = Inbox.row(1L, "box", agent("s/1", AgentState.Idle))
        val b = Inbox.row(2L, "other", agent("s/1", AgentState.Idle))
        assertEquals(2, Inbox.sections(listOf(a, b), "", emptySet()).single().shown.size)
    }

    @Test
    fun theGroup_isThePartBeforeTheDot_orTheWholeName() {
        assertEquals("alpha", Inbox.group("alpha · ONE"))
        assertEquals("alpha", Inbox.group("alpha · TWO · x"))
        assertEquals("solo", Inbox.group("solo"))
        assertEquals("", Inbox.group(""))
    }

    @Test
    fun theAccent_isStablePerGroup_andPinned() {
        assertEquals(0, Inbox.accentIndex("delta"))
        assertEquals(1, Inbox.accentIndex("sigma"))
        assertEquals(2, Inbox.accentIndex("alpha"))
        assertEquals(3, Inbox.accentIndex("kappa"))
        assertEquals(4, Inbox.accentIndex("beta"))
        assertEquals(5, Inbox.accentIndex("gamma"))
    }

    @Test
    fun spacesOfOneGroupShareAColour_andCaseDoesNotMatter() {
        assertEquals(Inbox.accentIndex(Inbox.group("alpha · ONE")), Inbox.accentIndex(Inbox.group("alpha · TWO")))
        assertEquals(Inbox.accentIndex("alpha"), Inbox.accentIndex("ALPHA"))
        assertTrue(Inbox.accentIndex("") in 0 until Inbox.ACCENT_COUNT)
    }

    @Test
    fun theLetter_isTheGroupsFirstCharacterUpperCase_andAQuestionMarkWhenThereIsNone() {
        assertEquals("A", Inbox.letter("alpha · ONE"))
        assertEquals("?", Inbox.letter(""))
    }

    @Test
    fun search_narrowsByEveryWord_acrossSpaceTabTitleKindAndHost() {
        val all = rows(
            agent("a", AgentState.Idle, space = "alpha · ONE", tab = "build", title = "fix parser", kind = "claude"),
            agent("b", AgentState.Idle, space = "beta · TWO", tab = "docs", kind = "codex"),
        )
        fun hits(q: String) = Inbox.sections(all, q, emptySet()).flatMap { it.shown }.map { it.agent.address.substringAfterLast('/') }
        assertEquals(listOf("a", "b"), hits("").sorted())
        assertEquals(listOf("a"), hits("alpha"))
        assertEquals(listOf("a"), hits("PARSER"))
        assertEquals(listOf("b"), hits("two docs"))
        assertEquals(listOf("b"), hits("codex"))
        assertEquals(listOf("a", "b"), hits("box").sorted())
        assertTrue(hits("nothing here").isEmpty())
        assertEquals(listOf("a"), hits("  alpha  "))
    }

    @Test
    fun aSearch_dropsSectionsWithNoHit() {
        val s = Inbox.sections(rows(agent("a", AgentState.Done, space = "alpha · ONE"), agent("b", AgentState.Idle, space = "beta · TWO")), "beta", emptySet())
        assertEquals(listOf(Inbox.Section.IDLE), s.map { it.section })
    }

    @Test
    fun theSecondLine_isTheTitleWhenThereIsOne_elseTheTab() {
        assertEquals("fix parser", Inbox.row(1L, "box", agent("a", AgentState.Idle, tab = "build", title = "fix parser")).detail)
        assertEquals("build", Inbox.row(1L, "box", agent("a", AgentState.Idle, tab = "build")).detail)
        assertEquals("build", Inbox.row(1L, "box", agent("a", AgentState.Idle, tab = "build", title = " ")).detail)
    }

    @Test
    fun theTime_isTheBoardsAge_orNothingWhenUnknown() {
        val r = Inbox.row(1L, "box", agent("a", AgentState.Idle, since = 1_000))
        assertEquals("12m", Inbox.age(r, now = 1_000 + 12 * 60_000L))
        assertNull(Inbox.age(Inbox.row(1L, "box", agent("b", AgentState.Idle)), now = 5))
    }

    @Test
    fun aTuiCard_becomesARowThatOpensItsSessionOnItsHost() {
        val card = BoardCard(
            session = "work", paneId = "p1", tabId = "t1", agent = "claude", state = HerdrAgentStatus.BLOCKED, title = "ask",
            tabLabel = "main", tabNumber = 1, workspaceLabel = "alpha · ONE", workspaceNumber = 1, since = 10,
        )
        val row = Inbox.fromCards(7L, "box", listOf(card)).single()
        assertEquals(7L, row.hostId)
        assertEquals("box", row.agent.machine)
        assertEquals(AgentState.NeedsYou(), row.agent.state)
        assertEquals("work", row.console?.session)
        assertEquals("alpha · ONE", row.console?.workspaceLabel)
    }

    @Test
    fun aCoreAgent_opensTheHostsOwnConsole() {
        val core = Agent("t1", AgentEngine.CORE, "box", "alpha · ONE", "main", state = AgentState.Idle)
        val row = Inbox.fromCore(7L, "box", CoreSource.Result.Agents(listOf(core))).rows.single()
        assertNull(row.console)
        assertEquals(7L, row.hostId)
    }

    @Test
    fun aCoreSourceThatIsOffOrDown_givesNoRows_andTheReasonOnlyWhenDown() {
        val off = Inbox.fromCore(1L, "box", CoreSource.Result.Off)
        assertTrue(off.rows.isEmpty())
        assertNull(off.note)
        val down = Inbox.fromCore(1L, "box", CoreSource.Result.Unavailable("herdr-core is not running"))
        assertTrue(down.rows.isEmpty())
        assertEquals("box: herdr-core is not running", down.note)
    }

    @Test
    fun aCardWithTwoSessions_keepsBothRows_evenWithTheSamePaneId() {
        fun card(session: String) = BoardCard(session, "p1", "t1", null, HerdrAgentStatus.IDLE, null, "main", 1, "alpha · ONE", 1)
        assertEquals(2, Inbox.fromCards(1L, "box", listOf(card("work"), card("review"))).map { it.agent.address }.distinct().size)
    }

    @Test
    fun theRunOfAnUnreachableHost_isNotACrash() {
        val src = CoreSource()
        val r = Inbox.fromCore(1L, "box", src.read(true, "box", 1L) { null as CommandOutput? })
        assertTrue(r.rows.isEmpty())
        assertEquals("box: could not reach the host", r.note)
    }

    @Test
    fun theSameAddressFromBothEnginesOfOneHost_staysTwoRows() {
        val tui = Inbox.row(1L, "box", Agent("t1", AgentEngine.TUI, null, "alpha · ONE", "a", state = AgentState.Idle))
        val core = Inbox.row(1L, "box", Agent("t1", AgentEngine.CORE, "box", "alpha · TWO", "b", state = AgentState.Idle))
        assertEquals(setOf("alpha · ONE", "alpha · TWO"), Inbox.sections(listOf(tui, core), "", emptySet()).single().shown.map { it.agent.space }.toSet())
    }
}
