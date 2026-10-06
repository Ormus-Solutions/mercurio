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

package org.connectbot.ui.screens.herd

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.usage.UsageActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.HerdrAction

@RunWith(AndroidJUnit4::class)
class HerdrPanelStoreTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("herdr-panel-test", Context.MODE_PRIVATE)
        prefs.edit { clear() }
    }

    /** A second store over the same preferences: what the next panel (or app start) reads. */
    private fun reread() = HerdrPanelStore(prefs).layout.value

    @Test
    fun startsAtTheDefaults_andStoresNothing() {
        assertTrue(HerdrPanelStore(prefs).layout.value.isDefault)
        assertFalse(prefs.contains(HerdrPanelStore.PREF_KEY))
    }

    @Test
    fun hideEditAndAdd_survive() {
        val store = HerdrPanelStore(prefs)

        store.hide(UsageActions.HERDR_SHEET_ZOOM)
        store.edit(UsageActions.HERDR_SHEET_GOTO, HerdrPanelKey(" Jump ", " ctrl+g "))
        store.add(HerdrPanelKey("Redraw", "shift+r"))

        val layout = reread()
        assertEquals(setOf(UsageActions.HERDR_SHEET_ZOOM), layout.hidden)
        // Trimmed as it is saved.
        assertEquals(mapOf(UsageActions.HERDR_SHEET_GOTO to HerdrPanelKey("Jump", "ctrl+g")), layout.edited)
        assertEquals(listOf(HerdrPanelKey("Redraw", "shift+r")), layout.custom)
        assertEquals(store.layout.value, layout)
    }

    @Test
    fun aKeyNeedsALabelAndAKeyATerminalCanSend() {
        val store = HerdrPanelStore(prefs)

        store.add(HerdrPanelKey(" ", "z"))
        store.add(HerdrPanelKey("Find", "super+f"))
        store.add(HerdrPanelKey("Empty", ""))
        store.edit(UsageActions.HERDR_SHEET_GOTO, HerdrPanelKey("Goto", "cmd+g"))

        assertTrue(store.layout.value.isDefault)
        assertFalse(prefs.contains(HerdrPanelStore.PREF_KEY))
    }

    @Test
    fun replaceAndRemove_customKeysByPosition() {
        val store = HerdrPanelStore(prefs)
        store.add(HerdrPanelKey("One", "1"))
        store.add(HerdrPanelKey("Two", "2"))

        store.replace(0, HerdrPanelKey("Uno", "alt+1"))
        store.replace(5, HerdrPanelKey("Gone", "x"))
        store.remove(1)
        store.remove(9)

        assertEquals(listOf(HerdrPanelKey("Uno", "alt+1")), reread().custom)
    }

    @Test
    fun restoreDefaults_clearsEverything_andTheStoredValue() {
        val store = HerdrPanelStore(prefs)
        store.hide(UsageActions.HERDR_SHEET_HELP)
        store.add(HerdrPanelKey("Redraw", "shift+r"))

        store.restoreDefaults()

        assertTrue(store.layout.value.isDefault)
        assertFalse(prefs.contains(HerdrPanelStore.PREF_KEY))
    }

    @Test
    fun anUnreadableValue_showsTheDefaults() {
        prefs.edit { putString(HerdrPanelStore.PREF_KEY, "{not json") }
        assertTrue(reread().isDefault)

        prefs.edit { putString(HerdrPanelStore.PREF_KEY, """{"hidden":"zoom"}""") }
        assertTrue(reread().isDefault)
    }

    @Test
    fun aKeyRunsAsAClientKey_withTheBytesItsSpecSends() {
        // HerdrCommands puts the host's prefix ahead of these bytes.
        assertEquals(HerdrAction.ClientKey("z"), HerdrPanelKey("Z", "z").action())
        assertEquals(HerdrAction.ClientKey("R"), HerdrPanelKey("Redraw", "shift+r").action())
        assertEquals(HerdrAction.ClientKey(24.toChar().toString()), HerdrPanelKey("Cut", "ctrl+x").action())
        assertEquals(HerdrAction.ClientKey(27.toChar() + "x"), HerdrPanelKey("Alt", "alt+x").action())
        assertNull(HerdrPanelKey("Super", "super+x").action())
    }
}
