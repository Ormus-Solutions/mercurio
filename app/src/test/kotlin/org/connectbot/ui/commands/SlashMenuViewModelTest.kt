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

package org.connectbot.ui.commands

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.transport.CommandOutput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import solutions.ormus.logos.commands.AgentKind
import solutions.ormus.logos.commands.SlashCommandRepository
import solutions.ormus.logos.commands.commandsFixture
import solutions.ormus.logos.commands.snapshotFixture
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import java.io.IOException

/**
 * The slash menu against a fake SSH runner that answers the own-commands fetch
 * with the Sun fixture, and a fake PTY.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SlashMenuViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(default = dispatcher, io = dispatcher, main = dispatcher)
    private lateinit var prefs: SharedPreferences

    private val ran = mutableListOf<String>()
    private val pty = mutableListOf<String>()
    private var answer: (String) -> CommandOutput? = { CommandOutput(commandsFixture("commands/own-commands-sun.txt"), "", 0) }

    private fun runner(command: String): CommandOutput? {
        ran += command
        return answer(command)
    }

    private fun repository() = SlashCommandRepository(tmp.root, prefs, dispatchers) { 1_700_000_000_000L }

    private fun target(snapshot: HerdrSessionSnapshot? = null, hostId: Long = 7) = SlashTarget(
        hostId = hostId,
        snapshot = snapshot?.let { MutableStateFlow(it) },
        runner = ::runner,
        inject = { pty += it },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("slash-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun herdrHost_showsTheFocusedAgentAndReadsOwnCommandsForItsProject() = runTest(dispatcher) {
        val viewModel = SlashMenuViewModel(repository(), dispatchers)

        viewModel.open(target(snapshotFixture("commands/snapshot-focus-claude.json")))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AgentKind.CLAUDE, state.agent)
        assertTrue(state.detected)
        assertEquals("clear", state.builtIns.first().name)
        assertEquals(
            listOf("00-gnosis", "brain", "claude-md-overhaul", "codeql", "dev:commit-smart", "learn", "mplan", "speckit.plan"),
            state.own.map { it.name },
        )
        assertEquals(1_700_000_000_000L, state.fetchedAt)
        // One read, for the focused pane's project.
        assertEquals(1, ran.size)
        assertTrue(ran.single().endsWith(" sh '/home/alice/projects/demo-app'"))
    }

    @Test
    fun plainHost_usesTheRememberedPickPerHost() = runTest(dispatcher) {
        val viewModel = SlashMenuViewModel(repository(), dispatchers)
        viewModel.open(target(hostId = 7))
        advanceUntilIdle()
        assertEquals(AgentKind.CLAUDE, viewModel.uiState.value.agent)
        assertFalse(viewModel.uiState.value.detected)

        viewModel.pickAgent(AgentKind.GROK)
        advanceUntilIdle()
        assertEquals(listOf("brain", "user:calcinate"), viewModel.uiState.value.own.map { it.name })

        // Next open on the same host starts on Grok; another host still on Claude.
        val again = SlashMenuViewModel(repository(), dispatchers)
        again.open(target(hostId = 7))
        advanceUntilIdle()
        assertEquals(AgentKind.GROK, again.uiState.value.agent)
        again.open(target(hostId = 8))
        advanceUntilIdle()
        assertEquals(AgentKind.CLAUDE, again.uiState.value.agent)
    }

    @Test
    fun cachedList_showsWithoutReadingAgain_untilRefresh() = runTest(dispatcher) {
        SlashMenuViewModel(repository(), dispatchers).open(target())
        advanceUntilIdle()
        assertEquals(1, ran.size)

        // A new process: the list comes from disk, no SSH read.
        val viewModel = SlashMenuViewModel(repository(), dispatchers)
        viewModel.open(target())
        advanceUntilIdle()
        assertEquals(1, ran.size)
        assertEquals(8, viewModel.uiState.value.own.size)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(2, ran.size)
    }

    @Test
    fun failedRead_keepsTheOldListAndSaysSo() = runTest(dispatcher) {
        val viewModel = SlashMenuViewModel(repository(), dispatchers)
        viewModel.open(target())
        advanceUntilIdle()

        answer = { throw IOException("Timed out") }
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failed)
        assertEquals(8, viewModel.uiState.value.own.size)

        answer = { null } // no SSH session up
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failed)
        assertFalse(viewModel.uiState.value.refreshing)
    }

    @Test
    fun search_filtersBothGroupsByNameAndDescription() = runTest(dispatcher) {
        val viewModel = SlashMenuViewModel(repository(), dispatchers)
        viewModel.open(target())
        advanceUntilIdle()

        viewModel.setQuery("/comp")
        advanceUntilIdle()
        assertEquals("compact", viewModel.uiState.value.builtIns.first().name)

        viewModel.setQuery("vault")
        advanceUntilIdle()
        assertEquals(listOf("brain"), viewModel.uiState.value.own.map { it.name })
    }

    @Test
    fun run_sendsBareBuiltInsAndInsertsTheRest() = runTest(dispatcher) {
        val viewModel = SlashMenuViewModel(repository(), dispatchers)
        viewModel.open(target())
        advanceUntilIdle()
        val field = mutableListOf<String>()
        val state = viewModel.uiState.value

        assertTrue(viewModel.run(state.builtIns.first { it.name == "clear" }, { field += it }))
        advanceUntilIdle()
        assertEquals(listOf("/clear", "\r"), pty)

        assertFalse(viewModel.run(state.own.first { it.name == "mplan" }, { field += it }))
        assertEquals(listOf("/mplan "), field)
        assertEquals(2, pty.size)
    }
}
