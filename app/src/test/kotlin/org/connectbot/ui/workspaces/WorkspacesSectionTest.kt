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

package org.connectbot.ui.workspaces

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.data.entity.Host
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.HerdrWorkspace

/** The Workspaces keys on the host list: one per kept console, a tap opens it. */
@RunWith(AndroidJUnit4::class)
class WorkspacesSectionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val box = Host(id = 1L, nickname = "box", hostname = "box.example", username = "me", postLogin = "herdr\n")
    private val other = Host(id = 2L, nickname = "other", hostname = "other.example", username = "me", postLogin = "herdr\n")
    private val work = HerdrConsole("work", workspaceLabel = "main")
    private val review = HerdrConsole("review")

    private val logged = mutableListOf<String>()
    private val opened = mutableListOf<Pair<Host, HerdrConsole>>()

    private fun show(workspaces: List<HerdrWorkspace>) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    WorkspacesSection(
                        workspaces = workspaces,
                        onOpen = { host, console -> opened += host to console },
                    )
                }
            }
        }
    }

    @Test
    fun oneKeyPerWorkboard_namedByItsWorkspaceOrSession_underTheHeading() {
        show(listOf(HerdrWorkspace(box, work), HerdrWorkspace(box, review)))

        composeTestRule.onNodeWithText("Workspaces").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(WORKSPACE_KEY_TAG).assertCountEquals(2)
        composeTestRule.onNodeWithText("main").assertIsDisplayed()
        composeTestRule.onNodeWithText("review").assertIsDisplayed()
        // One host: its name adds nothing.
        composeTestRule.onNodeWithText("box").assertDoesNotExist()
    }

    @Test
    fun theKeys_areOneLabeledGroup() {
        show(listOf(HerdrWorkspace(box, work), HerdrWorkspace(other, review)))

        composeTestRule.onNodeWithTag(WORKSPACES_GROUP_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Workspaces").assertIsDisplayed()
        composeTestRule.onAllNodes(hasTestTag(WORKSPACE_KEY_TAG) and !hasAnyAncestor(hasTestTag(WORKSPACES_GROUP_TAG))).assertCountEquals(0)
    }

    @Test
    fun aTapOpensThatConsoleOfItsHost_andLogsTheKey() {
        show(listOf(HerdrWorkspace(box, work), HerdrWorkspace(box, review)))

        composeTestRule.onNodeWithText("review").performClick()

        assertEquals(listOf(box to review), opened)
        assertEquals(listOf("workspaces:open"), logged)
    }

    @Test
    fun hostsAreNamed_onlyWhenTwoOrMoreHaveWorkboards() {
        show(listOf(HerdrWorkspace(box, work), HerdrWorkspace(other, review)))

        composeTestRule.onNodeWithText("box").assertIsDisplayed()
        composeTestRule.onNodeWithText("other").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(WORKSPACE_KEY_TAG).assertCountEquals(2)
    }
}
