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

package org.connectbot.ui.screens.spaces

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
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
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.CoreLinks
import solutions.ormus.logos.herd.CoreSpaces
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Spaces rows come from every saved machine, with no console open. */
class SpacesViewModelTest {
    private val unconfined = UnconfinedTestDispatcher()
    private val logins = AtomicInteger(0)
    private val made = mutableListOf<SpacesViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(unconfined)
    }

    @After
    fun tearDown() {
        made.forEach { it.viewModelScope.cancel() }
        Thread.sleep(100)
        Dispatchers.resetMain()
    }

    private fun host(id: Long, name: String, post: String? = null) = Host(id = id, nickname = name, hostname = "box", username = "u", postLogin = post)

    private val doc = """{"id":1,"result":{"value":{"spaces":[{"id":"s1","name":"alpha · ONE","folder":"/w","tabs":[{"id":"t1","name":"main","root":{"id":"p","type":"block","content":{"terminal":"t7"}}}]}]}}}"""

    private fun viewModel(saved: List<Host>, skip: String? = null, exec: (String) -> CommandOutput = { CommandOutput(doc, "", 0) }): SpacesViewModel {
        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.getBoolean(PreferenceConstants.HERDR_CORE, PreferenceConstants.HERDR_CORE_DEFAULT)).thenReturn(true)
        val manager = mock(TerminalManager::class.java)
        `when`(manager.bridgesFlow).thenReturn(MutableStateFlow(emptyList()))
        val links = CoreLinks(
            hosts = { saved },
            login = {
                logins.incrementAndGet()
                if (skip != null) {
                    CoreLinks.Opened.Skipped(skip)
                } else {
                    CoreLinks.Opened.Session(
                        object : HeadlessSession {
                            override fun login(keys: List<java.security.KeyPair>) = HeadlessSsh.Login.Ok

                            override fun exec(command: String) = exec(command)

                            override fun close() = Unit
                        },
                    )
                }
            },
        )
        return SpacesViewModel(prefs, CoroutineDispatchers(unconfined, Dispatchers.IO, unconfined), links).also {
            it.setTerminalManager(manager)
            made += it
        }
    }

    private fun await(vm: SpacesViewModel, ok: (List<SpacesSection>) -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ok(vm.sections.value) && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("never matched: ${vm.sections.value}", ok(vm.sections.value))
    }

    @Test
    fun fiveRowsToOneMachine_giveOneSection_withNoConsoleOpen() = runTest {
        val vm = viewModel(listOf(host(1, "shell"), host(2, "plain", "herdr"), host(3, "a", "herdr session attach 'a'"), host(4, "b"), host(5, "c")))
        vm.refresh()
        await(vm) { it.size == 1 && it.single().result is CoreSpaces.Result.Spaces }
        assertEquals(2L, vm.sections.value.single().hostId)
        assertEquals("plain", vm.sections.value.single().machine)
        assertEquals(1, logins.get())
    }

    @Test
    fun aMachineThatCannotBeLoggedInto_showsItsReason() = runTest {
        val vm = viewModel(listOf(host(1, "one")), skip = "no key that opens without asking")
        vm.refresh()
        await(vm) { it.size == 1 }
        assertEquals(CoreSpaces.Result.Unavailable("no key that opens without asking"), vm.sections.value.single().result)
    }

    private fun spacesOrList(list: CommandOutput): (String) -> CommandOutput = { command -> if (command.contains("hc ls")) list else CommandOutput(doc, "", 0) }

    @Test
    fun aRefreshFillsTheAgentStatesFromTheAgentList() = runTest {
        val vm = viewModel(listOf(host(1, "one")), exec = spacesOrList(CommandOutput("""[{"address":"t7","status":"blocked"},{"address":"t8","status":"working"}]""", "", 0)))
        vm.refresh()
        await(vm) { it.size == 1 && it.single().result is CoreSpaces.Result.Spaces }
        assertEquals(mapOf("t7" to AgentState.NeedsYou(), "t8" to AgentState.Working), vm.sections.value.single().states)
    }

    @Test
    fun anAgentListThatFailsLeavesTheStatesEmptyAndTheSpacesShown() = runTest {
        val vm = viewModel(listOf(host(1, "one")), exec = spacesOrList(CommandOutput("", "hc: not found", 127)))
        vm.refresh()
        await(vm) { it.size == 1 && it.single().result is CoreSpaces.Result.Spaces }
        assertEquals(emptyMap<String, AgentState>(), vm.sections.value.single().states)
    }

    @Test
    fun anAgentListThatThrowsLeavesTheStatesEmptyAndTheSpacesShown() = runTest {
        val vm = viewModel(listOf(host(1, "one")), exec = { command -> if (command.contains("hc ls")) throw IllegalStateException("boom") else CommandOutput(doc, "", 0) })
        vm.refresh()
        await(vm) { it.size == 1 && it.single().result is CoreSpaces.Result.Spaces }
        assertEquals(emptyMap<String, AgentState>(), vm.sections.value.single().states)
    }

    @Test
    fun aTransientAgentListFailureKeepsTheLastStatesForOneRefresh() = runTest {
        val lists = AtomicInteger(0)
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        val vm = viewModel(
            listOf(host(1, "one")),
            exec = { command ->
                if (command.contains("hc ls")) {
                    lists.incrementAndGet()
                    if (failing.get()) CommandOutput("", "down", 1) else CommandOutput("""[{"address":"t7","status":"blocked"}]""", "", 0)
                } else {
                    CommandOutput(doc, "", 0)
                }
            },
        )
        val full = mapOf("t7" to AgentState.NeedsYou())
        vm.refresh()
        await(vm) { it.size == 1 && it.single().states == full }

        failing.set(true)
        vm.refresh()
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (lists.get() < 2 && System.nanoTime() < end) Thread.sleep(10)
        Thread.sleep(300)
        assertEquals(full, vm.sections.value.single().states)

        vm.refresh()
        await(vm) { it.size == 1 && it.single().states.isEmpty() }
    }
}
