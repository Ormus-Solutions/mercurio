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

package solutions.ormus.logos.herd

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The machines Herdr was seen on: kept in the preferences, no database. */
@RunWith(AndroidJUnit4::class)
class HerdrHostsTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("herdr-hosts-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun nothingIsLearned_untilAMachineIs() {
        assertEquals(emptySet<String>(), HerdrHosts(prefs).learned())
    }

    @Test
    fun aLearnedMachine_survivesANewInstance_andIsKeptOnce() {
        HerdrHosts(prefs).learn("box.example:22:u")
        HerdrHosts(prefs).learn("box.example:22:u")
        HerdrHosts(prefs).learn("lab.example:2222:v")

        assertEquals(setOf("box.example:22:u", "lab.example:2222:v"), HerdrHosts(prefs).learned())
    }

    @Test
    fun aForgottenMachine_isGone_andForgettingAnUnknownOneChangesNothing() {
        val hosts = HerdrHosts(prefs)
        hosts.learn("box.example:22:u")
        hosts.forget("other.example:22:u")
        hosts.forget("box.example:22:u")

        assertEquals(emptySet<String>(), HerdrHosts(prefs).learned())
    }

    @Test
    fun anEditThatRemovesTheHerdrStart_isFoundAfterAnAppRestart_andForgetsTheMachine() = kotlinx.coroutines.runBlocking {
        val lab = org.connectbot.data.entity.Host(id = 1, nickname = "lab", hostname = "lab.example", username = "u", postLogin = "herdr")
        var saved = listOf(lab)
        val before = CoreLinks({ saved }, { error("no login") }, learned = HerdrHosts(prefs))
        before.machines(live = listOf(lab))
        assertEquals(setOf("lab.example:22:u"), HerdrHosts(prefs).learned())

        // The host is edited while the app is closed; the next start builds new objects over the same preferences.
        saved = listOf(lab.copy(postLogin = null))
        val after = CoreLinks({ saved }, { error("no login") }, learned = HerdrHosts(prefs))

        assertEquals(emptyList<Long>(), after.machines().map { it.primary.id })
        assertEquals(emptySet<String>(), HerdrHosts(prefs).learned())
    }

    @Test
    fun anEditMadeWhileTheConsoleIsOpen_winsWhenItClosesAfterAnAppRestart() = kotlinx.coroutines.runBlocking {
        val lab = org.connectbot.data.entity.Host(id = 1, nickname = "lab", hostname = "lab.example", username = "u", postLogin = "herdr")
        val before = CoreLinks({ listOf(lab) }, { error("no login") }, learned = HerdrHosts(prefs))
        before.machines(live = listOf(lab))

        val edited = lab.copy(postLogin = null)
        val whileOpen = CoreLinks({ listOf(edited) }, { error("no login") }, learned = HerdrHosts(prefs))
        assertEquals(listOf(1L), whileOpen.machines(live = listOf(edited)).map { it.primary.id })
        // The console stays open at the next refresh: the edit is still remembered, not learned over.
        assertEquals(listOf(1L), whileOpen.machines(live = listOf(edited)).map { it.primary.id })
        assertEquals(emptySet<String>(), HerdrHosts(prefs).learned())

        // The app restarts with the console gone.
        val after = CoreLinks({ listOf(edited) }, { error("no login") }, learned = HerdrHosts(prefs))
        assertEquals(emptyList<Long>(), after.machines().map { it.primary.id })
        assertEquals(emptySet<String>(), HerdrHosts(prefs).learned())
    }

    @Test
    fun theSettingsText_namesHerdrMachines_andNoLongerEverySavedHost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (tag in listOf("en", "es")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) })
            val text = localized.getString(R.string.settings_panel_core_on)
            assertTrue("$tag: $text", text.contains("Herdr"))
            // It names the herdr-core agents read as a way in, beside the Herdr session.
            assertTrue("$tag: $text", text.contains("herdr-core agents") || text.contains("agentes de herdr-core"))
            val line = localized.getString(R.string.inbox_no_herdr_machine)
            assertTrue("$tag: $line", line.contains("Herdr"))
            assertFalse("$tag: $text", text.contains("each saved host", ignoreCase = true) || text.contains("cada host guardado", ignoreCase = true))
        }
    }

    @Test
    fun theListIsAStringSetUnderOneKey_nothingElseIsWritten() {
        HerdrHosts(prefs).learn("box.example:22:u")

        assertEquals(setOf(HerdrHosts.PREF_KEY), prefs.all.keys)
        assertEquals(setOf("box.example:22:u"), prefs.getStringSet(HerdrHosts.PREF_KEY, null))
    }
}
