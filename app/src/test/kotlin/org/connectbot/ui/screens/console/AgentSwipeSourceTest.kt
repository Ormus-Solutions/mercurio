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

package org.connectbot.ui.screens.console

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.Agent
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentSince
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.CoreRead
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrTabInfo
import solutions.ormus.logos.herd.HerdrWorkspaceInfo
import solutions.ormus.logos.herd.NeedsYouKind
import solutions.ormus.logos.terminal.AgentSwipe

/**
 * The rows a watched console moves between: what the Inbox shows, rebuilt from the Herd sides of the open consoles
 * (the Inbox view model is not there while a console shows). Several hosts of one machine are one machine; the
 * freshest herdr-core read of a machine counts; a terminal of the same name on another machine is another row.
 */
class AgentSwipeSourceTest {
    private fun host(id: Long, name: String, hostname: String = "$name.example", user: String = "u", postLogin: String? = null) = Host(id = id, nickname = name, hostname = hostname, username = user, port = 22, protocol = "ssh", postLogin = postLogin)

    private fun core(address: String, state: AgentState = AgentState.Working, space: String = "alpha", since: Long? = null) = Agent(address = address, engine = AgentEngine.CORE, space = space, tab = address, state = state, since = since)

    private fun read(at: Long, vararg agents: Agent) = CoreRead(CoreSource.Result.Agents(agents.toList()), at)

    private val needs = AgentState.NeedsYou(NeedsYouKind.QUESTION)

    private fun placeOf(reads: List<AgentSwipeSource.Read>, host: Host, address: String) = AgentSwipeSource.placeOf(reads, host, CoreWatchTarget(address, "alpha", address))

    @Test
    fun oneMachine_itsTerminalsInTheInboxOrder_withThePlaceOfTheWatchedOne() {
        val box = host(1, "box")
        val reads = listOf(AgentSwipeSource.Read(box, core = read(10, core("t1"), core("t2", needs), core("t3", AgentState.Done))))

        val place = placeOf(reads, box, "t3")!!

        assertEquals(2, place.number)
        assertEquals(3, place.total)
        assertEquals("t2", place.previous?.agent?.address)
        assertEquals("the Working one follows the Done one", "t1", place.next?.agent?.address)
    }

    @Test
    fun theRowsOpenOnThePrimaryHostOfTheMachine() {
        val box = host(1, "box")
        val reads = listOf(AgentSwipeSource.Read(box, core = read(10, core("t1"), core("t2", needs))))

        val place = placeOf(reads, box, "t2")!!

        assertEquals(1L, place.next?.hostId)
    }

    @Test
    fun twoHostsOfOneMachine_areOneMachine_andTheFreshestReadCounts() {
        // The same machine, two saved logins; one console each. Their reads differ: the later one has a new terminal.
        val plain = host(1, "box", hostname = "box.example", postLogin = "herdr")
        val other = host(2, "box tmux", hostname = "box.example", postLogin = null)
        val reads = listOf(
            AgentSwipeSource.Read(other, core = read(20, core("t1"), core("t2"), core("t3"))),
            AgentSwipeSource.Read(plain, core = read(10, core("t1"), core("t2"))),
        )

        val place = placeOf(reads, plain, "t2")!!

        assertEquals("each terminal once, from the later read", 3, place.total)
        assertEquals("the primary is the plain herdr login", 1L, place.previous?.hostId)
    }

    @Test
    fun aConsoleOnAnotherLoginOfTheMachine_isTheSameTerminal() {
        val plain = host(1, "box", hostname = "box.example", postLogin = "herdr")
        val other = host(2, "box tmux", hostname = "box.example", postLogin = null)
        val reads = listOf(
            AgentSwipeSource.Read(plain, core = read(10, core("t1"), core("t2"))),
            AgentSwipeSource.Read(other, core = read(10, core("t1"), core("t2"))),
        )

        assertEquals(2, placeOf(reads, other, "t2")!!.number)
    }

    @Test
    fun twoMachines_bothInOneOrder_theSwipeCrossesFromOneToTheOther() {
        val one = host(1, "one")
        val two = host(2, "two")
        val reads = listOf(
            AgentSwipeSource.Read(one, core = read(10, core("t1", AgentState.Working), core("t2", AgentState.Idle))),
            AgentSwipeSource.Read(two, core = read(10, core("t1", needs, space = "beta"))),
        )

        // one/t1 Working, one/t2 Idle, two/t1 NeedsYou: two's terminal is first, one's two follow.
        val place = placeOf(reads, one, "t1")!!

        assertEquals(2, place.number)
        assertEquals(3, place.total)
        assertEquals("two", place.previous?.host)
        assertEquals(2L, place.previous?.hostId)
        assertEquals("one", place.next?.host)
    }

    @Test
    fun aTerminalOfTheSameNameOnAnotherMachine_isNotTheWatchedOne() {
        val one = host(1, "one")
        val two = host(2, "two")
        val reads = listOf(
            AgentSwipeSource.Read(one, core = read(10, core("t1", needs))),
            AgentSwipeSource.Read(two, core = read(10, core("t1", AgentState.Idle))),
        )

        assertEquals(1, placeOf(reads, one, "t1")!!.number)
        assertEquals(2, placeOf(reads, two, "t1")!!.number)
    }

    @Test
    fun aMachineThatDidNotAnswerWithAgents_addsNoRows_theOthersStay() {
        val one = host(1, "one")
        val two = host(2, "two")
        val reads = listOf(
            AgentSwipeSource.Read(one, core = read(10, core("t1"), core("t2"))),
            AgentSwipeSource.Read(two, core = CoreRead(CoreSource.Result.Unavailable("no herdr-core"), 10)),
            AgentSwipeSource.Read(host(3, "three"), core = null),
        )

        assertEquals(2, placeOf(reads, one, "t1")!!.total)
    }

