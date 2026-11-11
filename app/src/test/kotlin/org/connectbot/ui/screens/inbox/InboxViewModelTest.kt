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
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.AgentSince
import solutions.ormus.logos.herd.CoreLinks
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

    private fun prefs(core: Boolean): SharedPreferences {
        val p = mock(SharedPreferences::class.java)
        `when`(p.getBoolean(PreferenceConstants.HERDR_CORE, PreferenceConstants.HERDR_CORE_DEFAULT)).thenReturn(core)
        `when`(p.getBoolean(PreferenceConstants.HERDR_CORE_SEEN, false)).thenReturn(seen.get())
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(p.edit()).thenReturn(editor)
        `when`(editor.putBoolean(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenAnswer {
            if (it.getArgument<String>(0) == PreferenceConstants.HERDR_CORE_SEEN) seen.set(it.getArgument(1))
            editor
        }
        return p
    }

    private fun bridge(id: Long, nickname: String, exec: (String) -> CommandOutput?): Pair<TerminalBridge, HerdSession> {
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
        `when`(herd.exec(org.mockito.kotlin.any())).thenAnswer { exec(it.getArgument(0)) }
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(id = id, nickname = nickname, hostname = "box$id", username = "u"))
        `when`(bridge.herd).thenReturn(herd)
        return bridge to herd
    }

    private var logins = AtomicInteger(0)
    private val closed = AtomicInteger(0)

    /** A login that always works and runs [exec]: nothing here reaches a network. */
    private fun fakeLinks(saved: List<Host>, exec: (String) -> CommandOutput?, skip: String? = null) = CoreLinks(
        hosts = { saved },
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
}
