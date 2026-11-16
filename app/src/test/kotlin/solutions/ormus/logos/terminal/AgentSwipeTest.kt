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

package solutions.ormus.logos.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import solutions.ormus.logos.herd.Agent
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.herd.NeedsYouKind
import solutions.ormus.logos.terminal.AgentSwipe.Claim
import solutions.ormus.logos.terminal.AgentSwipe.Direction

/**
 * The agent swipe of a watched herdr-core console: when a one-finger touch is a swipe, which way it
 * goes, and which rows it moves between (the Inbox's order, one place for the chip). Distances in dp,
 * speeds in dp per second, y grows downward.
 */
class AgentSwipeTest {
    private fun core(address: String, state: AgentState, space: String = "alpha", tab: String = address, since: Long? = null, pinned: Boolean = false, parent: String? = null, hostId: Long = 1) = InboxRow(
        Agent(address = address, engine = AgentEngine.CORE, space = space, tab = tab, state = state, since = since, pinned = pinned, parent = parent),
        hostId = hostId,
        host = "box",
    )

    private fun tui(session: String, pane: String, state: AgentState, hostId: Long = 1) = InboxRow(
        Agent(address = "$session/$pane", engine = AgentEngine.TUI, space = "ws", tab = pane, state = state),
        hostId = hostId,
        host = "box",
        console = HerdrConsole(session),
    )

    private val needs = AgentState.NeedsYou(NeedsYouKind.QUESTION)

    // The touch judged as it moves.

    @Test
    fun aTouchUnder20dp_isStillWaiting() {
        assertEquals(Claim.WAIT, AgentSwipe.claim(-19f, 0f))
        assertEquals(Claim.WAIT, AgentSwipe.claim(0f, 0f))
    }

    @Test
    fun aTouchAcross_isTakenOnceItPasses20dp_leftOrRight() {
        assertEquals(Claim.CLAIM, AgentSwipe.claim(-21f, 4f))
        assertEquals(Claim.CLAIM, AgentSwipe.claim(21f, -4f))
    }

    @Test
    fun aTouchUpOrDown_isLetGoAtOnce_soTheTerminalScrolls() {
        assertEquals(Claim.RELEASE, AgentSwipe.claim(0f, 25f))
        assertEquals(Claim.RELEASE, AgentSwipe.claim(3f, -80f))
    }

    @Test
    fun aDiagonalTouch_isLetGo_andNeverFastEnoughToChangeThat() {
        // Across must lead up or down by 2 to 1 (HerdrSwipes.horizontal).
        assertEquals(Claim.RELEASE, AgentSwipe.claim(-30f, 20f))
        assertEquals(Claim.RELEASE, AgentSwipe.claim(-30f, -30f))
    }

    // The swipe a taken touch makes when it lifts.

    @Test
    fun leftIsNext_rightIsPrevious_asTheHerdrTabSwipeTurnsAPage() {
        assertEquals(Direction.NEXT, AgentSwipe.classify(-80f, 4f, -200f))
        assertEquals(Direction.PREVIOUS, AgentSwipe.classify(80f, -4f, 200f))
    }

    @Test
    fun aShortSlowSwipe_doesNotFire_butAShortFastOneDoes() {
        assertNull(AgentSwipe.classify(-40f, 0f, -200f))
        assertEquals(Direction.NEXT, AgentSwipe.classify(-40f, 0f, -600f))
        assertNull("under the 20 dp judging distance", AgentSwipe.classify(-15f, 0f, -2_000f))
    }

    @Test
    fun aSwipeFastTheOtherWay_doesNotFire() {
        // Moved left 40 dp but the finger leaves going right: a turn back, not a flick.
        assertNull(AgentSwipe.classify(-40f, 0f, 700f))
    }

    @Test
    fun aTouchThatEndsOnAnotherAxis_doesNotFire() {
        assertNull(AgentSwipe.classify(-80f, 70f, -400f))
        assertNull(AgentSwipe.classify(5f, -90f, 0f))
    }

