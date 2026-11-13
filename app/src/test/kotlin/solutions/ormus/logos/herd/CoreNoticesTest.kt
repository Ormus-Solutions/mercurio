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

@file:OptIn(ExperimentalStdlibApi::class)

package solutions.ormus.logos.herd

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.connectbot.data.entity.Host
import org.connectbot.transport.CommandOutput
import solutions.ormus.logos.herd.HerdAttention.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notices and Approve or Deny for herdr-core agents, from synthetic terminals. */
class CoreNoticesTest {
    private var now = 0L
    private val attention = HerdAttention(clock = { now })

    private fun agent(
        address: String = "t1",
        state: AgentState,
        kind: String? = "claude-code",
        turn: Int? = 4,
        parent: String? = null,
    ) = Agent(
        address = address,
        engine = AgentEngine.CORE,
        machine = "box",
        space = "alpha · ONE",
        tab = "main",
        kind = kind,
        state = state,
        turn = turn,
        parent = parent,
    )

    private val permission = AgentState.NeedsYou(NeedsYouKind.PERMISSION)

    private fun poll(atMs: Long, vararg agents: Agent): List<Notice> {
        now = atMs
        return attention.onSnapshot(CoreNotices.snapshot(agents.toList()), sessionOnScreen = false)
    }

    @Test
    fun `first snapshot posts nothing`() {
        assertTrue(poll(0, agent(state = permission)).isEmpty())
    }

    @Test
    fun `a core agent that stays blocked gets one Needs you with its place`() {
        poll(0, agent(state = AgentState.Working))
        assertTrue(poll(1_000, agent(state = permission)).isEmpty())
        val notice = poll(7_000, agent(state = permission)).single() as Notice.NeedsYou
        assertEquals("t1", notice.agent.paneId)
        assertEquals("claude-code", notice.agent.agent)
        assertEquals("alpha · ONE", notice.workspaceLabel)
        assertEquals("main", notice.tabLabel)
        assertEquals(1_000L, notice.since)
        assertTrue(poll(70_000, agent(state = permission)).isEmpty())
    }

    @Test
    fun `a core agent that finishes gets one Finished`() {
        poll(0, agent(state = AgentState.Working))
        val notice = poll(4_000, agent(state = AgentState.Done)).single()
        assertTrue(notice is Notice.Finished)
        assertTrue(poll(8_000, agent(state = AgentState.Done)).isEmpty())
    }

    @Test
    fun `a helper never notifies`() {
        poll(0, agent("t2", AgentState.Working, parent = "t1"))
        assertTrue(poll(1_000, agent("t2", permission, parent = "t1")).isEmpty())
        assertTrue(poll(7_000, agent("t2", permission, parent = "t1")).isEmpty())
        assertTrue(poll(8_000, agent("t2", AgentState.Done, parent = "t1")).isEmpty())
    }

    @Test
    fun `Approve and Deny show only for a permission block of a claude agent with a turn`() {
        fun offers(a: Agent) = HerdApproval.offersAnswers(CoreNotices.snapshot(listOf(a)).agents.single())
        assertTrue(offers(agent(state = permission)))
        assertFalse(offers(agent(state = AgentState.NeedsYou(NeedsYouKind.QUESTION))))
        assertFalse(offers(agent(state = AgentState.NeedsYou(NeedsYouKind.AUTH))))
        assertFalse(offers(agent(state = AgentState.NeedsYou(null))))
        assertFalse(offers(agent(state = AgentState.Working)))
        assertFalse(offers(agent(state = permission, kind = "codex")))
        assertFalse(offers(agent(state = permission, turn = null)))
    }

