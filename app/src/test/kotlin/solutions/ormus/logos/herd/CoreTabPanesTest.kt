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

import solutions.ormus.logos.herd.CoreSpaces.BoxRect
import solutions.ormus.logos.herd.CoreSpaces.CoreSpace
import solutions.ormus.logos.herd.CoreSpaces.CoreTab
import solutions.ormus.logos.herd.CoreSpaces.PaneBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CoreTabPanes: the panes of the tab a watched terminal sits in, with the pane that is the watched one, from synthetic
 * layouts only. The console draws a small picture of them (a later PR); this is the model it reads.
 */
class CoreTabPanesTest {
    private fun pane(terminal: String?, weight: Float = 1f) = PaneBox.Pane(terminal, weight)

    private fun row(vararg kids: PaneBox) = PaneBox.Split(false, kids.toList(), 1f)

    private fun col(vararg kids: PaneBox) = PaneBox.Split(true, kids.toList(), 1f)

    private fun tab(id: String, name: String, root: PaneBox?) = CoreTab(id, name, root)

    private fun space(id: String, name: String, vararg tabs: CoreTab) = CoreSpace(id, name, "", tabs.toList())

    private val spaces = listOf(
        space("s1", "alpha · ONE", tab("a", "main", row(pane("t7"))), tab("b", "work", row(pane("t8"), pane("t9", weight = 3f)))),
        space("s2", "beta · TWO", tab("c", "", col(pane("t10"), row(pane("t11"), pane(null))))),
    )

    @Test
    fun `the panes of the tab the watched terminal sits in, in drawing order, with the watched one marked`() {
        val tabPanes = assertNotNull(CoreTabPanes.of(spaces, CoreWatchTarget("t9", "alpha · ONE", "work")))
        assertEquals("alpha · ONE", tabPanes.space)
        assertEquals("work", tabPanes.tab)
        assertEquals(listOf("t8", "t9"), tabPanes.panes.map { it.terminal })
        assertEquals(listOf(false, true), tabPanes.panes.map { it.watched })
        assertEquals("t9", tabPanes.watched?.terminal)
    }

    @Test
    fun `each pane has its place in the tab as shares of its width and height`() {
        val tabPanes = assertNotNull(CoreTabPanes.of(spaces, CoreWatchTarget("t8", "alpha · ONE", "work")))
        assertEquals(BoxRect(0f, 0f, 0.25f, 1f), tabPanes.panes[0].rect)
        assertEquals(BoxRect(0.25f, 0f, 0.75f, 1f), tabPanes.panes[1].rect)
    }

    @Test
    fun `a tab of one pane has one pane, and it is the watched one`() {
        val tabPanes = assertNotNull(CoreTabPanes.of(spaces, CoreWatchTarget("t7", "alpha · ONE", "main")))
        assertEquals(1, tabPanes.panes.size)
        assertEquals(BoxRect(0f, 0f, 1f, 1f), tabPanes.panes.single().rect)
        assertTrue(tabPanes.panes.single().watched)
    }

    @Test
    fun `a terminal of the second space finds its own tab, in a nested layout`() {
        val tabPanes = assertNotNull(CoreTabPanes.of(spaces, CoreWatchTarget("t11", "beta · TWO", "")))
        assertEquals("beta · TWO", tabPanes.space)
        assertEquals("", tabPanes.tab)
        assertEquals(listOf("t10", "t11", null), tabPanes.panes.map { it.terminal })
        assertEquals(listOf(false, true, false), tabPanes.panes.map { it.watched })
        assertEquals(BoxRect(0f, 0f, 1f, 0.5f), tabPanes.panes[0].rect)
        assertEquals(BoxRect(0f, 0.5f, 0.5f, 0.5f), tabPanes.panes[1].rect)
    }

