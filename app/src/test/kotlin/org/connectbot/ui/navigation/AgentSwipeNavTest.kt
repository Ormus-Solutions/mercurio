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

package org.connectbot.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.screens.console.AgentSwipeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.Agent
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.terminal.AgentSwipe

/**
 * Where a swipe lands: the route of the row it moves to is the one the Inbox opens that row by, and opening it
 * through the Jump sheet's call puts the new console in the place of this one (no pile of consoles, Back
 * returns to where the first one was opened from).
 */
@RunWith(AndroidJUnit4::class)
class AgentSwipeNavTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: TestNavHostController

    private fun core(address: String, hostId: Long = 4) = InboxRow(
        Agent(address = address, engine = AgentEngine.CORE, space = "alpha one", tab = "build & test", state = AgentState.Working),
        hostId = hostId,
        host = "box",
    )

    private fun host(start: String) {
        composeTestRule.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            NavHost(nav, startDestination = start) {
                composable(NavDestinations.HOST_LIST) {}
                composable(NavDestinations.INBOX) {}
                composable(
                    NavDestinations.CONSOLE_ROUTE,
                    arguments = listOf(navArgument(NavArgs.HOST_ID) { type = NavType.LongType }) +
                        listOf(NavArgs.SESSION, NavArgs.LABEL, NavArgs.WATCH, NavArgs.WATCH_SPACE, NavArgs.WATCH_TAB)
                            .map { name ->
                                navArgument(name) {
                                    type = NavType.StringType
                                    nullable = true
                                }
                            },
                ) {}
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun go(block: () -> Unit) {
        composeTestRule.runOnUiThread(block)
        composeTestRule.waitForIdle()
    }

    private fun routes() = nav.currentBackStack.value.mapNotNull { it.destination.route }

    // What a swipe does with the place: the route to open, or nothing.

    private fun placeOf(address: String, vararg rows: InboxRow) = AgentSwipe.place(AgentSwipe.order(rows.toList())) { it.agent.address == address }

    private fun rows() = listOf(
        core("t1").let { it.copy(agent = it.agent.copy(state = AgentState.NeedsYou())) },
        core("t2").let { it.copy(agent = it.agent.copy(state = AgentState.Done)) },
        core("t3"),
    ).toTypedArray()

    @Test
    fun open_next_isTheRouteOfTheNextRow_previous_theRouteOfThePreviousRow() {
        val place = placeOf("t2", *rows())!!

        assertEquals(AgentSwipeSource.route(core("t3")), AgentSwipeSource.open(place, AgentSwipe.Direction.NEXT))
        assertEquals(AgentSwipeSource.route(core("t1")), AgentSwipeSource.open(place, AgentSwipe.Direction.PREVIOUS))
    }

    @Test
    fun open_atTheEnds_isNothing_noWrap() {
        assertNull(AgentSwipeSource.open(placeOf("t1", *rows())!!, AgentSwipe.Direction.PREVIOUS))
        assertNull(AgentSwipeSource.open(placeOf("t3", *rows())!!, AgentSwipe.Direction.NEXT))
    }

    @Test
    fun open_withNoPlaceYet_isNothing() {
        assertNull(AgentSwipeSource.open(null, AgentSwipe.Direction.NEXT))
        assertNull(AgentSwipeSource.open(null, AgentSwipe.Direction.PREVIOUS))
    }

    @Test
    fun aCoreRow_opensByTheRouteTheInboxUses_withItsWatch() {
        val row = core("box/t7")

        assertEquals(NavDestinations.console(4, null, CoreWatchTarget("box/t7", "alpha one", "build & test")), AgentSwipeSource.route(row))
    }

    @Test
    fun aTuiRow_opensItsHerdrConsole() {
        val row = InboxRow(
            Agent(address = "work/w1:p1", engine = AgentEngine.TUI, space = "ws", tab = "tab", state = AgentState.Working),
            hostId = 4,
            host = "box",
            console = HerdrConsole("work", workspaceLabel = "ws"),
        )

        assertEquals(NavDestinations.console(4, HerdrConsole("work", workspaceLabel = "ws"), null), AgentSwipeSource.route(row))
    }

    @Test
    fun aSwipeFromAConsoleOpenedFromTheInbox_putsTheNextConsoleInItsPlace() {
        host(NavDestinations.HOST_LIST)
        go { nav.selectPlace(HomePlace.INBOX) }
        // The Inbox opens a console: it leaves the stack, the hosts home stays below.
        go { nav.navigate(AgentSwipeSource.route(core("t1"))) { popUpTo(NavDestinations.INBOX) { inclusive = true } } }
        assertEquals(listOf(NavDestinations.HOST_LIST, NavDestinations.CONSOLE_ROUTE), routes())

        go { nav.openJumpedConsole(AgentSwipeSource.route(core("t2"))) }
        go { nav.openJumpedConsole(AgentSwipeSource.route(core("t3"))) }

        assertEquals("one console, in the place of the first", listOf(NavDestinations.HOST_LIST, NavDestinations.CONSOLE_ROUTE), routes())
        assertEquals("t3", nav.currentBackStackEntry?.arguments?.getString(NavArgs.WATCH))
        go { nav.backOrHosts() }
        assertEquals("Back from the last console goes where the first was opened from", NavDestinations.HOST_LIST, nav.currentDestination?.route)
    }

    @Test
    fun aSwipeToTheSameConsoleAgain_isAFreshConsoleOfThatTerminal() {
        host(NavDestinations.HOST_LIST)
        go { nav.navigate(AgentSwipeSource.route(core("t1"))) }

        go { nav.openJumpedConsole(AgentSwipeSource.route(core("t2"))) }
        go { nav.openJumpedConsole(AgentSwipeSource.route(core("t1"))) }

        assertEquals(listOf(NavDestinations.HOST_LIST, NavDestinations.CONSOLE_ROUTE), routes())
        assertEquals("t1", nav.currentBackStackEntry?.arguments?.getString(NavArgs.WATCH))
    }
}