    @Test
    fun `an answer goes only while the turn is unchanged`() {
        val screen = "Run it?\n❯ 1. Yes\n2. No"
        assertNull(CoreNotices.guard(agent(state = permission, turn = 4), turn = 4, screen = screen, prompt = CoreNotices.fingerprint(screen)))
        assertEquals(HerdAnswerOutcome.PromptChanged, CoreNotices.guard(agent(state = permission, turn = 5), turn = 4, screen = null, prompt = null))
        assertEquals(HerdAnswerOutcome.NotBlocked, CoreNotices.guard(agent(state = AgentState.Working, turn = 4), turn = 4, screen = null, prompt = null))
        assertEquals(HerdAnswerOutcome.NotBlocked, CoreNotices.guard(agent(state = AgentState.NeedsYou(NeedsYouKind.QUESTION)), turn = 4, screen = null, prompt = null))
        assertEquals(HerdAnswerOutcome.NotBlocked, CoreNotices.guard(null, turn = 4, screen = null, prompt = null))
    }

    @Test
    fun `a second prompt in the same turn is not the one the notice showed`() {
        val first = "Run tool A?\n❯ 1. Yes\n2. No"
        val second = "Run tool B?\n❯ 1. Yes\n2. No"
        val seen = CoreNotices.fingerprint(first)
        val blocked = agent(state = permission, turn = 4)
        assertNull(CoreNotices.guard(blocked, turn = 4, screen = first, prompt = seen))
        assertEquals(HerdAnswerOutcome.PromptChanged, CoreNotices.guard(blocked, turn = 4, screen = second, prompt = seen))
        // A screen that cannot be read counts as changed when the notice showed one.
        assertEquals(HerdAnswerOutcome.PromptChanged, CoreNotices.guard(blocked, turn = 4, screen = null, prompt = seen))
    }

    @Test
    fun `an answer with no fingerprint of the prompt is refused, even in the same turn`() = runTest {
        val screen = "Run it?\n❯ 1. Yes\n2. No"
        assertEquals(HerdAnswerOutcome.Unproven, CoreNotices.guard(agent(state = permission, turn = 4), turn = 4, screen = screen, prompt = null))
        val core = Core("blocked", kind = "permission", turn = 4)
        assertEquals(HerdAnswerOutcome.Unproven, session(core, enabled = true).answerCore("t1", HerdAnswer.APPROVE, turn = 4, prompt = null))
        assertTrue(core.ran.none { "hc keys" in it }, core.ran.toString())
    }

    @Test
    fun `a core answer goes through a changed status line under the prompt and not through another command`() {
        fun screen(command: String, status: String) = "Bash command\n  $command\nDo you want to proceed?\n❯ 1. Yes\n  2. No\n\nEsc to cancel\n$status"
        val shown = CoreNotices.fingerprint(screen("make", "ctx 0% (0k/1M)"))
        val blocked = agent(state = permission, turn = 4)
        assertNull(CoreNotices.guard(blocked, turn = 4, screen = screen("make", "ctx 9% (90k/1M)"), prompt = shown))
        assertEquals(HerdAnswerOutcome.PromptChanged, CoreNotices.guard(blocked, turn = 4, screen = screen("make clean", "ctx 0% (0k/1M)"), prompt = shown))
    }

    @Test
    fun `a bad address from the host is dropped from the snapshot and refused by answerCore`() = runTest {
        val bad = listOf("--help", "t1; x", "")
        assertTrue(CoreNotices.snapshot(bad.map { agent(address = it, state = permission) }).agents.isEmpty())
        assertFalse(CoreNotices.isAddress("--help"))
        assertTrue(CoreNotices.isAddress("t15"))
        val core = Core("blocked", kind = "permission")
        val s = session(core, enabled = true)
        assertTrue(s.answerCore("--help", HerdAnswer.APPROVE, turn = 4, prompt = null) is HerdAnswerOutcome.Failed)
        assertTrue(core.ran.isEmpty(), core.ran.toString())
    }

