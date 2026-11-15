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

import org.connectbot.transport.CommandOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin or unpin the terminal a console watches: read its `herdr.pinned` label with `hc get`, write it with
 * the Inbox's own write ([CoreSource.write]). Synthetic output in the shapes measured on hc 0.1.21:
 * a terminal with no labels has no `labels` key, and a pinned one has `"herdr.pinned":"1"`.
 */
class CorePinTest {
    private fun get(labels: String?) = """{"address":"t7","state":"live","turn":4,${labels?.let { "\"labels\":$it," }.orEmpty()}"screen":"inexact"}"""

    private fun ok(text: String) = CommandOutput(text, "", 0)

    @Test
    fun `a terminal with the pin label is pinned`() {
        assertEquals(true, CorePin.pinnedOf(get("""{"herdr.pinned":"1","herdr.seen":"3"}""")))
    }

    @Test
    fun `a terminal with other labels is not pinned`() {
        assertEquals(false, CorePin.pinnedOf(get("""{"herdr.seen":"3","ormus.title":"Herd"}""")))
    }

    @Test
    fun `a terminal with no labels key is not pinned`() {
        assertEquals(false, CorePin.pinnedOf(get(null)))
    }

    @Test
    fun `a pin label with another value is not a pin, as the Inbox listing asks for 1`() {
        assertEquals(false, CorePin.pinnedOf(get("""{"herdr.pinned":""}""")))
        assertEquals(false, CorePin.pinnedOf(get("""{"herdr.pinned":"0"}""")))
        assertEquals(false, CorePin.pinnedOf(get("""{"herdr.pinned":"yes"}""")))
    }

    @Test
    fun `a banner before the json does not hide the label`() {
        assertEquals(true, CorePin.pinnedOf("welcome\n" + get("""{"herdr.pinned":"1"}""")))
    }

    @Test
    fun `an answer that is not a terminal gives no state`() {
        assertNull(CorePin.pinnedOf(""))
        assertNull(CorePin.pinnedOf("no json"))
        assertNull(CorePin.pinnedOf("{broken"))
        assertNull(CorePin.pinnedOf("""["herdr.pinned"]"""))
        assertNull(CorePin.pinnedOf("""{"labels":{"herdr.pinned":"1"}}"""))
    }

    @Test
    fun `look asks hc get of the terminal through the login shell`() {
        val ran = mutableListOf<String>()
        assertEquals(
            true,
            CorePin.look("box/t7") {
                ran += it
                ok(get("""{"herdr.pinned":"1"}"""))
            },
        )
        assertEquals(listOf("bash -lc 'hc get box/t7 --json'"), ran)
    }

    @Test
    fun `look gives no state when the read fails, so the entry never guesses`() {
        assertNull(CorePin.look("t7") { null })
        assertNull(CorePin.look("t7") { CommandOutput("", "no such terminal", 1) })
        assertNull(CorePin.look("t7") { CommandOutput(get("""{"herdr.pinned":"1"}"""), "", null) })
        assertNull(CorePin.look("t7") { error("link dropped") })
        assertNull(CorePin.look("t7") { ok("not json") })
    }

    @Test
    fun `look never runs a command for an address that is not a plain terminal`() {
        val ran = mutableListOf<String>()
        listOf("--help", "t7; id", "", "a/b/c").forEach {
            assertNull(
                CorePin.look(it) { c ->
                    ran += c
                    ok("{}")
                },
            )
        }
        assertEquals(emptyList(), ran)
    }

    @Test
    fun `pin writes herdr pinned 1 and unpin clears it, as the Inbox swipe does`() {
        val ran = mutableListOf<String>()
        assertTrue(
            CorePin.set("box/t7", pin = true) {
                ran += it
                ok("")
            },
        )
        assertTrue(
            CorePin.set("box/t7", pin = false) {
                ran += it
                ok("")
            },
        )
        assertEquals(listOf("bash -lc 'hc label box/t7 herdr.pinned=1'", "bash -lc 'hc label box/t7 herdr.pinned='"), ran)
    }

    @Test
    fun `the write is the Inbox's own write, command for command`() {
        val viaInbox = mutableListOf<String>()
        val viaConsole = mutableListOf<String>()
        CoreSource.write(RowAction.PIN, "t7", null) {
            viaInbox += it
            ok("")
        }
        CoreSource.write(RowAction.UNPIN, "t7", null) {
            viaInbox += it
            ok("")
        }
        CorePin.set("t7", pin = true) {
            viaConsole += it
            ok("")
        }
        CorePin.set("t7", pin = false) {
            viaConsole += it
            ok("")
        }
        assertEquals(viaInbox, viaConsole)
    }

    @Test
    fun `a write that failed is false, whatever the way it failed`() {
        assertFalse(CorePin.set("t7", pin = true) { CommandOutput("", "denied", 1) })
        assertFalse(CorePin.set("t7", pin = true) { null })
        assertFalse(CorePin.set("t7", pin = false) { error("link dropped") })
        assertFalse(CorePin.set("t7", pin = false) { CommandOutput("", "", null) })
    }

    @Test
    fun `a write for an address that is not a plain terminal runs nothing`() {
        val ran = mutableListOf<String>()
        listOf("--help", "t7; id", "").forEach {
            assertFalse(
                CorePin.set(it, pin = true) { c ->
                    ran += c
                    ok("")
                },
            )
        }
        assertEquals(emptyList(), ran)
    }
}
