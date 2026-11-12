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
    fun defaultBarIsTheHerdrAppsEighteenKeysInItsOrderFiveToAPage_thenSpace() {
        assertEquals(
            listOf(
                listOf("esc", "ctrl", "alt", "tab", "shift-tab"),
                listOf("arrows", "ctrl-c", "ctrl-d", "ctrl-z", "ctrl-l"),
                listOf("pipe", "tilde", "slash", "dash", "home"),
                listOf("end", "pgup", "pgdn", "space"),
            ),
            KeyBarLayout.DEFAULT.pages,
        )
        assertEquals(19, KeyBarLayout.DEFAULT.pages.sumOf { it.size })
        // Every page fills the row, Space two slots wide.
        KeyBarLayout.DEFAULT.pages.forEach { page -> assertEquals(KeyBarLayout.MAX_KEYS_PER_PAGE, page.sumOf { KeyBarCatalog.byId(it)!!.span }) }
    }

    @Test
    fun theSpaceKeyTypesASpace_repeatsWhenHeld_andIsTwoSlotsWide() {
        val space = KeyBarCatalog.byId("space")!!
        assertEquals(" ", space.sequence)
        assertEquals(KeyKind.SEQUENCE, space.kind)
        assertTrue(space.repeats)
        assertEquals(2, space.span)
        assertEquals(1, KeyBarCatalog.byId("esc")!!.span)
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
    fun decodeSpillsAPageThatIsTooWideIntoTheNextPage() {
        // Space is two slots: Space plus four keys is six slots, so the fourth key moves on.
        assertEquals(
            listOf(listOf("space", "esc", "tab", "slash"), listOf("dash"), listOf("pipe")),
            KeyBarLayout.decode("space,esc,tab,slash,dash;pipe").pages,
        )
        val page = KeyBarLayout.decode("space,esc,tab,slash,dash").pages
        assertEquals(listOf(listOf("space", "esc", "tab", "slash"), listOf("dash")), page)
        page.indices.forEach { assertTrue(KeyBarLayout(page).slots(it) <= KeyBarLayout.MAX_KEYS_PER_PAGE) }
    }

    @Test
    fun aLegacySixKeyPageKeepsAllSixKeysAcrossTwoPages() {
        val six = listOf("esc", "tab", "slash", "dash", "pipe", "tilde")
        val layout = KeyBarLayout.decode(six.joinToString(","))
        assertEquals(listOf(six.take(5), six.drop(5)), layout.pages)
    }

    @Test
    fun decodeNeverDropsAKnownKey_evenOnAFullEightPageLayout() {
        val full = List(KeyBarLayout.MAX_PAGES) { listOf("esc", "tab", "slash", "dash", "pipe") }
        val saved = full.mapIndexed { i, page -> if (i == 0) page + "tilde" else page } + listOf(listOf("home"))
        val layout = KeyBarLayout.decode(saved.joinToString(";") { it.joinToString(",") })
        assertEquals(KeyBarLayout.MAX_PAGES, layout.pages.size)
        // Every key, in its saved order; the leftovers ride on the last page.
        assertEquals(saved.flatten(), layout.pages.flatten())
        assertEquals("home", layout.pages.last().last())
    }

    @Test
    fun aLegacyLayoutSurvivesDecodeAndEncodeWithoutLosingAKey() {
        val legacy = "esc,tab,slash,dash,pipe,tilde;ctrl-c,ctrl-d,ctrl-z,ctrl-l,ctrl-a,ctrl-e,ctrl-u"
        val once = KeyBarLayout.decode(legacy)
        assertEquals(legacy.split(";", ",").sorted(), once.pages.flatten().sorted())
        // Saving what he sees and opening it again changes nothing.
        assertEquals(once, KeyBarLayout.decode(once.encode()))
    }

    @Test
    fun shiftTabHasAShortLabelAndTheFullNameForAScreenReader() {
        val key = KeyBarCatalog.byId("shift-tab")!!
        assertEquals("S-Tab", key.label)
        assertEquals("Shift+Tab", key.description)
    }

    @Test
    fun noOneSlotKeyLabelIsLongerThanTheBarShowsOnAPhone() {
        // Measured on a phone (5 slots): "Shift+T" showed, "Shift+Tab" was cut. Seven characters fit.
        val tooLong = KeyBarCatalog.all.filter { it.span == 1 && it.label.length > MAX_LABEL_CHARS_PER_SLOT }
        assertEquals(emptyList<String>(), tooLong.map { it.label })
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
    fun storeIsOnAndDefaultUntilEdited() {
        val store = KeyBarStore(prefs)
        assertTrue(store.enabled)
        assertEquals(KeyBarLayout.DEFAULT, store.layout)
    }

    @Test
    fun aPageIsFullAtFiveSlots_spaceTakingTwo() {
        val four = KeyBarLayout(listOf(listOf("esc", "tab", "slash", "dash")))
        assertEquals(four, four.add(0, "space"))
        assertEquals(listOf("esc", "tab", "slash", "dash", "pipe"), four.add(0, "pipe").pages[0])
        val three = KeyBarLayout(listOf(listOf("esc", "tab", "slash")))
        val withSpace = three.add(0, "space")
        assertEquals(listOf("esc", "tab", "slash", "space"), withSpace.pages[0])
        assertEquals(withSpace, withSpace.add(0, "pipe"))
        assertEquals(5, withSpace.slots(0))
        assertTrue(three.fits(0, "space"))
        assertFalse(four.fits(0, "space"))
    }

    @Test
    fun storeKeepsHisOrderAcrossARestart() {
        val edited = KeyBarLayout.DEFAULT.move(0, 0, 4).remove(1, 0).add(1, "esc")
        KeyBarStore(prefs).apply {
            enabled = false
            layout = edited
        }
        // A new store over the same preferences is what the app builds after a restart.
        val reopened = KeyBarStore(PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>()))
        assertFalse(reopened.enabled)
        assertEquals(edited, reopened.layout)
    }

    private companion object {
        const val MAX_LABEL_CHARS_PER_SLOT = 7
    }
}
