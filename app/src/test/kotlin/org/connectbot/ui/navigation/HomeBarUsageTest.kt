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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A tap on a place of the bottom bar logs that place's usage id. */
@RunWith(AndroidJUnit4::class)
class HomeBarUsageTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun eachPlaceTap_logsItsOwnId() {
        val logged = mutableListOf<String>()
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    HomeBar(places = HomePlace.entries, selected = HomePlace.SPACES, onSelect = {})
                }
            }
        }

        composeTestRule.onNodeWithTag(homePlaceTag(HomePlace.INBOX)).performClick()
        composeTestRule.onNodeWithTag(homePlaceTag(HomePlace.SPACES)).performClick()
        composeTestRule.onNodeWithTag(homePlaceTag(HomePlace.HOSTS)).performClick()

        assertEquals(listOf(UsageActions.PLACES_INBOX, UsageActions.PLACES_SPACES, UsageActions.PLACES_HOSTS), logged)
    }
}
