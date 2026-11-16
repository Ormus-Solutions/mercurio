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

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.connectbot.HiltComponentActivity
import org.connectbot.ui.components.TOP_BAR_HOME_TAG
import org.connectbot.ui.components.TOP_BAR_SETTINGS_TAG
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.util.PreferenceConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shared top bar on the app's real Spaces route: the graph's own wiring, not a stand-in. The Inbox icon goes home, the gear to Settings. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TopBarWiringTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())

    @Before
    fun setUp() {
        hiltRule.inject()
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun TestNavHostController.routes() = currentBackStack.value.mapNotNull { it.destination.route }

    private fun open(start: String): TestNavHostController {
        lateinit var nav: TestNavHostController
        composeTestRule.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            ConnectBotTheme {
                ConnectBotNavHost(navController = nav, onNavigateToConsole = {}, startDestination = start)
            }
        }
        composeTestRule.waitForIdle()
        return nav
    }

    @Test
    fun theGearOnSpaces_landsOnTheSettingsRoute() {
        val nav = open(NavDestinations.SPACES)
        assertEquals(NavDestinations.SPACES, nav.currentDestination?.route)
        composeTestRule.onNodeWithTag(TOP_BAR_SETTINGS_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(nav.currentBackStack.value.map { it.destination.route }.toString(), true, nav.currentDestination?.route?.startsWith(NavDestinations.SETTINGS))
    }

    @Test
    fun theInboxIconOnSpaces_goesHome_whichIsTheInboxWhileItsSettingIsOn() {
        val nav = open(NavDestinations.SPACES)
        composeTestRule.onNodeWithTag(TOP_BAR_HOME_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(NavDestinations.INBOX, nav.currentDestination?.route)
    }

    @Test
    fun theInboxIconOnTheInbox_isInert() {
        val nav = open(NavDestinations.INBOX)
        composeTestRule.onNodeWithTag(TOP_BAR_HOME_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(NavDestinations.INBOX, nav.currentDestination?.route)
        assertEquals(1, nav.currentBackStack.value.count { it.destination.route == NavDestinations.INBOX })
    }

    // Gate lane 2: with the Inbox off the home is the hosts, and the stack stays [host_list] (the user has no bottom bar here).
    @Test
    fun withTheInboxOff_theHomeIconOnSpaces_goesToTheHosts_andTheStackIsJustTheHosts() {
        prefs.edit().putBoolean(PreferenceConstants.INBOX, false).commit()
        val nav = open(NavDestinations.HOST_LIST)
        composeTestRule.runOnUiThread { nav.selectPlace(HomePlace.SPACES) }
        composeTestRule.waitForIdle()
        assertEquals(listOf(NavDestinations.HOST_LIST, NavDestinations.SPACES), nav.routes())
        composeTestRule.onNodeWithTag(TOP_BAR_HOME_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(NavDestinations.HOST_LIST), nav.routes())
    }
}
