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

package org.connectbot.ui.screens.inbox

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.transport.CommandOutput
import org.connectbot.transport.HeadlessSession
import org.connectbot.transport.HeadlessSsh
import org.connectbot.util.PreferenceConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.AgentSince
import solutions.ormus.logos.herd.CoreLinks
import solutions.ormus.logos.herd.CoreRead
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrTabInfo
import solutions.ormus.logos.herd.HerdrWorkspaceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What the Inbox reads from the connections: the Herdr rows at once, the herdr-core reads host by host. */
class InboxViewModelTest {
    private val unconfined = UnconfinedTestDispatcher()
    private val release = CountDownLatch(1)
    private val blocked = AtomicInteger(0)
    private val seen = java.util.concurrent.atomic.AtomicBoolean(false)

    /** A command that never answers until the test ends. */
    private fun hang(): CommandOutput? {
        blocked.incrementAndGet()
        try {
            release.await()
        } finally {
            blocked.decrementAndGet()
        }
        return null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(unconfined)
    }

    private val made = mutableListOf<InboxViewModel>()

    @After
    fun tearDown() {
        // The scope goes first: a read freed after Main is reset must not land anywhere.
        made.forEach { it.viewModelScope.cancel() }
        release.countDown()
        // Let a freed read leave its thread before Main goes away, or it fails in the next test.
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (blocked.get() > 0 && System.nanoTime() < end) Thread.sleep(5)
        Thread.sleep(100)
        Dispatchers.resetMain()
    }

