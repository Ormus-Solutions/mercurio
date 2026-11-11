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

package org.connectbot.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.data.entity.Profile
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.util.PreferenceConstants
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The full settings screen as grouped cards: each row shows its current value at the right and still writes its own key. */
@RunWith(AndroidJUnit4::class)
class SettingsCardsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeTestRule.activity.getString(id)

    private fun scrollTo(tag: String) {
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
    }

    private fun show(
        uiState: SettingsUiState = SettingsUiState(canAuthenticate = true),
        switches: MutableMap<String, Boolean> = mutableMapOf(),
        picks: MutableMap<String, String> = mutableMapOf(),
        bellVolume: MutableList<Float> = mutableListOf(),
    ) {
        composeTestRule.setContent {
            ConnectBotTheme {
                SettingsScreenContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onAuthOnLaunchChange = { switches[PreferenceConstants.AUTH_ON_LAUNCH] = it },
                    onMemkeysChange = { switches[PreferenceConstants.MEMKEYS] = it },
                    onConnPersistChange = { switches[PreferenceConstants.CONNECTION_PERSIST] = it },
                    onWifilockChange = { switches[PreferenceConstants.WIFI_LOCK] = it },
                    onBackupkeysChange = { switches[PreferenceConstants.BACKUP_KEYS] = it },
                    onScrollbackChange = { picks[PreferenceConstants.SCROLLBACK] = it },
                    onAddCustomTerminalType = {},
                    onRemoveCustomTerminalType = {},
                    onFontFamilyChange = { picks[PreferenceConstants.FONT_FAMILY] = it },
                    onAddCustomFont = {},
                    onRemoveCustomFont = {},
                    onClearFontError = {},
                    onImportLocalFont = { _, _ -> },
                    onDeleteLocalFont = {},
                    onClearImportError = {},
                    onDefaultProfileChange = { picks["profile"] = it.toString() },
                    onLanguageChange = { picks["language"] = it },
                    onRotationChange = {},
                    onFullscreenChange = { switches[PreferenceConstants.FULLSCREEN] = it },
                    onTitleBarHideChange = { switches[PreferenceConstants.TITLEBARHIDE] = it },
                    onPgUpDnGestureChange = { switches[PreferenceConstants.PG_UPDN_GESTURE] = it },
                    onVolumeFontChange = { switches[PreferenceConstants.VOLUME_FONT] = it },
                    onKeepAliveChange = { switches[PreferenceConstants.KEEP_ALIVE] = it },
                    onAlwaysVisibleChange = { switches[PreferenceConstants.KEY_ALWAYS_VISIBLE] = it },
                    onShiftFkeysChange = { switches[PreferenceConstants.SHIFT_FKEYS] = it },
                    onCtrlFkeysChange = { switches[PreferenceConstants.CTRL_FKEYS] = it },
                    onStickyModifiersChange = { picks[PreferenceConstants.STICKY_MODIFIERS] = it },
                    onKeyModeChange = { picks[PreferenceConstants.KEYMODE] = it },
                    onCameraChange = { picks[PreferenceConstants.CAMERA] = it },
                    onBumpyArrowsChange = { switches[PreferenceConstants.BUMPY_ARROWS] = it },
                    onBellChange = { switches[PreferenceConstants.BELL] = it },
                    onBellVolumeChange = { bellVolume += it },
                    onBellVibrateChange = { switches[PreferenceConstants.BELL_VIBRATE] = it },
                    onBellNotificationChange = { switches[PreferenceConstants.BELL_NOTIFICATION] = it },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertValue(key: String, text: String) {
        scrollTo(settingTag(key))
        // The row merges its children, so the value is read from the unmerged tree.
        composeTestRule.onNodeWithTag(settingValueTag(key), useUnmergedTree = true).assertTextContains(text)
    }

    @Test
    fun everySection_isOneCard() {
        show()
        listOf("security", "terminal", "profiles", "interface", "keyboard", "bell").forEach { name ->
            scrollTo(settingsGroupTag(name))
            composeTestRule.onNodeWithTag(settingsGroupTag(name)).assertExists()
        }
    }

    @Test
    fun securityCard_isThereWithoutAuthentication_andLosesOnlyTheAuthRow() {
        show(SettingsUiState(canAuthenticate = false))
        composeTestRule.onNodeWithTag(settingsGroupTag("security")).assertExists()
        composeTestRule.onNodeWithTag(settingTag(PreferenceConstants.AUTH_ON_LAUNCH)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(settingTag(PreferenceConstants.MEMKEYS)).assertExists()
    }

    @Test
    fun listRows_showTheirCurrentValueAtTheRight() {
        show(
            SettingsUiState(
                scrollback = "500",
                stickymodifiers = "alt",
                keymode = PreferenceConstants.KEYMODE_RIGHT,
                camera = PreferenceConstants.CAMERA_ESC,
                defaultProfileId = 2L,
                availableProfiles = listOf(Profile(id = 1L, name = "Work"), Profile(id = 2L, name = "Home")),
            ),
        )
        assertValue(PreferenceConstants.SCROLLBACK, "500")
        assertValue(PreferenceConstants.STICKY_MODIFIERS, string(R.string.only_alt))
        assertValue(PreferenceConstants.KEYMODE, string(R.string.list_keymode_right))
        assertValue(PreferenceConstants.CAMERA, string(R.string.list_camera_esc_description))
        assertValue("profile", "Home")
        assertValue("language", string(R.string.pref_language_system_default))
        assertValue(PreferenceConstants.FONT_FAMILY, "Default (Monospace)")
    }

    @Test
    fun bellVolume_isShownAsAPercentAtTheRight() {
        show(SettingsUiState(bellVolume = 0.75f))
        assertValue(PreferenceConstants.BELL_VOLUME, "75%")
    }

    @Test
    fun everySwitchRow_writesItsOwnKey_withTheOppositeOfItsValue() {
        val switches = mutableMapOf<String, Boolean>()
        val state = SettingsUiState(canAuthenticate = true)
        show(state, switches)
        val expected = mapOf(
            PreferenceConstants.AUTH_ON_LAUNCH to !state.authOnLaunch,
            PreferenceConstants.MEMKEYS to !state.memkeys,
            PreferenceConstants.CONNECTION_PERSIST to !state.connPersist,
            PreferenceConstants.WIFI_LOCK to !state.wifilock,
            PreferenceConstants.BACKUP_KEYS to !state.backupkeys,
            PreferenceConstants.FULLSCREEN to !state.fullscreen,
            PreferenceConstants.TITLEBARHIDE to !state.titlebarhide,
            PreferenceConstants.PG_UPDN_GESTURE to !state.pgupdngesture,
            PreferenceConstants.VOLUME_FONT to !state.volumefont,
            PreferenceConstants.KEEP_ALIVE to !state.keepalive,
            PreferenceConstants.KEY_ALWAYS_VISIBLE to !state.alwaysvisible,
            PreferenceConstants.SHIFT_FKEYS to !state.shiftfkeys,
            PreferenceConstants.CTRL_FKEYS to !state.ctrlfkeys,
            PreferenceConstants.BUMPY_ARROWS to !state.bumpyarrows,
            PreferenceConstants.BELL to !state.bell,
            PreferenceConstants.BELL_VIBRATE to !state.bellVibrate,
            PreferenceConstants.BELL_NOTIFICATION to !state.bellNotification,
        )
        expected.keys.forEach { key ->
            scrollTo(settingTag(key))
            composeTestRule.onNodeWithTag(settingTag(key)).performClick()
        }
        assertEquals(expected, switches)
    }

    @Test
    fun listRows_stillWriteTheirKey_whenAnEntryIsPicked() {
        val picks = mutableMapOf<String, String>()
        show(SettingsUiState(stickymodifiers = "no", keymode = "none"), picks = picks)

        scrollTo(settingTag(PreferenceConstants.STICKY_MODIFIERS))
        composeTestRule.onNodeWithTag(settingTag(PreferenceConstants.STICKY_MODIFIERS)).performClick()
        composeTestRule.onNodeWithText(string(R.string.only_alt)).performClick()

        scrollTo(settingTag(PreferenceConstants.KEYMODE))
        composeTestRule.onNodeWithTag(settingTag(PreferenceConstants.KEYMODE)).performClick()
        composeTestRule.onNodeWithText(string(R.string.list_keymode_left)).performClick()

        assertEquals(
            mapOf(
                PreferenceConstants.STICKY_MODIFIERS to "alt",
                PreferenceConstants.KEYMODE to PreferenceConstants.KEYMODE_LEFT,
            ),
            picks,
        )
    }
}
