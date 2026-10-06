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

package org.connectbot.ui.screens.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalBridge
import org.connectbot.util.TerminalText.LF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import solutions.ormus.logos.herd.HerdFocus

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OutputReaderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(
        default = testDispatcher,
        io = testDispatcher,
        main = testDispatcher,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun plainSession_showsTranscript() = runTest(testDispatcher) {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(nickname = "Sun"))
        `when`(bridge.transcriptText()).thenReturn("one" + LF + "two")
        val viewModel = OutputReaderViewModel(dispatchers)

        viewModel.load(bridge)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("one" + LF + "two", state.text)
        assertNull(state.herdr)
        assertNull(state.herdrError)
    }

    @Test
    fun herdrSessionWithoutSsh_fallsBackToTranscript() = runTest(testDispatcher) {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(nickname = "Sun", postLogin = "herdr"))
        `when`(bridge.transcriptText()).thenReturn("local")
        val viewModel = OutputReaderViewModel(dispatchers)

        viewModel.load(bridge)
        advanceUntilIdle()

        assertEquals("local", viewModel.uiState.value.text)
    }

    @Test
    fun title_usesAgentAndPlaceElseHost() {
        val herdr = OutputReaderUiState(
            herdr = HerdFocus(paneId = "w1:p1", agent = "claude", workspaceLabel = "~Demo", tabLabel = "2"),
        )
        assertEquals("claude · ~Demo / 2", herdr.title("Sun"))
        assertEquals("Sun", OutputReaderUiState().title("Sun"))
    }
}
