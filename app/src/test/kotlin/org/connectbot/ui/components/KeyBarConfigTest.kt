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
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyBarConfigTest {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun defaultBarHasEighteenKeysInFourPages() {
        assertEquals(4, KeyBarLayout.DEFAULT.pages.size)
        assertEquals(18, KeyBarLayout.DEFAULT.pages.sumOf { it.size })
        assertEquals(listOf("esc", "tab", "shift-tab", "ctrl-c", "ctrl-d"), KeyBarLayout.DEFAULT.pages[0])
    }

    @Test
    fun everyDefaultKeyIsInTheCatalog() {
        KeyBarLayout.DEFAULT.pages.flatten().forEach { assertNotNull(it, KeyBarCatalog.byId(it)) }
    }

    @Test
    fun catalogSequences() {
        assertEquals("\u001b", KeyBarCatalog.byId("esc")!!.sequence)
        assertEquals("\u001b[Z", KeyBarCatalog.byId("shift-tab")!!.sequence)
        assertEquals("\u0003", KeyBarCatalog.byId("ctrl-c")!!.sequence)
        assertEquals("\u001b[A", KeyBarCatalog.byId("up")!!.sequence)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val layout = KeyBarLayout(listOf(listOf("up", "esc"), listOf("ctrl-z"), emptyList()))
        assertEquals(layout, KeyBarLayout.decode(layout.encode()))
    }

    @Test
    fun decodeNullIsDefaultAndBlankIsOneEmptyPage() {
        assertEquals(KeyBarLayout.DEFAULT, KeyBarLayout.decode(null))
        assertEquals(KeyBarLayout(listOf(emptyList())), KeyBarLayout.decode(""))
    }

    @Test
    fun decodeDropsUnknownKeys() {
        assertEquals(KeyBarLayout(listOf(listOf("esc", "tab"))), KeyBarLayout.decode("esc,nope,tab"))
    }

    @Test
    fun addAppendsAndRespectsThePageLimit() {
        val layout = KeyBarLayout(listOf(listOf("esc")))
        assertEquals(listOf("esc", "tab"), layout.add(0, "tab").pages[0])
        assertEquals(layout, layout.add(0, "nope"))
        var full = layout
        repeat(KeyBarLayout.MAX_KEYS_PER_PAGE) { full = full.add(0, "tab") }
        assertEquals(KeyBarLayout.MAX_KEYS_PER_PAGE, full.pages[0].size)
    }

    @Test
    fun aPageFitsAPhoneRowNextToThePageKey() {
        assertEquals(6, KeyBarLayout.MAX_KEYS_PER_PAGE)
    }

    @Test
    fun herdrPrefixKeyIsNotInTheCatalog() {
        assertEquals(null, KeyBarCatalog.byId("ctrl-b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aLayoutNeedsAPage() {
        KeyBarLayout(emptyList())
    }

    @Test
    fun removeAndMove() {
        val layout = KeyBarLayout(listOf(listOf("esc", "tab", "up")))
        assertEquals(listOf("esc", "up"), layout.remove(0, 1).pages[0])
        assertEquals(listOf("tab", "up", "esc"), layout.move(0, 0, 2).pages[0])
        assertEquals(layout, layout.move(0, 0, 9))
        assertEquals(layout, layout.remove(0, 9))
    }

    @Test
    fun pagesAddAndRemoveKeepAtLeastOne() {
        val one = KeyBarLayout(listOf(listOf("esc")))
        assertEquals(2, one.addPage().pages.size)
        assertEquals(one, one.removePage(0))
        assertEquals(listOf(listOf("esc")), one.addPage().removePage(1).pages)
    }

    @Test
    fun storeIsOffAndDefaultUntilEdited() {
        val store = KeyBarStore(prefs)
        assertFalse(store.enabled)
        assertEquals(KeyBarLayout.DEFAULT, store.layout)
    }

    @Test
    fun storeKeepsHisOrderAcrossARestart() {
        val edited = KeyBarLayout.DEFAULT.move(0, 0, 4).remove(1, 0).add(1, "esc")
        KeyBarStore(prefs).apply {
            enabled = true
            layout = edited
        }
        // A new store over the same preferences is what the app builds after a restart.
        val reopened = KeyBarStore(PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>()))
        assertTrue(reopened.enabled)
        assertEquals(edited, reopened.layout)
    }
}
