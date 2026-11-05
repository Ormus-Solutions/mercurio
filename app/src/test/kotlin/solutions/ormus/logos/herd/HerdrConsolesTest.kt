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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * How an extra console of a Herdr host lands on its workspace, and its name.
 *
 * snapshot-picker-test.json is `herdr api snapshot` recorded on Sun (herdr 0.9.0) from a
 * throwaway session: workspaces w1 (tmp), w2 (beta), w3 (gamma), w1 focused.
 */
class HerdrConsolesTest {
    private val snapshot = checkNotNull(parseHerdrSnapshot(herdrFixture("snapshot-picker-test.json")))
    private val keys = HerdrKeys.DEFAULT

    // Herdr's default switcher: prefix (ctrl+b) w, the arrows, Enter.
    private val picker = HerdrStroke.Bytes(2.toChar().toString() + "w")
    private val down = HerdrStroke.Key(HerdrTermKey.DOWN)
    private val up = HerdrStroke.Key(HerdrTermKey.UP)
    private val enter = HerdrStroke.Key(HerdrTermKey.ENTER)

    private fun landing(
        console: HerdrConsole,
        snapshot: HerdrSessionSnapshot? = this.snapshot,
        keys: HerdrKeys = this.keys,
        crossesMachines: Boolean = false,
        shared: Boolean = true,
    ) = HerdrConsoles.landing(console, snapshot, keys, crossesMachines, shared)

    @Test
    fun landing_asIsWithoutAWorkspace_orWhereTheFocusAlreadyIs() {
        assertEquals(HerdrLanding.AsIs, landing(HerdrConsole("beta")))
        assertEquals(HerdrLanding.AsIs, landing(HerdrConsole("beta", "w1", "tmp")))
        assertEquals(HerdrLanding.AsIs, landing(HerdrConsole("beta", "w1", "tmp"), shared = false))
    }

    @Test
    fun landing_theFocus_whileNoOtherConsoleShowsTheSession() {
        assertEquals(HerdrLanding.Focus("w3"), landing(HerdrConsole("beta", "w3", "gamma"), shared = false))
    }

    @Test
    fun landing_switcherKeysFromTheFocusedWorkspace_whenAnotherConsoleShowsTheSession() {
        // A new client opens on w1, the session's focus: two steps down to w3.
        assertEquals(HerdrLanding.Keys(listOf(picker, down, down, enter)), landing(HerdrConsole("beta", "w3", "gamma")))
        // With w3 focused, w1 is two steps up.
        val onGamma = snapshot.copy(focusedWorkspaceId = "w3")
        assertEquals(HerdrLanding.Keys(listOf(picker, up, up, enter)), landing(HerdrConsole("beta", "w1", "tmp"), snapshot = onGamma))
        // The sidebar order is the workspace numbers, whatever order the snapshot lists them in.
        val shuffled = snapshot.copy(workspaces = snapshot.workspaces.reversed())
        assertEquals(HerdrLanding.Keys(listOf(picker, down, enter)), landing(HerdrConsole("beta", "w2", "beta"), snapshot = shuffled))
    }

    @Test
    fun landing_theFocus_whenTheKeysCannotCountTheSteps() {
        val console = HerdrConsole("beta", "w3", "gamma")
        assertEquals(HerdrLanding.Focus("w3"), landing(console, snapshot = null))
        assertEquals(HerdrLanding.Focus("w3"), landing(console, snapshot = snapshot.copy(focusedWorkspaceId = null)))
        // Herdr's switcher walks saved machines' workspaces too: the count would be off.
        assertEquals(HerdrLanding.Focus("w3"), landing(console, crossesMachines = true))
        assertEquals(HerdrLanding.Focus("w9"), landing(HerdrConsole("beta", "w9", "gone")))
        assertEquals(HerdrLanding.Focus("w3"), landing(console, keys = HerdrKeys.fromConfig("[keys]\nworkspace_picker = \"cmd+w\"\n")))
    }

    @Test
    fun console_refusesANameHerdrWouldNotGive() {
        assertThrows(IllegalArgumentException::class.java) { HerdrConsole("beta; rm -rf ~") }
        assertThrows(IllegalArgumentException::class.java) { HerdrConsole("") }
    }

    @Test
    fun label_hostThenSessionThenWorkspace() {
        assertEquals("box", HerdrConsoles.label("box", HerdrCommands.DEFAULT_SESSION, null))
        assertEquals("box · beta", HerdrConsoles.label("box", "beta", null))
        assertEquals("box · beta · gamma", HerdrConsoles.label("box", "beta", HerdrConsole("beta", "w3", "gamma")))
        assertEquals("box · beta", HerdrConsoles.label("box", "beta", HerdrConsole("beta")))
        assertEquals("box · tmp", HerdrConsoles.label("box", HerdrCommands.DEFAULT_SESSION, HerdrConsole(HerdrCommands.DEFAULT_SESSION, "w1", "tmp")))
        // A jump attached the console to another session: its opening workspace no longer applies.
        assertEquals("box · alpha", HerdrConsoles.label("box", "alpha", HerdrConsole("beta", "w3", "gamma")))
    }