    // INFERRED, not measured on herdr-core: the keys are the TUI path's (same agent, same prompt).
    @Test
    fun `the keys pinned - Approve is enter, Deny is esc, through the login shell`() {
        assertEquals("bash -lc 'hc keys t7 enter'", CoreNotices.keysCommand("t7", HerdAnswer.APPROVE))
        assertEquals("bash -lc 'hc keys t7 esc'", CoreNotices.keysCommand("t7", HerdAnswer.DENY))
        assertEquals("bash -lc 'hc get t7 --json'", CoreNotices.getCommand("t7"))
    }

    @Test
    fun `an address that is not a terminal address never reaches a shell`() {
        for (bad in listOf("t1; rm x", "", "t 1", "\$(x)")) {
            assertTrue(runCatching { CoreNotices.keysCommand(bad, HerdAnswer.APPROVE) }.isFailure)
            assertTrue(runCatching { CoreNotices.getCommand(bad) }.isFailure)
        }
    }

    @Test
    fun `the screen is read from hc get`() {
        assertEquals("Run it?\n❯ 1. Yes\n2. No", CoreNotices.screenOf("""{"address":"t1","screen":"Run it?\n❯ 1. Yes\n2. No"}"""))
        assertNull(CoreNotices.screenOf("not json"))
        assertNull(CoreNotices.screenOf("""{"address":"t1"}"""))
    }

    @Test
    fun `the core session is no name Herdr could give`() {
        assertFalse(HerdrSessions.isName(CoreNotices.SESSION))
        assertTrue(CoreNotices.isCore(CoreNotices.SESSION))
        assertFalse(CoreNotices.isCore("work"))
    }

    @Test
    fun `CoreSource reads parent and a single hc get`() {
        val get = """{"address":"t9","parent":"t1","state":"live","status":{"app":"claude-code","state":"blocked","kind":"permission"},"status_ms":1700,"turn":6,"screen":"x"}"""
        val a = CoreSource().parseOne(get, "box", 5_000L)!!
        assertEquals("t1", a.parent)
        assertEquals("claude-code", a.kind)
        assertEquals(6, a.turn)
        assertEquals(AgentState.NeedsYou(NeedsYouKind.PERMISSION), a.state)
        assertNull(CoreSource().parseOne("nope", "box", 0))
    }

    @Test
    fun `CoreSource reads parent from hc ls too`() {
        val ls = """{"terminals":[{"address":"t2","agent":"claude-code","status":"working","turn":1,"where":"alpha · ONE / main","parent":"t1"}]}"""
        val r = CoreSource().read(true, "box", 0L) { if ("--version" in it) CommandOutput("herdr-core 0.1.0", "", 0) else CommandOutput(ls, "", 0) }
        assertEquals("t1", (r as CoreSource.Result.Agents).agents.single().parent)
    }

    // A scripted herdr-core host with one terminal, t1.
    private class Core(var state: String, var kind: String? = null, var turn: Int = 4) {
        val ran = mutableListOf<String>()

        fun run(command: String): CommandOutput? {
            ran += command
            val kindJson = kind?.let { ""","kind":"$it"""" }.orEmpty()
            return when {
                "--version" in command -> CommandOutput("herdr-core 0.1.0\n", "", 0)

                "hc ls" in command -> CommandOutput(
                    """{"terminals":[{"address":"t1","agent":"claude-code","state":"live","status":"$state","turn":$turn,"where":"alpha · ONE / main"}]}""",
                    "",
                    0,
                )

                "hc get" in command -> CommandOutput(
                    """{"address":"t1","state":"live","status":{"app":"claude-code","state":"$state"$kindJson},"turn":$turn,"screen":"Run it?\n❯ 1. Yes\n2. No"}""",
                    "",
                    0,
                )

                else -> CommandOutput("", "", 0)
            }
        }
    }

    private var clockMs = 0L

