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

package org.connectbot.ui.screens.console

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.DiagnosticsReporter
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.transport.CommandOutput
import org.connectbot.ui.components.ComposeDraftStore
import org.connectbot.util.NotificationPermissionHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrConsole

/**
 * The Pin entry of the console menu: offered for a console that watches a herdr-core terminal, and
 * read and written over that console's own connection with the Inbox's write. A plain SSH console and a
 * Herdr TUI pane have no labels, so they are offered nothing and run nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorePinViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(default = testDispatcher, io = testDispatcher, main = testDispatcher)
    private val prefs: SharedPreferences = mock()
    private val notificationPermissionHelper: NotificationPermissionHelper = mock()
    private val hostRepository: HostRepository = mock()
    private val diagnosticsReporter: DiagnosticsReporter = mock()
    private lateinit var terminalManager: TerminalManager
    private lateinit var savedStateHandle: SavedStateHandle

    private val target = CoreWatchTarget("box/t7", "alpha · ONE", "build")

    // What the host says to each command the console's connection runs; every command is logged.
    private val ran = mutableListOf<String>()
    private var answer: (String) -> CommandOutput? = { CommandOutput("", "", 0) }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        terminalManager = mock()
        savedStateHandle = mock()
        whenever(terminalManager.bridgesFlow).thenReturn(MutableStateFlow(emptyList()))
        whenever(terminalManager.hostStatusChangedFlow).thenReturn(MutableSharedFlow())
        whenever(terminalManager.showConsole).thenReturn(MutableStateFlow(null))
        whenever(notificationPermissionHelper.isGranted()).thenReturn(true)
        whenever(savedStateHandle.get<Long>("hostId")).thenReturn(1L)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun herd(): HerdSession = mock<HerdSession>().also { session ->
        whenever(session.exec(any())).thenAnswer {
            val command = it.getArgument<String>(0)
            ran += command
            answer(command)
        }
    }

    private fun bridge(watch: CoreWatchTarget? = null, console: HerdrConsole? = null, withHerd: Boolean = true): TerminalBridge {
        val session = if (withHerd) herd() else null
        val bridge = mock<TerminalBridge>()
        whenever(bridge.host).thenReturn(Host(id = 1L, hostname = "box", nickname = "box", protocol = "ssh", port = 22, username = "u"))
        whenever(bridge.watch).thenReturn(watch)
        whenever(bridge.console).thenReturn(console)
        whenever(bridge.herd).thenReturn(session)
        return bridge
    }

    private fun viewModel() = ConsoleViewModel(savedStateHandle, dispatchers, prefs, notificationPermissionHelper, hostRepository, diagnosticsReporter, ComposeDraftStore())
        .also { it.setTerminalManager(terminalManager) }

    private fun get(labels: String?) = CommandOutput("""{"address":"t7","state":"live","turn":4,${labels?.let { "\"labels\":$it," }.orEmpty()}"screen":"inexact"}""", "", 0)

    @Test
    fun aWatchConsoleIsOfferedPin() {
        assertTrue(viewModel().canPinCore(bridge(watch = target)))
    }

    @Test
    fun aPlainSshConsoleIsOfferedNothing() = runTest {
        val vm = viewModel()
        val plain = bridge()
        assertFalse(vm.canPinCore(plain))
        assertNull(vm.corePinned(plain))
        assertFalse(vm.setCorePin(plain, pin = true))
        assertEquals(emptyList<String>(), ran)
    }

    @Test
    fun aHerdrTuiPaneIsOfferedNothing() = runTest {
        val vm = viewModel()
        val pane = bridge(console = HerdrConsole("default", "ws1", "ormus · MERCURIO"))
        assertFalse(vm.canPinCore(pane))
        assertNull(vm.corePinned(pane))
        assertFalse(vm.setCorePin(pane, pin = false))
        assertEquals(emptyList<String>(), ran)
    }

    @Test
    fun noConsoleAndAWatchWithNoSshSessionAreOfferedNothing() = runTest {
        val vm = viewModel()
        assertFalse(vm.canPinCore(null))
        val noSession = bridge(watch = target, withHerd = false)
        assertFalse(vm.canPinCore(noSession))
        assertNull(vm.corePinned(noSession))
        assertFalse(vm.setCorePin(noSession, pin = true))
    }

    @Test
    fun theStateIsReadOnTheConsolesOwnConnection() = runTest {
        answer = { get("""{"herdr.pinned":"1"}""") }
        val vm = viewModel()
        assertEquals(true, vm.corePinned(bridge(watch = target)))
        assertEquals(listOf("bash -lc 'hc get box/t7 --json'"), ran)
        ran.clear()
        answer = { get(null) }
        assertEquals(false, vm.corePinned(bridge(watch = target)))
    }

    @Test
    fun aReadThatDoesNotAnswerIsNoState() = runTest {
        val vm = viewModel()
        answer = { null }
        assertNull(vm.corePinned(bridge(watch = target)))
        answer = { CommandOutput("", "no such terminal", 1) }
        assertNull(vm.corePinned(bridge(watch = target)))
    }

    @Test
    fun pinAndUnpinAreTheInboxsWriteOnTheConsolesConnection() = runTest {
        val vm = viewModel()
        val watched = bridge(watch = target)
        assertTrue(vm.setCorePin(watched, pin = true))
        assertTrue(vm.setCorePin(watched, pin = false))
        assertEquals(listOf("bash -lc 'hc label box/t7 herdr.pinned=1'", "bash -lc 'hc label box/t7 herdr.pinned='"), ran)
    }

    @Test
    fun aWriteThatFailedIsFalse() = runTest {
        val vm = viewModel()
        answer = { CommandOutput("", "denied", 1) }
        assertFalse(vm.setCorePin(bridge(watch = target), pin = true))
        answer = { null }
        assertFalse(vm.setCorePin(bridge(watch = target), pin = false))
    }
}
