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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.data.entity.Host
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.home.HomeAgents
import solutions.ormus.logos.home.HomeBoard
import solutions.ormus.logos.home.HomeBoards
import solutions.ormus.logos.home.HomeWorkboards
import solutions.ormus.logos.home.HomeWorkspace
import solutions.ormus.logos.home.WorkboardBlock

/** The workboards on the host list: blocks, keys, what a tap opens, and what an off key says. */
@RunWith(AndroidJUnit4::class)
class WorkboardsSectionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val box = Host(id = 1L, nickname = "Box", protocol = "ssh", username = "me", hostname = "box.example", postLogin = "herdr\n")
    private val work = HomeBoard("work", machine = "box", session = "work", agents = HomeAgents(blocked = 2), workspaces = listOf(HomeWorkspace("review", agents = HomeAgents(blocked = 1)), HomeWorkspace("ops")))
    private val far = HomeBoard("far", machine = "far", session = "far", workspaces = listOf(HomeWorkspace("edge")))
    private val lab = HomeBoard("lab", workspaces = listOf(HomeWorkspace("bench")))
    private val odd = HomeBoard("odd", machine = "box", session = "no way", workspaces = listOf(HomeWorkspace("x")))
    private val feed = HomeBoards(boards = listOf(work, far, lab, odd), hosts = mapOf("box" to listOf("box.example"), "far" to listOf("far.example")))

    // The real join, so the keys say what the join found.
    private val blocks: List<WorkboardBlock> = HomeWorkboards.blocks(feed, listOf(box))

    private val logged = mutableListOf<String>()
    private val opened = mutableListOf<Pair<Host, HerdrConsole>>()
    private val saved = mutableListOf<Host>()

    private fun show(blocks: List<WorkboardBlock>, fetchedAt: Long = System.currentTimeMillis(), error: String? = null) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    WorkboardsSection(blocks = blocks, fetchedAt = fetchedAt, error = error, onOpen = { host, console -> opened += host to console }, onSave = { saved += it })
                }
            }
        }
    }

    @Test
    fun oneBlockPerWorkboard_namedWithItsMachine_andOneKeyPerWorkspace() {
        show(blocks)

        composeTestRule.onNodeWithText("Workboards").assertIsDisplayed()
        composeTestRule.onNodeWithText("as of ", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("work · box").assertIsDisplayed()
        composeTestRule.onNodeWithText("far · far").assertIsDisplayed()
        // A board the feed places nowhere reads by its name alone.
        composeTestRule.onNodeWithText("lab").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(WORKBOARD_KEY_TAG).assertCountEquals(5)
        // The board's count once; a workspace whose own agents wait carries the mark.
        composeTestRule.onNodeWithText("Needs you · 2").assertIsDisplayed()
        composeTestRule.onNodeWithText(NEEDS_YOU_MARK + "review").assertIsDisplayed()
        composeTestRule.onNodeWithText("ops").assertIsDisplayed()
        composeTestRule.onNodeWithText("Needs you · 0").assertDoesNotExist()
        composeTestRule.onNodeWithText("Not read:", substring = true).assertDoesNotExist()
    }

    @Test
    fun theBoards_areOneLabeledGroup_withEveryKeyAndSaveKeyInside() {
        show(blocks)

        composeTestRule.onNodeWithTag(WORKBOARDS_GROUP_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Workboards").assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("as of ", substring = true) and hasAnyAncestor(hasTestTag(WORKBOARDS_GROUP_TAG))).assertCountEquals(1)
        composeTestRule.onAllNodes(hasTestTag(WORKBOARD_KEY_TAG) and !hasAnyAncestor(hasTestTag(WORKBOARDS_GROUP_TAG))).assertCountEquals(0)
        composeTestRule.onAllNodes(hasTestTag(WORKBOARD_SAVE_TAG) and !hasAnyAncestor(hasTestTag(WORKBOARDS_GROUP_TAG))).assertCountEquals(0)
    }

    @Test
    fun aKeyThatOpens_callsBackWithTheHostAndTheConsole_andLogsIt() {
        show(blocks)

        composeTestRule.onNodeWithText(NEEDS_YOU_MARK + "review").assertIsEnabled().performClick()

        assertEquals(listOf(box to HerdrConsole("work", workspaceLabel = "review")), opened)
        assertEquals(listOf(UsageActions.WORKBOARDS_OPEN), logged)
        // The spoken name says it waits on the user; the plain one does not.
        composeTestRule.onNodeWithContentDescription("Open review of work on box; needs you").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Open ops of work on box").assertIsDisplayed()
    }

    @Test
    fun anOffKey_cannotBeTapped_andSaysWhatTheJoinFound() {
        show(blocks)

        composeTestRule.onNodeWithText("edge").assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText("bench").assertIsNotEnabled()
        composeTestRule.onNodeWithText("x").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("edge of far: no saved host on far").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("bench of lab: no session known").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("x of odd: no way is not a session name").assertIsDisplayed()
        assertEquals(emptyList<Pair<Host, HerdrConsole>>(), opened)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun aFailedRead_isSaidUnderTheHeading_andNoTime_noAsOf() {
        show(blocks, fetchedAt = 0L, error = "The feed answered HTTP 503")

        composeTestRule.onNodeWithText("as of ", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Not read: The feed answered HTTP 503").assertIsDisplayed()
    }

    @Test
    fun aBoardWithNoSavedCopy_hasASaveKey_thatHandsTheHostToSave_andLogsIt() {
        show(blocks)

        // Only work: far has no host and no link on its machine, lab no place, odd no session name.
        composeTestRule.onAllNodesWithTag(WORKBOARD_SAVE_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription("Save work on box as a host").assertIsDisplayed().performClick()

        val host = saved.single()
        assertEquals("work", host.nickname)
        assertEquals(box.hostname, host.hostname)
        assertEquals("herdr session attach 'work'\n", host.postLogin)
        assertEquals(listOf(UsageActions.WORKBOARDS_SAVE_HOST), logged)
        assertEquals(emptyList<Pair<Host, HerdrConsole>>(), opened)
    }

    @Test
    fun aBoardWithASavedCopy_hasNoSaveKey() {
        val copy = Host(id = 2L, nickname = "work", protocol = "ssh", username = "me", hostname = "box.example", postLogin = "herdr session attach work\n")

        show(HomeWorkboards.blocks(feed, listOf(box, copy)))

        composeTestRule.onAllNodesWithTag(WORKBOARD_SAVE_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithText("Save").assertDoesNotExist()
    }

    // A board the feed leaves spread over two machines: a session of its name runs on both.
    private val wide = HomeBoard("wide", workspaces = listOf(HomeWorkspace("a", machine = "box", session = "wide"), HomeWorkspace("b", machine = "far", session = "wide")))
    private val wideFeed = HomeBoards(boards = listOf(wide), hosts = feed.hosts, links = mapOf("far" to "ssh://ops@far.example"))

    @Test
    fun aBoardSpreadOverMachines_hasASaveKeyPerMachine_eachNamingIt() {
        show(HomeWorkboards.blocks(wideFeed, listOf(box)))

        composeTestRule.onAllNodesWithTag(WORKBOARD_SAVE_TAG).assertCountEquals(2)
        composeTestRule.onNodeWithText("Save").assertDoesNotExist()
        composeTestRule.onNodeWithText("Save on box").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("Save wide on far as a host").assertIsDisplayed().performClick()

        // Box's key hands its saved host's account; Far's, with no host there, the feed's link. Both carry the
        // board's name: the host list frees the second against the rows when it saves (HostListViewModel.addHost).
        assertEquals(listOf(box.hostname, "far.example"), saved.map { it.hostname })
        assertEquals(listOf("wide", "wide"), saved.map { it.nickname })
        assertEquals(listOf("herdr session attach 'wide'\n"), saved.map { it.postLogin }.distinct())
        assertEquals(listOf(UsageActions.WORKBOARDS_SAVE_HOST, UsageActions.WORKBOARDS_SAVE_HOST), logged)
    }

    @Test
    fun aSpreadBoardSavedOnOneMachine_keepsTheOtherMachinesKey_stillNamingIt() {
        val onBox = Host(id = 2L, nickname = "wide", protocol = "ssh", username = "me", hostname = "box.example", postLogin = "herdr session attach 'wide'\n")

        show(HomeWorkboards.blocks(wideFeed, listOf(box, onBox)))

        composeTestRule.onAllNodesWithTag(WORKBOARD_SAVE_TAG).assertCountEquals(1)
        // The block's heading names no machine, so the one key left still does.
        composeTestRule.onNodeWithText("Save").assertDoesNotExist()
        composeTestRule.onNodeWithText("Save on far").assertIsDisplayed()
    }

    @Test
    fun aBoardWhoseOneKeyIsNotOnTheHeadingsMachine_namesTheMachine() {
        // The feed gives the board a machine but no session; its one workspace stands elsewhere.
        val away = HomeBoard("away", machine = "box", workspaces = listOf(HomeWorkspace("a", machine = "far", session = "away")))

        show(HomeWorkboards.blocks(HomeBoards(boards = listOf(away), hosts = feed.hosts, links = wideFeed.links), listOf(box)))

        composeTestRule.onAllNodesWithTag(WORKBOARD_SAVE_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithText("Save").assertDoesNotExist()
        composeTestRule.onNodeWithText("Save on far").assertIsDisplayed()
    }
}