    // The order: what the Inbox shows.

    @Test
    fun theOrder_isTheInboxsSections_needsYouDoneWorkingIdleUnknown() {
        val rows = listOf(
            core("t1", AgentState.Idle),
            core("t2", AgentState.Working),
            core("t3", needs),
            core("t4", AgentState.Done),
            core("t5", AgentState.Unknown),
        )

        assertEquals(listOf("t3", "t4", "t2", "t1", "t5"), AgentSwipe.order(rows).map { it.agent.address })
    }

    @Test
    fun theOrder_inASection_isPinnedFirst_thenTheOneWaitingLongest_thenTheAddress() {
        val rows = listOf(
            core("t1", AgentState.Working, since = 500),
            core("t2", AgentState.Working, since = 100),
            core("t3", AgentState.Working, since = 900, pinned = true),
            core("t5", AgentState.Working, since = 100),
        )

        assertEquals(listOf("t3", "t2", "t5", "t1"), AgentSwipe.order(rows).map { it.agent.address })
    }

    @Test
    fun theOrder_isTheInboxsOwnOrder_withEveryRowOfAFoldedSection() {
        // Five Working rows: the Inbox folds the section to 3 (Inbox.FOLD) until it is opened; the swipe reaches all five.
        val rows = (1..5).map { core("t$it", AgentState.Working, since = it.toLong()) } + core("t9", needs)

        val sections = Inbox.sections(rows, "", emptySet())
        assertEquals(3, sections.first { it.section == Inbox.Section.WORKING }.shown.size)
        assertEquals(sections.flatMap { it.rows }.map { it.key }, AgentSwipe.order(rows).map { it.key })
        assertEquals(6, AgentSwipe.order(rows).size)
    }

    @Test
    fun theOrder_foldsHelpersUnderTheirParent_asTheInboxDoes() {
        val rows = listOf(
            core("t1", AgentState.Working),
            core("t2", AgentState.Idle, parent = "t1"),
            core("t3", AgentState.Done),
        )

        val order = AgentSwipe.order(rows)

        assertEquals(listOf("t3", "t1"), order.map { it.agent.address })
        assertEquals(listOf("t2"), order.first { it.agent.address == "t1" }.helperRows.map { it.agent.address })
    }

    @Test
    fun aParentWhoseHelperNeedsYou_sitsInNeedsYou() {
        val rows = listOf(
            core("t1", AgentState.Working),
            core("t2", needs, parent = "t1"),
            core("t3", AgentState.Done),
        )

        assertEquals(listOf("t1", "t3"), AgentSwipe.order(rows).map { it.agent.address })
    }

    @Test
    fun aCoreTerminalThatCannotBeWatched_isLeftOut() {
        val rows = listOf(core("t1", needs), core("bad address", AgentState.Working), core("t2", AgentState.Idle))

        assertEquals(listOf("t1", "t2"), AgentSwipe.order(rows).map { it.agent.address })
    }

    @Test
    fun aHerdrSessionsAgent_isNotInTheRun_soASwipeNeverLandsInAHerdrConsole() {
        val rows = listOf(
            core("t1", needs),
            tui("default", "w1:p1", needs),
            core("t2", AgentState.Working),
            tui("default", "w1:p2", AgentState.Done),
        )

        assertEquals(listOf("t1", "t2"), AgentSwipe.order(rows).map { it.agent.address })
    }

    // The place.

    @Test
    fun thePlace_isTheCurrentRowsNumber_withItsNeighbours() {
        val order = AgentSwipe.order(listOf(core("t1", needs), core("t2", AgentState.Done), core("t3", AgentState.Working)))

        val place = AgentSwipe.place(order) { it.agent.address == "t2" }!!

        assertEquals(1, place.index)
        assertEquals(2, place.number)
        assertEquals(3, place.total)
        assertEquals("t1", place.previous?.agent?.address)
        assertEquals("t3", place.next?.agent?.address)
        assertEquals("t3", place.toward(Direction.NEXT)?.agent?.address)
        assertEquals("t1", place.toward(Direction.PREVIOUS)?.agent?.address)
    }

