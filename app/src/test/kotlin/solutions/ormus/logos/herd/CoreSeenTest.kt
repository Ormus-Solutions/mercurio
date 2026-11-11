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
import kotlin.test.assertTrue

/** A watch console raises `herdr.seen` to the terminal's turn once per turn (herdr-app docs/labels.md). */
class CoreSeenTest {
    private class Host(var turn: Int? = 5, var seen: String? = null, var writeOk: Boolean = true, var readOk: Boolean = true) {
        val log = mutableListOf<String>()

        fun run(cmd: String): CommandOutput? {
            log += cmd
            return when {
                "hc label" in cmd -> CommandOutput("", "", if (writeOk) 0 else 1)

                !readOk -> CommandOutput("", "boom", 1)

                else -> {
                    val labels = seen?.let { """{"herdr.seen":"$it"}""" } ?: "{}"
                    val t = turn?.let { ""","turn":$it""" }.orEmpty()
                    CommandOutput(
                        """banner
{"address":"t7","status":"idle"$t,"labels":$labels}""",
                        "",
                        0,
                    )
                }
            }
        }

        val writes get() = log.filter { "hc label" in it }
    }

    @Test
    fun `a missing label is written with the turn`() {
        val host = Host(turn = 5, seen = null)
        CoreSeen("t7").look(host::run)
        assertEquals(1, host.writes.size)
        assertTrue("hc label t7 herdr.seen=5" in host.writes.single())
    }

    @Test
    fun `a label below the turn is raised`() {
        val host = Host(turn = 5, seen = "3")
        CoreSeen("t7").look(host::run)
        assertTrue("hc label t7 herdr.seen=5" in host.writes.single())
    }

    @Test
    fun `a label at the turn is left alone`() {
        val host = Host(turn = 5, seen = "5")
        CoreSeen("t7").look(host::run)
        assertTrue(host.writes.isEmpty())
    }

    @Test
    fun `a mark unread from another client is not overruled in the same turn`() {
        val host = Host(turn = 5, seen = "3")
        val seen = CoreSeen("t7")
        seen.look(host::run)
        host.seen = "4"
        seen.look(host::run)
        assertEquals(1, host.writes.size)
    }

    @Test
    fun `a new turn on screen writes again`() {
        val host = Host(turn = 5, seen = null)
        val seen = CoreSeen("t7")
        seen.look(host::run)
        host.turn = 6
        host.seen = "5"
        seen.look(host::run)
        assertEquals(2, host.writes.size)
        assertTrue("herdr.seen=6" in host.writes.last())
    }

    @Test
    fun `a turn already seen is not written and not written later`() {
        val host = Host(turn = 5, seen = "5")
        val seen = CoreSeen("t7")
        seen.look(host::run)
        host.seen = "4"
        seen.look(host::run)
        assertTrue(host.writes.isEmpty())
    }

    @Test
    fun `a failed read writes nothing and the next look tries again`() {
        val host = Host(turn = 5, seen = null, readOk = false)
        val seen = CoreSeen("t7")
        seen.look(host::run)
        assertTrue(host.writes.isEmpty())
        host.readOk = true
        seen.look(host::run)
        assertEquals(1, host.writes.size)
    }

    @Test
    fun `a failed write is tried again on the next look`() {
        val host = Host(turn = 5, seen = null, writeOk = false)
        val seen = CoreSeen("t7")
        seen.look(host::run)
        host.writeOk = true
        seen.look(host::run)
        assertEquals(2, host.writes.size)
    }

    @Test
    fun `a terminal with no turn is not written`() {
        val host = Host(turn = null, seen = null)
        CoreSeen("t7").look(host::run)
        assertTrue(host.writes.isEmpty())
    }

    @Test
    fun `a remote terminal never writes the local one of the same number`() {
        val host = Host(turn = 5, seen = null)
        CoreSeen("box/t7").look(host::run)
        assertTrue("hc get box/t7" in host.log.first())
        assertTrue("hc label box/t7 herdr.seen=5" in host.writes.single())
        assertTrue(host.log.none { "hc label t7" in it || "hc get t7" in it })
    }

    @Test
    fun `an address that is not a plain terminal runs nothing`() {
        val host = Host()
        CoreSeen("t7; rm -rf x").look(host::run)
        CoreSeen("--help").look(host::run)
        assertTrue(host.log.isEmpty())
    }

    @Test
    fun `a console that left the screen during the read writes nothing`() {
        val host = Host(turn = 5, seen = null)
        var shown = true
        CoreSeen("t7").look(
            run = { cmd -> host.run(cmd).also { shown = false } },
            stillShown = { shown },
        )
        assertTrue(host.writes.isEmpty())
    }

    @Test
    fun `a turn that goes back is looked at again`() {
        val host = Host(turn = 5, seen = null)
        val seen = CoreSeen("t7")
        seen.look(host::run)
        host.turn = 2
        host.seen = null
        seen.look(host::run)
        assertEquals(2, host.writes.size)
        assertTrue("herdr.seen=2" in host.writes.last())
    }
}
