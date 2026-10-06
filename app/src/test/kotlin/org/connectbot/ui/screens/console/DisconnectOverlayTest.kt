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

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.service.DiagnosticsInput
import org.connectbot.service.DiagnosticsReport
import org.connectbot.service.FixPrompt
import org.connectbot.service.NetworkSummary
import org.connectbot.service.SessionSummary
import org.connectbot.ui.components.COPY_DETAILS_TAG
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.TimeZone

/**
 * The connection-lost overlay offers "Copy details" next to Close and
 * Reconnect; one tap puts the diagnostics report on the clipboard and the
 * button confirms with "Copied".
 */
@RunWith(AndroidJUnit4::class)
class DisconnectOverlayTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeTestRule.activity.getString(id)

    private val expectedReport = DiagnosticsReport.build(
        DiagnosticsInput(
            appName = "Mercurio",
            versionName = "1.4.0-oss",
            applicationId = "solutions.ormus.logos.debug",
            deviceModel = "Google Pixel 8",
            androidVersion = "15 (API 35)",
            network = NetworkSummary(type = "cellular", vpnActive = false),
            session = SessionSummary(
                nickname = "Sun",
                protocol = "ssh",
                username = "alice",
                hostname = "sun.example",
                port = 22,
                state = "disconnected",
                disconnectReason = "AUTH_FAIL",
                failure = IOException("Password authentication failed"),
                connectionLog = "Connecting to sun.example:22 via ssh\nTrying password authentication",
            ),
            nowMillis = 0L,
            timeZone = TimeZone.getTimeZone("UTC"),
            secrets = listOf("hunter2-Saved!"),
        ),
    )

    @Test
    fun copyDetailsPutsTheReportOnTheClipboard() {
        var closed = false
        var reconnected = false
        composeTestRule.setContent {
            ConnectBotTheme {
                DisconnectOverlay(
                    onClose = { closed = true },
                    onReconnect = { reconnected = true },
                    report = { expectedReport },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.alert_disconnect_msg)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.console_menu_close)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.console_menu_reconnect)).assertIsDisplayed()

        // Hold the clock so the brief "Copied" label can be seen before it reverts.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithText(string(R.string.copy_details)).assertIsDisplayed().performClick()
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.onNodeWithTag(COPY_DETAILS_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.copy_details_copied)).assertIsDisplayed()

        val clipboard = composeTestRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        // The report goes out wrapped in a prompt an agent can act on.
        assertEquals(FixPrompt.connection(expectedReport), copied)
        assertTrue(copied.startsWith("Mercurio can't connect, and I need you to fix it."))
        assertTrue(copied.contains("Sun (ssh) alice@sun.example:22"))
        assertTrue(copied.contains("java.io.IOException: Password authentication failed"))
        assertFalse(copied.contains("hunter2-Saved!"))

        // The label returns once the confirmation has been seen.
        composeTestRule.mainClock.advanceTimeBy(3000)
        composeTestRule.onNodeWithText(string(R.string.copy_details)).assertIsDisplayed()
        assertFalse(closed)
        assertFalse(reconnected)
    }

    @Test
    fun wishListActionSitsNextToCopyDetails() {
        composeTestRule.setContent {
            ConnectBotTheme {
                DisconnectOverlay(
                    onClose = {},
                    onReconnect = {},
                    report = { expectedReport },
                    wishListAction = { Text("Send to wish list") },
                )
            }
        }
        composeTestRule.onNodeWithText(string(R.string.copy_details)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Send to wish list").assertIsDisplayed()
    }

    @Test
    fun closeAndReconnectStillWork() {
        var closed = false
        var reconnected = false
        composeTestRule.setContent {
            ConnectBotTheme {
                DisconnectOverlay(
                    onClose = { closed = true },
                    onReconnect = { reconnected = true },
                    report = { expectedReport },
                )
            }
        }
        composeTestRule.onNodeWithText(string(R.string.console_menu_close)).performClick()
        composeTestRule.onNodeWithText(string(R.string.console_menu_reconnect)).performClick()
        assertTrue(closed)
        assertTrue(reconnected)
    }
}
