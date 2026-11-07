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

package org.connectbot.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.home.HomeBoard
import solutions.ormus.logos.home.HomeBoards
import solutions.ormus.logos.home.HomeState

/** The "Home feed" row in Settings: what it says of the feed, and the editor that sets the URL. */
@RunWith(AndroidJUnit4::class)
class HomeFeedSettingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val logged = mutableListOf<String>()
    private val saved = mutableListOf<String>()

    private fun show(state: HomeState) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    HomeFeedSettingContent(state = state, onSave = { saved += it })
                }
            }
        }
    }

    private fun string(id: Int, vararg args: Any) = composeTestRule.activity.getString(id, *args)

    @Test
    fun noUrl_saysSo() {
        show(HomeState())

        composeTestRule.onNodeWithText(string(R.string.pref_home_category)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.pref_home_feed_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.pref_home_feed_none)).assertIsDisplayed()
    }

    @Test
    fun aUrlNotReadYet_saysSo() {
        show(HomeState(url = URL))

        composeTestRule.onNodeWithText(URL + "\n" + string(R.string.pref_home_feed_pending)).assertIsDisplayed()
    }

    @Test
    fun aUrlRead_countsItsWorkboards_andSaysWhen() {
        val boards = HomeBoards(boards = listOf(HomeBoard("work"), HomeBoard("lab")))
        show(HomeState(URL, boards, fetchedAt = System.currentTimeMillis()))

        composeTestRule.onNodeWithText("Workboards: 2, read ", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(URL, substring = true).assertIsDisplayed()
    }

    @Test
    fun aReadThatFailed_namesWhy() {
        val boards = HomeBoards(boards = listOf(HomeBoard("work")))
        show(HomeState(URL, boards, fetchedAt = 1L, error = "The feed answered HTTP 503"))

        composeTestRule.onNodeWithText(string(R.string.pref_home_feed_failed, "The feed answered HTTP 503"), substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Workboards:", substring = true).assertDoesNotExist()
    }

    @Test
    fun theEditor_savesWhatWasTyped_andLogsTheKey() {
        show(HomeState())

        composeTestRule.onNodeWithTag(HOME_FEED_TAG).performClick()
        composeTestRule.onNodeWithTag(HOME_FEED_FIELD_TAG).performTextInput(URL)
        composeTestRule.onNodeWithText(string(R.string.button_ok)).performClick()

        assertEquals(listOf(URL), saved)
        assertEquals(listOf(UsageActions.HOME_FEED_SET), logged)
    }

    @Test
    fun theEditor_cancelled_savesNothing() {
        show(HomeState(url = URL))

        composeTestRule.onNodeWithTag(HOME_FEED_TAG).performClick()
        composeTestRule.onNodeWithText(string(R.string.button_cancel)).performClick()

        assertEquals(emptyList<String>(), saved)
        assertEquals(emptyList<String>(), logged)
    }

    private companion object {
        const val URL = "https://feed.example/feeds/sessions.json"
    }
}
