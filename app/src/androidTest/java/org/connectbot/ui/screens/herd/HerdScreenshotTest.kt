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

package org.connectbot.ui.screens.herd

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.connectbot.transport.CommandOutput
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.UsageActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.parseHerdrSnapshot

/**
 * Renders the Herd screen and the Herdr command sheet on a device from the
 * snapshot recorded on Sun, and saves a screenshot of each to
 * /data/local/tmp/herd-*.png (pull with adb) as evidence of how they look.
 */
@RunWith(AndroidJUnit4::class)
class HerdScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fixture = checkNotNull(javaClass.getResourceAsStream("/herdr/snapshot-sun-mixed.json")).readBytes().decodeToString()

    private fun screencap(name: String) {
        composeTestRule.waitForIdle()
        // Let the first frame reach the display before capturing it.
        Thread.sleep(SCREENCAP_SETTLE_MS)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/$name.png")
            .close()
        // screencap runs asynchronously in the shell; give it a moment to write.
        Thread.sleep(SCREENCAP_SETTLE_MS)
    }

    @Test
    fun herdScreen() {
        val snapshot = checkNotNull(parseHerdrSnapshot(fixture))
        composeTestRule.setContent {
            ConnectBotTheme {
                // The test activity draws edge to edge; keep clear of the bars as the app's dialog does.
                Box(Modifier.safeDrawingPadding()) {
                    HerdScreen(snapshot = snapshot, onOpenAgent = {}, onRefresh = {}, onOpenCommands = {}, onClose = {})
                }
            }
        }

        composeTestRule.onAllNodesWithTag(HERD_CARD_TAG).assertCountEquals(6)
        screencap("herd-screen")
    }

    @Test
    fun commandSheet() {
        val session = HerdSession(
            hostName = "Sun",
            runner = { command ->
                CommandOutput(if (command == Herd.snapshotCommand) fixture else """{"result":{}}""", "", 0)
            },
            sendKeys = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        composeTestRule.setContent {
            ConnectBotTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { HerdrCommandPanel(session = session, section = HerdrSection.PANES, onSection = {}) }
                }
            }
        }

        screencap("herd-sheet-top")
        composeTestRule.onNodeWithText("Close pane").performClick()
        screencap("herd-sheet-confirm")
    }

    @Test
    fun editablePanel() {
        val session = HerdSession(
            hostName = "Sun",
            runner = { command ->
                CommandOutput(if (command == Herd.snapshotCommand) fixture else """{"result":{}}""", "", 0)
            },
            sendKeys = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        // Its own preferences file, so the app's real panel layout is never touched.
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("herdr-panel-screenshot", Context.MODE_PRIVATE)
        prefs.edit { clear() }
        val store = HerdrPanelStore(prefs)
        store.hide(UsageActions.HERDR_SHEET_RELOAD_CONFIG)
        store.add(HerdrPanelKey("Redraw", "shift+r"))
        composeTestRule.setContent {
            ConnectBotTheme {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        HerdrCommandPanel(session = session, section = HerdrSection.SESSION, onSection = {}, store = store)
                    }
                }
            }
        }

        screencap("herd-panel-session")
        composeTestRule.onNodeWithText("Goto").performTouchInput { longClick() }
        screencap("herd-panel-menu")
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Add key").performClick()
        composeTestRule.onNodeWithText("Label").performTextInput("Find")
        composeTestRule.onNodeWithText("Key").performTextInput("super+f")
        screencap("herd-panel-add")
        prefs.edit { clear() }
    }

    private companion object {
        const val SCREENCAP_SETTLE_MS = 1_500L
    }
}
