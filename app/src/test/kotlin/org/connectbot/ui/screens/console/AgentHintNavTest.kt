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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.navigation.NavArgs
import org.connectbot.ui.navigation.NavDestinations
import org.connectbot.ui.navigation.openJumpedConsole
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.Agent
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.terminal.AgentSwipe
import solutions.ormus.logos.terminal.AgentSwipe.Direction

/**
 * The hint of an agent swipe over the real navigation: a swipe that opens another agent leaves the console it came from
 * (the new console takes its place, `openJumpedConsole`), so the hint is seen on the console that opens, not on the one that
 * fades out. Each destination holds the hint as `ConsoleScreen` does and swipes through [handleAgentSwipe].
 */
@RunWith(AndroidJUnit4::class)
class AgentHintNavTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: TestNavHostController
    private val swipes = mutableMapOf<String, (Direction) -> Unit>()
    private val logged = mutableListOf<String>()
    private val neverUp = mutableSetOf<String>()

    @Before
    fun clear() {
        AgentHintHandoff.take("t2", Long.MAX_VALUE)
    }

    private fun core(address: String, state: AgentState) = InboxRow(
        Agent(address = address, engine = AgentEngine.CORE, space = "alpha", tab = address, state = state),
        hostId = 4,
        host = "box",
    )

    // t1 needs you, t2 is done, t3 is working: that is their order.
    private val rows = listOf(core("t1", AgentState.NeedsYou()), core("t2", AgentState.Done), core("t3", AgentState.Working))

    private fun watchOf(address: String) = CoreWatchTarget(address, "alpha", address)

    private fun place(address: String) = AgentSwipe.place(AgentSwipe.order(rows)) { it.agent.address == address }

    private fun setContent(start: String) {
        rule.setContent {
            ConnectBotTheme {
                nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
                NavHost(nav, startDestination = NavDestinations.console(4, null, watchOf(start))) {
                    composable(
                        NavDestinations.CONSOLE_ROUTE,
                        arguments = listOf(
                            navArgument(NavArgs.HOST_ID) {
                                type = NavType.LongType
                                defaultValue = 4L
                            },
                        ) +
                            listOf(NavArgs.SESSION, NavArgs.LABEL, NavArgs.WATCH, NavArgs.WATCH_SPACE, NavArgs.WATCH_TAB)
                                .map { name ->
                                    navArgument(name) {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    }
                                },
                    ) { entry ->
                        val watch = entry.arguments?.getString(NavArgs.WATCH).orEmpty()
                        // A console that never lists its bridge (an error screen, a lookup that does not end) never composes the state,
                        // as ConsoleScreen's terminal branch does not.
                        if (watch in neverUp) {
                            Box(Modifier.fillMaxSize().testTag("dest-$watch")) { Text("loading $watch") }
                        } else {
                            val hint = rememberAgentHintState(watch)
                            Box(Modifier.fillMaxSize().testTag("dest-$watch")) {
                                Text("terminal $watch")
                                AgentSwipeHint(hint.hint, hint.fired, Modifier.align(Alignment.TopCenter))
                            }
                            SideEffect {
                                swipes[watch] = { direction -> handleAgentSwipe(place(watch), direction, hint, { logged += it }) { route -> nav.openJumpedConsole(route) } }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun swipe(from: String, direction: Direction) {
        rule.runOnUiThread { swipes.getValue(from)(direction) }
    }

    private fun shown(text: String, over: String): Boolean = rule.onAllNodes(hasText(text, ignoreCase = true) and hasAnyAncestor(hasTestTag("dest-$over")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    // In steps of 50 ms, each followed by an idle sync, as a test that looks at the screen every frame would: with the clock stopped,
    // the effect that starts the hint's 900 ms runs only after a sync, and one big step would end before it.
    private fun elapse(ms: Int) = repeat(ms / 50) {
        rule.mainClock.advanceTimeBy(50)
        rule.waitForIdle()
    }

    private fun here(): String? = nav.currentBackStackEntry?.arguments?.getString(NavArgs.WATCH)

    @Test
    fun aSwipeThatOpensTheNextAgent_showsNextAgentOverThatAgent_andItGoesAfter900ms() {
        setContent("t1")
        rule.mainClock.autoAdvance = false

        swipe("t1", Direction.NEXT)
        elapse(700) // the transition between the two consoles

        assertEquals("t2", here())
        assertEquals("one console, in the place of the first", 1, nav.currentBackStack.value.count { it.destination.route == NavDestinations.CONSOLE_ROUTE })
        assertTrue("Next agent shows over the console that opened", shown("Next agent", over = "t2"))

        elapse(1_000)
        assertTrue("and goes after its 900 ms", !shown("Next agent", over = "t2"))
    }

    @Test
    fun aSwipeToThePreviousAgent_showsPreviousAgentOverThatAgent() {
        setContent("t2")
        rule.mainClock.autoAdvance = false

        swipe("t2", Direction.PREVIOUS)
        elapse(700)

        assertEquals("t1", here())
        assertTrue("Previous agent shows over the console that opened", shown("Previous agent", over = "t1"))
    }

    @Test
    fun atTheEnd_noMoreAgentsShowsOverTheSameConsole_andNothingOpens() {
        setContent("t1")
        rule.mainClock.autoAdvance = false

        swipe("t1", Direction.PREVIOUS)
        elapse(700)

        assertEquals("t1", here())
        assertTrue("No more agents shows over the console that stays", shown("No more agents", over = "t1"))
        assertEquals("nothing is logged for a swipe that opened nothing", emptyList<String>(), logged)
        elapse(1_000)
        assertTrue("and goes after 900 ms", !shown("No more agents", over = "t1"))
    }

    @Test
    fun aSwipeThatOpens_logsItsUsageId() {
        setContent("t1")

        swipe("t1", Direction.NEXT)
        rule.waitForIdle()

        assertEquals(listOf("swipe:next-agent"), logged)
    }

    @Test
    fun aTapWithin2sAfterASwipeWhoseConsoleNeverCameUp_showsNoHintOverTheTappedConsole() {
        setContent("t1")
        neverUp += "t2"
        rule.mainClock.autoAdvance = false

        swipe("t1", Direction.NEXT)
        elapse(700)
        assertEquals("t2", here())
        elapse(300)
        rule.runOnUiThread { nav.openJumpedConsole(NavDestinations.console(4, null, watchOf("t3"))) }
        elapse(700)

        assertEquals("t3", here())
        assertTrue("the hint was for t2, not for a console a tap opened", !shown("Next agent", over = "t3"))
    }

    @Test
    fun aConsoleThatOpensForAnotherReason_showsNoHint() {
        setContent("t1")
        rule.mainClock.autoAdvance = false

        rule.runOnUiThread { nav.openJumpedConsole(NavDestinations.console(4, null, watchOf("t3"))) }
        elapse(700)

        assertEquals("t3", here())
        assertTrue(!shown("Next agent", over = "t3") && !shown("Previous agent", over = "t3") && !shown("No more agents", over = "t3"))
    }
}