    @Test
    fun `a terminal in no tab, whose tab is in no space either, or no spaces at all, gives nothing`() {
        assertNull(CoreTabPanes.of(spaces, CoreWatchTarget("t99", "alpha · ONE", "closed")))
        assertNull(CoreTabPanes.of(spaces, CoreWatchTarget("t99", "gamma · THREE", "work")))
        assertNull(CoreTabPanes.of(emptyList(), CoreWatchTarget("t7", "alpha · ONE", "main")))
    }

    @Test
    fun `a tab with no layout gives nothing, drawn or named`() {
        assertNull(CoreTabPanes.of(listOf(space("s", "x", tab("a", "main", null))), CoreWatchTarget("t7", "x", "main")))
    }

    @Test
    fun `a pane that is not a terminal (a mod) has no address and cannot be opened`() {
        val tabPanes = assertNotNull(CoreTabPanes.of(spaces, CoreWatchTarget("t11", "beta · TWO", "")))
        val mod = tabPanes.panes.last()
        assertNull(mod.terminal)
        assertFalse(mod.watched)
        assertNull(tabPanes.target(mod))
    }

    @Test
    fun `another pane opens as a watch of its terminal, named by the space and the tab`() {
        val tabPanes = assertNotNull(CoreTabPanes.of(spaces, CoreWatchTarget("t8", "alpha · ONE", "work")))
        assertEquals(CoreWatchTarget("t9", "alpha · ONE", "work"), tabPanes.target(tabPanes.panes[1]))
    }

    @Test
    fun `an address that is not a plain word is never a target`() {
        val odd = listOf(space("s", "x", tab("a", "main", row(pane("t1"), pane("t2; rm -rf /")))))
        val tabPanes = assertNotNull(CoreTabPanes.of(odd, CoreWatchTarget("t1", "x", "main")))
        assertNull(tabPanes.target(tabPanes.panes[1]))
        assertNotNull(tabPanes.target(tabPanes.panes[0]))
    }

    @Test
    fun `the panes carry no text of any terminal`() {
        val tabPanes = assertNotNull(CoreTabPanes.of(spaces, CoreWatchTarget("t8", "alpha · ONE", "work")))
        assertEquals("TabPanes(space=alpha · ONE, tab=work, panes=[Pane(terminal=t8, rect=BoxRect(x=0.0, y=0.0, w=0.25, h=1.0), watched=true), Pane(terminal=t9, rect=BoxRect(x=0.25, y=0.0, w=0.75, h=1.0), watched=false)])", tabPanes.toString())
    }

    // Gate lane M1 (measured on a real host: 89 of 264 terminals): a helper that `hc ls` puts in a tab, but that the tab's layout does
    // not draw now (swapped into a pane on demand), is not "in no tab". The watch names the tab (space and tab), so the console still
    // gets the panes of that tab, with no pane marked as the watched one.
    @Test
    fun `a helper its tab does not draw gets the tab the watch names, with no pane watched`() {
        val tabPanes = assertNotNull(CoreTabPanes.of(spaces, CoreWatchTarget("t50", "alpha · ONE", "work")))
        assertEquals("alpha · ONE", tabPanes.space)
        assertEquals("work", tabPanes.tab)
        assertEquals(listOf("t8", "t9"), tabPanes.panes.map { it.terminal })
        assertEquals(listOf(false, false), tabPanes.panes.map { it.watched })
        assertNull(tabPanes.watched)
        assertEquals(CoreWatchTarget("t9", "alpha · ONE", "work"), tabPanes.target(tabPanes.panes[1]))
    }

    @Test
    fun `a terminal a layout draws is found by its address first, whatever tab the watch names`() {
        val tabPanes = assertNotNull(CoreTabPanes.of(spaces, CoreWatchTarget("t9", "alpha · ONE", "main")))
        assertEquals("work", tabPanes.tab)
        assertEquals("t9", tabPanes.watched?.terminal)
    }

