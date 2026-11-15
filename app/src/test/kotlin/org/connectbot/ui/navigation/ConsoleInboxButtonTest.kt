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
import androidx.navigation.navOptions
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The console's Inbox key: one tap lands on the Inbox from every way into a console (the Inbox,
 * Spaces, the hosts, a notice), and the console leaves the back stack. Each way in builds its stack
 * as the app's graph does: the Inbox drops itself when it opens a console unless it is the bottom,
 * Spaces and the hosts push the console, a notice opens it over the start destination. The app can
 * start on the hosts home or on the Inbox, so each way in runs from both.
 */
@RunWith(AndroidJUnit4::class)
class ConsoleInboxButtonTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: TestNavHostController

    private fun host(start: String) {
        composeTestRule.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            NavHost(nav, startDestination = start) {
                composable(NavDestinations.HOST_LIST) {}
                composable(NavDestinations.INBOX) {}
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
    }

    private fun go(block: () -> Unit) {
        composeTestRule.runOnUiThread(block)
        composeTestRule.waitForIdle()
    }

    private fun routes(): List<String> = nav.currentBackStack.value.mapNotNull { it.destination.route }

    private fun consoles() = routes().count { it == NavDestinations.CONSOLE_ROUTE }

    private fun inboxes() = routes().count { it == NavDestinations.INBOX }

    private val console = NavDestinations.console(1)

    /** The Inbox opens a console as the graph's Inbox route does: it leaves the stack, unless it is the bottom. */
    private fun openFromTheInbox() {
        go {
            nav.navigate(console) {
                if (nav.previousBackStackEntry != null) popUpTo(NavDestinations.INBOX) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    private fun openFromSpaces() {
        go { nav.navigate(console, navOptions { launchSingleTop = true }) }
    }

    private fun openFromTheHosts() {
        go { nav.navigate(console) }
    }

    private fun openFromANotice() {
        go { nav.openConsoleRoute(console) }
    }

    /** Taps the Inbox key in the console that the way in opened, and checks where it lands. */
    private fun assertTheKeyLandsOnTheInbox(start: String, via: String) {
        assertEquals("$via from $start: a console is open", 1, consoles())
        assertEquals("$via from $start: the console is the screen", NavDestinations.CONSOLE_ROUTE, nav.currentDestination?.route)

        go { nav.openInboxFromConsole() }

        val stack = routes().toString()
        assertEquals("$via from $start: lands on the Inbox, stack $stack", NavDestinations.INBOX, nav.currentDestination?.route)
        assertEquals("$via from $start: the console is off the stack, stack $stack", 0, consoles())
        assertEquals("$via from $start: one Inbox on the stack, stack $stack", 1, inboxes())
        // Back from the Inbox is the hosts home, as from any place: it never returns into the session.
        go { nav.backOrHosts() }
        assertEquals("$via from $start: Back from the Inbox is the hosts home, stack ${routes()}", NavDestinations.HOST_LIST, nav.currentDestination?.route)
    }

    private val onTheHosts = NavDestinations.HOST_LIST
    private val onTheInbox = NavDestinations.INBOX

    private fun fromTheInbox(start: String) {
        host(start)
        if (start != NavDestinations.INBOX) go { nav.selectPlace(HomePlace.INBOX) }
        openFromTheInbox()
        assertTheKeyLandsOnTheInbox(start, "Inbox")
    }

    private fun fromSpaces(start: String) {
        host(start)
        go { nav.selectPlace(HomePlace.SPACES) }
        openFromSpaces()
        assertTheKeyLandsOnTheInbox(start, "Spaces")
    }

    private fun fromTheHostsHome(start: String) {
        host(start)
        if (start != NavDestinations.HOST_LIST) go { nav.selectPlace(HomePlace.HOSTS) }
        openFromTheHosts()
        assertTheKeyLandsOnTheInbox(start, "Hosts")
    }

    private fun fromANotice(start: String) {
        host(start)
        openFromANotice()
        assertTheKeyLandsOnTheInbox(start, "notice")
    }

    @Test
    fun aConsoleOpenedFromTheInbox_theKeyLandsOnTheInbox_whenTheAppOpenedOnTheInbox() = fromTheInbox(onTheInbox)

    @Test
    fun aConsoleOpenedFromTheInbox_theKeyLandsOnTheInbox_whenTheAppOpenedOnTheHosts() = fromTheInbox(onTheHosts)

    @Test
    fun aConsoleOpenedFromSpaces_theKeyLandsOnTheInbox_whenTheAppOpenedOnTheInbox() = fromSpaces(onTheInbox)

    @Test
    fun aConsoleOpenedFromSpaces_theKeyLandsOnTheInbox_whenTheAppOpenedOnTheHosts() = fromSpaces(onTheHosts)

    @Test
    fun aConsoleOpenedFromTheHosts_theKeyLandsOnTheInbox_whenTheAppOpenedOnTheInbox() = fromTheHostsHome(onTheInbox)

    @Test
    fun aConsoleOpenedFromTheHosts_theKeyLandsOnTheInbox_whenTheAppOpenedOnTheHosts() = fromTheHostsHome(onTheHosts)

    @Test
    fun aConsoleOpenedFromANotice_theKeyLandsOnTheInbox_whenTheAppOpenedOnTheInbox() = fromANotice(onTheInbox)

    @Test
    fun aConsoleOpenedFromANotice_theKeyLandsOnTheInbox_whenTheAppOpenedOnTheHosts() = fromANotice(onTheHosts)

    @Test
    fun aNoticeOverAStackWithSpaces_theKeyLandsOnTheInbox() {
        host(onTheHosts)
        go { nav.selectPlace(HomePlace.SPACES) }
        openFromANotice()

        go { nav.openInboxFromConsole() }

        assertEquals(NavDestinations.INBOX, nav.currentDestination?.route)
        assertEquals(0, consoles())
    }
}
