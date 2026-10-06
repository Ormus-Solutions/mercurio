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

package org.connectbot.ui.commands

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import org.connectbot.di.CoroutineDispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import solutions.ormus.logos.commands.AgentKind
import solutions.ormus.logos.commands.OwnCommands
import solutions.ormus.logos.commands.SlashCatalogs
import solutions.ormus.logos.commands.SlashCommand
import solutions.ormus.logos.commands.SlashCommandRepository
import solutions.ormus.logos.commands.commandsFixture

/** The slash menu's content: groups, agent picker, taps and long presses. */
@RunWith(AndroidJUnit4::class)
class SlashMenuContentTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val own = OwnCommands.parse(commandsFixture("commands/own-commands-sun.txt"))
        .filter { it.agent == AgentKind.CLAUDE }
        .map { it.command }
        .sortedBy { it.name }

    private val state = SlashMenuUiState(
        agent = AgentKind.CLAUDE,
        detected = true,
        builtIns = SlashCatalogs.CLAUDE.take(6),
        own = own,
        fetchedAt = System.currentTimeMillis() - 5 * 60_000,
    )

    private val runs = mutableListOf<Pair<String, Boolean>>()
    private val picks = mutableListOf<AgentKind>()

    private fun show(state: SlashMenuUiState = this.state) {
        composeTestRule.setContent {
            MenuTheme {
                SlashMenuContent(
                    state = state,
                    query = state.query,
                    onQueryChange = {},
                    onPickAgent = { picks += it },
                    onRefresh = {},
                    onRun = { command: SlashCommand, insert: Boolean -> runs += command.text to insert },
                )
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun render_builtInsAndOwnCommands() {
        show()

        composeTestRule.onNodeWithText("Slash commands").assertIsDisplayed()
        composeTestRule.onNodeWithText("In the focused pane").assertIsDisplayed()
        composeTestRule.onNodeWithText("Built-in").assertIsDisplayed()
        composeTestRule.onNodeWithTag(SLASH_ROW_TAG + "/clear").assertIsDisplayed()
        composeTestRule.onNodeWithTag(SLASH_ROW_TAG + "/00-gnosis").assertExists()
        saveRender(composeTestRule.activity, "slash-menu.png")
    }

    @Test
    fun tapRuns_longPressInserts() {
        show()

        composeTestRule.onNodeWithTag(SLASH_ROW_TAG + "/clear").performClick()
        composeTestRule.onNodeWithTag(SLASH_ROW_TAG + "/clear").performTouchInput { longClick() }

        assertEquals(listOf("/clear" to false, "/clear" to true), runs)
    }

    @Test
    fun agentChips_pickAnAgent() {
        show()

        composeTestRule.onNodeWithText("Grok").performClick()

        assertEquals(listOf(AgentKind.GROK), picks)
    }

    @Test
    fun searchField_keepsEveryTypedCharacterInOrder_whileTheListCatchesUp() {
        // A dispatcher that never runs: the view model's filtered state lags forever,
        // as it does for a moment on a phone while dictation types fast.
        val stalled = StandardTestDispatcher()
        val dispatchers = CoroutineDispatchers(default = stalled, io = stalled, main = stalled)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SlashCommandRepository(context.cacheDir, context.getSharedPreferences("slash-ui-test", Context.MODE_PRIVATE), dispatchers)
        val viewModel = SlashMenuViewModel(repository, dispatchers)
        composeTestRule.setContent {
            MenuTheme {
                SlashMenuSheet(SlashTarget(1, null, { null }, {}), onInsert = {}, onDismiss = {}, viewModel = viewModel)
            }
        }

        val field = composeTestRule.onNodeWithTag(SLASH_SEARCH_TAG)
        "add-dir".forEach { field.performTextInput(it.toString()) }

        field.assertTextEquals("add-dir")
    }

    @Test
    fun ownStatus_saysWhenTheReadFailed() {
        show(state.copy(own = emptyList(), failed = true))

        composeTestRule.onNodeWithText("Could not read your commands from this host. Connect, then refresh.").assertIsDisplayed()
    }
}
