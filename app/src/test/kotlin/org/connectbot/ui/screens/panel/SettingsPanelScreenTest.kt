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

package org.connectbot.ui.screens.panel

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.ui.theme.Planet
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.connectbot.util.PreferenceConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The settings panel behind the console's gear: the planet picker, the Enter switch and the way on to all settings. */
@RunWith(AndroidJUnit4::class)
class SettingsPanelScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val logged = mutableListOf<String>()
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun setPanel(onAllSettings: () -> Unit = {}) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    SettingsPanelScreen(onNavigateBack = {}, onNavigateToAllSettings = onAllSettings)
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun enterSends_untilTheSwitchIsOn_andTheSwitchIsSaved() {
        setPanel()
        composeTestRule.onNodeWithTag(ENTER_NEW_LINE_TAG).assertIsOff()
        composeTestRule.onNodeWithText("Enter sends, like the ⏎ key on the bar.").assertExists()

        composeTestRule.onNodeWithTag(ENTER_NEW_LINE_TAG).performClick()

        composeTestRule.onNodeWithTag(ENTER_NEW_LINE_TAG).assertIsOn()
        composeTestRule.onNodeWithText("Enter adds a line. The ⏎ key on the bar sends.").assertExists()
        assertTrue(prefs.getBoolean(PreferenceConstants.ENTER_NEW_LINE, false))
        assertEquals(listOf(UsageActions.PANEL_ENTER_NEW_LINE), logged)

        composeTestRule.onNodeWithTag(ENTER_NEW_LINE_TAG).performClick()
        assertFalse(prefs.getBoolean(PreferenceConstants.ENTER_NEW_LINE, true))
    }

    @Test
    fun voiceKey_isOff_withNoServerRows_untilTheSwitchIsOn_thenShowsServerAndStyleAsValues() {
        setPanel()
        composeTestRule.onNodeWithTag(org.connectbot.ui.components.VOICE_SWITCH_TAG).assertIsOff()
        composeTestRule.onNodeWithTag(org.connectbot.ui.components.VOICE_SERVER_TAG).assertDoesNotExist()
        assertFalse(prefs.getBoolean(PreferenceConstants.VOICE_KEY, false))

        composeTestRule.onNodeWithTag(org.connectbot.ui.components.VOICE_SWITCH_TAG).performClick()

        assertTrue(prefs.getBoolean(PreferenceConstants.VOICE_KEY, false))
        composeTestRule.onNodeWithTag(org.connectbot.ui.components.VOICE_SERVER_TAG).assertTextContains("This session's host", substring = true)
        composeTestRule.onNodeWithTag(org.connectbot.ui.components.VOICE_STYLE_TAG).assertTextContains("Server's own", substring = true)
    }

    @Test
    fun voiceServerAndStyle_areEditedFromTheirRows_andSaved() {
        prefs.edit().putBoolean(PreferenceConstants.VOICE_KEY, true).commit()
        setPanel()

        composeTestRule.onNodeWithTag(org.connectbot.ui.components.VOICE_SERVER_TAG).performClick()
        composeTestRule.onNodeWithTag(org.connectbot.ui.components.VOICE_SERVER_FIELD_TAG).performTextInput("box.example")
        composeTestRule.onNodeWithText("OK").performClick()
        assertEquals("box.example", prefs.getString(PreferenceConstants.VOICE_SERVER, null))
        composeTestRule.onNodeWithTag(org.connectbot.ui.components.VOICE_SERVER_TAG).assertTextContains("box.example", substring = true)

        composeTestRule.onNodeWithTag(org.connectbot.ui.components.VOICE_STYLE_TAG).performClick()
        composeTestRule.onNodeWithText("Formal").performClick()
        assertEquals("formal", prefs.getString(PreferenceConstants.VOICE_STYLE, null))
    }

    @Test
    fun aSavedSwitch_readsBackOn() {
        prefs.edit().putBoolean(PreferenceConstants.ENTER_NEW_LINE, true).commit()
        setPanel()
        composeTestRule.onNodeWithTag(ENTER_NEW_LINE_TAG).assertIsOn()
    }

    @Test
    fun sunIsPicked_untilAnotherPlanetIs_andThePickIsSaved() {
        setPanel()
        composeTestRule.onNodeWithTag(planetTag(Planet.SUN)).assertIsSelected()
        composeTestRule.onNodeWithTag(planetTag(Planet.MARS)).assertIsNotSelected()

        composeTestRule.onNodeWithTag(planetTag(Planet.MARS)).performClick()

        assertEquals("mars", prefs.getString(PreferenceConstants.PLANET, null))
        assertEquals(listOf(UsageActions.PANEL_PLANET), logged)
        composeTestRule.onNodeWithTag(planetTag(Planet.MARS)).assertIsSelected()
        composeTestRule.onNodeWithTag(planetTag(Planet.SUN)).assertIsNotSelected()
    }

    @Test
    fun everyPlanet_hasATile_withItsName() {
        setPanel()
        Planet.entries.forEach { composeTestRule.onNodeWithTag(planetTag(it)).assertExists() }
        listOf("Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn").forEach {
            assertTrue(it, composeTestRule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun allSettings_opensTheFullSettingsScreen() {
        var opened = false
        setPanel(onAllSettings = { opened = true })

        composeTestRule.onNodeWithTag(ALL_SETTINGS_TAG).performClick()

        assertTrue(opened)
        assertEquals(listOf(UsageActions.PANEL_ALL_SETTINGS), logged)
    }

    @Test
    fun eachKindOfSetting_isInItsOwnLabeledGroup() {
        setPanel()

        listOf("Look", "Compose bar", "More").forEach { composeTestRule.onNodeWithText(it).assertExists() }
        Planet.entries.forEach { inGroup(planetTag(it), LOOK_GROUP_TAG) }
        inGroup(ENTER_NEW_LINE_TAG, COMPOSE_GROUP_TAG)
        inGroup(ALL_SETTINGS_TAG, MORE_GROUP_TAG)
    }

    private fun inGroup(tag: String, group: String) {
        composeTestRule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(group)), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun theInboxAndCoreSwitches_areOnByDefault_andEachIsSaved() {
        setPanel()
        composeTestRule.onNodeWithTag(INBOX_SWITCH_TAG).assertIsOn()
        composeTestRule.onNodeWithTag(CORE_SWITCH_TAG).assertIsOn()
        assertFalse(prefs.contains(PreferenceConstants.INBOX))

        composeTestRule.onNodeWithTag(INBOX_SWITCH_TAG).performClick()
        composeTestRule.onNodeWithTag(CORE_SWITCH_TAG).performClick()

        composeTestRule.onNodeWithTag(INBOX_SWITCH_TAG).assertIsOff()
        composeTestRule.onNodeWithTag(CORE_SWITCH_TAG).assertIsOff()
        assertTrue(prefs.contains(PreferenceConstants.INBOX))
        assertFalse(prefs.getBoolean(PreferenceConstants.INBOX, true))
        assertFalse(prefs.getBoolean(PreferenceConstants.HERDR_CORE, true))
    }

    @Test
    fun aSavedOff_showsOff() {
        prefs.edit().putBoolean(PreferenceConstants.INBOX, false).putBoolean(PreferenceConstants.HERDR_CORE, false).commit()
        setPanel()
        composeTestRule.onNodeWithTag(INBOX_SWITCH_TAG).assertIsOff()
        composeTestRule.onNodeWithTag(CORE_SWITCH_TAG).assertIsOff()
    }

    @Test
    fun eachSettingRow_showsItsCurrentValueOnTheRight() {
        prefs.edit().putString(PreferenceConstants.PLANET, "saturn").commit()
        setPanel()

        composeTestRule.onNodeWithTag(THEME_VALUE_TAG, useUnmergedTree = true).assertTextContains("Saturn")
        composeTestRule.onNodeWithTag(FONT_ROW_TAG, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(FONT_VALUE_TAG, useUnmergedTree = true).assertTextContains("Default (Monospace)")
        composeTestRule.onNodeWithTag(KEY_BAR_VALUE_TAG, useUnmergedTree = true).assertTextContains("19 keys")
    }

    @Test
    fun theKeyBarValue_countsTheKeysOfTheCustomBar() {
        prefs.edit().putBoolean(PreferenceConstants.KEY_BAR_CUSTOM, true).commit()
        setPanel()
        composeTestRule.onNodeWithTag(KEY_BAR_VALUE_TAG, useUnmergedTree = true).assertTextContains("19 keys")
    }

    @Test
    fun theFontRow_opensAllSettings() {
        var opened = false
        setPanel(onAllSettings = { opened = true })
        composeTestRule.onNodeWithTag(FONT_ROW_TAG).performClick()
        assertTrue(opened)
    }

    @Test
    fun sendFeedback_opensAMailIntent_withVersionsOnly() {
        prefs.edit().putString(PreferenceConstants.HERDR_CORE_VERSION, "0.1.20").commit()
        setPanel()

        composeTestRule.onNodeWithTag(FEEDBACK_ROW_TAG).performClick()

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        val intent = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals(android.content.Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty()
        assertTrue(text.contains("herdr-core 0.1.20"))
        assertTrue(text.contains(org.connectbot.BuildConfig.VERSION_NAME))
    }

    @Test
    fun feedbackAndValueRows_areInLabeledGroups() {
        setPanel()
        listOf("Look", "Compose bar", "More").forEach { composeTestRule.onNodeWithText(it).assertExists() }
        inGroup(FONT_ROW_TAG, LOOK_GROUP_TAG)
        inGroup(KEY_BAR_VALUE_TAG, COMPOSE_GROUP_TAG)
        inGroup(FEEDBACK_ROW_TAG, MORE_GROUP_TAG)
    }

    @Test
    fun theKeyBarValue_followsTheSwitchInThePanel() {
        setPanel()
        composeTestRule.onNodeWithTag(KEY_BAR_VALUE_TAG, useUnmergedTree = true).assertTextContains("19 keys")

        composeTestRule.onNodeWithTag(org.connectbot.ui.components.KEY_BAR_SWITCH_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(KEY_BAR_VALUE_TAG, useUnmergedTree = true).assertTextContains("Off")
    }
}
