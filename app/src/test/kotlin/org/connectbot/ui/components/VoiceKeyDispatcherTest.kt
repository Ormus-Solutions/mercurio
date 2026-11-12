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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.voice.VoiceKeyController
import solutions.ormus.logos.voice.VoiceSettings

/** The voice key takes its dispatcher from the caller, so it renders in a test app that has no Hilt graph. */
@RunWith(AndroidJUnit4::class)
class VoiceKeyDispatcherTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rememberVoiceKey_withoutHilt_doesNotCrash() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
        var controller: VoiceKeyController? = null
        composeTestRule.setContent {
            controller = rememberVoiceKey(Dispatchers.Unconfined, VoiceSettings(prefs), "example-host", 1L) {}
        }
        composeTestRule.waitForIdle()
        assertNotNull(controller)
    }
}
