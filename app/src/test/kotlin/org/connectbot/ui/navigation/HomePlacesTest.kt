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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The bottom bar's places, the bar itself, and moving between the places. */
@RunWith(AndroidJUnit4::class)
class HomePlacesTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theBarOffersWhatIsOn_andTheHostsHomeAlways() {
        assertEquals(listOf(HomePlace.INBOX, HomePlace.SPACES, HomePlace.HOSTS), HomePlace.available(inbox = true, core = true))
        assertEquals(listOf(HomePlace.INBOX, HomePlace.HOSTS), HomePlace.available(inbox = true, core = false))
        assertEquals(listOf(HomePlace.SPACES, HomePlace.HOSTS), HomePlace.available(inbox = false, core = true))
        assertEquals(listOf(HomePlace.HOSTS), HomePlace.available(inbox = false, core = false))
    }

    @Test
    fun aRouteIsAPlaceOrNot() {
        assertEquals(HomePlace.INBOX, HomePlace.of(NavDestinations.INBOX))
        assertEquals(HomePlace.SPACES, HomePlace.of(NavDestinations.SPACES))
        assertEquals(HomePlace.HOSTS, HomePlace.of(NavDestinations.HOST_LIST))
        assertNull(HomePlace.of(NavDestinations.SETTINGS))
        assertNull(HomePlace.of(NavDestinations.CONSOLE_ROUTE))
        assertNull(HomePlace.of(null))
    }

    @Test
    fun theBarShowsOnAPlaceOfTheOnesOn_andNowhereElse() {
        val all = HomePlace.entries
        assertEquals(all, HomePlace.shown(NavDestinations.INBOX, inbox = true, core = true, makingShortcut = false))
        assertEquals(all, HomePlace.shown(NavDestinations.HOST_LIST, inbox = true, core = true, makingShortcut = false))
        // Not a place, a shortcut pick, one place alone, or a place that is switched off: no bar.
        assertEquals(emptyList<HomePlace>(), HomePlace.shown(NavDestinations.SETTINGS, inbox = true, core = true, makingShortcut = false))
        assertEquals(emptyList<HomePlace>(), HomePlace.shown(NavDestinations.HOST_LIST, inbox = true, core = true, makingShortcut = true))
        assertEquals(emptyList<HomePlace>(), HomePlace.shown(NavDestinations.HOST_LIST, inbox = false, core = false, makingShortcut = false))
        assertEquals(emptyList<HomePlace>(), HomePlace.shown(NavDestinations.INBOX, inbox = false, core = true, makingShortcut = false))
        assertEquals(emptyList<HomePlace>(), HomePlace.shown(null, inbox = true, core = true, makingShortcut = false))
    }

    @Test
    fun theBarShowsEveryPlace_marksTheSelectedOne_andReportsATap() {
        val tapped = mutableListOf<HomePlace>()
        composeTestRule.setContent {
            ConnectBotTheme {
                HomeBar(places = HomePlace.entries, selected = HomePlace.SPACES, onSelect = { tapped += it })
            }
        }

        HomePlace.entries.forEach { composeTestRule.onNodeWithTag(homePlaceTag(it)).assertIsDisplayed() }
        composeTestRule.onNodeWithTag(homePlaceTag(HomePlace.SPACES)).assertIsSelected()
        composeTestRule.onNodeWithTag(homePlaceTag(HomePlace.INBOX)).assertIsNotSelected()

        composeTestRule.onNodeWithTag(homePlaceTag(HomePlace.INBOX)).performClick()
        composeTestRule.onNodeWithTag(homePlaceTag(HomePlace.HOSTS)).performClick()
        assertEquals(listOf(HomePlace.INBOX, HomePlace.HOSTS), tapped)
    }

    private lateinit var nav: TestNavHostController

    private fun host(start: String) {
        composeTestRule.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            NavHost(nav, startDestination = start) {
                composable(NavDestinations.HOST_LIST) {}
                composable(NavDestinations.INBOX) {}
                composable(NavDestinations.SPACES) {}
                composable("console/{id}") {}
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun select(place: HomePlace) {
        composeTestRule.runOnUiThread { nav.selectPlace(place) }
        composeTestRule.waitForIdle()
    }

    private fun stack(): List<String?> = nav.currentBackStack.value.mapNotNull { it.destination.route }.filter { HomePlace.of(it) != null }

    @Test
    fun fromTheInbox_eachPlaceIsOneTap_andTheStackStaysShallow() {
        host(NavDestinations.INBOX)

        select(HomePlace.SPACES)
        assertEquals(NavDestinations.SPACES, nav.currentDestination?.route)
        select(HomePlace.HOSTS)
        assertEquals(NavDestinations.HOST_LIST, nav.currentDestination?.route)
        select(HomePlace.INBOX)
        assertEquals(NavDestinations.INBOX, nav.currentDestination?.route)
        assertEquals(listOf(NavDestinations.INBOX), stack())
    }

    @Test
    fun fromTheHosts_theInboxAndSpacesAreOneTap_andBackReturnsToTheHosts() {
        host(NavDestinations.HOST_LIST)

        select(HomePlace.INBOX)
        select(HomePlace.SPACES)
        assertEquals(listOf(NavDestinations.HOST_LIST, NavDestinations.SPACES), stack())

        composeTestRule.runOnUiThread { nav.popBackStack() }
        composeTestRule.waitForIdle()
        assertEquals(NavDestinations.HOST_LIST, nav.currentDestination?.route)
    }

    @Test
    fun backFromAPlaceThatIsTheBottom_goesToTheHosts_notToAnEmptyStack() {
        host(NavDestinations.INBOX)

        composeTestRule.runOnUiThread { nav.backOrHosts() }
        composeTestRule.waitForIdle()

        assertEquals(NavDestinations.HOST_LIST, nav.currentDestination?.route)
        assertEquals(listOf(NavDestinations.INBOX, NavDestinations.HOST_LIST), stack())
    }

    @Test
    fun backFromAPlaceAboveTheHosts_returnsToTheHosts() {
        host(NavDestinations.HOST_LIST)
        select(HomePlace.SPACES)

        composeTestRule.runOnUiThread { nav.backOrHosts() }
        composeTestRule.waitForIdle()

        assertEquals(NavDestinations.HOST_LIST, nav.currentDestination?.route)
        assertEquals(listOf(NavDestinations.HOST_LIST), stack())
    }

    @Test
    fun aConsoleOpenedOverAConsole_replacesIt_whereverTheAppOpened() {
        host(NavDestinations.INBOX)

        composeTestRule.runOnUiThread { nav.openConsoleRoute("console/1") }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { nav.openConsoleRoute("console/2") }
        composeTestRule.waitForIdle()

        assertEquals("2", nav.currentBackStackEntry?.arguments?.getString("id"))
        assertEquals(1, nav.currentBackStack.value.count { it.destination.route == "console/{id}" })
        assertEquals(listOf(NavDestinations.INBOX), stack())
    }

    @Test
    fun aTapOnThePlaceYouAreOn_doesNothing() {
        host(NavDestinations.INBOX)
        select(HomePlace.INBOX)
        assertEquals(listOf(NavDestinations.INBOX), stack())
    }
}
