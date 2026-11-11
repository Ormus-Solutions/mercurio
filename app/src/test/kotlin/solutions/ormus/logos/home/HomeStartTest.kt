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

package solutions.ormus.logos.home

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.navigation.NavDestinations
import org.connectbot.util.PreferenceConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.CoreSource

/** The Inbox and herdr-core defaults, and where the app opens. */
@RunWith(AndroidJUnit4::class)
class HomeStartTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
        prefs.edit().clear().commit()
    }

    private fun save(vararg pairs: Pair<String, Boolean>) = prefs.edit().apply { pairs.forEach { (k, v) -> putBoolean(k, v) } }.commit()

    @Test
    fun aFreshInstall_hasBothOn() {
        assertTrue(HomeStart.inboxOn(prefs))
        assertTrue(HomeStart.coreOn(prefs))
    }

    @Test
    fun anInstallThatNeverTouchedThem_hasBothOn_evenWithOtherSettingsSaved() {
        save(PreferenceConstants.ENTER_NEW_LINE to true)
        assertTrue(HomeStart.inboxOn(prefs))
        assertTrue(HomeStart.coreOn(prefs))
    }

    @Test
    fun aSavedOff_staysOff() {
        save(PreferenceConstants.INBOX to false, PreferenceConstants.HERDR_CORE to false)
        assertFalse(HomeStart.inboxOn(prefs))
        assertFalse(HomeStart.coreOn(prefs))
    }

    @Test
    fun aSavedOn_isOn_andEachSettingIsReadAlone() {
        save(PreferenceConstants.INBOX to false, PreferenceConstants.HERDR_CORE to true)
        assertFalse(HomeStart.inboxOn(prefs))
        assertTrue(HomeStart.coreOn(prefs))
    }

    @Test
    fun withNoHostThatAnswered_theHomeOpensOnTheHosts() {
        assertEquals(NavDestinations.HOST_LIST, HomeStart.destination(prefs))
    }

    @Test
    fun withAHostThatAnswered_theHomeOpensOnTheInbox() {
        save(PreferenceConstants.HERDR_CORE_SEEN to true)
        assertEquals(NavDestinations.INBOX, HomeStart.destination(prefs))
    }

    @Test
    fun aSettingOff_opensTheHosts_evenAfterAnAnswer() {
        save(PreferenceConstants.HERDR_CORE_SEEN to true, PreferenceConstants.INBOX to false)
        assertEquals(NavDestinations.HOST_LIST, HomeStart.destination(prefs))
        save(PreferenceConstants.INBOX to true, PreferenceConstants.HERDR_CORE to false)
        assertEquals(NavDestinations.HOST_LIST, HomeStart.destination(prefs))
    }

    @Test
    fun makingAShortcut_opensTheHosts() {
        save(PreferenceConstants.HERDR_CORE_SEEN to true)
        assertEquals(NavDestinations.HOST_LIST, HomeStart.destination(prefs, makingShortcut = true))
    }

    private val answered = CoreSource.Result.Agents(emptyList())
    private val missing = CoreSource.Result.Unavailable(CoreSource.NOT_INSTALLED)

    @Test
    fun theProbe_isRememberedOnAnAnswer_andForgottenWhenNoHostHasHerdrCore() {
        HomeStart.noteProbes(prefs, listOf(answered))
        assertEquals(NavDestinations.INBOX, HomeStart.destination(prefs))

        HomeStart.noteProbes(prefs, listOf(missing))
        assertEquals(NavDestinations.HOST_LIST, HomeStart.destination(prefs))
    }

    @Test
    fun withTwoHosts_oneAnswerIsEnough_whateverTheOrder() {
        HomeStart.noteProbes(prefs, listOf(answered, missing))
        assertEquals(NavDestinations.INBOX, HomeStart.destination(prefs))

        HomeStart.noteProbes(prefs, listOf(missing, answered))
        assertEquals(NavDestinations.INBOX, HomeStart.destination(prefs))
    }

    @Test
    fun anUnreachableHostOrAnOffSetting_changesNothing() {
        HomeStart.noteProbes(prefs, listOf(answered))

        HomeStart.noteProbes(prefs, listOf(CoreSource.Result.Unavailable("could not reach the host")))
        HomeStart.noteProbes(prefs, listOf(CoreSource.Result.Unavailable("herdr-core is not running (hc ls exit 1)"), CoreSource.Result.Off))
        HomeStart.noteProbes(prefs, emptyList())

        assertEquals(NavDestinations.INBOX, HomeStart.destination(prefs))
    }
}
