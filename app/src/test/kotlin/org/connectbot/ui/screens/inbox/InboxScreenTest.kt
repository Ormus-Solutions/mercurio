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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doSuspendableAnswer
import org.robolectric.annotation.Config
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
                InboxContent(rows = rows, notes = notes, loading = loading, now = now, onOpen = { opened += it }, onAction = { r, a -> acted += r to a })
            }
        }
    }

    private val keyLogged = mutableListOf<String>()
    private var unblockTaps = 0

    private fun showWithKey(unblock: Int?) {
        composeTestRule.setContent {
            ConnectBotTheme {
                androidx.compose.runtime.CompositionLocalProvider(org.connectbot.usage.LocalUsageLog provides org.connectbot.usage.UsageLog { keyLogged += it }) {
                    InboxContent(rows = emptyList(), notes = emptyList(), now = now, onOpen = {}, unblock = unblock, onUnblock = { unblockTaps++ })
                }
            }
        }
    }

    @Test
    fun theUnblockKey_showsTheCountOfOpenQuestions_opensTheView_andLogsItsId() {
        showWithKey(unblock = 3)

        composeTestRule.onNodeWithText("Unblock (3)").assertIsDisplayed().performClick()

        assertEquals(1, unblockTaps)
        assertEquals(listOf(org.connectbot.usage.UsageActions.NEEDS_INBOX_KEY), keyLogged)
    }

    @Test
    fun noUnblockKey_withNoQuestionsStore() {
        showWithKey(unblock = null)

        composeTestRule.onNodeWithText("Unblock", substring = true).assertDoesNotExist()
        assertEquals(0, unblockTaps)
    }

    private val countStarted = java.util.concurrent.atomic.AtomicInteger(0)
    private val countCancelled = java.util.concurrent.atomic.AtomicInteger(0)

    @Test
    fun anInboxWithNoHerdrMachine_readsTheLineFromTheStringResource() {
        val inbox = org.mockito.Mockito.mock(InboxViewModel::class.java)
        org.mockito.Mockito.`when`(inbox.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(InboxViewModel.State(loaded = true, noHerdrMachine = true)))
        val needs = org.mockito.Mockito.mock(org.connectbot.ui.needs.NeedsCountViewModel::class.java)
        org.mockito.Mockito.`when`(needs.count).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(null))
        kotlinx.coroutines.runBlocking {
            org.mockito.kotlin.whenever(inbox.watch()).doSuspendableAnswer { kotlinx.coroutines.awaitCancellation() }
            org.mockito.kotlin.whenever(needs.watch()).doSuspendableAnswer { kotlinx.coroutines.awaitCancellation() }
        }
        composeTestRule.setContent { ConnectBotTheme { InboxScreen(onOpen = {}, viewModel = inbox, needsViewModel = needs) } }

        composeTestRule.onNodeWithText(composeTestRule.activity.getString(org.connectbot.R.string.inbox_no_herdr_machine)).assertIsDisplayed()
    }

    @Test
    fun theInboxCountsTheStoresQuestions_onlyWhileItShows() {
        val inbox = org.mockito.Mockito.mock(InboxViewModel::class.java)
        org.mockito.Mockito.`when`(inbox.state).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(InboxViewModel.State(loaded = true)))
        val needs = org.mockito.Mockito.mock(org.connectbot.ui.needs.NeedsCountViewModel::class.java)
        org.mockito.Mockito.`when`(needs.count).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(null))
        kotlinx.coroutines.runBlocking {
            org.mockito.kotlin.whenever(inbox.watch()).doSuspendableAnswer { kotlinx.coroutines.awaitCancellation() }
            org.mockito.kotlin.whenever(needs.watch()).doSuspendableAnswer {
                countStarted.incrementAndGet()
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    countCancelled.incrementAndGet()
                }
            }
        }
        composeTestRule.setContent { ConnectBotTheme { InboxScreen(onOpen = {}, viewModel = inbox, needsViewModel = needs) } }
        composeTestRule.waitForIdle()
        assertEquals(1 to 0, countStarted.get() to countCancelled.get())

        composeTestRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        composeTestRule.waitForIdle()
        assertEquals("the count stopped when the Inbox stopped", 1 to 1, countStarted.get() to countCancelled.get())

        composeTestRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()
        assertEquals(2 to 1, countStarted.get() to countCancelled.get())
    }

    @Test
    fun theTopBarHasASettingsGear_withADescription_thatOpensSettings_andLogsItsId() {
        val settingsLogged = mutableListOf<String>()
        var settingsTaps = 0
        composeTestRule.setContent {
            ConnectBotTheme {
                androidx.compose.runtime.CompositionLocalProvider(org.connectbot.usage.LocalUsageLog provides org.connectbot.usage.UsageLog { settingsLogged += it }) {
                    InboxContent(rows = emptyList(), notes = emptyList(), now = now, onOpen = {}, onSettings = { settingsTaps++ })
                }
            }
        }

        val gear = composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(org.connectbot.R.string.title_settings))
        gear.assertIsDisplayed().performClick()

        assertEquals(1, settingsTaps)
        assertEquals(listOf(org.connectbot.usage.UsageActions.TOPBAR_SETTINGS), settingsLogged)
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

    private fun core(address: String, state: AgentState, unread: Boolean? = null, pinned: Boolean = false, turn: Int? = 3, role: String? = null) = Inbox.row(1L, "box", Agent(address, AgentEngine.CORE, "box", "alpha · ONE", "work", null, null, state, null, turn, unread, pinned, role = role))

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

    private val starLogged = mutableListOf<String>()

    private fun showStarred(rows: List<InboxRow>) {
        composeTestRule.setContent {
            ConnectBotTheme {
                androidx.compose.runtime.CompositionLocalProvider(org.connectbot.usage.LocalUsageLog provides org.connectbot.usage.UsageLog { starLogged += it }) {
                    InboxContent(rows = rows, notes = emptyList(), now = now, onOpen = { opened += it }, onAction = { r, a -> acted += r to a })
                }
            }
        }
    }

    private fun stars() = composeTestRule.onAllNodesWithTag(INBOX_STAR_TAG, useUnmergedTree = true)

    @Test
    fun everyCoreRow_hasAStar_andATuiRowHasNone() {
        showStarred(listOf(core("a", AgentState.Working), core("b", AgentState.Idle, pinned = true), row("t", AgentState.Idle)))
        stars().assertCountEquals(2)
    }

    @Test
    fun aTapOnTheStar_pinsTheRow_logsItsOwnId_andOpensNothing() {
        val r = core("a", AgentState.Idle)
        showStarred(listOf(r))
        stars()[0].performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(r to RowAction.PIN), acted)
        assertEquals(listOf(org.connectbot.usage.UsageActions.INBOX_STAR), starLogged)
        assertEquals(emptyList<InboxRow>(), opened)
    }

    @Test
    fun aTapOnTheStarOfAPinnedRow_unpinsIt_andLogsItsOwnId() {
        val r = core("a", AgentState.Idle, pinned = true)
        showStarred(listOf(r))
        stars()[0].performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(r to RowAction.UNPIN), acted)
        assertEquals(listOf(org.connectbot.usage.UsageActions.INBOX_UNSTAR), starLogged)
    }

    @Test
    fun onAnUnreadRow_oneTapOnTheStarPins_whereTheSwipeNeedsTwo() {
        val r = core("a", AgentState.Done, unread = true)
        showStarred(listOf(r))
        stars()[0].performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(r to RowAction.PIN), acted)
    }

    @Test
    fun theStar_isFilledOnAPinnedRow_andOutlinedOnAnotherOne() {
        showStarred(listOf(core("a", AgentState.Idle, pinned = true), core("b", AgentState.Idle)))
        composeTestRule.onAllNodesWithTag(INBOX_PINNED_TAG, useUnmergedTree = true).assertCountEquals(1)
        stars()[0].assertContentDescriptionContains("Unpin alpha · ONE")
        stars()[1].assertContentDescriptionContains("Pin alpha · ONE")
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_theStarSaysWhatATapDoes() {
        showStarred(listOf(core("a", AgentState.Idle, pinned = true), core("b", AgentState.Idle)))
        stars()[0].assertContentDescriptionContains("Dejar de fijar alpha · ONE")
        stars()[1].assertContentDescriptionContains("Fijar alpha · ONE")
    }

    @Test
    fun theRowOffersPinAsAnAction_toTalkBack_asASecondWayToTheStar() {
        showStarred(listOf(core("a", AgentState.Idle), core("b", AgentState.Idle, pinned = true)))
        fun labels(i: Int) = composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[i].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label }
        // The pinned row is first in its section.
        assertEquals(listOf("Unpin alpha · ONE"), labels(0))
        assertEquals(listOf("Pin alpha · ONE"), labels(1))
    }

    private fun rowDescription(i: Int) = composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[i].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString(" | ")

    private fun starState(i: Int) = stars()[i].fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)

    private fun starLabels() = stars().fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString(" | ") }

    // M1 of the gate lane's review: the row said "Pinned" through its 16 dp pin icon; the star replaced the icon, and the row went silent.
    @Test
    fun aPinnedCoreRow_saysPinned_inItsMergedDescription_andAnotherRowDoesNot() {
        showStarred(listOf(core("a", AgentState.Idle, pinned = true), core("b", AgentState.Idle)))
        // The pinned row is first in its section.
        assertTrue("the pinned row: ${rowDescription(0)}", rowDescription(0).contains("Pinned"))
        assertFalse("the other row: ${rowDescription(1)}", rowDescription(1).contains("Pinned"))
    }

    @Test
    fun theStar_hasAState_pinnedOrNotPinned() {
        showStarred(listOf(core("a", AgentState.Idle, pinned = true), core("b", AgentState.Idle)))
        assertEquals("Pinned", starState(0))
        assertEquals("Not pinned", starState(1))
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_aPinnedRowAndItsStar_sayFijada_andTheOtherStarSaysNoFijada() {
        showStarred(listOf(core("a", AgentState.Idle, pinned = true), core("b", AgentState.Idle)))
        assertTrue("the pinned row: ${rowDescription(0)}", rowDescription(0).contains("Fijada"))
        assertEquals("Fijada", starState(0))
        assertEquals("No fijada", starState(1))
    }

    // L2: two terminals of one space got the same label, "Pin alpha · ONE".
    @Test
    fun twoTerminalsOfOneSpace_withTwoRoles_getTwoStarLabels_andTwoActions() {
        showStarred(listOf(core("a", AgentState.Idle, role = "Gate lane"), core("b", AgentState.Idle, role = "Herd lane")))
        assertEquals(setOf("Pin alpha · ONE, Gate lane", "Pin alpha · ONE, Herd lane"), starLabels().toSet())
        val actions = (0..1).flatMap { i -> composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[i].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label } }
        assertEquals(setOf("Pin alpha · ONE, Gate lane", "Pin alpha · ONE, Herd lane"), actions.toSet())
    }

    @Test
    fun aPinnedRowWithARole_hasAnUnpinLabelThatNamesTheRole() {
        showStarred(listOf(core("a", AgentState.Idle, pinned = true, role = "Gate lane")))
        assertEquals(listOf("Unpin alpha · ONE, Gate lane"), starLabels())
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_theStarLabelNamesTheRole() {
        showStarred(listOf(core("a", AgentState.Idle, role = "Gate lane"), core("b", AgentState.Idle, pinned = true, role = "Herd lane")))
        assertEquals(setOf("Fijar alpha · ONE, Gate lane", "Dejar de fijar alpha · ONE, Herd lane"), starLabels().toSet())
    }

    @Test
    fun theStarAction_ofTalkBack_pinsTheRowToo() {
        val r = core("a", AgentState.Idle)
        showStarred(listOf(r))
        val action = composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[0].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == "Pin alpha · ONE" }
        composeTestRule.runOnUiThread { action.action() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(r to RowAction.PIN), acted)
        assertEquals(listOf(org.connectbot.usage.UsageActions.INBOX_STAR), starLogged)
    }

    @Test
    fun aHelperRow_hasItsOwnStar_thatPinsTheHelper() {
        val parent = core("p", AgentState.Working)
        val helper = Agent("h", AgentEngine.CORE, "box", "alpha · ONE", "work", null, "claude-code", AgentState.NeedsYou(), null, 3, null, false, "p")
        showStarred(listOf(parent, Inbox.row(1L, "box", helper)))
        stars().assertCountEquals(2)
        stars()[1].performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("h"), acted.map { it.first.agent.address })
        assertEquals(listOf(RowAction.PIN), acted.map { it.second })
    }

    @Test
    fun theSwipe_stillMarksAnUnreadRowRead_andPinsAnotherOne_withTheStarThere() {
        val unread = core("a", AgentState.Done, unread = true)
        showStarred(listOf(unread))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()
        assertEquals(listOf(unread to RowAction.MARK_READ), acted)
        assertEquals(listOf(org.connectbot.usage.UsageActions.INBOX_MARK_READ), starLogged)
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

    private fun lane(address: String, role: String?, name: String? = null, parent: String? = null, orchestrator: Boolean = false, tab: String = "Dev") = Inbox.row(
        1L,
        "box",
        Agent(address = address, engine = AgentEngine.CORE, machine = "box", space = "ormus · MERCURIO", tab = tab, kind = "claude-code", state = AgentState.Working, turn = 3, parent = parent, name = name, role = role),
    ).copy(orchestrator = orchestrator)

    @Test
    fun aCoreRow_showsItsRoleName_andTalkBackReadsIt() {
        show(listOf(lane("t2", "Herd: Inbox, Spaces, notices", name = "mdev-herd")))
        composeTestRule.onNodeWithText("Herd: Inbox, Spaces, notices").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dev").assertDoesNotExist()
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).assertContentDescriptionContains("Open ormus · MERCURIO, Herd: Inbox, Spaces, notices, on box")
    }

    @Test
    fun aCoreRowWithNoRole_showsItsTerminalName_thenItsTab() {
        show(listOf(lane("t2", null, name = "mdev-herd"), lane("t3", null)))
        composeTestRule.onNodeWithText("mdev-herd").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dev").assertIsDisplayed()
    }

    @Test
    fun aTerminalOthersNameAsParent_showsAnOrchestratorMark_inWords_andTalkBackReadsIt() {
        show(listOf(lane("t1", "Dev lead", orchestrator = true), lane("t2", "Herd", parent = "t1")))
        composeTestRule.onNodeWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Orchestrator", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[0].assertContentDescriptionContains("Open ormus · MERCURIO, Dev lead, on box, Orchestrator, 1 helper")
    }

    @Test
    fun aRowThatIsNotAnOrchestrator_hasNoMark() {
        show(listOf(lane("t1", "Dev lead"), lane("t2", "Herd")))
        composeTestRule.onAllNodesWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Orchestrator", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun aRoleThatAlreadySaysOrchestrator_isNotRepeatedByTheMark_butTheRowStillReadsIt() {
        show(listOf(lane("t1", "Orchestrator", orchestrator = true), lane("t2", "Herd", parent = "t1")))
        composeTestRule.onAllNodesWithText("Orchestrator", useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[0].assertContentDescriptionContains("Open ormus · MERCURIO, Orchestrator, on box, 1 helper")
    }

    @Test
    fun theMark_showsOnAnOrchestratorWithNoRole_andTheRowReadsItsNameThenTheMark() {
        show(listOf(lane("t1", null, name = "mdev-orch", orchestrator = true)))
        composeTestRule.onNodeWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).assertContentDescriptionContains("Open ormus · MERCURIO, mdev-orch, on box, Orchestrator")
    }

    @Test
    fun aParentNamedOnlyByItsTab_thatSaysOrchestrator_isStillMarked_andReadAsAnOrchestrator() {
        // Round 2 (R5): the mark was compared with what the row shows (the tab too), but the spoken name reads the role or the name only.
        show(listOf(lane("t1", null, orchestrator = true, tab = "Orchestrator"), lane("t2", "Herd", parent = "t1")))
        composeTestRule.onAllNodesWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[0].assertContentDescriptionContains("Open ormus · MERCURIO on box, Orchestrator, 1 helper")
    }

    @Test
    fun aParentNamedOnlyByItsTerminalName_thatSaysOrchestrator_isNotMarkedAgain() {
        show(listOf(lane("t1", null, name = "mdev-orchestrator", orchestrator = true), lane("t2", "Herd", parent = "t1")))
        composeTestRule.onAllNodesWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[0].assertContentDescriptionContains("Open ormus · MERCURIO, mdev-orchestrator, on box, 1 helper")
    }

    @Test
    fun theMarkWord_isTheWordTheSearchReads() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(Inbox.ORCHESTRATOR_WORD, context.getString(org.connectbot.R.string.inbox_orchestrator))
    }

    @Test
    fun aRoleThatSaysOrchestratorInLowerCase_isNotRepeatedByTheMark() {
        // 7 of the 11 roles on a real host that carry the word write it in lower case ("Builds orchestrator").
        show(listOf(lane("t1", "Builds orchestrator", orchestrator = true), lane("t2", "Herd", parent = "t1")))
        composeTestRule.onAllNodesWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(INBOX_ROW_TAG)[0].assertContentDescriptionContains("Open ormus · MERCURIO, Builds orchestrator, on box, 1 helper")
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_aRowWithARole_isReadInSpanish() {
        show(listOf(lane("t1", "Herd: Inbox, Spaces, notices")))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).assertContentDescriptionContains("Abrir ormus · MERCURIO, Herd: Inbox, Spaces, notices, en box")
    }

    @Test
    fun aPlainHerdrRow_keepsItsDescriptionAndHasNoMark() {
        show(listOf(row("a", AgentState.Idle)))
        composeTestRule.onNodeWithTag(INBOX_ROW_TAG).assertContentDescriptionContains("Open alpha · ONE on box")
        composeTestRule.onAllNodesWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theTopBar_hasTheTitle_theInboxIconSelectedAndInert_andTheGear() {
        show(emptyList())
        composeTestRule.onNodeWithText("Inbox").assertIsDisplayed()
        val home = composeTestRule.onNodeWithTag(org.connectbot.ui.components.TOP_BAR_HOME_TAG, useUnmergedTree = true)
        assertEquals(true, home.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Selected))
        home.performClick()
        // Inert: the click changes nothing (the icon stays the selected place, nothing opens), and the gear is still there.
        assertEquals(true, home.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Selected))
        assertEquals(emptyList<InboxRow>(), opened)
        composeTestRule.onNodeWithTag(org.connectbot.ui.components.TOP_BAR_SETTINGS_TAG, useUnmergedTree = true).assertIsDisplayed()
        // The bar has no back arrow: Back is the system's.
        composeTestRule.onAllNodesWithContentDescription("Back").assertCountEquals(0)
    }
}
