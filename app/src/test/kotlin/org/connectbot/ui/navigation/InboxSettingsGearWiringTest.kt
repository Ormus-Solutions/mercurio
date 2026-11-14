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

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.connectbot.HiltComponentActivity
import org.connectbot.ui.screens.inbox.INBOX_SETTINGS_TAG
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The gear on the app's real Inbox route: the graph's own wiring, not a stand-in, lands on the Settings route. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class InboxSettingsGearWiringTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun aTapOnTheGear_onTheRealInboxRoute_landsOnTheSettingsRoute() {
        lateinit var nav: TestNavHostController
        composeTestRule.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            ConnectBotTheme {
                ConnectBotNavHost(navController = nav, onNavigateToConsole = {}, startDestination = NavDestinations.INBOX)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(NavDestinations.INBOX, nav.currentDestination?.route)

        composeTestRule.onNodeWithTag(INBOX_SETTINGS_TAG).performClick()
        composeTestRule.waitForIdle()

        val stack = nav.currentBackStack.value.map { it.destination.route }
        assertEquals(stack.toString(), true, nav.currentDestination?.route?.startsWith(NavDestinations.SETTINGS))
    }
}
