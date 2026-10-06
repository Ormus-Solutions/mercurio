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

package org.connectbot.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The brand has no light mode: the app renders the dark scheme whatever the phone is set to. */
@RunWith(AndroidJUnit4::class)
class ConnectBotThemeDarkOnlyTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun rendered(): Pair<ColorScheme, OrmusExtras> {
        lateinit var scheme: ColorScheme
        lateinit var extras: OrmusExtras
        rule.setContent {
            ConnectBotTheme {
                scheme = MaterialTheme.colorScheme
                extras = Ormus.extras
            }
        }
        rule.waitForIdle()
        return scheme to extras
    }

    @Test
    @Config(qualifiers = "notnight")
    fun lightPhone_stillRendersDark() {
        val (scheme, extras) = rendered()
        assertSame(OrmusDarkColorScheme, scheme)
        assertSame(OrmusDarkExtras, extras)
    }

    @Test
    @Config(qualifiers = "night")
    fun nightPhone_rendersDark() {
        val (scheme, extras) = rendered()
        assertSame(OrmusDarkColorScheme, scheme)
        assertSame(OrmusDarkExtras, extras)
    }
}