    // What the Inbox wrote with putString, by key.
    private val written = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun prefs(core: Boolean): SharedPreferences {
        val p = mock(SharedPreferences::class.java)
        `when`(p.getBoolean(PreferenceConstants.HERDR_CORE, PreferenceConstants.HERDR_CORE_DEFAULT)).thenReturn(core)
        `when`(p.getBoolean(PreferenceConstants.HERDR_CORE_SEEN, false)).thenReturn(seen.get())
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(p.edit()).thenReturn(editor)
        `when`(editor.putString(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenAnswer {
            written[it.getArgument(0)] = it.getArgument(1)
            editor
        }
        `when`(editor.putBoolean(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenAnswer {
            if (it.getArgument<String>(0) == PreferenceConstants.HERDR_CORE_SEEN) seen.set(it.getArgument(1))
            editor
        }
        return p
    }

    private fun bridge(id: Long, nickname: String, coreRead: CoreRead? = null, hostname: String = "box$id", exec: (String) -> CommandOutput?): Pair<TerminalBridge, HerdSession> {
        val snapshot = HerdrSessionSnapshot(
            workspaces = listOf(HerdrWorkspaceInfo("w1", label = "alpha · $nickname", number = 1)),
            tabs = listOf(HerdrTabInfo("w1:t1", workspaceId = "w1", label = "main", number = 1)),
            agents = listOf(HerdrAgentInfo(paneId = "w1:p1", workspaceId = "w1", tabId = "w1:t1", agent = "claude", agentStatus = HerdrAgentStatus.BLOCKED)),
        )
        val herd = mock(HerdSession::class.java)
        `when`(herd.snapshot).thenReturn(MutableStateFlow(snapshot))
        `when`(herd.sessions).thenReturn(MutableStateFlow(listOf("default")))
        `when`(herd.others).thenReturn(MutableStateFlow(emptyMap()))
        `when`(herd.since).thenReturn(AgentSince(clock = { 0L }))
        `when`(herd.coreRead).thenReturn(MutableStateFlow(coreRead))
        `when`(herd.exec(org.mockito.kotlin.any())).thenAnswer { exec(it.getArgument(0)) }
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(id = id, nickname = nickname, hostname = hostname, username = "u"))
        `when`(bridge.herd).thenReturn(herd)
        return bridge to herd
    }

    private var logins = AtomicInteger(0)
    private val closed = AtomicInteger(0)

    /** A login that always works and runs [exec]: nothing here reaches a network. */
    private fun fakeLinks(saved: List<Host>, exec: (String) -> CommandOutput?, skip: String? = null, live: (() -> List<Host>)? = null) = CoreLinks(
        hosts = live ?: { saved },
        login = {
            logins.incrementAndGet()
            if (skip != null) {
                CoreLinks.Opened.Skipped(skip)
            } else {
                CoreLinks.Opened.Session(
                    object : HeadlessSession {
                        override fun login(keys: List<java.security.KeyPair>) = HeadlessSsh.Login.Ok

                        override fun exec(command: String) = exec(command) ?: CommandOutput("", "", 1)

                        override fun close() {
                            closed.incrementAndGet()
                        }
                    },
                )
            }
        },
    )

    private fun viewModel(core: Boolean, vararg bridges: TerminalBridge, links: CoreLinks = fakeLinks(bridges.map { it.host }, { null })): InboxViewModel {
        val manager = mock(TerminalManager::class.java)
        `when`(manager.bridgesFlow).thenReturn(MutableStateFlow(bridges.toList()))
        return InboxViewModel(prefs(core), CoroutineDispatchers(unconfined, Dispatchers.IO, unconfined), links).also {
            it.setTerminalManager(manager)
            made += it
        }
    }

    private val version = CommandOutput("herdr-core 0.1.0\n", "", 0)
    private val oneTerminal = CommandOutput("""{"terminals":[{"address":"t1","agent":"codex","status":"idle","where":"beta · TWO / main"}]}""", "", 0)

    private fun answers(cmd: String) = when {
        "--version" in cmd -> version
        solutions.ormus.logos.herd.CoreSource.PINNED_CMD in cmd -> CommandOutput("", "", 0)
        else -> oneTerminal
    }

    private fun awaitState(vm: InboxViewModel, ok: (InboxViewModel.State) -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ok(vm.state.value) && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("state never matched: ${vm.state.value}", ok(vm.state.value))
    }

    @Test
    fun theHerdrRowsShowAtOnce_andTheCoreIsNotReadWhenItsSettingIsOff() = runTest {
        val (b, herd) = bridge(1L, "one") { answers(it) }
        val vm = viewModel(false, b)
        vm.refresh()
        assertEquals(1, vm.state.value.rows.size)
        verify(herd, never()).exec(org.mockito.kotlin.any())
    }

    private fun saved(id: Long, nickname: String, hostname: String = "box", post: String? = null) = Host(id = id, nickname = nickname, hostname = hostname, username = "u", postLogin = post)

    private val core = solutions.ormus.logos.herd.AgentEngine.CORE

    @Test
    fun coreRowsAppear_withNoOpenConsole_throughAHeadlessLogin() = runTest {
        val vm = viewModel(true, links = fakeLinks(listOf(saved(1, "one")), { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core && it.host == "one" } }
        assertEquals(1, logins.get())
    }

    @Test
    fun fiveSavedRowsOfOneMachine_areOneLogin_andOneSetOfRows() = runTest {
        val hosts = listOf(saved(1, "shell"), saved(2, "plain", post = "herdr"), saved(3, "a", post = "herdr session attach 'x'"), saved(4, "b", post = "herdr session attach 'y'"), saved(5, "c", post = "herdr session attach 'z'"))
        val vm = viewModel(true, links = fakeLinks(hosts, { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        assertEquals(1, vm.state.value.rows.count { it.agent.engine == core })
        // A tap opens the plain herdr row.
        assertEquals(2L, vm.state.value.rows.first { it.agent.engine == core }.hostId)
        assertEquals(1, logins.get())
    }

    @Test
    fun aMachineThatCannotBeLoggedInto_isSkippedWithItsReason() = runTest {
        val vm = viewModel(true, links = fakeLinks(listOf(saved(1, "one")), { answers(it) }, skip = "no key that opens without asking"))
        vm.refresh()
        awaitState(vm) { it.notes.isNotEmpty() }
        assertEquals(listOf("one: no key that opens without asking"), vm.state.value.notes)
        assertTrue(vm.state.value.rows.none { it.agent.engine == core })
    }

    @Test
    fun theVersionProbe_isAskedOnce_andTheLoginIsHeldAcrossPolls() = runTest {
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val vm = viewModel(
            true,
            links = fakeLinks(listOf(saved(1, "one")), {
                log += it
                answers(it)
            }),
        )
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        vm.refresh()
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (log.count { "hc ls" in it } < 2 && System.nanoTime() < end) Thread.sleep(5)
        assertEquals(1, log.count { "--version" in it })
        assertEquals(1, logins.get())
    }

    @Test
    fun aHostWithoutCore_isNotProbedAgainOnPolls_butIsWhenTheInboxOpens() = runTest {
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val vm = viewModel(
            true,
            links = fakeLinks(listOf(saved(1, "one")), {
                log += it
                if ("--version" in it) CommandOutput("", "", 127) else answers(it)
            }),
        )
        vm.refresh()
        awaitState(vm) { it.notes.isNotEmpty() }
        vm.refresh()
        Thread.sleep(200)
        assertEquals(1, log.count { "--version" in it })
        val watching = launch(Dispatchers.Default) { vm.watch() }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (log.count { "--version" in it } < 2 && System.nanoTime() < end) Thread.sleep(10)
        watching.cancel()
        assertEquals(2, log.count { "--version" in it })
    }

    @Test
    fun anOpenConsole_isUsedInsteadOfALogin_andRowsAreNotDoubled() = runTest {
        val (b, _) = bridge(1L, "one") { answers(it) }
        val vm = viewModel(true, b, links = fakeLinks(listOf(saved(1, "one", "box1"), saved(2, "other", "box1")).map { it.copy(hostname = "box1") }, { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        Thread.sleep(100)
        assertEquals(1, vm.state.value.rows.count { it.agent.engine == core })
        assertEquals(0, logins.get())
    }

    @Test
    fun leavingTheScreen_logsOutOfEveryMachine() = runTest {
        val links = fakeLinks(listOf(saved(1, "one")), { answers(it) })
        val vm = viewModel(true, links = links)
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        links.close()
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (closed.get() < 1 && System.nanoTime() < end) Thread.sleep(5)
        assertEquals(1, closed.get())
    }

    @Test
    fun aHostThatAnswersTheProbe_isRememberedSoTheNextLaunchOpensTheInbox() = runTest {
        val (b, _) = bridge(1L, "one") { answers(it) }
        val vm = viewModel(true, b)
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == solutions.ormus.logos.herd.AgentEngine.CORE } }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!seen.get() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(seen.get())
    }

    @Test
    fun aHostThatNeverAnswers_doesNotHoldBackTheOthers() = runTest {
        val (stuck, _) = bridge(1L, "stuck") { hang() }
        val (fine, _) = bridge(2L, "fine") { answers(it) }
        val vm = viewModel(true, stuck, fine)
        vm.refresh()
        // Both hosts' Herdr rows are there already; the free host's core row follows.
        awaitState(vm) { s -> s.rows.count { it.agent.engine == solutions.ormus.logos.herd.AgentEngine.CORE && it.host == "fine" } == 1 }
        assertEquals(2, vm.state.value.rows.count { it.agent.engine == solutions.ormus.logos.herd.AgentEngine.TUI })
    }

    @Test
    fun aHostIsReadOneAtATime() = runTest {
        val (stuck, herd) = bridge(1L, "stuck") { hang() }
        val vm = viewModel(true, stuck)
        vm.refresh()
        vm.refresh()
        vm.refresh()
        // The read starts on another thread: wait until it is inside the hung command, else the check races it.
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (blocked.get() < 1 && System.nanoTime() < end) Thread.sleep(5)
        verify(herd, times(1)).exec(org.mockito.kotlin.any())
    }

    @Test
    fun aReadThatThrows_becomesANote_notACrash() = runTest {
        val (bad, _) = bridge(1L, "bad") { error("boom") }
        val vm = viewModel(true, bad)
        vm.refresh()
        awaitState(vm) { it.notes.isNotEmpty() }
        assertEquals(listOf("bad: boom"), vm.state.value.notes)
        assertEquals(1, vm.state.value.rows.size)
    }

    // The herdr-core read the Herd session of the host took (the Inbox reads the same host: one poll, not two).
    private fun sessionRead(name: String, ageMs: Long = 0L) = CoreRead(CoreSource().read(true, name, System.currentTimeMillis()) { answers(it) }, System.currentTimeMillis() - ageMs)

    @Test
    fun aFreshReadOfTheHerdSession_isTheInboxsRead_andNoCommandRuns() = runTest {
        val (b, herd) = bridge(1L, "one", coreRead = sessionRead("one")) { answers(it) }
        val vm = viewModel(true, b, links = fakeLinks(listOf(saved(1, "one", "box1")), { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core && it.host == "one" } }
        Thread.sleep(100)
        assertEquals(1, vm.state.value.rows.count { it.agent.engine == core })
        verify(herd, never()).exec(org.mockito.kotlin.any())
        assertEquals(0, logins.get())
    }

    @Test
    fun aReadOfTheHerdSessionThatIsTooOld_isNotUsed_theInboxReadsTheHostItself() = runTest {
        val (b, herd) = bridge(1L, "one", coreRead = sessionRead("one", ageMs = InboxViewModel.SESSION_READ_FRESH_MS + 1_000)) { answers(it) }
        val vm = viewModel(true, b, links = fakeLinks(listOf(saved(1, "one", "box1")), { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        verify(herd, atLeastOnce()).exec(org.mockito.kotlin.any())
    }

    @Test
    fun aSessionReadThatIsNotAgents_isNotUsed_soAnInstallShowsAtOnce() = runTest {
        // The session cached "not installed" and keeps storing that answer; the host has herdr-core now.
        val answers = listOf(CoreSource.NOT_INSTALLED, "could not reach the host", "herdr-core did not answer", "herdr-core is not running (hc ls exit 1)")
        for (why in answers) {
            val unavailable = CoreRead(CoreSource.Result.Unavailable(why), System.currentTimeMillis())
            val (b, herd) = bridge(1L, "one", coreRead = unavailable) { answers(it) }
            val vm = viewModel(true, b, links = fakeLinks(listOf(saved(1, "one", "box1")), { answers(it) }))
            vm.refresh()
            awaitState(vm) { s -> s.rows.any { it.agent.engine == core && it.host == "one" } }
            verify(herd, atLeastOnce()).exec(org.mockito.kotlin.any())
            assertTrue(why, vm.state.value.notes.none { why in it })
        }
    }

    @Test
    fun aReadOfTheHerdSessionDatedInTheFuture_isNotUsed() = runTest {
        val (b, herd) = bridge(1L, "one", coreRead = sessionRead("one", ageMs = -60_000)) { answers(it) }
        val vm = viewModel(true, b, links = fakeLinks(listOf(saved(1, "one", "box1")), { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        verify(herd, atLeastOnce()).exec(org.mockito.kotlin.any())
    }

    // A read of one terminal at [address], as the Herd session of a host would have taken it [ageMs] ago.
    private fun terminalRead(name: String, address: String, ageMs: Long = 0L) = CoreRead(
        CoreSource().read(true, name, System.currentTimeMillis()) { cmd ->
            when {
                "--version" in cmd -> version
                CoreSource.PINNED_CMD in cmd -> CommandOutput("", "", 0)
                else -> CommandOutput("""{"terminals":[{"address":"$address","agent":"codex","status":"idle","where":"beta · TWO / main"}]}""", "", 0)
            }
        },
        System.currentTimeMillis() - ageMs,
    )

    private fun addressesOf(vm: InboxViewModel, host: String) = vm.state.value.rows.filter { it.agent.engine == core && it.host == host }.map { it.agent.address }

    @Test
    fun twoMachinesWithOneSessionEach_keepEachOthersTerminalsApart() = runTest {
        val (a, _) = bridge(1L, "one", coreRead = terminalRead("one", "t1")) { answers(it) }
        val (b, _) = bridge(2L, "two", coreRead = terminalRead("two", "t2")) { answers(it) }
        val vm = viewModel(true, a, b, links = fakeLinks(listOf(saved(1, "one", "box1"), saved(2, "two", "box2")), { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.count { it.agent.engine == core } == 2 }
        assertEquals(listOf("t1"), addressesOf(vm, "one"))
        assertEquals(listOf("t2"), addressesOf(vm, "two"))
    }

    @Test
    fun ofTwoSessionReadsOfOneMachine_theNewestIsTaken() = runTest {
        val (older, _) = bridge(1L, "one", coreRead = terminalRead("one", "t1", ageMs = 3_000), hostname = "shared") { answers(it) }
        val (newer, _) = bridge(2L, "two", coreRead = terminalRead("two", "t2"), hostname = "shared") { answers(it) }
        val vm = viewModel(true, older, newer, links = fakeLinks(listOf(saved(1, "one", "shared")), { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        assertEquals(listOf("t2"), addressesOf(vm, "one"))
    }

    @Test
    fun aSharedRead_writesTheProbeAndTheVersionNotes() = runTest {
        val (b, herd) = bridge(1L, "one", coreRead = sessionRead("one")) { answers(it) }
        `when`(herd.coreVersion).thenReturn("herdr-core 0.1.0")
        val vm = viewModel(true, b, links = fakeLinks(listOf(saved(1, "one", "box1")), { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        assertTrue("the probe says herdr-core was seen", seen.get())
        assertEquals("0.1.0", written[PreferenceConstants.HERDR_CORE_VERSION])
    }

    @Test
    fun aGoodSharedRead_clearsTheFailedWriteLine() = runTest {
        val (b, herd) = bridge(1L, "one") { if ("hc label" in it) CommandOutput("", "no", 1) else answers(it) }
        val vm = viewModel(true, b, links = fakeLinks(listOf(saved(1, "one", "box1")), { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        vm.act(coreRow(vm), solutions.ormus.logos.herd.RowAction.PIN)
        awaitState(vm) { s -> s.notes.any { "could not write" in it } }
        Thread.sleep(5)
        (herd.coreRead as MutableStateFlow<CoreRead?>).value = sessionRead("one")
        vm.refresh()
        awaitState(vm) { s -> s.notes.none { "could not write" in it } }
    }

    @Test
    fun aReadOfTheHerdSessionTakenBeforeAWrite_isNotUsed_soThePatchStays() = runTest {
        val old = sessionRead("one", ageMs = 1_000)
        val pinned = java.util.concurrent.atomic.AtomicBoolean(false)
        // The host keeps the label once written, as the real one does; the session's read predates it.
        val (b, _) = bridge(1L, "one", coreRead = old) { cmd ->
            when {
                "hc label" in cmd -> CommandOutput("", "", 0).also { pinned.set(true) }
                CoreSource.PINNED_CMD in cmd -> CommandOutput(if (pinned.get()) """{"terminals":[{"address":"t1"}]}""" else "", "", 0)
                else -> answers(cmd)
            }
        }
        val vm = viewModel(true, b, links = fakeLinks(listOf(saved(1, "one", "box1")), { answers(it) }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        vm.act(coreRow(vm), solutions.ormus.logos.herd.RowAction.PIN)
        awaitState(vm) { s -> s.rows.first { it.agent.engine == core }.agent.pinned }
        // The session's read still says "not pinned" and is older than the write: the next refresh must not use it.
        vm.refresh()
        Thread.sleep(100)
        assertTrue(vm.state.value.rows.first { it.agent.engine == core }.agent.pinned)
    }

    private fun coreRow(vm: InboxViewModel) = vm.state.value.rows.first { it.agent.engine == solutions.ormus.logos.herd.AgentEngine.CORE }

    @Test
    fun aPin_isWrittenThroughTheLoginShell_andShowsAtOnce() = runTest {
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val (b, _) = bridge(1L, "one") {
            log += it
            answers(it)
        }
        val vm = viewModel(true, b)
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == solutions.ormus.logos.herd.AgentEngine.CORE } }
        vm.act(coreRow(vm), solutions.ormus.logos.herd.RowAction.PIN)
        awaitState(vm) { s -> s.rows.first { it.agent.engine == solutions.ormus.logos.herd.AgentEngine.CORE }.agent.pinned }
        assertTrue(solutions.ormus.logos.herd.HerdrApi.loginShell("hc label t1 herdr.pinned=1") in log)
    }

    @Test
    fun aWriteThatFails_leavesTheRowAndSaysSo() = runTest {
        val (b, _) = bridge(1L, "one") { if ("hc label" in it) CommandOutput("", "no", 1) else answers(it) }
        val vm = viewModel(true, b)
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == solutions.ormus.logos.herd.AgentEngine.CORE } }
        vm.act(coreRow(vm), solutions.ormus.logos.herd.RowAction.PIN)
        awaitState(vm) { it.notes.any { n -> n.startsWith("one:") } }
        assertEquals(false, coreRow(vm).agent.pinned)
    }

    @Test
    fun anInterrupt_pressesEsc_andChangesNoRow() = runTest {
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val (b, _) = bridge(1L, "one") {
            log += it
            answers(it)
        }
        val vm = viewModel(true, b)
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == solutions.ormus.logos.herd.AgentEngine.CORE } }
        val before = coreRow(vm)
        vm.act(before, solutions.ormus.logos.herd.RowAction.INTERRUPT)
        awaitState(vm) { _ -> solutions.ormus.logos.herd.HerdrApi.loginShell("hc keys t1 esc") in log }
        assertEquals(before, coreRow(vm))
    }

    @Test
    fun aTuiRow_isNeverWritten() = runTest {
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val (b, _) = bridge(1L, "one") {
            log += it
            answers(it)
        }
        val vm = viewModel(false, b)
        vm.refresh()
        vm.act(vm.state.value.rows.first(), solutions.ormus.logos.herd.RowAction.PIN)
        Thread.sleep(100)
        assertTrue(log.isEmpty())
    }

    @Test
    fun aFailedWriteNote_goesWhenTheInboxIsOpenedAgain() = runTest {
        val (b, _) = bridge(1L, "one") { if ("hc label" in it) CommandOutput("", "no", 1) else answers(it) }
        val vm = viewModel(true, b)
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == solutions.ormus.logos.herd.AgentEngine.CORE } }
        vm.act(coreRow(vm), solutions.ormus.logos.herd.RowAction.PIN)
        awaitState(vm) { it.notes.any { n -> n.startsWith("one:") } }
        backgroundScope.launch(unconfined) { vm.watch() }
        assertTrue(vm.state.value.notes.none { it.contains("could not write") })
    }

    @Test
    fun theInboxIsLoading_untilTheFirstReadCompletes() = runTest {
        val vm = viewModel(true, links = fakeLinks(listOf(saved(1, "one")), { answers(it) }))
        assertEquals(false, vm.state.value.loaded)
        vm.refresh()
        awaitState(vm) { it.loaded }
        assertTrue(vm.state.value.rows.any { it.agent.engine == core })
    }

    @Test
    fun aFailingRead_isLoaded_andCarriesItsFailure() = runTest {
        val vm = viewModel(true, links = fakeLinks(listOf(saved(1, "one")), { answers(it) }, skip = "no key"))
        vm.refresh()
        awaitState(vm) { it.loaded }
        assertEquals(listOf("one: no key"), vm.state.value.notes)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test
    fun aFailedWriteNote_goesWhenTheNextReadSucceeds() = runTest {
        val (b, _) = bridge(1L, "one") { if ("hc label" in it) CommandOutput("", "no", 1) else answers(it) }
        val vm = viewModel(true, b)
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        vm.act(coreRow(vm), solutions.ormus.logos.herd.RowAction.PIN)
        awaitState(vm) { it.notes.any { n -> n.contains("could not write") } }
        vm.refresh()
        awaitState(vm) { it.notes.none { n -> n.contains("could not write") } }
    }

    private val slow = java.util.concurrent.atomic.AtomicBoolean(false)
    private val labelsCalls = AtomicInteger(0)
    private val labelsEntered = CountDownLatch(1)
    private val gateRead = CountDownLatch(1)
    private val laterRead = CountDownLatch(1)

    /**
     * While [slow] is set, the first labels command of a read waits on [gateRead] and then gives the
     * answer it had before the wait; any later one signals [laterRead] and hangs, so a read after the
     * held one never confirms anything on its own.
     */
    private fun slowAnswers(cmd: String): CommandOutput? {
        val out = answers(cmd)
        if (slow.get() && solutions.ormus.logos.herd.CoreSource.PINNED_CMD in cmd) {
            if (labelsCalls.incrementAndGet() == 1) {
                labelsEntered.countDown()
                gateRead.await(10, TimeUnit.SECONDS)
                return out
            }
            laterRead.countDown()
            return hang()
        }
        return out
    }

    /** Poll until a read after the held one has begun: the held one has then published or dropped its rows. */
    private suspend fun awaitHeldReadDone(vm: InboxViewModel) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!laterRead.await(20, TimeUnit.MILLISECONDS) && System.nanoTime() < end) vm.refresh()
        assertTrue("the held read never finished", laterRead.count == 0L)
    }

    @Test
    fun aPollThatStartedBeforeAWrite_doesNotUndoItsPatch() = runTest {
        val (b, _) = bridge(1L, "one") { slowAnswers(it) }
        val vm = viewModel(true, b)
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        // The second read is inside its labels command when the pin is written and patched.
        slow.set(true)
        vm.refresh()
        assertTrue(labelsEntered.await(10, TimeUnit.SECONDS))
        vm.act(coreRow(vm), solutions.ormus.logos.herd.RowAction.PIN)
        awaitState(vm) { s -> s.rows.first { it.agent.engine == core }.agent.pinned }
        gateRead.countDown()
        awaitHeldReadDone(vm)
        assertTrue(coreRow(vm).agent.pinned)
    }

    @Test
    fun aFailedWriteNote_staysWhenAReadAlreadyInFlightFinishes() = runTest {
        val gateWrite = CountDownLatch(1)
        val (b, _) = bridge(1L, "one") {
            if ("hc label" in it && slow.get()) {
                gateWrite.await(10, TimeUnit.SECONDS)
                CommandOutput("", "no", 1)
            } else {
                slowAnswers(it)
            }
        }
        val vm = viewModel(true, b)
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        slow.set(true)
        vm.act(coreRow(vm), solutions.ormus.logos.herd.RowAction.PIN)
        // A read begins while the write is still out, then the write fails, then the read ends well.
        vm.refresh()
        assertTrue(labelsEntered.await(10, TimeUnit.SECONDS))
        gateWrite.countDown()
        awaitState(vm) { it.notes.any { n -> n.contains("could not write") } }
        gateRead.countDown()
        awaitHeldReadDone(vm)
        assertTrue(vm.state.value.notes.any { it.contains("could not write") })
    }

    @Test
    fun aReadThatNeverAnswers_endsInANote_andTheInboxIsLoaded() = runTest {
        val (stuck, _) = bridge(1L, "stuck") { hang() }
        val vm = viewModel(true, stuck)
        vm.readTimeoutMs = 300L
        vm.refresh()
        awaitState(vm) { it.loaded }
        assertEquals(listOf("stuck: no answer"), vm.state.value.notes)
    }

    @Test
    fun aNewVisit_readsAHostAgain_whenTheLastVisitsReadNeverReturned() = runTest {
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val (b, _) = bridge(1L, "one") { if (first.getAndSet(false)) hang() else answers(it) }
        val vm = viewModel(true, b)
        vm.refresh()
        backgroundScope.launch(unconfined) { vm.watch() }
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
    }

    @Test
    fun aFailedWriteNote_goesWhenItsMachineLeaves() = runTest {
        val hosts = java.util.concurrent.CopyOnWriteArrayList(listOf(saved(1, "one")))
        val vm = viewModel(true, links = fakeLinks(emptyList(), { if ("hc label" in it) CommandOutput("", "no", 1) else answers(it) }, live = { hosts.toList() }))
        vm.refresh()
        awaitState(vm) { s -> s.rows.any { it.agent.engine == core } }
        vm.act(coreRow(vm), solutions.ormus.logos.herd.RowAction.PIN)
        awaitState(vm) { it.notes.any { n -> n.contains("could not write") } }
        hosts.clear()
        vm.refresh()
        awaitState(vm) { it.notes.none { n -> n.contains("could not write") } }
    }

    @Test
    fun aManagerThatNeverBinds_endsLoadingWithANote() = runTest {
        val vm = InboxViewModel(prefs(true), CoroutineDispatchers(unconfined, Dispatchers.IO, unconfined), fakeLinks(emptyList(), { null })).also { made += it }
        vm.refresh()
        assertEquals(false, vm.state.value.loaded)
        repeat(InboxViewModel.UNBOUND_POLLS) { vm.refresh() }
        assertEquals(true, vm.state.value.loaded)
        assertEquals(1, vm.state.value.notes.size)
    }
}
