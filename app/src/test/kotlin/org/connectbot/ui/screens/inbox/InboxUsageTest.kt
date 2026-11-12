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

package org.connectbot.ui.screens.inbox

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.Agent
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow

/** Every Inbox action logs its usage id, and only the id: never the typed search or an agent's name. */
@RunWith(AndroidJUnit4::class)
class InboxUsageTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val logged = mutableListOf<String>()

    private fun core(address: String, state: AgentState, unread: Boolean? = null, pinned: Boolean = false) = Inbox.row(1L, "devbox", Agent(address, AgentEngine.CORE, "devbox", "alpha · ONE", "work", null, null, state, null, 3, unread, pinned))

    private fun show(rows: List<InboxRow>) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    InboxContent(rows = rows, notes = emptyList(), now = 10_000_000L, onOpen = {}, onNavigateBack = {})
                }
            }
        }
    }

    @Test
    fun swipeRight_onAnIdleRow_logsPin() {
        show(listOf(core("a", AgentState.Idle)))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(UsageActions.INBOX_PIN), logged)
    }

    @Test
    fun swipeRight_onAPinnedRow_logsUnpin() {
        show(listOf(core("a", AgentState.Idle, pinned = true)))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(UsageActions.INBOX_UNPIN), logged)
    }

    @Test
    fun swipeRight_onAnUnreadRow_logsMarkRead() {
        show(listOf(core("a", AgentState.Done, unread = true)))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(UsageActions.INBOX_MARK_READ), logged)
    }

    @Test
    fun esc_logsWhenConfirmed() {
        show(listOf(core("a", AgentState.Working)))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Send Esc").performClick()
        assertEquals(listOf(UsageActions.INBOX_ESC), logged)
    }

    @Test
    fun esc_cancelled_logsNothing() {
        show(listOf(core("a", AgentState.Working)))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun aRowTap_logsOpen() {
        show(listOf(core("a", AgentState.Idle)))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performClick()
        assertEquals(listOf(UsageActions.INBOX_OPEN), logged)
    }

    @Test
    fun search_logsOnceWhenItStarts_notPerKey_andNeverTheText() {
        show(listOf(core("a", AgentState.Idle)))
        composeTestRule.onNodeWithTag(INBOX_SEARCH_TAG).performTextInput("devbox")
        composeTestRule.onNodeWithTag(INBOX_SEARCH_TAG).performTextInput("2")
        assertEquals(listOf(UsageActions.INBOX_SEARCH), logged)
    }

    @Test
    fun showMoreAndFewer_logFold() {
        show((1..5).map { core("a$it", AgentState.Idle) })
        composeTestRule.onNodeWithText("Show 2 more").performClick()
        composeTestRule.onNodeWithText("Show fewer").performClick()
        assertEquals(listOf(UsageActions.INBOX_FOLD, UsageActions.INBOX_FOLD), logged)
    }

    @Test
    fun theHelperCount_logsHelpersOnEachToggle_andAHelperRowTapLogsOpen() {
        val parent = Inbox.row(1L, "devbox", Agent("t1", AgentEngine.CORE, "devbox", "alpha · ONE", "work", null, "claude", AgentState.Working, null, 3, null, false, null))
        val helper = Inbox.row(1L, "devbox", Agent("t2", AgentEngine.CORE, "devbox", "alpha · ONE", "work", "lint pass", "claude", AgentState.Working, null, 3, null, false, "t1"))
        show(listOf(parent, helper))
        composeTestRule.onNodeWithTag(INBOX_HELPERS_TAG, useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("lint pass").performClick()
        composeTestRule.onNodeWithTag(INBOX_HELPERS_TAG, useUnmergedTree = true).performClick()
        assertEquals(listOf(UsageActions.INBOX_HELPERS, UsageActions.INBOX_OPEN, UsageActions.INBOX_HELPERS), logged)
    }
}
