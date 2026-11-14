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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.ui.screens.inbox.InboxContent
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The gear on the Inbox top bar opens the app's Settings, the same route the Hosts screen uses, and Back returns. */
@RunWith(AndroidJUnit4::class)
class InboxSettingsGearTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var nav: TestNavHostController

    @Test
    fun theGear_navigatesToSettings_andBackReturnsToTheInbox() {
        composeTestRule.setContent {
            nav = TestNavHostController(LocalContext.current).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
            ConnectBotTheme {
                NavHost(nav, startDestination = NavDestinations.INBOX) {
                    composable(NavDestinations.INBOX) {
                        InboxContent(rows = emptyList(), notes = emptyList(), now = 0L, onOpen = {}, onNavigateBack = {}, onSettings = { nav.openSettings() })
                    }
                    composable(NavDestinations.SETTINGS) {}
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(NavDestinations.INBOX, nav.currentDestination?.route)

        composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.title_settings)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(NavDestinations.SETTINGS, nav.currentDestination?.route)
        composeTestRule.runOnUiThread { nav.popBackStack() }
        composeTestRule.waitForIdle()
        assertEquals(NavDestinations.INBOX, nav.currentDestination?.route)
    }
}
