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

package org.connectbot.ui.needs

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
import solutions.ormus.logos.home.Ask
import solutions.ormus.logos.home.AskOption
import solutions.ormus.logos.home.NeedsPass

/** The Unblock view on synthetic asks: what one ask shows, the taps, the lines, and the swipes. */
@RunWith(AndroidJUnit4::class)
class NeedsContentTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val logged = mutableListOf<String>()
    private val chosen = mutableListOf<Pair<String, String>>()
    private var laterTaps = 0
    private var homes = 0

    private val one = Ask("ask-one", "Which branch should the fix land on?", "Two branches carry the change.", listOf(AskOption("main", "The default"), AskOption("next")))
    private val two = Ask("ask-two", "Run the slow tests too?", options = listOf(AskOption("Yes"), AskOption("No")))
    private val pass = NeedsPass().read(listOf(one, two))

    private fun show(state: NeedsUi) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    NeedsContent(state = state, onChoose = { id, label -> chosen += id to label }, onLater = { laterTaps++ }, onHome = { homes++ })
                }
            }
        }
    }

    private fun string(id: Int) = composeTestRule.activity.getString(id)

    @Test
    fun oneAsk_showsItsPlace_question_context_andOneKeyPerOption_thenLater() {
        show(NeedsUi(pass = pass, read = true))

        composeTestRule.onNodeWithText("1 of 2").assertIsDisplayed()
        composeTestRule.onNodeWithText(one.question).assertIsDisplayed()
        composeTestRule.onNodeWithText("Two branches carry the change.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(NeedsTags.option(0)).assertIsDisplayed()
        composeTestRule.onNodeWithText("main").assertIsDisplayed()
        composeTestRule.onNodeWithText("next").assertIsDisplayed()
        composeTestRule.onNodeWithTag(NeedsTags.LATER).assertIsDisplayed()
    }

    @Test
    fun aTapOnAnOption_choosesItsLabel_andLogsTheIdOnly() {
        show(NeedsUi(pass = pass, read = true))

        composeTestRule.onNodeWithTag(NeedsTags.option(1)).performClick()

        assertEquals(listOf("ask-one" to "next"), chosen)
        assertEquals(listOf(UsageActions.NEEDS_ANSWER), logged)
    }

    @Test
    fun later_putsTheAskOff_andLogsIt() {
        show(NeedsUi(pass = pass, read = true))

        composeTestRule.onNodeWithTag(NeedsTags.LATER).performClick()

        assertEquals(1, laterTaps)
        assertEquals(listOf(UsageActions.NEEDS_LATER), logged)
    }

    @Test
    fun aClosedAsk_readsOneLine_overTheNext() {
        show(NeedsUi(pass = pass.closed(), read = true, notice = NeedsNotice.CLOSED))

        composeTestRule.onNodeWithText(string(R.string.needs_closed)).assertIsDisplayed()
        composeTestRule.onNodeWithText(two.question).assertIsDisplayed()
    }

    @Test
    fun multiAsksLeftOut_readOneLine_thatPointsToThePage_underTheAskOnScreen() {
        val several = Ask("ask-many", "Which of these should ship?", options = listOf(AskOption("Logo"), AskOption("Banner")), multi = true)
        show(NeedsUi(pass = NeedsPass().read(listOf(one, several)), read = true))

        composeTestRule.onNodeWithText(one.question).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.needs_on_page)).assertIsDisplayed()
        composeTestRule.onNodeWithText(several.question).assertDoesNotExist()
    }

    @Test
    fun onlyMultiAsksOpen_readsNoneLeft_andTheLineThatPointsToThePage() {
        val several = Ask("ask-many", "Which of these should ship?", options = listOf(AskOption("Logo"), AskOption("Banner")), multi = true)
        show(NeedsUi(pass = NeedsPass().read(listOf(several)), read = true))

        composeTestRule.onNodeWithText(string(R.string.needs_none)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.needs_on_page)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(NeedsTags.HOME).assertIsDisplayed()
    }

    @Test
    fun noMultiAsk_readsNoLineAboutThePage() {
        show(NeedsUi(pass = pass, read = true))

        composeTestRule.onNodeWithText(string(R.string.needs_on_page)).assertDoesNotExist()
    }

    @Test
    fun aRefusal_readsNoLineAboutThePage() {
        val several = Ask("ask-many", "Which of these should ship?", options = listOf(AskOption("Logo")), multi = true)
        show(NeedsUi(pass = NeedsPass().read(listOf(one, several)), read = true, notice = NeedsNotice.REFUSED))

        composeTestRule.onNodeWithText(string(R.string.needs_on_page)).assertDoesNotExist()
    }

    private fun assertKeysOff(ui: NeedsUi) {
        show(ui)
        composeTestRule.onNodeWithTag(NeedsTags.option(0)).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(NeedsTags.option(1)).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(NeedsTags.LATER).assertIsNotEnabled()
    }

    @Test
    fun theKeysAreOff_whileASendRuns() = assertKeysOff(NeedsUi(pass = pass, read = true, sending = true))

    @Test
    fun theKeysAreOff_whileANewAskSettles() = assertKeysOff(NeedsUi(pass = pass, read = true, settling = true))

    @Test
    fun anAskWithNoOption_readsOneLine_thatPointsToThePage() {
        val bare = Ask("ask-bare", "Why did the build stop?")
        show(NeedsUi(pass = NeedsPass().read(listOf(bare)), read = true))

        composeTestRule.onNodeWithText(bare.question).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.needs_no_options)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(NeedsTags.LATER).assertIsDisplayed()
    }

    @Test
    fun theHomeKey_logsItsId() {
        show(NeedsUi(pass = NeedsPass(), read = true))

        composeTestRule.onNodeWithTag(NeedsTags.HOME).performClick()

        assertEquals(listOf(UsageActions.NEEDS_HOME), logged)
        assertEquals(1, homes)
    }

    @Test
    fun theSwipeHome_logsItsId() {
        show(NeedsUi(pass = pass, read = true))

        composeTestRule.onNodeWithTag(NeedsTags.VIEW).performTouchInput { swipeRight(startX = width * 0.3f, endX = right) }

        assertEquals(listOf(UsageActions.NEEDS_HOME), logged)
        assertEquals(1, homes)
    }

    @Test
    fun aRefusal_readsOneLine_andOffersHomeOnly() {
        show(NeedsUi(pass = pass, read = true, notice = NeedsNotice.REFUSED))

        composeTestRule.onNodeWithText(string(R.string.needs_refused)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(NeedsTags.option(0)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(NeedsTags.HOME).performClick()
        assertEquals(1, homes)
    }

    @Test
    fun aSwipeLeftToRight_goesHome() {
        show(NeedsUi(pass = pass, read = true))

        composeTestRule.onNodeWithTag(NeedsTags.VIEW).performTouchInput { swipeRight(startX = width * 0.3f, endX = right) }

        assertEquals(1, homes)
        assertEquals(emptyList<Pair<String, String>>(), chosen)
    }
}