    @Test
    fun aTerminalThatIsNotInAnyRead_hasNoPlace() {
        val box = host(1, "box")
        val reads = listOf(AgentSwipeSource.Read(box, core = read(10, core("t1"), core("t2"))))

        assertNull(placeOf(reads, box, "t9"))
        assertNull(placeOf(emptyList(), box, "t1"))
    }

    private fun snapshot(vararg status: HerdrAgentStatus) = HerdrSessionSnapshot(
        workspaces = listOf(HerdrWorkspaceInfo("w1", label = "alpha", number = 1)),
        tabs = status.indices.map { HerdrTabInfo("w1:t$it", workspaceId = "w1", label = "tab$it", number = it + 1) },
        agents = status.mapIndexed { i, s -> HerdrAgentInfo(paneId = "w1:p$i", workspaceId = "w1", tabId = "w1:t$i", agent = "claude", agentStatus = s) },
    )

    // A Herdr session's agents are not in the run: a swipe goes between herdr-core terminals only.

    @Test
    fun aMachinesConsoleWhoseOwnReadHasNotCome_stillHasAPlace_fromAnotherLoginsRead() {
        // One machine, two saved logins; the watched console is on the second, whose first poll has not answered.
        val plain = host(1, "box", hostname = "box.example", postLogin = "herdr")
        val other = host(2, "box tmux", hostname = "box.example", postLogin = null)
        val reads = listOf(
            AgentSwipeSource.Read(plain, core = read(10, core("t1"), core("t2"))),
            AgentSwipeSource.Read(other, core = null),
        )

        val place = placeOf(reads, other, "t1")!!

        assertEquals(2, place.total)
        assertEquals("t2", place.next?.agent?.address)
        assertEquals("the machine's primary login", 1L, place.next?.hostId)
    }

    @Test
    fun aMachineWithTwoSavedLogins_andAConsoleOnlyOnTheSecond_opensOnThatLogin() {
        // Known limit: the Inbox groups every saved host, so its primary is the plain herdr login (host 1); the swipe sees
        // only the hosts of open consoles, so with only host 2 open its rows are on host 2, and a terminal the Inbox
        // opens on host 1 and the same one a swipe opens on host 2 are two watch consoles. Needs two saved logins of a machine.
        val other = host(2, "box tmux", hostname = "box.example", postLogin = null)
        val reads = listOf(AgentSwipeSource.Read(other, core = read(10, core("t1"), core("t2"))))

        assertEquals(2L, placeOf(reads, other, "t1")!!.next?.hostId)
    }

    // The flow the console collects.

    private fun bridgeOf(host: Host, herd: HerdSession?): TerminalBridge {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(host)
        `when`(bridge.herd).thenReturn(herd)
        return bridge
    }

    private fun herdOf(coreRead: MutableStateFlow<CoreRead?>, snapshot: HerdrSessionSnapshot? = null): HerdSession {
        val herd = mock(HerdSession::class.java)
        `when`(herd.snapshot).thenReturn(MutableStateFlow(snapshot))
        `when`(herd.others).thenReturn(MutableStateFlow<Map<String, HerdrSessionSnapshot>>(emptyMap()))
        `when`(herd.sessions).thenReturn(MutableStateFlow(listOf("default")))
        `when`(herd.coreRead).thenReturn(coreRead)
        `when`(herd.since).thenReturn(AgentSince(clock = { 0L }))
        return herd
    }

    @Test
    fun theFlow_followsTheCoreReadOfTheOpenConsoles() = runTest {
        val box = host(1, "box")
        val coreRead = MutableStateFlow<CoreRead?>(null)
        val bridge = bridgeOf(box, herdOf(coreRead))
        val places = AgentSwipeSource.places(flowOf(listOf(bridge)), bridge, CoreWatchTarget("t1", "alpha", "t1"))

        assertNull("before the first read there is no place", places.first())

        coreRead.value = read(10, core("t1"), core("t2"))
        assertEquals(2, places.first { it != null }!!.total)

        coreRead.value = read(20, core("t1", needs), core("t2"), core("t3"))
        assertEquals(3, places.first { it != null && it.total == 3 }!!.total)
    }

    @Test
    fun theFlow_leavesOutTheAgentsOfAHerdrSession() = runTest {
        val box = host(1, "box")
        val coreRead = MutableStateFlow<CoreRead?>(read(10, core("t1"), core("t2")))
        val bridge = bridgeOf(box, herdOf(coreRead, snapshot(HerdrAgentStatus.BLOCKED, HerdrAgentStatus.WORKING)))

        val places = AgentSwipeSource.places(flowOf(listOf(bridge)), bridge, CoreWatchTarget("t1", "alpha", "t1"))

        assertEquals("the two Herdr agents of the open session are not rows of the run", 2, places.first { it != null }!!.total)
    }

    @Test
    fun theFlow_skipsAConsoleWithNoHerdSide_andAnEmptyList() = runTest {
        val box = host(1, "box")
        val coreRead = MutableStateFlow<CoreRead?>(read(10, core("t1"), core("t2")))
        val bridge = bridgeOf(box, herdOf(coreRead))
        val telnet = bridgeOf(host(7, "tel"), null)

        val places = AgentSwipeSource.places(flowOf(listOf(telnet, bridge)), bridge, CoreWatchTarget("t2", "alpha", "t2"))
        assertEquals(2, places.first { it != null }!!.number)

        assertNull(AgentSwipeSource.places(flowOf(emptyList()), bridge, CoreWatchTarget("t2", "alpha", "t2")).first())
    }
}
