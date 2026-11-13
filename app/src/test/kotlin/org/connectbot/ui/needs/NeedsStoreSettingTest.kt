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

package org.connectbot.ui.needs

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import org.connectbot.R
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.home.NeedsStore

/** The "Questions store" row in Settings: its address is kept, and saving it logs one usage id, never the address. */
@RunWith(AndroidJUnit4::class)
class NeedsStoreSettingTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var prefs: SharedPreferences
    private val logged = mutableListOf<String>()

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("needs-setting-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun theAddressHint_isHttps_becausePlainHttpIsAllowedOnlyToLoopback() {
        val hint = composeTestRule.activity.getString(R.string.pref_needs_store_hint)
        org.junit.Assert.assertTrue(hint, hint.startsWith("https://"))
    }

    @Test
    fun savingTheAddress_keepsIt_andLogsTheIdOnly() {
        val vm = NeedsViewModel(NeedsStore(prefs, CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)))
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) { NeedsStoreSetting(viewModel = vm) }
            }
        }

        composeTestRule.onNodeWithTag(NEEDS_STORE_TAG).performClick()
        composeTestRule.onNodeWithTag(NEEDS_STORE_FIELD_TAG).performTextInput("https://store.example/needs")
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.button_ok)).performClick()

        assertEquals("https://store.example/needs", vm.address.value)
        assertEquals(listOf(UsageActions.NEEDS_STORE_SET), logged)
    }
}
