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

    @Test fun `a tap attaches the terminal as the phone's own view, never as a watcher`() {
        // A plain attach makes the phone the active viewer: the program is reflowed to the phone's grid,
        // as the Herdr app opens a terminal. `exec` ends the console with the attach: no shell is left.
        assertEquals("exec hc attach 't7'\n", CoreWatch.attachCommand("t7"))
        assertEquals("exec hc attach 'box/t12'\n", CoreWatch.attachCommand("box/t12"))
        assertFalse(CoreWatch.attachCommand("t7")!!.contains("--observe"))
    }

    @Test fun `the session runs the attach through the login shell, in place of a shell`() {
        // No prompt and no typed line before the terminal paints; `exec` leaves no bash behind the attach.
        assertEquals(HerdrApi.loginShell("exec hc attach 't7'"), CoreWatch.sessionCommand("t7"))
        assertEquals("bash -lc 'exec hc attach '\\''box/t12'\\'''", CoreWatch.sessionCommand("box/t12"))
        assertNull(CoreWatch.sessionCommand("t7'; id"))
        assertNull(CoreWatch.sessionCommand("1/core/t7"))
    }

    @Test fun `an Inbox row of a core agent attaches the terminal as hc ls named it`() {
        val row = Inbox.row(1, "box", agent())
        assertEquals("t7", CoreWatch.target(row.agent)?.address)
        assertEquals("exec hc attach 't7'\n", CoreWatch.target(row.agent)?.let { CoreWatch.attachCommand(it.address) })
        assertEquals("box/t7", CoreWatch.target(Inbox.row(1, "box", agent(address = "box/t7")).agent)?.address)
    }

    @Test fun `an address with more than one slash is not a terminal`() {
        assertNull(CoreWatch.target(agent(address = "1/core/t7")))
        assertNull(CoreWatch.attachCommand("1/core/t7"))
        assertFalse(CoreWatch.isSafe("a/b/c"))
        assertTrue(CoreWatch.isSafe("box/t7"))
    }

    @Test fun `the attach refuses an address that could break out of its quotes`() {
        assertNull(CoreWatch.attachCommand("t7'; id"))
        assertNull(CoreWatch.attachCommand("t7 t8"))
        assertNull(CoreWatch.attachCommand("\$(id)"))
        assertNull(CoreWatch.attachCommand(""))
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
