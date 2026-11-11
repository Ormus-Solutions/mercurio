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
import kotlin.test.assertTrue

/** CoreSource: the herdr-core terminals of one host as [Agent]s, from synthetic `hc ls --json` output. */
class CoreSourceTest {
    private val version = CommandOutput("herdr-core 0.1.0\n", "", 0)

    private fun ls(vararg records: String) = CommandOutput("""{"hidden_finished":0,"terminals":[${records.joinToString(",")}]}""", "", 0)

    private fun rec(
        address: String,
        agent: String = "claude-code",
        status: String? = "\"idle\"",
        where: String = "alpha · ONE / main",
        turn: Int = 3,
        extra: String = "",
    ) = """{"address":"$address","agent":"$agent","cwd":"/work/x","state":"live","status":${status ?: "null"},"turn":$turn,"where":"$where"$extra}"""

    /** A host answering the two commands; [log] collects what was run. */
    private fun host(
        versionOut: CommandOutput? = version,
        lsOut: CommandOutput?,
        log: MutableList<String> = mutableListOf(),
    ): (String) -> CommandOutput? = { cmd ->
        log += cmd
        if ("--version" in cmd) versionOut else lsOut
    }

    private fun agents(result: CoreSource.Result) = assertIs<CoreSource.Result.Agents>(result).agents

