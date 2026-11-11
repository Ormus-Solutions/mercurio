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
    fun defaultBarIsTheHerdrAppsEighteenKeysInItsOrderFiveToAPage() {
        assertEquals(
            listOf(
                listOf("esc", "ctrl", "alt", "tab", "shift-tab"),
                listOf("arrows", "ctrl-c", "ctrl-d", "ctrl-z", "ctrl-l"),
                listOf("pipe", "tilde", "slash", "dash", "home"),
                listOf("end", "pgup", "pgdn"),
            ),
            KeyBarLayout.DEFAULT.pages,
        )
        assertEquals(18, KeyBarLayout.DEFAULT.pages.sumOf { it.size })
    }

    @Test
    fun catalogHasModifiersActionsAndTheArrowsKey() {
        assertEquals(KeyKind.CTRL, KeyBarCatalog.byId("ctrl")!!.kind)
        assertEquals(KeyKind.ALT, KeyBarCatalog.byId("alt")!!.kind)
        assertEquals(KeyKind.ARROWS, KeyBarCatalog.byId("arrows")!!.kind)
        assertEquals(KeyKind.PASTE, KeyBarCatalog.byId("paste")!!.kind)
        assertEquals(KeyKind.COMPOSE, KeyBarCatalog.byId("compose")!!.kind)
        assertEquals(KeyKind.SEQUENCE, KeyBarCatalog.byId("esc")!!.kind)
    }

    @Test
    fun catalogHasF1ToF12WithXtermSequences() {
        val esc = "\u001b"
        val expected = listOf("OP", "OQ", "OR", "OS", "[15~", "[17~", "[18~", "[19~", "[20~", "[21~", "[23~", "[24~")
        expected.forEachIndexed { i, tail ->
            val key = KeyBarCatalog.byId("f${i + 1}")
            assertNotNull("f${i + 1}", key)
            assertEquals(esc + tail, key!!.sequence)
            assertEquals("F${i + 1}", key.label)
        }
        assertEquals(null, KeyBarCatalog.byId("f13"))
    }

    @Test
    fun noKeyEverSendsHerdrsPrefix() {
        assertTrue(KeyBarCatalog.all.none { it.sequence == "\u0002" })
    }

    @Test
    fun textKeyTypesItsTextAndSurvivesTheLayoutEncoding() {
        val text = "/clear, now; 100% \u00e9"
        val id = KeyBarCatalog.textKeyId(text, enter = false)!!
        val key = KeyBarCatalog.byId(id)!!
        assertEquals(KeyKind.TEXT, key.kind)
        assertEquals(text, key.sequence)
        assertEquals(false, key.enter)
        assertEquals(text, key.description)
        val layout = KeyBarLayout(listOf(listOf("esc", id, "tab")))
        assertEquals(layout, KeyBarLayout.decode(layout.encode()))
    }

    @Test
    fun textKeyWithEnterIsAnotherKeyFromTheSameText() {
        val plain = KeyBarCatalog.textKeyId("/clear", enter = false)!!
        val withEnter = KeyBarCatalog.textKeyId("/clear", enter = true)!!
        assertTrue(plain != withEnter)
        val key = KeyBarCatalog.byId(withEnter)!!
        assertEquals("/clear", key.sequence)
        assertTrue(key.enter)
    }

    @Test
    fun textKeyRefusesBlankAndTooLongText() {
        assertEquals(null, KeyBarCatalog.textKeyId("", enter = false))
        assertEquals(null, KeyBarCatalog.textKeyId("   ", enter = true))
        assertEquals(null, KeyBarCatalog.textKeyId("x".repeat(KeyBarCatalog.MAX_TEXT_KEY + 1), enter = false))
        assertNotNull(KeyBarCatalog.textKeyId("x".repeat(KeyBarCatalog.MAX_TEXT_KEY), enter = false))
    }

    @Test
    fun textKeyRefusesControlCharactersSoNoPrefixByteGetsThrough() {
        assertEquals(null, KeyBarCatalog.textKeyId("a\u0002b", enter = false))
        assertEquals(null, KeyBarCatalog.byId("text:%02"))
        assertEquals(null, KeyBarCatalog.byId("textcr:a%1Bb"))
    }

    @Test
    fun aBrokenTextKeyIdIsDroppedOnDecode() {
        assertEquals(KeyBarLayout(listOf(listOf("esc", "tab"))), KeyBarLayout.decode("esc,text:%ZZ,text:,tab"))
    }

    @Test
    fun layoutAddTakesATextKey() {
        val id = KeyBarCatalog.textKeyId("/compact", enter = true)!!
        assertEquals(listOf("esc", id), KeyBarLayout(listOf(listOf("esc"))).add(0, id).pages[0])
    }

    @Test
    fun arrowsKeySendsTheArrowUnderTheTap() {
        val esc = "\u001b"
        assertEquals(esc + "[A", ArrowPad.sequenceAt(50f, 5f, 100f, 100f))
        assertEquals(esc + "[B", ArrowPad.sequenceAt(50f, 95f, 100f, 100f))
        assertEquals(esc + "[D", ArrowPad.sequenceAt(5f, 50f, 100f, 100f))
        assertEquals(esc + "[C", ArrowPad.sequenceAt(95f, 50f, 100f, 100f))
        assertEquals(esc + "[A", ArrowPad.sequenceAt(60f, 10f, 100f, 100f))
    }

    @Test
    fun aSwipeOnTheArrowsKeyPressesOneArrowPerStepItCrosses() {
        val esc = "\u001b"
        val swipe = ArrowSwipe(stepPx = 10f)
        assertEquals(emptyList<String>(), swipe.to(9f, 2f))
        assertEquals(listOf(esc + "[C"), swipe.to(12f, 3f))
        assertEquals(listOf(esc + "[C", esc + "[C"), swipe.to(31f, 3f))
        assertEquals(emptyList<String>(), swipe.to(35f, 3f))
    }

    @Test
    fun aSwipeOnTheArrowsKeyKeepsTheAxisItFirstWentAlong_andGoingBackPressesTheOppositeArrow() {
        val esc = "\u001b"
        val swipe = ArrowSwipe(stepPx = 10f)
        assertEquals(listOf(esc + "[A", esc + "[A"), swipe.to(4f, -25f))
        // Sideways drift on a vertical swipe is no left or right arrow.
        assertEquals(emptyList<String>(), swipe.to(40f, -25f))
        assertEquals(listOf(esc + "[B", esc + "[B", esc + "[B"), swipe.to(40f, 12f))
    }

    @Test
    fun aSwipeOnTheArrowsKeyGoesLeftAndDownToo() {
        val esc = "\u001b"
        assertEquals(listOf(esc + "[D"), ArrowSwipe(stepPx = 10f).to(-14f, 3f))
        assertEquals(listOf(esc + "[B"), ArrowSwipe(stepPx = 10f).to(3f, 14f))
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
    fun aPageHoldsFiveKeysLikeTheHerdrApp() {
        assertEquals(5, KeyBarLayout.MAX_KEYS_PER_PAGE)
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
