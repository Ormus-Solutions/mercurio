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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

/** The Inbox screen on synthetic rows: sections, folding, search, the tap. */
@RunWith(AndroidJUnit4::class)
class InboxScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val now = 10_000_000L
    private val opened = mutableListOf<InboxRow>()

    private fun row(
        address: String,
        state: AgentState,
        space: String = "alpha · ONE",
        tab: String = "work",
        minutes: Long? = null,
    ) = Inbox.row(1L, "box", Agent(address, AgentEngine.TUI, null, space, tab, null, null, state, minutes?.let { now - it * 60_000L }))

    private fun show(rows: List<InboxRow>, notes: List<String> = emptyList()) {
        composeTestRule.setContent {
            ConnectBotTheme {
                InboxContent(rows = rows, notes = notes, now = now, onOpen = { opened += it }, onNavigateBack = {})
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
    fun noRows_saysThereAreNoAgents_andNotesShow() {
        show(emptyList(), notes = listOf("box: herdr-core is not running"))
        composeTestRule.onNodeWithText("No agents yet").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("box: herdr-core is not running").assertCountEquals(1)
    }

    @Test
    fun theHomeKey_opensTheInbox() {
        var taps = 0
        composeTestRule.setContent { ConnectBotTheme { InboxEntry(onClick = { taps++ }) } }
        composeTestRule.onNodeWithTag(INBOX_ENTRY_TAG).performClick()
        assertEquals(1, taps)
    }
}
