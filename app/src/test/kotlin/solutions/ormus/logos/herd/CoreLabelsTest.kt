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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The herdr.seen and herdr.pinned labels the Herdr app shares: read from synthetic hc output, written as hc commands. */
class CoreLabelsTest {
    private val version = CommandOutput("herdr-core 0.1.0\n", "", 0)

    private fun rec(address: String, status: String, turn: Int) = """{"address":"$address","agent":"claude-code","state":"live","status":"$status","turn":$turn,"where":"alpha · ONE / main"}"""

    private fun ls(vararg records: String) = CommandOutput("""{"hidden_finished":0,"terminals":[${records.joinToString(",")}]}""", "", 0)

    private fun get(address: String, vararg labels: Pair<String, String>) = """{"address":"$address","labels":{${labels.joinToString(",") { "\"${it.first}\":\"${it.second}\"" }}}}"""

    /** A host: [labels] is what the labels command prints. [log] collects every command. */
    private fun host(listing: CommandOutput, labels: CommandOutput?, log: MutableList<String> = mutableListOf()): (String) -> CommandOutput? = { cmd ->
        log += cmd
        when {
            "--version" in cmd -> version
            CoreSource.PINNED_CMD in cmd -> labels
            else -> listing
        }
    }

    private fun labelOut(pinned: List<String>, vararg gets: String) = CommandOutput(
        """{"hidden_finished":0,"terminals":[${pinned.joinToString(",") { rec(it, "idle", 1) }}]}""" + "\n" + gets.joinToString("") { it + "\n" },
        "",
        0,
    )

    private fun agents(run: (String) -> CommandOutput?) = assertIs<CoreSource.Result.Agents>(CoreSource().read(true, "box", 1L, run)).agents.associateBy { it.address }

    @Test
    fun `a done agent past its seen turn is unread and stays Done`() {
        val a = agents(host(ls(rec("t1", "done", 5)), labelOut(emptyList(), get("t1", "herdr.seen" to "4"))))
        assertEquals(AgentState.Done, a.getValue("t1").state)
        assertEquals(true, a.getValue("t1").unread)
    }

    @Test
    fun `an agent whose seen reached its turn is Idle and read, even when the core says done`() {
        val a = agents(host(ls(rec("t1", "done", 5), rec("t2", "idle", 3)), labelOut(emptyList(), get("t1", "herdr.seen" to "5"), get("t2", "herdr.seen" to "9"))))
        assertEquals(AgentState.Idle, a.getValue("t1").state)
        assertEquals(false, a.getValue("t1").unread)
        assertEquals(AgentState.Idle, a.getValue("t2").state)
    }

    @Test
    fun `an idle agent past its seen turn becomes Done`() {
        val a = agents(host(ls(rec("t1", "idle", 2)), labelOut(emptyList(), get("t1", "herdr.seen" to "1"))))
        assertEquals(AgentState.Done, a.getValue("t1").state)
        assertEquals(true, a.getValue("t1").unread)
    }

    @Test
    fun `a missing seen label is not zero, the core status stands and unread is unknown`() {
        val a = agents(host(ls(rec("t1", "done", 5), rec("t2", "idle", 5)), labelOut(emptyList(), get("t1", "herdr.pinned" to "1"), get("t2"))))
        assertEquals(AgentState.Done, a.getValue("t1").state)
        assertNull(a.getValue("t1").unread)
        assertEquals(AgentState.Idle, a.getValue("t2").state)
        assertNull(a.getValue("t2").unread)
    }

    @Test
    fun `a seen label that is not a number is treated as missing`() {
        val a = agents(host(ls(rec("t1", "done", 5)), labelOut(emptyList(), get("t1", "herdr.seen" to "soon"))))
        assertEquals(AgentState.Done, a.getValue("t1").state)
        assertNull(a.getValue("t1").unread)
    }

    @Test
    fun `needs you and working agents keep their state whatever seen says`() {
        val a = agents(host(ls(rec("t1", "blocked", 5), rec("t2", "working", 5)), labelOut(emptyList())))
        assertEquals(AgentState.NeedsYou(), a.getValue("t1").state)
        assertEquals(AgentState.Working, a.getValue("t2").state)
        assertNull(a.getValue("t1").unread)
    }

