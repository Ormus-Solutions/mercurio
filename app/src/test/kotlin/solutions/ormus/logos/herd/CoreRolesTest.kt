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
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The role of a herdr-core terminal: its name from `hc ls` and its `ormus.title` label from `hc get`,
 * read once for a new address and kept. Synthetic output only.
 */
class CoreRolesTest {
    private val version = CommandOutput("herdr-core 0.1.0\n", "", 0)

    private fun rec(address: String, status: String = "working", turn: Int = 3, name: String? = null, cwd: String = "/work/x") = """{"address":"$address","agent":"claude-code","cwd":"$cwd",${name?.let { "\"name\":\"$it\"," }.orEmpty()}"state":"live","status":"$status","turn":$turn,"where":"alpha · ONE / main"}"""

    private fun ls(vararg records: String) = CommandOutput("""{"hidden_finished":0,"terminals":[${records.joinToString(",")}]}""", "", 0)

    /** A host. [titles] is what `hc get` says per address (a terminal with no labels has no `labels` key: measured); [log] collects every command. */
    private class Host(var listing: CommandOutput, val titles: MutableMap<String, String?> = HashMap(), val labelsOk: Boolean = true) {
        val log = mutableListOf<String>()
        val run: (String) -> CommandOutput? = { cmd ->
            log += cmd
            when {
                "--version" in cmd -> CommandOutput("herdr-core 0.1.0\n", "", 0)
                CoreSource.PINNED_CMD in cmd -> if (!labelsOk) null else CommandOutput("""{"terminals":[]}""" + "\n" + asked(cmd).joinToString("") { a -> titleLine(a) + "\n" }, "", 0)
                else -> listing
            }
        }

        private fun titleLine(address: String): String {
            val title = titles[address]
            return if (title == null) """{"address":"$address"}""" else """{"address":"$address","labels":{"ormus.title":"$title"}}"""
        }

        /** The addresses a labels command asks `hc get` about. */
        fun asked(cmd: String) = Regex("hc get ([A-Za-z0-9_/-]+) --json").findAll(cmd).map { it.groupValues[1] }.toList()

        /** The addresses every labels command so far asked about, in order. */
        fun askedAll() = log.filter { CoreSource.PINNED_CMD in it }.flatMap { asked(it) }

        fun labelsCommands() = log.count { CoreSource.PINNED_CMD in it }

        fun clear() = log.clear()
    }

    private fun CoreSource.agents(host: Host, now: Long = 1L) = assertIs<CoreSource.Result.Agents>(read(true, "box", now, host.run)).agents.associateBy { it.address }

    @Test
    fun `the name of a terminal and its ormus title reach its agent`() {
        val host = Host(ls(rec("t1", name = "mdev-herd")), mutableMapOf("t1" to "Herd: Inbox, Spaces, notices"))
        val a = CoreSource().agents(host).getValue("t1")
        assertEquals("mdev-herd", a.name)
        assertEquals("Herd: Inbox, Spaces, notices", a.role)
    }

    @Test
    fun `a terminal with no title label has its name and no role`() {
        val host = Host(ls(rec("t1", name = "mdev-gate")))
        val a = CoreSource().agents(host).getValue("t1")
        assertEquals("mdev-gate", a.name)
        assertNull(a.role)
    }

    @Test
    fun `a terminal with no name has no name`() {
        val a = CoreSource().agents(Host(ls(rec("t1")), mutableMapOf("t1" to "Orchestrator"))).getValue("t1")
        assertNull(a.name)
        assertEquals("Orchestrator", a.role)
    }

    @Test
    fun `a title with only spaces is no title, and a title is trimmed`() {
        val host = Host(ls(rec("t1"), rec("t2")), mutableMapOf("t1" to "   ", "t2" to "  Gate: review  "))
        val a = CoreSource().agents(host)
        assertNull(a.getValue("t1").role)
        assertEquals("Gate: review", a.getValue("t2").role)
    }

