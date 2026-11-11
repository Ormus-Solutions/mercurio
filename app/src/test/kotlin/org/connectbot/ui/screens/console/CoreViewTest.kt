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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.DiagnosticsReporter
import org.connectbot.service.DisconnectReason
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.ui.components.KeyBarCatalog
import org.connectbot.ui.components.KeyBarLayout
import org.connectbot.ui.navigation.NavArgs
import org.connectbot.util.NotificationPermissionHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdrKeys

/**
 * A console screen opened for a core terminal shows that terminal alone, says so in its header,
 * ends its watch when the screen goes, and leaves the Herdr TUI keys off its bar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreViewTest {
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(default = testDispatcher, io = testDispatcher, main = testDispatcher)
    private lateinit var terminalManager: TerminalManager
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var bridgesFlow: MutableStateFlow<List<TerminalBridge>>
    private val prefs: SharedPreferences = mock()
    private val notificationPermissionHelper: NotificationPermissionHelper = mock()
    private val hostRepository: HostRepository = mock()
    private val diagnosticsReporter: DiagnosticsReporter = mock()

    private val target = CoreWatchTarget("box/t1", "alpha · ONE", "build")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        CoreView.resetViewers()
        terminalManager = mock()
        savedStateHandle = mock()
        bridgesFlow = MutableStateFlow(emptyList())
        whenever(terminalManager.bridgesFlow).thenReturn(bridgesFlow)
        whenever(terminalManager.hostStatusChangedFlow).thenReturn(MutableSharedFlow())
        whenever(terminalManager.showConsole).thenReturn(MutableStateFlow(null))
        whenever(notificationPermissionHelper.isGranted()).thenReturn(true)
        whenever(savedStateHandle.get<Long>("hostId")).thenReturn(1L)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun asksToWatch(watch: CoreWatchTarget) {
        whenever(savedStateHandle.get<String>(NavArgs.WATCH)).thenReturn(watch.address)
        whenever(savedStateHandle.get<String>(NavArgs.WATCH_SPACE)).thenReturn(watch.space)
        whenever(savedStateHandle.get<String>(NavArgs.WATCH_TAB)).thenReturn(watch.tab)
    }

    private fun bridge(hostId: Long, watch: CoreWatchTarget? = null): TerminalBridge {
        val bridge = mock<TerminalBridge>()
        whenever(bridge.host).thenReturn(Host(id = hostId, hostname = "box", nickname = "box", protocol = "ssh", port = 22, username = "u"))
        whenever(bridge.watch).thenReturn(watch)
        whenever(bridge.isSessionOpen).thenReturn(true)
        whenever(bridge.isDisconnected).thenReturn(false)
        whenever(bridge.bellEvents).thenReturn(MutableSharedFlow())
        whenever(bridge.progressState).thenReturn(MutableStateFlow(null))
        whenever(bridge.networkStatusMessages).thenReturn(MutableSharedFlow())
        return bridge
    }

    private fun viewModel(): ConsoleViewModel = ConsoleViewModel(savedStateHandle, dispatchers, prefs, notificationPermissionHelper, hostRepository, diagnosticsReporter)
        .also { it.setTerminalManager(terminalManager) }

    /** The view model inside a store, so that clearing the store is the screen going away. */
    private fun inStore(): Pair<ViewModelStore, ConsoleViewModel> {
        val store = ViewModelStore()
        val made = viewModel()
        val provider = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = made as T
            },
        )
        return store to provider[ConsoleViewModel::class.java]
    }

    @Test
    fun watchScreen_showsOnlyTheWatchedConsole() = runTest {
        val own = bridge(1L)
        val other = bridge(1L, CoreWatchTarget("box/t2", "alpha · ONE", "tests"))
        val watched = bridge(1L, target)
        val elsewhere = bridge(2L)
        bridgesFlow.value = listOf(own, other, watched, elsewhere)
        asksToWatch(target)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(watched), vm.uiState.value.bridges)
        assertEquals(0, vm.uiState.value.currentBridgeIndex)
    }

    @Test
    fun watchScreen_beforeTheWatchOpens_showsNoOtherConsole() = runTest {
        bridgesFlow.value = listOf(bridge(1L), bridge(2L))
        asksToWatch(target)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(emptyList<TerminalBridge>(), vm.uiState.value.bridges)
    }

    @Test
    fun otherScreens_stillShowEveryConsoleOfTheHost() = runTest {
        val own = bridge(1L)
        val watched = bridge(1L, target)
        bridgesFlow.value = listOf(own, watched, bridge(2L))

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(own, watched), vm.uiState.value.bridges)
    }

    @Test
    fun leavingTheWatchScreen_closesTheWatchConsoleAndNoOther() = runTest {
        val own = bridge(1L)
        val other = bridge(1L, CoreWatchTarget("box/t2", "alpha · ONE", "tests"))
        val watched = bridge(1L, target)
        bridgesFlow.value = listOf(own, other, watched)
        asksToWatch(target)
        val (store, _) = inStore()
        advanceUntilIdle()

        store.clear()

        verify(watched).dispatchDisconnect(DisconnectReason.USER_REQUESTED)
        verify(own, never()).dispatchDisconnect(DisconnectReason.USER_REQUESTED)
        verify(other, never()).dispatchDisconnect(DisconnectReason.USER_REQUESTED)
    }

    @Test
    fun leavingAnotherScreen_closesNothing() = runTest {
        val own = bridge(1L)
        val watched = bridge(1L, target)
        bridgesFlow.value = listOf(own, watched)
        val (store, _) = inStore()
        advanceUntilIdle()

        store.clear()

        verify(own, never()).dispatchDisconnect(DisconnectReason.USER_REQUESTED)
        verify(watched, never()).dispatchDisconnect(DisconnectReason.USER_REQUESTED)
    }

    @Test
    fun header_titleIsTheTabAndTheSecondLineIsSpaceAndHost() {
        assertEquals("build", CoreView.title(target))
        assertEquals("alpha · ONE · box", CoreView.subtitle(target, "box"))
    }

    @Test
    fun header_aBlankTabTitlesWithTheSpace() {
        val blank = CoreWatchTarget("box/t1", "alpha · ONE", "")
        assertEquals("alpha · ONE", CoreView.title(blank))
        assertEquals("alpha · ONE · box", CoreView.subtitle(blank, "box"))
    }

    @Test
    fun header_withNoSpaceTheSecondLineIsTheHost() {
        val bare = CoreWatchTarget("box/t1", "", "")
        assertEquals("box/t1", CoreView.title(bare))
        assertEquals("box", CoreView.subtitle(bare, "box"))
    }

    @Test
    fun keyBar_dropsTheKeysThatWriteHerdrsPrefixOrABinding() {
        val keys = HerdrKeys.fromConfig("[keys]\nprefix = \"ctrl+a\"\ntoggle_sidebar = \"ctrl+z\"\n")
        val layout = KeyBarLayout(listOf(listOf("esc", "ctrl-a", "tab"), listOf("ctrl-z", "ctrl-c", "up"), listOf("ctrl-a")))

        val shown = CoreView.keyBar(layout, keys)

        assertEquals(listOf(listOf("esc", "tab"), listOf("ctrl-c", "up"), emptyList<String>()), shown.pages)
    }

    @Test
    fun keyBar_keepsPlainAndCustomKeys() {
        val custom = KeyBarCatalog.textKeyId("git status", enter = true)!!
        val layout = KeyBarLayout(listOf(listOf("esc", "tab", "up", "ctrl-c", "ctrl-l", custom, "arrows", "ctrl", "alt")))

        assertEquals(layout.pages, CoreView.keyBar(layout, HerdrKeys.DEFAULT).pages)
    }

    @Test
    fun watchScreen_showsTheLiveConsoleNotTheEndedOneBesideIt() = runTest {
        val ended = bridge(1L, target)
        whenever(ended.isDisconnected).thenReturn(true)
        val fresh = bridge(1L, target)
        bridgesFlow.value = listOf(ended, fresh)
        asksToWatch(target)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(fresh), vm.uiState.value.bridges)
    }

    @Test
    fun leavingOneOfTwoScreensOnTheSameTerminal_keepsTheConsoleForTheOther() = runTest {
        val watched = bridge(1L, target)
        bridgesFlow.value = listOf(watched)
        asksToWatch(target)
        val (oldStore, _) = inStore()
        val (newStore, _) = inStore()
        advanceUntilIdle()

        oldStore.clear()
        verify(watched, never()).dispatchDisconnect(DisconnectReason.USER_REQUESTED)

        newStore.clear()
        verify(watched).dispatchDisconnect(DisconnectReason.USER_REQUESTED)
    }
}
