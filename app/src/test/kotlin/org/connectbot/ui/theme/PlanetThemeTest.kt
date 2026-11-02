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

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.util.PreferenceConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app theme follows the planet saved in the settings panel, and a new pick repaints it at once. */
@RunWith(AndroidJUnit4::class)
class PlanetThemeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun render(): () -> Pair<ColorScheme, OrmusExtras> {
        var scheme: ColorScheme? = null
        var extras: OrmusExtras? = null
        rule.setContent {
            ConnectBotTheme {
                scheme = MaterialTheme.colorScheme
                extras = Ormus.extras
            }
        }
        rule.waitForIdle()
        return { scheme!! to extras!! }
    }

    @Test
    fun noPick_isSun() {
        val (scheme, extras) = render()()
        assertSame(Planet.SUN.colorScheme, scheme)
        assertSame(Planet.SUN.extras, extras)
    }

    @Test
    fun aSavedPlanet_paintsTheApp_inItsPalette() {
        prefs.edit().putString(PreferenceConstants.PLANET, "mars").commit()
        val (scheme, extras) = render()()
        assertEquals(OrmusTokens.Planets.Mars.gold, scheme.primary)
        assertEquals(OrmusTokens.Planets.Mars.deep, scheme.background)
        assertEquals(OrmusTokens.Planets.Mars.gold, extras.status.blocked)
    }

    @Test
    fun aNewPick_repaintsAtOnce() {
        val rendered = render()
        prefs.edit().putString(PreferenceConstants.PLANET, "venus").commit()
        rule.waitForIdle()
        assertSame(Planet.VENUS.colorScheme, rendered().first)
    }

    @Test
    fun anUnknownKey_fallsBackToSun() {
        assertSame(Planet.SUN, Planet.fromKey("pluto"))
        assertSame(Planet.SUN, Planet.fromKey(null))
    }
}
