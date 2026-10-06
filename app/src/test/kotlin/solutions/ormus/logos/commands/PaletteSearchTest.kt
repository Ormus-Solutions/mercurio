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

package solutions.ormus.logos.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The palette's fuzzy match tiers, and recent / most-used ordering inside a tier. */
class PaletteSearchTest {
    private fun entry(key: String, title: String, usageId: String? = null, keywords: String = "", subtitle: String? = null) = PaletteEntry(key, title, PaletteGroup.SCREENS, PaletteAction.Open(PaletteScreen.READER), subtitle, usageId, keywords)

    private fun keys(list: List<PaletteEntry>) = list.map { it.key }

    @Test
    fun tiers_exactThenPrefixThenWordThenSubstringThenFuzzyThenKeywords() {
        val entries = listOf(
            entry("keyword", "Zzz", keywords = "tab stop"),
            entry("fuzzy", "Toggle a bar"),
            entry("substring", "Detab"),
            entry("word", "Next tab"),
            entry("prefix", "Tabs list"),
            entry("exact", "Tab"),
            entry("none", "Escape"),
        )

        assertEquals(listOf("exact", "prefix", "word", "substring", "fuzzy", "keyword"), keys(PaletteSearch.rank(entries, "tab")))
    }

    @Test
    fun slashAndCase_doNotMatter() {
        val clear = entry("clear", "/clear")
        assertEquals(800, PaletteSearch.matchScore("/CL", clear))
        assertEquals(800, PaletteSearch.matchScore("cl", clear))
        assertEquals(1000, PaletteSearch.matchScore("clear", clear))
    }

    @Test
    fun fuzzy_prefersTighterMatches_andMissesReturnNull() {
        val tight = PaletteSearch.matchScore("spr", entry("a", "xxsplit-rightxx"))!!
        val loose = PaletteSearch.matchScore("spr", entry("b", "s-----p------r"))!!
        assertTrue(tight > loose)
        assertNull(PaletteSearch.matchScore("qqq", entry("c", "Split right")))
    }

    @Test
    fun everyWordOfTheQuery_findsWordsInAnyOrder() {
        assertEquals(listOf("swap"), keys(PaletteSearch.rank(listOf(entry("swap", "Swap pane", subtitle = "Herdr left"), entry("other", "Swap")), "left swap")))
    }

    @Test
    fun insideATier_recentFirstThenMostUsed() {
        val entries = listOf(
            entry("reader", "Read output", usageId = "reader:open"),
            entry("resize", "Resize", usageId = "menu:resize"),
            entry("reconnect", "Reconnect", usageId = "menu:reconnect"),
        )
        val counts = mapOf("menu:reconnect" to 40L, "reader:open" to 3L)

        // All three are prefix matches for "re": the recent one leads, then by count.
        assertEquals(listOf("resize", "reconnect", "reader"), keys(PaletteSearch.rank(entries, "re", recents = listOf("resize"), counts = counts)))
        assertEquals(listOf("reconnect", "reader", "resize"), keys(PaletteSearch.rank(entries, "re", counts = counts)))
    }

    @Test
    fun boosts_neverLiftAWorseMatchOverABetterOne() {
        val heavy = entry("heavy", "Toggle tab bar", usageId = "x")
        val exact = entry("exact", "Tab")
        val ranked = PaletteSearch.rank(listOf(heavy, exact), "tab", recents = listOf("heavy"), counts = mapOf("x" to 1_000_000L))
        assertEquals(listOf("exact", "heavy"), keys(ranked))
    }

    @Test
    fun emptyQuery_listsRecentThenMostUsedThenTheRestInOrder() {
        val entries = listOf(entry("a", "A"), entry("b", "B", usageId = "b"), entry("c", "C"), entry("d", "D", usageId = "d"))
        val ranked = PaletteSearch.rank(entries, "  ", recents = listOf("c"), counts = mapOf("b" to 2L, "d" to 50L))
        assertEquals(listOf("c", "d", "b", "a"), keys(ranked))
    }
}