    @Test
    fun atTheEnds_thereIsNoNeighbourPastThem_noWrap() {
        val order = AgentSwipe.order(listOf(core("t1", needs), core("t2", AgentState.Done)))

        val first = AgentSwipe.place(order) { it.agent.address == "t1" }!!
        val last = AgentSwipe.place(order) { it.agent.address == "t2" }!!

        assertNull(first.previous)
        assertNull(first.toward(Direction.PREVIOUS))
        assertEquals("t2", first.next?.agent?.address)
        assertNull(last.next)
        assertNull(last.toward(Direction.NEXT))
    }

    @Test
    fun aTerminalNotInTheOrder_hasNoPlace() {
        val order = AgentSwipe.order(listOf(core("t1", needs), core("t2", AgentState.Done)))

        assertNull(AgentSwipe.place(order) { it.agent.address == "t9" })
        assertNull(AgentSwipe.place(emptyList()) { true })
    }

    @Test
    fun aHelperTerminal_readsItsParentsPlace() {
        val order = AgentSwipe.order(
            listOf(
                core("t1", needs),
                core("t2", AgentState.Working),
                core("t3", AgentState.Idle, parent = "t2"),
                core("t4", AgentState.Idle),
            ),
        )

        val place = AgentSwipe.place(order) { it.agent.address == "t3" }!!

        assertEquals("t2", order[place.index].agent.address)
        assertEquals(2, place.number)
        assertEquals("t4", place.next?.agent?.address)
        assertEquals("t1", place.previous?.agent?.address)
    }

    @Test
    fun thePlaceMovesWithTheOrder_whenAStateChanges() {
        val before = AgentSwipe.order(listOf(core("t1", AgentState.Working), core("t2", AgentState.Working, since = 1)))
        val after = AgentSwipe.order(listOf(core("t1", needs), core("t2", AgentState.Working, since = 1)))

        assertEquals(2, AgentSwipe.place(before) { it.agent.address == "t1" }!!.number)
        assertEquals(1, AgentSwipe.place(after) { it.agent.address == "t1" }!!.number)
    }

    // The hint over the terminal after a swipe fired.

    @Test
    fun theHint_saysWhereTheSwipeWent_orThatThereIsNoMore() {
        val order = AgentSwipe.order(listOf(core("t1", needs), core("t2", AgentState.Done), core("t3", AgentState.Working)))
        val middle = AgentSwipe.place(order) { it.agent.address == "t2" }!!
        val first = AgentSwipe.place(order) { it.agent.address == "t1" }!!
        val last = AgentSwipe.place(order) { it.agent.address == "t3" }!!

        assertEquals(AgentSwipe.Hint.NEXT, AgentSwipe.hint(middle, Direction.NEXT))
        assertEquals(AgentSwipe.Hint.PREVIOUS, AgentSwipe.hint(middle, Direction.PREVIOUS))
        assertEquals(AgentSwipe.Hint.NONE_LEFT, AgentSwipe.hint(first, Direction.PREVIOUS))
        assertEquals(AgentSwipe.Hint.NONE_LEFT, AgentSwipe.hint(last, Direction.NEXT))
        assertEquals(AgentSwipe.Hint.NEXT, AgentSwipe.hint(first, Direction.NEXT))
    }

    @Test
    fun theHint_withNoPlaceYet_saysThereIsNoMore() {
        assertEquals(AgentSwipe.Hint.NONE_LEFT, AgentSwipe.hint(null, Direction.NEXT))
    }

    @Test
    fun theUsageIds_areOnePerDirection() {
        assertEquals("swipe:next-agent", AgentSwipe.usageId(Direction.NEXT))
        assertEquals("swipe:prev-agent", AgentSwipe.usageId(Direction.PREVIOUS))
    }
}