    @Test
    fun `only idle or done agents past turn 0 are asked for their labels`() {
        val log = mutableListOf<String>()
        agents(host(ls(rec("t1", "done", 5), rec("t2", "idle", 0), rec("t3", "working", 2), rec("t4", "idle", 1)), labelOut(emptyList()), log))
        assertEquals(listOf(HerdrApi.loginShell(CoreSource.labelsCmd(listOf("t1", "t4")))), log.filter { CoreSource.PINNED_CMD in it })
    }

    @Test
    fun `an address that is not a terminal name never reaches the shell`() {
        assertEquals(CoreSource.PINNED_CMD, CoreSource.labelsCmd(emptyList()))
        assertEquals("${CoreSource.PINNED_CMD}; hc get t1 --json", CoreSource.labelsCmd(listOf("t1", "t2; id", "a b", "")))
    }

    @Test
    fun `pinned comes from the pinned listing, for any state`() {
        val a = agents(host(ls(rec("t1", "working", 2), rec("t2", "idle", 0)), labelOut(listOf("t1"))))
        assertTrue(a.getValue("t1").pinned)
        assertFalse(a.getValue("t2").pinned)
    }

    @Test
    fun `a labels read that fails leaves the core status and nothing pinned`() {
        for (bad in listOf(null, CommandOutput("", "boom", 1), CommandOutput("not json", "", 0))) {
            val a = agents(host(ls(rec("t1", "done", 5)), bad))
            assertEquals(AgentState.Done, a.getValue("t1").state)
            assertNull(a.getValue("t1").unread)
            assertFalse(a.getValue("t1").pinned)
        }
    }

    @Test
    fun `a get that fails among the others costs only its own agent`() {
        val out = CommandOutput(
            """{"hidden_finished":0,"terminals":[]}""" + "\n" + """{"error":{"kind":"not_found"}}""" + "\n" + get("t2", "herdr.seen" to "1") + "\n",
            "",
            1,
        )
        val a = agents(host(ls(rec("t1", "done", 5), rec("t2", "idle", 3)), out))
        assertNull(a.getValue("t1").unread)
        assertEquals(true, a.getValue("t2").unread)
    }

    @Test
    fun `time in state restarts when a label moves the agent from Done to Idle`() {
        val src = CoreSource()
        val unread = host(ls(rec("t1", "done", 5)), labelOut(emptyList(), get("t1", "herdr.seen" to "4")))
        val read = host(ls(rec("t1", "done", 5)), labelOut(emptyList(), get("t1", "herdr.seen" to "5")))
        assertEquals(1000L, assertIs<CoreSource.Result.Agents>(src.read(true, "box", 1000L, unread)).agents[0].since)
        assertEquals(2000L, assertIs<CoreSource.Result.Agents>(src.read(true, "box", 2000L, read)).agents[0].since)
    }

    // Writes

    private fun record(): Pair<MutableList<String>, (String) -> CommandOutput?> {
        val log = mutableListOf<String>()
        return log to { cmd ->
            log += cmd
            CommandOutput("", "", 0)
        }
    }

    @Test
    fun `mark read writes the current turn`() {
        val (log, run) = record()
        assertTrue(CoreSource.write(RowAction.MARK_READ, "t7", turn = 5, run = run))
        assertEquals(listOf(HerdrApi.loginShell("hc label t7 herdr.seen=5")), log)
    }

    @Test
    fun `pin writes 1 and unpin writes the empty value`() {
        val (log, run) = record()
        assertTrue(CoreSource.write(RowAction.PIN, "t7", turn = null, run = run))
        assertTrue(CoreSource.write(RowAction.UNPIN, "t7", turn = null, run = run))
        assertEquals(listOf(HerdrApi.loginShell("hc label t7 herdr.pinned=1"), HerdrApi.loginShell("hc label t7 herdr.pinned=")), log)
    }

    @Test
    fun `interrupt presses esc`() {
        val (log, run) = record()
        assertTrue(CoreSource.write(RowAction.INTERRUPT, "t7", turn = 2, run = run))
        assertEquals(listOf(HerdrApi.loginShell("hc keys t7 esc")), log)
    }