    private fun TestScope.session(core: Core, enabled: Boolean, posted: MutableList<Notice> = mutableListOf()) = HerdSession(
        hostName = "box",
        runner = core::run,
        sendKeys = {},
        scope = backgroundScope,
        io = coroutineContext[CoroutineDispatcher]!!,
        pollIntervalMs = 2_500,
        clock = { clockMs },
        onNotices = { posted += it },
        coreEnabled = { enabled },
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `setting off runs no hc command`() = runTest {
        val core = Core("working")
        val s = session(core, enabled = false)
        s.start()
        advanceTimeBy(10_000)
        s.stop()
        assertTrue(core.ran.none { "hc " in it || "herdr-core" in it }, core.ran.toString())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the setting is read each poll - off stops hc, on starts it`() = runTest {
        val core = Core("working")
        var on = false
        val s = HerdSession("box", core::run, {}, backgroundScope, coroutineContext[CoroutineDispatcher]!!, pollIntervalMs = 2_500, coreEnabled = { on })
        s.start()
        advanceTimeBy(6_000)
        assertTrue(core.ran.none { "hc " in it || "herdr-core" in it }, core.ran.toString())
        on = true
        advanceTimeBy(6_000)
        assertTrue(core.ran.any { "hc ls" in it })
        core.ran.clear()
        on = false
        advanceTimeBy(6_000)
        s.stop()
        assertTrue(core.ran.none { "hc " in it || "herdr-core" in it }, core.ran.toString())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `each poll leaves its read in coreRead with the time it was taken`() = runTest {
        val core = Core("working")
        val s = session(core, enabled = true)
        assertNull(s.coreRead.value)
        s.start()
        advanceTimeBy(100)
        val first = s.coreRead.value
        assertTrue(first?.result is CoreSource.Result.Agents, first.toString())
        assertEquals(0L, first?.at)
        clockMs = 2_500
        advanceTimeBy(2_500)
        assertEquals(2_500L, s.coreRead.value?.at)
        s.stop()
        assertNull(s.coreRead.value, "a stopped session serves no read")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the read carries the time it began, not the time it ended`() = runTest {
        val core = Core("working")
        // The clock moves while the read runs: a read that began before a write and ended after it must not look newer than the write.
        val run = { cmd: String ->
            if (CoreSource.LIST_CMD in cmd) clockMs += 800
            core.run(cmd)
        }
        val s = HerdSession("box", run, {}, backgroundScope, coroutineContext[CoroutineDispatcher]!!, pollIntervalMs = 2_500, clock = { clockMs }, coreEnabled = { true })
        s.start()
        advanceTimeBy(100)
        assertEquals(800L, clockMs)
        assertEquals(0L, s.coreRead.value?.at)
        s.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start clears the read of the session before it`() = runTest {
        val s = session(Core("working"), enabled = true)
        s.start()
        advanceTimeBy(100)
        assertTrue(s.coreRead.value != null)
        s.start()
        assertNull(s.coreRead.value)
        s.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a read in flight while the session stops is not kept`() = runTest {
        val core = Core("working")
        lateinit var s: HerdSession
        var stopping = true
        val run = { cmd: String ->
            if ("hc ls" in cmd && stopping) {
                stopping = false
                s.stop()
            }
            core.run(cmd)
        }
        s = HerdSession("box", run, {}, backgroundScope, coroutineContext[CoroutineDispatcher]!!, pollIntervalMs = 2_500, clock = { clockMs }, coreEnabled = { true })
        s.start()
        advanceTimeBy(100)
        assertNull(s.coreRead.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a read in flight while the session restarts does not outlive the restart`() = runTest {
        val core = Core("working")
        lateinit var s: HerdSession
        var restarting = true
        val run = { cmd: String ->
            if ("hc ls" in cmd && restarting) {
                restarting = false
                clockMs = 1_000
                s.start()
            }
            core.run(cmd)
        }
        s = HerdSession("box", run, {}, backgroundScope, coroutineContext[CoroutineDispatcher]!!, pollIntervalMs = 2_500, clock = { clockMs }, coreEnabled = { true })
        val seen = mutableListOf<Long?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.coreRead.collect { seen += it?.at } }
        s.start()
        advanceTimeBy(100)
        s.stop()
        assertFalse(0L in seen, "the read begun before the restart was stored: $seen")
        assertTrue(1_000L in seen, "the read after the restart was stored: $seen")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a read that found no herdr-core is kept too, so a reader shows its note`() = runTest {
        val run = { cmd: String -> if ("--version" in cmd) CommandOutput("", "", 127) else CommandOutput("", "", 0) }
        val s = HerdSession("box", run, {}, backgroundScope, coroutineContext[CoroutineDispatcher]!!, pollIntervalMs = 2_500, clock = { clockMs }, coreEnabled = { true })
        s.start()
        advanceTimeBy(100)
        assertEquals(CoreSource.Result.Unavailable(CoreSource.NOT_INSTALLED), s.coreRead.value?.result)
        s.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `setting off leaves no read`() = runTest {
        val s = session(Core("working"), enabled = false)
        s.start()
        advanceTimeBy(10_000)
        s.stop()
        assertNull(s.coreRead.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a host without herdr-core is probed once, and again after a restart of the session`() = runTest {
        val ran = mutableListOf<String>()
        val run = { cmd: String ->
            ran += cmd
            if ("--version" in cmd) CommandOutput("", "", 127) else CommandOutput("", "", 0)
        }
        val s = HerdSession("box", run, {}, backgroundScope, coroutineContext[CoroutineDispatcher]!!, pollIntervalMs = 2_500, clock = { clockMs }, coreEnabled = { true })
        s.start()
        advanceTimeBy(10_000)
        s.stop()
        assertEquals(1, ran.count { "--version" in it })
        s.start()
        advanceTimeBy(10_000)
        s.stop()
        assertEquals(2, ran.count { "--version" in it })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `setting on posts a Needs you with the screen in session core`() = runTest {
        val core = Core("working")
        val posted = mutableListOf<Notice>()
        val s = session(core, enabled = true, posted = posted)
        s.start()
        advanceTimeBy(100)
        core.state = "blocked"
        core.kind = "permission"
        repeat(4) {
            clockMs += 2_500
            advanceTimeBy(2_500)
        }
        s.stop()
        val notice = posted.single() as Notice.NeedsYou
        assertEquals(CoreNotices.SESSION, notice.session)
        assertEquals("t1", notice.agent.paneId)
        assertEquals(4, notice.agent.core?.turn)
        assertEquals("Run it?\n❯ 1. Yes\n2. No", notice.paneTail)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `answer sends the key while the turn holds and nothing when it moved`() = runTest {
        val core = Core("blocked", kind = "permission", turn = 4)
        val s = session(core, enabled = true)
        assertEquals(HerdAnswerOutcome.Sent, s.answerCore("t1", HerdAnswer.APPROVE, turn = 4, prompt = CoreNotices.fingerprint("Run it?\n❯ 1. Yes\n2. No")))
        assertTrue(core.ran.contains("bash -lc 'hc keys t1 enter'"), core.ran.toString())
        core.ran.clear()
        core.turn = 5
        assertEquals(HerdAnswerOutcome.PromptChanged, s.answerCore("t1", HerdAnswer.DENY, turn = 4, prompt = CoreNotices.fingerprint("Run it?\n❯ 1. Yes\n2. No")))
        assertTrue(core.ran.none { "hc keys" in it }, core.ran.toString())
        // The check re-reads the terminal first.
        assertEquals("bash -lc 'hc get t1 --json'", core.ran.first())
    }

    @Test
    fun `the notice route lets one console of the machine post a core notice`() {
        val a = Host(nickname = "n", hostname = "h", username = "u", port = 22)
        val b = a.copy(nickname = "n2")
        val picked = HerdNoticeRoute.poster(listOf(a, b), b, CoreNotices.SESSION, { it }, { "default" }, { true }, { false })
        assertEquals(a, picked)
    }
}
