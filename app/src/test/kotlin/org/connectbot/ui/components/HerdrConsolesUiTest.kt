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

package org.connectbot.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.WorkspaceChoice
import solutions.ormus.logos.herd.WorkspacePick

/**
 * The consoles of one Herdr host in the session drawer, and the drawer's Open a
 * workspace list. Synthetic host and workspace names only.
 */
@RunWith(AndroidJUnit4::class)
class HerdrConsolesUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val box = Host(id = 7, nickname = "box", username = "me", hostname = "box.test")

    // An extra console's Herdr side shows its own session from the start, as TerminalBridge's does.
    private fun console(id: Long, target: HerdrConsole?): TerminalBridge {
        val herdSide = mock<HerdSession> { on { attached } doReturn MutableStateFlow(target?.session ?: HerdrCommands.DEFAULT_SESSION) }
        return mock {
            on { host } doReturn box
            on { consoleId } doReturn id
            on { console } doReturn target
            on { herd } doReturn herdSide
            on { activityPreview } doReturn MutableStateFlow("")
        }
    }

    private fun drawer(bridges: List<TerminalBridge>, onSelect: (TerminalBridge) -> Unit = {}, onOpenWorkspace: (() -> Unit)? = null) {
        rule.setContent {
            ConnectBotTheme {
                SessionDrawer(
                    bridges = bridges,
                    activeBridge = bridges.first(),
                    onSelect = onSelect,
                    onDisconnect = {},
                    onQuickConnect = {},
                    onOpenHostList = {},
                    onOpenWorkspace = onOpenWorkspace,
                )
            }
        }
    }

    @Test
    fun drawer_aHostsConsolesAreOneRowEach_namedBySessionAndWorkspace() {
        val first = console(1, null)
        val extra = console(2, HerdrConsole("beta", "w3", "gamma"))
        val selected = mutableListOf<TerminalBridge>()
        drawer(listOf(first, extra), onSelect = { selected += it })

        rule.onNodeWithText("box").assertExists()
        rule.onNodeWithText("box · beta · gamma").performClick()
        assertEquals(listOf(extra), selected)
    }

    @Test
    fun drawer_openAWorkspace_onlyWhereItIsOffered() {
        var opened = 0
        drawer(listOf(console(1, null)), onOpenWorkspace = { opened++ })
        rule.onNodeWithText("Open a workspace").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun drawer_noOpenAWorkspace_onAHostWithoutIt() {
        drawer(listOf(console(1, null)))
        rule.onAllNodesWithText("Open a workspace").assertCountEquals(0)
    }

    private fun choice(session: String, id: String, number: Int, label: String) = WorkspaceChoice(id, number, label, focused = false, state = HerdrAgentStatus.IDLE, session = session)

    @Test
    fun openWorkspace_rowsBySession_andARowOpensItsWorkspace() {
        val gamma = choice("beta", "w3", 3, "gamma")
        val choices = listOf(choice("default", "w1", 1, "home"), choice("beta", "w1", 1, "tmp"), gamma)
        val opened = mutableListOf<WorkspaceChoice>()
        rule.setContent { ConnectBotTheme { HerdrOpenWorkspaceContent(choices) { opened += it } } }

        rule.onNodeWithText("Open in a new console").assertExists()
        // Two sessions: each one's rows under its name.
        rule.onNodeWithText("default").assertExists()
        rule.onNodeWithText("beta").assertExists()
        rule.onNodeWithText("3 · gamma").performClick()
        assertEquals(listOf(gamma), opened)
    }

    @Test
    fun openWorkspace_saysItIsReading_untilTheRowsCome() {
        rule.setContent { ConnectBotTheme { HerdrOpenWorkspaceContent(emptyList()) {} } }
        rule.onNodeWithText("Reading Herdr…").assertExists()
    }

    private val home = WorkspaceChoice("w1", 1, "home", focused = true, state = HerdrAgentStatus.IDLE)
    private val one = choice("alpha", "w1", 1, "one")
    private val three = choice("alpha", "w3", 3, "three")

    private fun picker(): MutableList<Pair<WorkspacePick, List<WorkspaceChoice>>> {
        val picks = mutableListOf<Pair<WorkspacePick, List<WorkspaceChoice>>>()
        rule.setContent { ConnectBotTheme { HerdrWorkspacePromptContent(listOf(home, one, three)) { pick, alsoOpen -> picks += pick to alsoOpen } } }
        return picks
    }

    @Test
    fun picker_aPlusMarksARow_andThePickCarriesIt() {
        val picks = picker()
        rule.onNodeWithContentDescription("Also open three in its own console").performClick()
        rule.onNodeWithContentDescription("three opens in its own console, tap to undo").assertExists()
        rule.onNodeWithText("1 · one").performClick()
        assertEquals(listOf(WorkspacePick.Focus("w1", "alpha") to listOf(three)), picks)
    }

    @Test
    fun picker_theRowThisConsoleAttachesOpensOnce() {
        val picks = picker()
        rule.onNodeWithContentDescription("Also open one in its own console").performClick()
        rule.onNodeWithText("1 · one").performClick()
        assertEquals(listOf(WorkspacePick.Focus("w1", "alpha") to emptyList<WorkspaceChoice>()), picks)
    }

    @Test
    fun picker_theGoldKeyLeavesOutTheWorkspaceItAttaches() {
        val picks = picker()
        rule.onNodeWithContentDescription("Also open home in its own console").performClick()
        rule.onNodeWithContentDescription("Also open three in its own console").performClick()
        rule.onNodeWithText("Attach · home").performClick()
        assertEquals(listOf(WorkspacePick.Attach to listOf(three)), picks)
    }

    @Test
    fun picker_aSecondTapUnmarks() {
        val picks = picker()
        rule.onNodeWithContentDescription("Also open three in its own console").performClick()
        rule.onNodeWithContentDescription("three opens in its own console, tap to undo").performClick()
        rule.onNodeWithText("1 · one").performClick()
        assertEquals(listOf(WorkspacePick.Focus("w1", "alpha") to emptyList<WorkspaceChoice>()), picks)
    }
}
