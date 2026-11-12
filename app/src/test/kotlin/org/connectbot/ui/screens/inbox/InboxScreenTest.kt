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
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.Agent
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.herd.RowAction

/** The Inbox screen on synthetic rows: sections, folding, search, the tap. */
@RunWith(AndroidJUnit4::class)
class InboxScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val now = 10_000_000L
    private val opened = mutableListOf<InboxRow>()
    private val acted = mutableListOf<Pair<InboxRow, RowAction>>()

    private fun row(
        address: String,
        state: AgentState,
        space: String = "alpha · ONE",
        tab: String = "work",
        minutes: Long? = null,
    ) = Inbox.row(1L, "box", Agent(address, AgentEngine.TUI, null, space, tab, null, null, state, minutes?.let { now - it * 60_000L }))

    private fun show(rows: List<InboxRow>, notes: List<String> = emptyList(), loading: Boolean = false) {
        composeTestRule.setContent {
            ConnectBotTheme {
                InboxContent(rows = rows, notes = notes, loading = loading, now = now, onOpen = { opened += it }, onNavigateBack = {}, onAction = { r, a -> acted += r to a })
            }
        }
    }

    @Test
    fun oneGroupPerState_inOrder_withTheSearchFieldOnTop() {
        show(
            listOf(
                row("i", AgentState.Idle),
                row("n", AgentState.NeedsYou()),
                row("d", AgentState.Done),
                row("w", AgentState.Working),
            ),
        )
        composeTestRule.onNodeWithTag(INBOX_SEARCH_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Jump to a terminal…").assertIsDisplayed()
        listOf("Needs you", "Done", "Working", "Idle").forEach { composeTestRule.onNodeWithText(it).assertIsDisplayed() }
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG).assertCountEquals(4)
        composeTestRule.onNodeWithText("Unknown").assertDoesNotExist()
    }

    @Test
    fun aRow_showsTheSpace_theTab_theLetterAndTheTimeInState() {
        show(listOf(row("a", AgentState.Idle, space = "beta · TWO", tab = "docs", minutes = 12)))
        composeTestRule.onNodeWithText("beta · TWO").assertIsDisplayed()
        composeTestRule.onNodeWithText("docs").assertIsDisplayed()
        composeTestRule.onNodeWithText("12m").assertIsDisplayed()
        composeTestRule.onNodeWithText("B").assertIsDisplayed()
    }

    @Test
    fun aLongSection_foldsAfterThree_andShowMoreOpensIt() {
        show((1..7).map { row("a$it", AgentState.Idle, minutes = it.toLong()) })
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG).assertCountEquals(3)
        composeTestRule.onNodeWithText("Show 4 more").performClick()
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG).assertCountEquals(7)
        composeTestRule.onNodeWithText("Show fewer").performClick()
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG).assertCountEquals(3)
    }

    @Test
    fun typingNarrowsTheRows() {
        show(listOf(row("a", AgentState.Idle, space = "alpha · ONE"), row("b", AgentState.Working, space = "beta · TWO")))
        composeTestRule.onNodeWithTag(INBOX_SEARCH_TAG).performTextInput("beta")
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithText("beta · TWO").assertIsDisplayed()
        composeTestRule.onNodeWithText("Idle").assertDoesNotExist()
    }

    @Test
    fun aSearchWithNoHit_saysSo() {
        show(listOf(row("a", AgentState.Idle)))
        composeTestRule.onNodeWithTag(INBOX_SEARCH_TAG).performTextInput("zzz")
        composeTestRule.onNodeWithText("No terminal matches").assertIsDisplayed()
    }

    @Test
    fun aTap_opensThatRow() {
        val a = row("a", AgentState.Idle, space = "alpha · ONE")
        val b = row("b", AgentState.Working, space = "beta · TWO")
        show(listOf(a, b))
        composeTestRule.onNodeWithText("beta · TWO").performClick()
        assertEquals(listOf(b), opened)
    }

    @Test
    fun aReadThatFound_noRows_saysThereAreNoAgents() {
        show(emptyList())
        composeTestRule.onNodeWithText("No agents yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Loading…").assertDoesNotExist()
    }

    @Test
    fun beforeTheFirstRead_theInboxSaysLoading_notNoAgents() {
        show(emptyList(), loading = true)
        composeTestRule.onNodeWithText("Loading…").assertIsDisplayed()
        composeTestRule.onNodeWithText("No agents yet").assertDoesNotExist()
    }

    @Test
    fun whileLoading_aFailureLineShows_andLoadingDoesNot() {
        show(emptyList(), notes = listOf("box: no answer"), loading = true)
        composeTestRule.onAllNodesWithText("box: no answer").assertCountEquals(1)
        composeTestRule.onNodeWithText("Loading…").assertDoesNotExist()
        composeTestRule.onNodeWithText("No agents yet").assertDoesNotExist()
    }

    @Test
    fun aFailingRead_withNoRows_showsTheFailure_notNoAgents() {
        show(emptyList(), notes = listOf("box: herdr-core is not running"))
        composeTestRule.onAllNodesWithText("box: herdr-core is not running").assertCountEquals(1)
        composeTestRule.onNodeWithText("No agents yet").assertDoesNotExist()
        composeTestRule.onNodeWithText("Loading…").assertDoesNotExist()
    }

    @Test
    fun theHomeKey_opensTheInbox() {
        var taps = 0
        composeTestRule.setContent { ConnectBotTheme { InboxEntry(onClick = { taps++ }) } }
        composeTestRule.onNodeWithTag(INBOX_ENTRY_TAG).performClick()
        assertEquals(1, taps)
    }

    private fun core(address: String, state: AgentState, unread: Boolean? = null, pinned: Boolean = false, turn: Int? = 3) = Inbox.row(1L, "box", Agent(address, AgentEngine.CORE, "box", "alpha · ONE", "work", null, null, state, null, turn, unread, pinned))

    @Test
    fun anUnreadRow_hasADot_aReadOneDoesNot() {
        show(listOf(core("a", AgentState.Done, unread = true), core("b", AgentState.Done, unread = null), core("c", AgentState.Idle, unread = false)))
        composeTestRule.onAllNodesWithTag(INBOX_UNREAD_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun aPinnedRow_showsAPin() {
        show(listOf(core("a", AgentState.Idle, pinned = true), core("b", AgentState.Idle)))
        composeTestRule.onAllNodesWithTag(INBOX_PINNED_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun swipeRight_pinsAnIdleRow() {
        val r = core("a", AgentState.Idle)
        show(listOf(r))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(r to RowAction.PIN), acted)
    }

    @Test
    fun swipeRight_marksAnUnreadRowRead() {
        val r = core("a", AgentState.Done, unread = true)
        show(listOf(r))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(r to RowAction.MARK_READ), acted)
    }

    @Test
    fun swipeLeft_asksFirst_andSendsEscOnlyOnConfirm() {
        val r = core("a", AgentState.Working)
        show(listOf(r))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        assertEquals(emptyList<Pair<InboxRow, RowAction>>(), acted)
        composeTestRule.onNodeWithText("Send Esc to alpha · ONE?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send Esc").performClick()
        assertEquals(listOf(r to RowAction.INTERRUPT), acted)
    }

    @Test
    fun swipeLeft_thenCancel_sendsNothing() {
        show(listOf(core("a", AgentState.Working)))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<Pair<InboxRow, RowAction>>(), acted)
        composeTestRule.onNodeWithText("Send Esc to alpha · ONE?").assertDoesNotExist()
    }

    @Test
    fun aTuiRow_doesNotSwipe() {
        show(listOf(row("a", AgentState.Idle)))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeRight() }
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        assertEquals(emptyList<Pair<InboxRow, RowAction>>(), acted)
        composeTestRule.onNodeWithText("Send Esc").assertDoesNotExist()
    }

    private fun withParent(address: String, state: AgentState, parent: String?, title: String? = null, unread: Boolean? = null) = Inbox.row(1L, "box", Agent(address, AgentEngine.CORE, "box", "alpha · ONE", "work", title, "claude", state, null, 3, unread, false, parent))

    @Test
    fun theHelperCount_expandsTheHelpersAsRows_andATapOnOneOpensThatHelper() {
        val parent = withParent("t1", AgentState.Working, null)
        val helper = withParent("t2", AgentState.Working, "t1", title = "lint pass")
        show(listOf(parent, helper))
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithTag(INBOX_HELPERS_TAG, useUnmergedTree = true).performClick()
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG).assertCountEquals(2)
        composeTestRule.onNodeWithText("lint pass").performClick()
        assertEquals(listOf(helper), opened)
        composeTestRule.onNodeWithTag(INBOX_HELPERS_TAG, useUnmergedTree = true).performClick()
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG).assertCountEquals(1)
    }

    @Test
    fun aHelperThatNeedsYou_isVisibleWithoutAnyTap_andTheParentTapStillOpensTheParent() {
        val parent = withParent("t1", AgentState.Working, null, title = "main task")
        val helper = withParent("t2", AgentState.NeedsYou(), "t1", title = "blocked helper")
        show(listOf(parent, helper))
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG).assertCountEquals(2)
        composeTestRule.onNodeWithText("main task").performClick()
        assertEquals(listOf(parent.copy(helperRows = listOf(helper))), opened)
    }

    @Test
    fun theRowDescription_namesTheHelpersTheNeedAndTheUnreadMark() {
        val parent = withParent("t1", AgentState.Working, null, title = "main task")
        val helper = withParent("t2", AgentState.NeedsYou(), "t1", title = "blocked helper", unread = true)
        show(listOf(parent, helper))
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[0].assertContentDescriptionContains("Open alpha · ONE on box, 1 helper · needs you, Unread")
        composeTestRule.onAllNodesWithTag(INBOX_HELPERS_UNREAD_TAG, useUnmergedTree = true).assertCountEquals(1)
    }
}