    @Test
    fun `the title of a new address is read once and kept`() {
        val host = Host(ls(rec("t1"), rec("t2")), mutableMapOf("t1" to "Orchestrator", "t2" to "Herd"))
        val source = CoreSource()
        source.agents(host)
        assertEquals(listOf("t1", "t2"), host.askedAll())
        host.clear()
        val again = source.agents(host, now = 2L)
        assertEquals(emptyList(), host.askedAll())
        assertEquals("Orchestrator", again.getValue("t1").role)
        assertEquals("Herd", again.getValue("t2").role)
    }

    @Test
    fun `a terminal that shows later is asked, and only it`() {
        val host = Host(ls(rec("t1")), mutableMapOf("t1" to "Orchestrator", "t2" to "Console"))
        val source = CoreSource()
        source.agents(host)
        host.clear()
        host.listing = ls(rec("t1"), rec("t2"))
        val a = source.agents(host, now = 2L)
        assertEquals(listOf("t2"), host.askedAll())
        assertEquals("Orchestrator", a.getValue("t1").role)
        assertEquals("Console", a.getValue("t2").role)
    }

    @Test
    fun `a title is dropped when its address goes, and read again when the address returns`() {
        val host = Host(ls(rec("t1")), mutableMapOf("t1" to "Orchestrator"))
        val source = CoreSource()
        source.agents(host)
        host.listing = ls()
        source.agents(host, now = 2L)
        host.clear()
        host.titles["t1"] = "Platform"
        host.listing = ls(rec("t1"))
        val a = source.agents(host, now = 3L)
        assertEquals(listOf("t1"), host.askedAll())
        assertEquals("Platform", a.getValue("t1").role)
    }

    @Test
    fun `an address that names another terminal is not given the old title`() {
        val host = Host(ls(rec("t1", name = "mdev-orch")), mutableMapOf("t1" to "Orchestrator"))
        val source = CoreSource()
        source.agents(host)
        host.clear()
        host.titles["t1"] = null
        host.listing = ls(rec("t1", name = "build"))
        val a = source.agents(host, now = 2L).getValue("t1")
        assertEquals(listOf("t1"), host.askedAll())
        assertNull(a.role)
        assertEquals("build", a.name)
    }

    @Test
    fun `an idle terminal is read for seen already, so one get serves both and its title stays fresh`() {
        val host = Host(ls(rec("t1", status = "idle", turn = 2)), mutableMapOf("t1" to "Orchestrator"))
        val source = CoreSource()
        source.agents(host)
        assertEquals(listOf("t1"), host.askedAll())
        host.clear()
        host.titles["t1"] = "Chief"
        val a = source.agents(host, now = 2L)
        assertEquals(listOf("t1"), host.askedAll())
        assertEquals("Chief", a.getValue("t1").role)
    }

    @Test
    fun `a terminal with no title is asked again when its turn changes, not before`() {
        val host = Host(ls(rec("t1", turn = 1)))
        val source = CoreSource()
        assertNull(source.agents(host).getValue("t1").role)
        host.clear()
        host.titles["t1"] = "Orchestrator"
        assertNull(source.agents(host, now = 2L).getValue("t1").role)
        assertEquals(emptyList(), host.askedAll())
        host.listing = ls(rec("t1", turn = 2))
        assertEquals("Orchestrator", source.agents(host, now = 3L).getValue("t1").role)
        assertEquals(listOf("t1"), host.askedAll())
    }

    @Test
    fun `a titled terminal is not asked again when its turn changes`() {
        val host = Host(ls(rec("t1", turn = 1)), mutableMapOf("t1" to "Orchestrator"))
        val source = CoreSource()
        source.agents(host)
        host.clear()
        host.listing = ls(rec("t1", turn = 2))
        source.agents(host, now = 2L)
        assertEquals(emptyList(), host.askedAll())
    }

    @Test
    fun `a labels command that did not answer leaves no role, and the terminal is asked again`() {
        val down = Host(ls(rec("t1")), mutableMapOf("t1" to "Orchestrator"), labelsOk = false)
        val source = CoreSource()
        assertNull(source.agents(down).getValue("t1").role)
        val up = Host(ls(rec("t1")), mutableMapOf("t1" to "Orchestrator"))
        assertEquals("Orchestrator", source.agents(up, now = 2L).getValue("t1").role)
        assertEquals(listOf("t1"), up.askedAll())
    }