    /** Runs [HerdrConsoles.land] with a real IO pool, recording each command. */
    private class Land(private val shared: Boolean, private val run: (String) -> CommandOutput?) {
        val commands = mutableListOf<String>()

        fun land(console: HerdrConsole): HerdrLanding = runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                HerdrConsoles.land(
                    console = console,
                    scope = scope,
                    io = Dispatchers.IO,
                    run = { command, _ ->
                        synchronized(commands) { commands += command }
                        run(command)
                    },
                    keys = HerdrKeys.DEFAULT,
                    crossesMachines = false,
                    shared = shared,
                )
            } finally {
                scope.cancel()
            }
        }
    }

    private val snapshotCommand = HerdrSessions.snapshotCommand("beta")
    private val ok = CommandOutput(stdout = herdrFixture("snapshot-picker-test.json"), stderr = "", exitCode = 0)

    private fun focusCommand(workspaceId: String) = (HerdrSessions.commands("beta").build(HerdrAction.FocusWorkspace(workspaceId), snapshot) as HerdrInvocation.Cli).command

    @Test
    fun land_focusesTheWorkspaceInItsSessionBeforeReturning() {
        val land = Land(shared = false) { if (it == snapshotCommand) ok else CommandOutput("", "", 0) }
        assertEquals(HerdrLanding.Focus("w3"), land.land(HerdrConsole("beta", "w3", "gamma")))
        assertEquals(listOf(snapshotCommand, focusCommand("w3")), land.commands)
    }

    @Test
    fun land_switcherKeysRunNoFocus() {
        val land = Land(shared = true) { if (it == snapshotCommand) ok else CommandOutput("", "", 0) }
        assertEquals(HerdrLanding.Keys(listOf(picker, down, down, enter)), land.land(HerdrConsole("beta", "w3", "gamma")))
        assertEquals(listOf(snapshotCommand), land.commands)
    }

    @Test
    fun land_noWorkspaceRunsNothing() {
        val land = Land(shared = true) { ok }
        assertEquals(HerdrLanding.AsIs, land.land(HerdrConsole("beta")))
        assertEquals(emptyList<String>(), land.commands)
    }

    @Test
    fun land_aSnapshotThatFailsStillLandsByTheFocus() {
        val land = Land(shared = true) { command ->
            if (command == snapshotCommand) throw IllegalStateException("exec channel closed") else CommandOutput("", "", 0)
        }
        assertEquals(HerdrLanding.Focus("w3"), land.land(HerdrConsole("beta", "w3", "gamma")))
        assertEquals(snapshotCommand, land.commands.first())
        assertEquals(2, land.commands.size)
    }

    @Test
    fun landing_aLabelWithNoIdLandsOnTheWorkspaceOfThatLabel() {
        val gamma = HerdrConsole("beta", workspaceLabel = "gamma")
        assertEquals(HerdrLanding.Focus("w3"), landing(gamma, shared = false))
        assertEquals(HerdrLanding.Keys(listOf(picker, down, down, enter)), landing(gamma))
        // Where the focus already is, or a label the session does not have: attach as it is.
        assertEquals(HerdrLanding.AsIs, landing(HerdrConsole("beta", workspaceLabel = "tmp")))
        assertEquals(HerdrLanding.AsIs, landing(HerdrConsole("beta", workspaceLabel = "gone")))
        assertEquals(HerdrLanding.AsIs, landing(gamma, snapshot = null))
    }

    @Test
    fun land_aLabelReadsTheSnapshotThenFocuses() {
        val land = Land(shared = false) { if (it == snapshotCommand) ok else CommandOutput("", "", 0) }
        assertEquals(HerdrLanding.Focus("w3"), land.land(HerdrConsole("beta", workspaceLabel = "gamma")))
        assertEquals(listOf(snapshotCommand, focusCommand("w3")), land.commands)
    }

    @Test
    fun parseList_oneConsolePerLine_sessionThenWorkspaceLabel() {
        // The first word is the session (a name Herdr gives, or the line is left out); the rest is a
        // label, matched against Herdr's workspace labels and shown, never run.
        val raw = """
            # the boards
            work
            lab   checks and lint
            review${"\t"}draft
            lab   checks and lint
            bad;name rm -rf
            # end
        """.trimIndent()
        assertEquals(
            listOf(
                HerdrConsole("work"),
                HerdrConsole("lab", workspaceLabel = "checks and lint"),
                HerdrConsole("review", workspaceLabel = "draft"),
            ),
            HerdrConsoles.parseList(raw),
        )
        assertEquals(emptyList<HerdrConsole>(), HerdrConsoles.parseList(""))
        assertEquals(HerdrConsoles.MAX_ON_CONNECT, HerdrConsoles.parseList((1..20).joinToString("\n") { "s$it" }).size)
    }

    /** Runs [HerdrConsoles.onConnect] with a real IO pool against [answers], recording each command. */
    private fun onConnect(answers: (String) -> CommandOutput?): Pair<List<HerdrConsole>?, List<String>> = runBlocking {
        val commands = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            HerdrConsoles.onConnect(scope, Dispatchers.IO) { command, _ ->
                synchronized(commands) { commands += command }
                answers(command)
            } to commands
        } finally {
            scope.cancel()
        }
    }

    // `herdr session list --json`: default, work and review run; lab is stopped.
    private val sessionList = CommandOutput(
        stdout = """{"sessions":[{"name":"default","running":true},{"name":"work","running":true},""" +
            """{"name":"lab","running":false},{"name":"review","running":true}]}""",
        stderr = "",
        exitCode = 0,
    )

    @Test
    fun onConnect_theListedSessionsThatRun_inTheListsOrder() {
        val list = CommandOutput("review\nlab\nwork main\n", "", 0)
        val (consoles, commands) = onConnect { if (it == HerdrConsoles.LIST_COMMAND) list else sessionList }
        assertEquals(listOf(HerdrConsole("review"), HerdrConsole("work", workspaceLabel = "main")), consoles)
        assertEquals(listOf(HerdrConsoles.LIST_COMMAND, HerdrSessions.listCommand), commands)
    }

    /** Runs [HerdrConsoles.read] with a real IO pool against [answers], recording each command. */
    private fun read(answers: (String) -> CommandOutput?): Pair<List<HerdrConsole>?, List<String>> = runBlocking {
        val commands = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            HerdrConsoles.read(scope, Dispatchers.IO) { command, _ ->
                synchronized(commands) { commands += command }
                answers(command)
            } to commands
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun read_whatTheHostLists_emptyWithNoFile_nullWhenTheReadDidNotAnswer() {
        // No file: cat prints nothing and exits 0 (|| true). Only the list is read: stopped sessions stay listed.
        val (none, commands) = read { if (it == HerdrConsoles.LIST_COMMAND) CommandOutput("", "", 0) else sessionList }
        assertEquals(emptyList<HerdrConsole>(), none)
        assertEquals(listOf(HerdrConsoles.LIST_COMMAND), commands)
        val (listed, read) = read { if (it == HerdrConsoles.LIST_COMMAND) CommandOutput("review\nlab\nwork main\n", "", 0) else sessionList }
        assertEquals(listOf(HerdrConsole("review"), HerdrConsole("lab"), HerdrConsole("work", workspaceLabel = "main")), listed)
        assertEquals(listOf(HerdrConsoles.LIST_COMMAND), read)
        // A read that fails, does not answer, or ends with no exit status (a dropped channel) says nothing.
        assertEquals(null, read { if (it == HerdrConsoles.LIST_COMMAND) CommandOutput("", "", 1) else sessionList }.first)
        assertEquals(null, read { if (it == HerdrConsoles.LIST_COMMAND) null else sessionList }.first)
        assertEquals(null, read { if (it == HerdrConsoles.LIST_COMMAND) CommandOutput("", "", null) else sessionList }.first)
    }

    @Test
    fun running_keepsTheListedSessionsThatRun_nullWhenTheSessionListDidNotAnswer() = runBlocking {
        val listed = listOf(HerdrConsole("review"), HerdrConsole("lab"), HerdrConsole("work", workspaceLabel = "main"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val running = { answer: CommandOutput? -> { _: String, _: Long -> answer } }
            assertEquals(listOf(HerdrConsole("review"), HerdrConsole("work", workspaceLabel = "main")), HerdrConsoles.running(listed, scope, Dispatchers.IO, running(sessionList)))
            assertEquals(emptyList<HerdrConsole>(), HerdrConsoles.running(listOf(HerdrConsole("lab")), scope, Dispatchers.IO, running(sessionList)))
            assertEquals(null, HerdrConsoles.running(listed, scope, Dispatchers.IO, running(null)))
            assertEquals(null, HerdrConsoles.running(listed, scope, Dispatchers.IO, running(CommandOutput("", "", null))))
            // Nothing listed: nothing to ask the host.
            assertEquals(emptyList<HerdrConsole>(), HerdrConsoles.running(emptyList(), scope, Dispatchers.IO) { _, _ -> error("not asked") })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onConnect_nothingWithoutAList_soThePickerRuns() {
        // No file: cat prints nothing (2>/dev/null); the session list is never read.
        val (none, commands) = onConnect { if (it == HerdrConsoles.LIST_COMMAND) CommandOutput("", "", 1) else sessionList }
        assertEquals(null, none)
        assertEquals(listOf(HerdrConsoles.LIST_COMMAND), commands)
        // Only stopped sessions, or a session list that fails: the picker too.
        assertEquals(null, onConnect { if (it == HerdrConsoles.LIST_COMMAND) CommandOutput("lab\n", "", 0) else sessionList }.first)
        assertEquals(null, onConnect { if (it == HerdrConsoles.LIST_COMMAND) CommandOutput("work\n", "", 0) else null }.first)
    }
}