    @Test
    fun `off by default returns Off and runs nothing`() {
        val log = mutableListOf<String>()
        val r = CoreSource().read(enabled = false, machine = "box", now = 1000L, run = host(lsOut = ls(), log = log))
        assertEquals(CoreSource.Result.Off, r)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `every command goes through the login shell`() {
        val log = mutableListOf<String>()
        CoreSource().read(true, "box", 1000L, host(lsOut = ls(), log = log))
        assertEquals(
            listOf(HerdrApi.loginShell("herdr-core --version"), HerdrApi.loginShell("hc ls --json")),
            log,
        )
    }

    @Test
    fun `a record becomes a core agent with space, tab, turn and kind`() {
        val a = agents(CoreSource().read(true, "box", 1000L, host(lsOut = ls(rec("t7", status = "\"working\"", turn = 5)))))
        assertEquals(1, a.size)
        val x = a[0]
        assertEquals("t7", x.address)
        assertEquals(AgentEngine.CORE, x.engine)
        assertEquals("box", x.machine)
        assertEquals("alpha · ONE", x.space)
        assertEquals("main", x.tab)
        assertEquals("claude-code", x.kind)
        assertEquals(AgentState.Working, x.state)
        assertEquals(5, x.turn)
    }

    @Test
    fun `status as a string maps to states`() {
        val a = agents(
            CoreSource().read(
                true,
                "box",
                1L,
                host(
                    lsOut = ls(
                        rec("t1", status = "\"idle\""),
                        rec("t2", status = "\"done\""),
                        rec("t3", status = "\"working\""),
                        rec("t4", status = "\"blocked\""),
                        rec("t5", status = "\"strange\""),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(AgentState.Idle, AgentState.Done, AgentState.Working, AgentState.NeedsYou(), AgentState.Unknown),
            a.map { it.state },
        )
    }

    @Test
    fun `status as an object (the hc get shape) maps the same, with the needs you kind`() {
        val a = agents(
            CoreSource().read(
                true,
                "box",
                1L,
                host(
                    lsOut = ls(
                        rec("t1", status = """{"app":"claude-code","state":"working"}"""),
                        rec("t2", status = """{"state":"blocked","kind":"permission"}"""),
                        rec("t3", status = """{"state":"blocked","kind":"question"}"""),
                        rec("t4", status = """{"state":"blocked","kind":"auth"}"""),
                        rec("t5", status = """{"state":"blocked"}"""),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                AgentState.Working,
                AgentState.NeedsYou(NeedsYouKind.PERMISSION),
                AgentState.NeedsYou(NeedsYouKind.QUESTION),
                AgentState.NeedsYou(NeedsYouKind.AUTH),
                AgentState.NeedsYou(),
            ),
            a.map { it.state },
        )
    }

    @Test
    fun `a bash terminal with no status is Unknown, and a missing status key too`() {
        val noKey = """{"address":"t9","agent":"sh","cwd":"/","state":"live","turn":0,"where":"alpha · ONE / shell"}"""
        val a = agents(CoreSource().read(true, "box", 1L, host(lsOut = ls(rec("t8", agent = "bash", status = null), noKey))))
        assertEquals(listOf(AgentState.Unknown, AgentState.Unknown), a.map { it.state })
        assertEquals(listOf("bash", "sh"), a.map { it.kind })
    }

    @Test
    fun `where without a separator is all space, and a missing where is empty`() {
        val noWhere = """{"address":"t2","agent":"bash","state":"live","status":null,"turn":0}"""
        val a = agents(CoreSource().read(true, "box", 1L, host(lsOut = ls(rec("t1", where = "solo"), noWhere))))
        assertEquals("solo" to "", a[0].space to a[0].tab)
        assertEquals("" to "", a[1].space to a[1].tab)
    }

    @Test
    fun `a record with no address is skipped`() {
        val a = agents(CoreSource().read(true, "box", 1L, host(lsOut = ls("""{"agent":"bash"}""", rec("t1")))))
        assertEquals(listOf("t1"), a.map { it.address })
    }

    @Test
    fun `a status_ms on the record is the time in state`() {
        val a = agents(CoreSource().read(true, "box", 9000L, host(lsOut = ls(rec("t1", extra = ""","status_ms":4242""")))))
        assertEquals(4242L, a[0].since)
    }

    @Test
    fun `without status_ms the time starts when the state is first seen and holds while it lasts`() {
        val src = CoreSource()
        val idle = host(lsOut = ls(rec("t1", status = "\"idle\"")))
        assertEquals(1000L, agents(src.read(true, "box", 1000L, idle))[0].since)
        assertEquals(1000L, agents(src.read(true, "box", 5000L, idle))[0].since)
        val working = host(lsOut = ls(rec("t1", status = "\"working\"")))
        assertEquals(6000L, agents(src.read(true, "box", 6000L, working))[0].since)
    }

    @Test
    fun `an empty list is a host with no agents, not a failure`() {
        assertEquals(emptyList(), agents(CoreSource().read(true, "box", 1L, host(lsOut = ls()))))
    }

    @Test
    fun `no herdr-core on the host gives a reason and no rows`() {
        val log = mutableListOf<String>()
        for (v in listOf(CommandOutput("", "bash: herdr-core: command not found", 127), CommandOutput("", "", 0))) {
            val r = CoreSource().read(true, "box", 1L, host(versionOut = v, lsOut = ls(rec("t1")), log = log))
            val u = assertIs<CoreSource.Result.Unavailable>(r)
            assertTrue(u.reason.contains("herdr-core"), u.reason)
        }
        assertTrue(log.none { "hc ls" in it })
    }

    @Test
    fun `a core that is down gives a reason and no rows`() {
        for (out in listOf(
            null,
            CommandOutput("", "error: core not running", 1),
            CommandOutput("not json", "", 0),
            CommandOutput("""{"unexpected":1}""", "", 0),
        )) {
            val r = CoreSource().read(true, "box", 1L, host(lsOut = out))
            val u = assertIs<CoreSource.Result.Unavailable>(r)
            assertTrue(u.reason.isNotBlank())
        }
    }

    @Test
    fun `a failed read gives no rows and keeps the times it knew`() {
        val src = CoreSource()
        agents(src.read(true, "box", 1000L, host(lsOut = ls(rec("t1")))))
        assertIs<CoreSource.Result.Unavailable>(src.read(true, "box", 2000L, host(lsOut = null)))
        assertEquals(1000L, agents(src.read(true, "box", 3000L, host(lsOut = ls(rec("t1")))))[0].since)
    }

    @Test
    fun `a terminal that left the list is forgotten`() {
        val src = CoreSource()
        agents(src.read(true, "box", 1000L, host(lsOut = ls(rec("t1")))))
        agents(src.read(true, "box", 2000L, host(lsOut = ls())))
        assertEquals(3000L, agents(src.read(true, "box", 3000L, host(lsOut = ls(rec("t1")))))[0].since)
    }

    @Test
    fun `a change of needs you kind keeps the time in state`() {
        val src = CoreSource()
        agents(src.read(true, "box", 1000L, host(lsOut = ls(rec("t1", status = "\"blocked\"")))))
        val a = agents(src.read(true, "box", 5000L, host(lsOut = ls(rec("t1", status = """{"state":"blocked","kind":"permission"}""")))))
        assertEquals(AgentState.NeedsYou(NeedsYouKind.PERMISSION), a[0].state)
        assertEquals(1000L, a[0].since)
    }

    @Test
    fun `a status_ms that is not a positive number is not a time`() {
        for (bad in listOf("\"4242\"", "0", "-5", "null")) {
            val a = agents(CoreSource().read(true, "box", 9000L, host(lsOut = ls(rec("t1", extra = ""","status_ms":$bad""")))))
            assertEquals(9000L, a[0].since, bad)
        }
    }

    @Test
    fun `a host that cannot be reached is not reported as missing herdr-core`() {
        val u = assertIs<CoreSource.Result.Unavailable>(CoreSource().read(true, "box", 1L, host(versionOut = null, lsOut = ls())))
        assertTrue(u.reason.contains("reach"), u.reason)
        assertTrue(!u.reason.contains("not installed"), u.reason)
    }

    @Test
    fun `login shell noise before the json is skipped`() {
        val noisy = CommandOutput("Welcome to box\n" + ls(rec("t1")).stdout, "", 0)
        assertEquals(listOf("t1"), agents(CoreSource().read(true, "box", 1L, host(lsOut = noisy))).map { it.address })
    }

    @Test
    fun `a good version probe is not run again while the host keeps answering`() {
        val log = mutableListOf<String>()
        val src = CoreSource()
        val run = host(lsOut = ls(), log = log)
        src.read(true, "box", 1L, run)
        src.read(true, "box", 2L, run)
        assertEquals(1, log.count { "--version" in it })
        assertEquals(2, log.count { "hc ls" in it })
    }

    @Test
    fun `after a failed read the version is probed again`() {
        val log = mutableListOf<String>()
        val src = CoreSource()
        src.read(true, "box", 1L, host(lsOut = CommandOutput("", "down", 1), log = log))
        src.read(true, "box", 2L, host(lsOut = ls(), log = log))
        assertEquals(2, log.count { "--version" in it })
    }
}
