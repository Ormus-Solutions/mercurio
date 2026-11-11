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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreWatchTest {
    private fun agent(address: String = "t7", space: String = "alpha", tab: String = "ONE") = Agent(address = address, engine = AgentEngine.CORE, machine = "box", space = space, tab = tab, state = AgentState.Idle)

    @Test fun `the target names the console space dot tab`() {
        assertEquals("alpha · ONE", CoreWatch.target(agent())?.label)
    }

    @Test fun `a blank tab leaves the space alone`() {
        assertEquals("alpha", CoreWatch.target(agent(tab = ""))?.label)
    }

    @Test fun `an address that is not a plain word has no target`() {
        assertNull(CoreWatch.target(agent(address = "t7; rm -rf x")))
        assertNull(CoreWatch.target(agent(address = "")))
    }

    @Test fun `the watch types hc attach observe for the agent's address`() {
        assertEquals("hc attach --observe 't7'\n", CoreWatch.observeCommand("t7"))
        assertEquals("hc attach --observe 'box/t12'\n", CoreWatch.observeCommand("box/t12"))
    }

    @Test fun `an Inbox row of a core agent watches the terminal as hc ls named it`() {
        val row = Inbox.row(1, "box", agent())
        assertEquals("t7", CoreWatch.target(row.agent)?.address)
        assertEquals("hc attach --observe 't7'\n", CoreWatch.target(row.agent)?.let { CoreWatch.observeCommand(it.address) })
        assertEquals("box/t7", CoreWatch.target(Inbox.row(1, "box", agent(address = "box/t7")).agent)?.address)
    }

    @Test fun `an address with more than one slash is not a terminal`() {
        assertNull(CoreWatch.target(agent(address = "1/core/t7")))
        assertNull(CoreWatch.observeCommand("1/core/t7"))
        assertNull(CoreWatch.takeOverCommand("a/b/c"))
        assertNull(CoreWatch.getCommand("a/b/c"))
        assertFalse(CoreWatch.isSafe("a/b/c"))
        assertTrue(CoreWatch.isSafe("box/t7"))
    }

    @Test fun `the takeover attaches without observe`() {
        assertEquals("hc attach 't7'\n", CoreWatch.takeOverCommand("t7"))
    }

    @Test fun `commands refuse an address that could break out of its quotes`() {
        assertNull(CoreWatch.observeCommand("t7'; id"))
        assertNull(CoreWatch.takeOverCommand("t7 t8"))
        assertNull(CoreWatch.getCommand("\$(id)"))
    }

    @Test fun `the size read goes through the login shell`() {
        assertEquals("bash -lc 'hc get '\\''t7'\\'' --json'", CoreWatch.getCommand("t7"))
    }

    @Test fun `detach is ctrl backslash`() {
        assertEquals(0x1c, CoreWatch.DETACH)
    }

    @Test fun `cols come from hc get json`() {
        assertEquals(135, CoreWatch.colsOf("""{"address": "t7", "cols": 135, "rows": 34}"""))
    }

    @Test fun `cols come after a login banner`() {
        assertEquals(87, CoreWatch.colsOf("welcome\n{\"cols\": 87}"))
    }

    @Test fun `no cols when the json has none or is not json`() {
        assertNull(CoreWatch.colsOf("""{"address": "t7"}"""))
        assertNull(CoreWatch.colsOf("not json"))
        assertNull(CoreWatch.colsOf("""{"cols": 0}"""))
    }

    @Test fun `the font shrinks so the core columns fit`() {
        // 67 phone columns at 14 sp; 135 core columns: 14 * 67 / 135 = 6.9, rounded down to a half step
        assertEquals(6.5f, CoreWatch.fitFont(14f, 67, 135), 0f)
    }

    @Test fun `the font never grows past the normal one`() {
        assertEquals(14f, CoreWatch.fitFont(14f, 141, 135), 0f)
    }

    @Test fun `the font stops at a readable floor`() {
        assertEquals(CoreWatch.MIN_FONT_SP, CoreWatch.fitFont(14f, 20, 400), 0f)
        assertTrue(CoreWatch.MIN_FONT_SP > 0f)
    }

    @Test fun `an unknown size leaves the font alone`() {
        assertEquals(14f, CoreWatch.fitFont(14f, 0, 135), 0f)
        assertEquals(14f, CoreWatch.fitFont(14f, 67, 0), 0f)
    }

    @Test fun `typing counts as a key`() {
        assertTrue(CoreWatch.isTyped("a".toByteArray()))
        assertTrue(CoreWatch.isTyped(byteArrayOf(0x0d)))
        assertTrue(CoreWatch.isTyped(byteArrayOf(0x1b)))
        assertTrue(CoreWatch.isTyped("\u001b[A".toByteArray()))
        assertTrue(CoreWatch.isTyped("\u001bOA".toByteArray()))
    }

    @Test fun `a reply to a terminal query is not a key`() {
        assertFalse(CoreWatch.isTyped("\u001b[12;40R".toByteArray()))
        assertFalse(CoreWatch.isTyped("\u001b[?62;22c".toByteArray()))
        assertFalse(CoreWatch.isTyped("\u001b[0n".toByteArray()))
        assertFalse(CoreWatch.isTyped("\u001b[?2004;2\$y".toByteArray()))
        assertFalse(CoreWatch.isTyped("\u001b[I".toByteArray()))
        assertFalse(CoreWatch.isTyped("\u001b]11;rgb:0000/0000/0000\u001b\\".toByteArray()))
        assertFalse(CoreWatch.isTyped(ByteArray(0)))
    }

    @Test fun `the first key takes over and later keys pass once it is done`() {
        val state = CoreWatchState()
        assertTrue(state.watching)
        assertEquals(WatchInput.PASS, state.onInput("\u001b[12;40R".toByteArray()))
        assertTrue(state.watching)
        assertEquals(WatchInput.TAKE_OVER, state.onInput("x".toByteArray()))
        assertFalse(state.watching)
        state.takeOverDone()
        assertEquals(WatchInput.PASS, state.onInput("y".toByteArray()))
        assertFalse(state.watching)
    }

    @Test fun `keys typed while the takeover runs are held, not sent into the attach line`() {
        val state = CoreWatchState()
        assertEquals(WatchInput.TAKE_OVER, state.onInput("l".toByteArray()))
        assertEquals(WatchInput.HOLD, state.onInput("s".toByteArray()))
        assertEquals(WatchInput.HOLD, state.onInput("\u001b[12;40R".toByteArray()))
        state.takeOverDone()
        assertEquals(WatchInput.PASS, state.onInput("s".toByteArray()))
    }

    @Test fun `a label falls back to the address when space and tab have no name`() {
        assertEquals("t7", CoreWatchTarget("t7", "", "").label)
    }

    @Test fun `a safe address is told from an unsafe one`() {
        assertTrue(CoreWatch.isSafe("t7"))
        assertTrue(CoreWatch.isSafe("box/t12"))
        assertFalse(CoreWatch.isSafe("t7; id"))
        assertFalse(CoreWatch.isSafe("-t7"))
        assertFalse(CoreWatch.isSafe(""))
    }
}
