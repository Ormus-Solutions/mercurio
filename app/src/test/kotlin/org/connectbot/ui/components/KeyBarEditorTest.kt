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

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyBarEditorTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())

    private fun open(store: KeyBarStore) {
        composeTestRule.setContent { ConnectBotTheme { KeyBarSettingsSection(store) } }
        composeTestRule.onNodeWithTag(KEY_BAR_EDIT_TAG).performClick()
    }

    @Test
    fun aCustomKeyIsAddedToThePageAndSaved() {
        val store = KeyBarStore(prefs)
        store.layout = KeyBarLayout(listOf(listOf("esc")))
        open(store)

        composeTestRule.onNodeWithTag(KEY_BAR_CUSTOM_KEY_TAG).performClick()
        composeTestRule.onNodeWithTag(KEY_BAR_CUSTOM_ADD_TAG).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(KEY_BAR_CUSTOM_TEXT_TAG).performTextInput("/clear")
        composeTestRule.onNodeWithTag(KEY_BAR_CUSTOM_ADD_TAG).performClick()

        assertEquals(listOf("esc", KeyBarCatalog.textKeyId("/clear", enter = false)), store.layout.pages[0])
    }

    @Test
    fun theCatalogOffersTheNewKeys() {
        val store = KeyBarStore(prefs)
        store.layout = KeyBarLayout(listOf(listOf("esc")))
        open(store)

        composeTestRule.onNodeWithTag("key_bar_add_f12").performClick()
        composeTestRule.onNodeWithTag("key_bar_add_paste").performClick()
        assertEquals(listOf("esc", "f12", "paste"), store.layout.pages[0])
    }
}
