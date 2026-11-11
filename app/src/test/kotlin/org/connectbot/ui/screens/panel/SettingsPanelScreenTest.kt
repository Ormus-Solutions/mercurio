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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            composeTestRule.onNodeWithText(it).assertExists()
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

        listOf("Theme", "Compose bar", "More").forEach { composeTestRule.onNodeWithText(it).assertExists() }
        Planet.entries.forEach { inGroup(planetTag(it), THEME_GROUP_TAG) }
        inGroup(ENTER_NEW_LINE_TAG, COMPOSE_GROUP_TAG)
        inGroup(ALL_SETTINGS_TAG, MORE_GROUP_TAG)
    }

    private fun inGroup(tag: String, group: String) {
        composeTestRule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(group)), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun theInboxAndCoreSwitches_areOffByDefault_andEachIsSaved() {
        setPanel()
        composeTestRule.onNodeWithTag(INBOX_SWITCH_TAG).assertIsOff()
        composeTestRule.onNodeWithTag(CORE_SWITCH_TAG).assertIsOff()
        assertFalse(prefs.getBoolean(PreferenceConstants.INBOX, false))

        composeTestRule.onNodeWithTag(INBOX_SWITCH_TAG).performClick()
        composeTestRule.onNodeWithTag(CORE_SWITCH_TAG).performClick()

        composeTestRule.onNodeWithTag(INBOX_SWITCH_TAG).assertIsOn()
        composeTestRule.onNodeWithTag(CORE_SWITCH_TAG).assertIsOn()
        assertTrue(prefs.getBoolean(PreferenceConstants.INBOX, false))
        assertTrue(prefs.getBoolean(PreferenceConstants.HERDR_CORE, false))
    }
}
