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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalBridge
import solutions.ormus.logos.herd.HerdFocus
import javax.inject.Inject

/** What the output reader shows. */
data class OutputReaderUiState(
    val loading: Boolean = false,
    val text: String = "",
    // Set when the text came from a Herdr pane rather than the local transcript.
    val herdr: HerdFocus? = null,
    // Why reading from Herdr failed, when it was tried and the reader fell back.
    val herdrError: String? = null,
) {
    /** "agent · workspace / tab" when Herdr says, else [fallback] (the host name). */
    fun title(fallback: String): String {
        val focus = herdr ?: return fallback
        val place = listOfNotNull(focus.workspaceLabel, focus.tabLabel).joinToString(" / ")
        return listOfNotNull(focus.agent, place.ifEmpty { null }).joinToString(" · ").ifEmpty { fallback }
    }
}

/**
 * Loads recent output for the full-screen reader. In a Herdr session the
 * terminal only holds the visible screen, so the focused pane's history is read
 * from Herdr over an SSH exec channel; otherwise, or if that fails, it shows the
 * session's own rolling transcript.
 */
@HiltViewModel
class OutputReaderViewModel @Inject constructor(
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OutputReaderUiState())
    val uiState: StateFlow<OutputReaderUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** Read (or re-read) [bridge]'s recent output; keeps the old text visible meanwhile. */
    fun load(bridge: TerminalBridge) {
        loadJob?.cancel()
        _uiState.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            _uiState.value = withContext(dispatchers.io) { read(bridge) }
        }
    }

    private fun read(bridge: TerminalBridge): OutputReaderUiState {
        val out = RecentOutput.read(bridge)
        return OutputReaderUiState(text = out.text, herdr = out.herdr, herdrError = out.herdrError)
    }
}
