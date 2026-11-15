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

package org.connectbot.ui.screens.console

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.connectbot.R
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Pin entry of the console menu, on a synthetic terminal: its label says what a tap does, and a write that failed says so. */
@RunWith(AndroidJUnit4::class)
class CorePinEntryTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeTestRule.activity.getString(id)

    private val written = mutableListOf<Boolean>()
    private val notes = mutableListOf<String>()
    private val logged = mutableListOf<String>()
    private var closed = 0

    private fun show(read: suspend () -> Boolean?, write: suspend (Boolean) -> Boolean = { true }) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    val scope = rememberCoroutineScope()
                    Column {
                        CorePinEntry(
                            read = read,
                            write = { pin ->
                                written += pin
                                write(pin)
                            },
                            scope = scope,
                            onClose = { closed++ },
                            onNote = { notes += it },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun anUnpinnedTerminalOffersPinToInbox_andOneTapPinsIt() {
        show(read = { false })

        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).assertIsEnabled().assertHasClickAction().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(true), written)
        assertEquals(listOf(string(R.string.console_pin_done)), notes)
        assertEquals(listOf(UsageActions.MENU_PIN), logged)
        assertEquals(1, closed)
    }

    @Test
    fun aPinnedTerminalOffersUnpin_andOneTapTakesThePinOff() {
        show(read = { true })

        composeTestRule.onNodeWithText(string(R.string.console_menu_unpin)).assertIsEnabled().performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(false), written)
        assertEquals(listOf(string(R.string.console_unpin_done)), notes)
        assertEquals(listOf(UsageActions.MENU_UNPIN), logged)
        assertEquals(1, closed)
    }

    @Test
    fun theLabelNamesTheStateOnlyOnce() {
        show(read = { true })

        composeTestRule.onNodeWithText(string(R.string.console_menu_unpin)).assertIsEnabled()
        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).assertDoesNotExist()
    }

    @Test
    fun aFailedPinSaysSo_andNothingElse() {
        show(read = { false }, write = { false })

        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(true), written)
        assertEquals(listOf(string(R.string.console_pin_failed)), notes)
    }

    @Test
    fun aFailedUnpinSaysSo_andNothingElse() {
        show(read = { true }, write = { false })

        composeTestRule.onNodeWithText(string(R.string.console_menu_unpin)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(false), written)
        assertEquals(listOf(string(R.string.console_unpin_failed)), notes)
    }

    @Test
    fun aStateThatCouldNotBeReadIsOffWithAReason_andATapWritesNothing() {
        show(read = { null })

        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).assertIsNotEnabled().performClick()
        composeTestRule.onNodeWithText(string(R.string.console_pin_unknown)).assertExists()
        composeTestRule.waitForIdle()

        assertEquals(emptyList<Boolean>(), written)
        assertEquals(emptyList<String>(), notes)
        assertEquals(emptyList<String>(), logged)
        assertEquals(0, closed)
    }

    @Test
    fun whileTheStateIsBeingRead_theEntryIsOffAndGivesNoReason() {
        val pending = CompletableDeferred<Boolean?>()
        show(read = { pending.await() })

        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(string(R.string.console_pin_unknown)).assertDoesNotExist()

        pending.complete(true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(string(R.string.console_menu_unpin)).assertIsEnabled()
    }

    @Test
    fun theWriteOutlivesTheMenuThatStartedIt() {
        // The tap closes the menu, which leaves the entry's composition: the write must still end and say how.
        val gate = CompletableDeferred<Boolean>()
        composeTestRule.setContent {
            ConnectBotTheme {
                val scope = rememberCoroutineScope()
                var open by remember { mutableStateOf(true) }
                if (open) {
                    CorePinEntry(read = { false }, write = { gate.await() }, scope = scope, onClose = { open = false }, onNote = { notes += it })
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).assertDoesNotExist()

        gate.complete(false)
        composeTestRule.waitForIdle()

        assertTrue(notes.toString(), notes == listOf(string(R.string.console_pin_failed)))
    }

    // L2 of the gate lane's round 2: a write that timed out after the host took the command may have written the label, so the
    // failure text must not say that nothing changed.
    @Test
    fun theFailureTexts_doNotSayNothingChanged_becauseTheHostMayHaveRunTheWrite() {
        for (id in listOf(R.string.console_pin_failed, R.string.console_unpin_failed)) {
            val text = string(id)
            assertFalse("the text says nothing changed: $text", text.contains("nothing changed", ignoreCase = true))
            assertTrue("the text names the failure: $text", text.startsWith("Could not"))
        }
    }
}