    @Test
    fun `mark read without a turn, or a bad address, runs nothing and fails`() {
        val (log, run) = record()
        assertFalse(CoreSource.write(RowAction.MARK_READ, "t7", turn = null, run = run))
        assertFalse(CoreSource.write(RowAction.PIN, "t7; id", turn = null, run = run))
        assertFalse(CoreSource.write(RowAction.INTERRUPT, "", turn = null, run = run))
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a write fails when the command did not run or exited non-zero`() {
        assertFalse(CoreSource.write(RowAction.PIN, "t7", null) { null })
        assertFalse(CoreSource.write(RowAction.PIN, "t7", null) { CommandOutput("", "no", 1) })
    }

    // The swipe table and the local patch

    private fun agent(engine: AgentEngine = AgentEngine.CORE, state: AgentState = AgentState.Idle, unread: Boolean? = null, pinned: Boolean = false, turn: Int? = 3) = Agent("t7", engine, "box", "alpha · ONE", "main", state = state, turn = turn, unread = unread, pinned = pinned)

    @Test
    fun `swipe right marks an unread row read, else toggles the pin`() {
        assertEquals(RowAction.MARK_READ, Inbox.rightSwipe(agent(state = AgentState.Done, unread = true)))
        assertEquals(RowAction.PIN, Inbox.rightSwipe(agent()))
        assertEquals(RowAction.UNPIN, Inbox.rightSwipe(agent(pinned = true)))
        assertEquals(RowAction.UNPIN, Inbox.rightSwipe(agent(state = AgentState.Done, unread = true, pinned = true).copy(unread = false)))
    }

    @Test
    fun `a TUI agent has no labels, so no swipe does anything`() {
        assertNull(Inbox.rightSwipe(agent(engine = AgentEngine.TUI)))
        assertFalse(Inbox.canInterrupt(agent(engine = AgentEngine.TUI)))
        assertTrue(Inbox.canInterrupt(agent()))
    }

    @Test
    fun `after a write the row is patched at once`() {
        assertEquals(AgentState.Idle, Inbox.applied(agent(state = AgentState.Done, unread = true), RowAction.MARK_READ).state)
        assertEquals(false, Inbox.applied(agent(state = AgentState.Done, unread = true), RowAction.MARK_READ).unread)
        assertTrue(Inbox.applied(agent(), RowAction.PIN).pinned)
        assertFalse(Inbox.applied(agent(pinned = true), RowAction.UNPIN).pinned)
        assertEquals(agent(), Inbox.applied(agent(), RowAction.INTERRUPT))
    }

    @Test
    fun `the terminal of a row is the address after the last slash`() {
        assertEquals("t7", Inbox.terminal("12/core/t7"))
        assertEquals("t7", Inbox.terminal("t7"))
    }

    @Test
    fun `a remote terminal keeps its machine, so a write cannot land on a local one`() {
        assertEquals("mac/t7", Inbox.terminal("12/core/mac/t7"))
        val (log, run) = record()
        assertTrue(CoreSource.write(RowAction.PIN, "mac/t7", turn = null, run = run))
        assertEquals(listOf(HerdrApi.loginShell("hc label mac/t7 herdr.pinned=1")), log)
        assertEquals("${CoreSource.PINNED_CMD}; hc get mac/t7 --json", CoreSource.labelsCmd(listOf("mac/t7")))
    }

    @Test
    fun `an address that starts with a dash is not a terminal`() {
        val (log, run) = record()
        assertFalse(CoreSource.write(RowAction.PIN, "-x", turn = null, run = run))
        assertTrue(log.isEmpty())
        assertEquals(CoreSource.PINNED_CMD, CoreSource.labelsCmd(listOf("-x")))
    }

    @Test
    fun `a patch lands only on the row that was acted on, at the turn it was acted on`() {
        val acted = agent(state = AgentState.Done, unread = true, turn = 5).copy(address = "1/core/t7")
        val same = Inbox.row(1, "box", acted.copy(address = "t7"))
        val later = Inbox.row(1, "box", acted.copy(address = "t7", turn = 6))
        val other = Inbox.row(1, "box", acted.copy(address = "t8"))
        assertEquals(false, Inbox.patched(listOf(same, other), acted, RowAction.MARK_READ)[0].agent.unread)
        assertEquals(true, Inbox.patched(listOf(same, other), acted, RowAction.MARK_READ)[1].agent.unread)
        assertEquals(listOf(later), Inbox.patched(listOf(later), acted, RowAction.MARK_READ))
    }

    @Test
    fun `pinned rows come first inside their section`() {
        val rows = listOf(
            Inbox.row(1, "box", agent().copy(address = "t1", since = 1)),
            Inbox.row(1, "box", agent(pinned = true).copy(address = "t2", since = 99)),
        )
        assertEquals(listOf("1/core/t2", "1/core/t1"), Inbox.sections(rows, "", emptySet())[0].rows.map { it.agent.address })
    }
}
