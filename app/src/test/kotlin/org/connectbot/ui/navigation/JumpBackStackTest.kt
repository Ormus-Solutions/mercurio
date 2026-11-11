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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.CoreWatchTarget

/** Back after a core terminal is picked on the Jump sheet, on the real console route. */
@RunWith(AndroidJUnit4::class)
class JumpBackStackTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: TestNavHostController

    private fun host(vararg opened: String) {
        composeTestRule.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            NavHost(nav, startDestination = NavDestinations.HOST_LIST) {
                composable(NavDestinations.HOST_LIST) {}
                composable(NavDestinations.SPACES) {}
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
        opened.forEach { go { nav.navigate(it) } }
    }

    private fun go(block: () -> Unit) {
        composeTestRule.runOnUiThread(block)
        composeTestRule.waitForIdle()
    }

    private fun consoles() = nav.currentBackStack.value.count { it.destination.route == NavDestinations.CONSOLE_ROUTE }

    private fun core(address: String) = NavDestinations.console(1, watch = CoreWatchTarget(address, "alpha", "main"))

    @Test
    fun aPickedCoreTerminal_takesTheConsolesPlace_andBackLeavesToWhereItOpened() {
        host(NavDestinations.console(1))

        go { nav.openJumpedConsole(core("t7")) }

        assertEquals("t7", nav.currentBackStackEntry?.arguments?.getString(NavArgs.WATCH))
        assertEquals(1, consoles())
        go { nav.popBackStack() }
        assertEquals(NavDestinations.HOST_LIST, nav.currentDestination?.route)
    }

    @Test
    fun aRunOfPicks_doesNotPileConsolesUp() {
        host(NavDestinations.console(1))

        go { nav.openJumpedConsole(core("t7")) }
        go { nav.openJumpedConsole(core("t8")) }
        go { nav.openJumpedConsole(core("t9")) }

        assertEquals("t9", nav.currentBackStackEntry?.arguments?.getString(NavArgs.WATCH))
        assertEquals(1, consoles())
    }

    @Test
    fun whatLiesBelowTheConsole_staysBelowThePick() {
        host(NavDestinations.SPACES, NavDestinations.console(1))

        go { nav.openJumpedConsole(core("t7")) }
        go { nav.popBackStack() }

        assertEquals(NavDestinations.SPACES, nav.currentDestination?.route)
    }

    @Test
    fun pickingTheTerminalAlreadyShown_leavesOneConsole() {
        host(core("t7"))

        go { nav.openJumpedConsole(core("t7")) }

        assertEquals(1, consoles())
        go { nav.popBackStack() }
        assertEquals(NavDestinations.HOST_LIST, nav.currentDestination?.route)
    }

    @Test
    fun aConsoleBelowTheOneJumpedFrom_isNotReplaced() {
        host(core("t1"), core("t2"))

        go { nav.openJumpedConsole(core("t3")) }

        assertEquals(listOf("t1", "t3"), nav.currentBackStack.value.filter { it.destination.route == NavDestinations.CONSOLE_ROUTE }.map { it.arguments?.getString(NavArgs.WATCH) })
    }
}