    @Test
    fun `a failed listing keeps the titles it knew`() {
        val host = Host(ls(rec("t1")), mutableMapOf("t1" to "Orchestrator"))
        val source = CoreSource()
        source.agents(host)
        host.listing = CommandOutput("", "down", 1)
        assertIs<CoreSource.Result.Unavailable>(source.read(true, "box", 2L, host.run))
        host.clear()
        host.listing = ls(rec("t1"))
        val a = source.agents(host, now = 3L)
        assertEquals(emptyList(), host.askedAll())
        assertEquals("Orchestrator", a.getValue("t1").role)
    }

    @Test
    fun `a refresh is still one list command and one labels command, titles or not`() {
        val host = Host(ls(rec("t1"), rec("t2"), rec("t3", status = "idle", turn = 4)), mutableMapOf("t1" to "A", "t2" to "B", "t3" to "C"))
        val source = CoreSource()
        source.agents(host)
        // The first read also asks the host if herdr-core is there; no refresh runs a command per terminal.
        assertEquals(1, host.log.count { "--version" in it })
        assertEquals(1, host.log.count { it.contains("hc ls --json") })
        assertEquals(1, host.labelsCommands())
        host.clear()
        source.agents(host, now = 2L)
        assertEquals(2, host.log.size)
        assertEquals(1, host.labelsCommands())
        assertEquals(listOf("t3"), host.askedAll())
    }

    @Test
    fun `states asks for no title`() {
        val host = Host(ls(rec("t1"), rec("t2", status = "idle", turn = 0)))
        CoreSource().states(host.run)
        assertEquals(1, host.log.size)
        assertEquals(emptyList(), host.askedAll())
    }

    @Test
    fun `an address that is not a terminal name is never asked for its title`() {
        val host = Host(ls(rec("t1"), rec("t2; id")))
        CoreSource().agents(host)
        assertEquals(listOf("t1"), host.askedAll())
    }

    @Test
    fun `an address that names another terminal has no role while the labels command fails`() {
        val source = CoreSource()
        source.agents(Host(ls(rec("t1", name = "mdev-orch")), mutableMapOf("t1" to "Orchestrator")))
        val down = Host(ls(rec("t1", name = "build")), labelsOk = false)
        val a = source.agents(down, now = 2L).getValue("t1")
        assertEquals("build", a.name)
        assertNull(a.role)
    }

    @Test
    fun `an address whose turn went back is another terminal, asked again, and has no role while the labels command fails`() {
        // Two unnamed terminals in one folder share name and cwd; a core that started again gives the old address to the new one.
        val source = CoreSource()
        source.agents(Host(ls(rec("t1", turn = 5)), mutableMapOf("t1" to "Old title")))
        val down = Host(ls(rec("t1", turn = 2)), labelsOk = false)
        assertNull(source.agents(down, now = 2L).getValue("t1").role)
        val up = Host(ls(rec("t1", turn = 2)), mutableMapOf("t1" to "New title"))
        val a = source.agents(up, now = 3L).getValue("t1")
        assertEquals(listOf("t1"), up.askedAll())
        assertEquals("New title", a.role)
    }

    @Test
    fun `a turn that stays or goes up keeps the title and asks nothing`() {
        val source = CoreSource()
        source.agents(Host(ls(rec("t1", turn = 5)), mutableMapOf("t1" to "Title")))
        val host = Host(ls(rec("t1", turn = 5)))
        assertEquals("Title", source.agents(host, now = 2L).getValue("t1").role)
        host.listing = ls(rec("t1", turn = 9))
        assertEquals("Title", source.agents(host, now = 3L).getValue("t1").role)
        assertEquals(emptyList(), host.askedAll())
    }

    @Test
    fun `parseOne reads the name of its one record`() {
        val a = CoreSource().parseOne("""{"address":"t1","name":"mdev-orch","agent":"claude-code","status":"idle","turn":1,"where":"a · B / c","labels":{"ormus.title":"Orchestrator"}}""", "box", 1L)
        assertEquals("mdev-orch", a?.name)
    }
}