    @Test
    fun `a stack's inactive child is not drawn, so its watch gets the tab with no pane watched`() {
        val stack = """{"id":"k","type":"stack","active":"pt2","children":[{"id":"pt1","type":"block","content":{"terminal":"t1"}},{"id":"pt2","type":"block","content":{"terminal":"t2"}}]}"""
        val doc = """{"id":1,"result":{"value":{"spaces":[{"id":"s","name":"a · B","folder":"/w","tabs":[{"id":"t","name":"x","root":{"id":"r","type":"row","children":[$stack,{"id":"p3","type":"block","content":{"terminal":"t3"}}]}}]}]}}}"""
        val parsed = assertNotNull(CoreSpaces.parse(doc)).spaces
        val inactive = assertNotNull(CoreTabPanes.of(parsed, CoreWatchTarget("t1", "a · B", "x")))
        assertEquals(listOf("t2", "t3"), inactive.panes.map { it.terminal })
        assertNull(inactive.watched)
        val active = assertNotNull(CoreTabPanes.of(parsed, CoreWatchTarget("t2", "a · B", "x")))
        assertEquals("t2", active.watched?.terminal)
    }

    @Test
    fun `a tab name the document leaves blank cannot be named, so an undrawn terminal of it gets nothing (the limit of a name match)`() {
        val blank = listOf(space("s", "x", tab("a", "", row(pane("t1"), pane("t2")))))
        // Drawn: found by its address, whatever the names.
        assertNotNull(CoreTabPanes.of(blank, CoreWatchTarget("t1", "x", "work")))
        // Not drawn, and `hc ls` names a tab the document has no name for: no match.
        assertNull(CoreTabPanes.of(blank, CoreWatchTarget("t50", "x", "work")))
        // A watch with a blank tab name (a watch that lost its tab name) names nothing, so it does not take the space's unnamed tab:
        // the Herdr app never writes a blank tab part in `hc ls`, so a guess here would be a wrong tab.
        assertNull(CoreTabPanes.of(blank, CoreWatchTarget("t50", "x", "")))
    }

    @Test
    fun `a terminal of another machine is not matched by name to a tab of this host`() {
        val local = listOf(space("s", "alpha · ONE", tab("a", "work", row(pane("t1"), pane("t7")))))
        // `box/t50` lives on another machine: this host's document holds only this host's spaces, so a tab of the same names is a
        // local one, and its panes would be terminals that have nothing to do with the watch.
        assertNull(CoreTabPanes.of(local, CoreWatchTarget("box/t50", "alpha · ONE", "work")))
        // The same names, for a terminal of this host, still name the tab.
        assertEquals(listOf("t1", "t7"), CoreTabPanes.of(local, CoreWatchTarget("t50", "alpha · ONE", "work"))?.panes?.map { it.terminal })
        // An address a layout of this host does draw is found by it, whatever its form.
        val drawn = listOf(space("s", "alpha · ONE", tab("a", "work", row(pane("box/t7"), pane("t1")))))
        assertEquals("box/t7", CoreTabPanes.of(drawn, CoreWatchTarget("box/t7", "alpha · ONE", "work"))?.watched?.terminal)
    }

    @Test
    fun `two tabs of one space with the same name cannot be told apart, so an undrawn terminal of them gets nothing`() {
        val twins = listOf(space("s", "x", tab("a", "work", row(pane("t1"))), tab("b", "work", row(pane("t2")))))
        assertNull(CoreTabPanes.of(twins, CoreWatchTarget("t50", "x", "work")))
        // A drawn one is found by its address.
        assertEquals("t2", CoreTabPanes.of(twins, CoreWatchTarget("t2", "x", "work"))?.watched?.terminal)
    }

    @Test
    fun `the name match needs the space as well as the tab`() {
        val two = listOf(space("s1", "alpha", tab("a", "work", row(pane("t1")))), space("s2", "beta", tab("b", "work", row(pane("t2")))))
        assertEquals(listOf("t2"), CoreTabPanes.of(two, CoreWatchTarget("t50", "beta", "work"))?.panes?.map { it.terminal })
    }
}
