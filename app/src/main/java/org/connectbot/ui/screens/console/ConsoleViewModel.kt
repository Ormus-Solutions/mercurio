/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.DiagnosticsReporter
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.terminal.ProgressState
import org.connectbot.util.NotificationPermissionHelper
import org.connectbot.util.PreferenceConstants
import javax.inject.Inject

data class ConsoleUiState(
    val bridges: List<TerminalBridge> = emptyList(),
    val currentBridgeIndex: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
    // Add a revision counter to force recomposition when bridge state changes
    val revision: Int = 0,
    // Progress state from OSC 9;4 escape sequences
    val progressState: ProgressState? = null,
    val progressValue: Int = 0,
)

@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val dispatchers: CoroutineDispatchers,
    private val prefs: SharedPreferences,
    private val notificationPermissionHelper: NotificationPermissionHelper,
    private val hostRepository: HostRepository,
    private val diagnosticsReporter: DiagnosticsReporter,
) : ViewModel() {
    private val hostId: Long = savedStateHandle.get<Long>("hostId") ?: -1L
    private var terminalManager: TerminalManager? = null

    private val _uiState = MutableStateFlow(ConsoleUiState())
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    // Every open bridge across all hosts (NOT filtered to this screen's hostId),
    // for the session drawer. uiState.bridges stays host-scoped for the terminal.
    private val _allBridges = MutableStateFlow<List<TerminalBridge>>(emptyList())
    val allBridges: StateFlow<List<TerminalBridge>> = _allBridges.asStateFlow()

    private val _networkStatusMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val networkStatusMessages: SharedFlow<String> = _networkStatusMessages.asSharedFlow()

    fun shouldShowNotificationWarning(): Boolean {
        if (!prefs.contains(PreferenceConstants.NOTIFICATION_PERMISSION_DENIED)) return false
        val connPersist = prefs.getBoolean(PreferenceConstants.CONNECTION_PERSIST, true)
        return !connPersist || !notificationPermissionHelper.isGranted()
    }

    fun setTerminalManager(manager: TerminalManager) {
        if (terminalManager != manager) {
            terminalManager = manager
            // Observe bridges flow from TerminalManager
            viewModelScope.launch {
                manager.bridgesFlow.collect { bridges ->
                    _allBridges.value = bridges
                    updateBridges(bridges)
                    subscribeToActiveBridgeBells(bridges)
                    subscribeToActiveBridgeProgress(bridges)
                    subscribeToNetworkStatusMessages(bridges)
                }
            }

            // Observe host status changes (connect/disconnect events) to refresh UI
            viewModelScope.launch {
                manager.hostStatusChangedFlow.collect {
                    _uiState.update { it.copy(revision = it.revision + 1) }
                }
            }

            // First, try to find or create the bridge for this host
            if (hostId != -1L) {
                viewModelScope.launch {
                    ensureBridgeExists()
                }
            }
        }
    }

    private fun subscribeToActiveBridgeBells(bridges: List<TerminalBridge>) {
        viewModelScope.launch {
            bridges.forEach { bridge ->
                launch {
                    bridge.bellEvents.collect {
                        val currentIndex = _uiState.value.currentBridgeIndex
                        val currentBridge = _uiState.value.bridges.getOrNull(currentIndex)

                        // Beep only for the on-screen session. When the belling
                        // session is off-screen or the app is backgrounded, the
                        // manager raises an agent-attention notification instead
                        // (TerminalBridge.onBell -> onAgentAttention).
                        if (currentBridge == bridge) {
                            terminalManager?.playBeep()
                        }
                    }
                }
            }
        }
    }

    private fun subscribeToActiveBridgeProgress(bridges: List<TerminalBridge>) {
        viewModelScope.launch {
            bridges.forEach { bridge ->
                launch {
                    bridge.progressState.collect { progressInfo ->
                        val currentIndex = _uiState.value.currentBridgeIndex
                        val currentBridge = _uiState.value.bridges.getOrNull(currentIndex)

                        if (currentBridge == bridge) {
                            // Update progress state for the visible bridge
                            _uiState.update {
                                if (progressInfo == null || progressInfo.state == ProgressState.HIDDEN) {
                                    it.copy(progressState = null, progressValue = 0)
                                } else {
                                    it.copy(
                                        progressState = progressInfo.state,
                                        progressValue = progressInfo.progress,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun subscribeToNetworkStatusMessages(bridges: List<TerminalBridge>) {
        viewModelScope.launch {
            bridges.forEach { bridge ->
                launch {
                    bridge.networkStatusMessages.collect { message ->
                        val currentBridge = _uiState.value.bridges.getOrNull(_uiState.value.currentBridgeIndex)
                        if (currentBridge == bridge) {
                            _networkStatusMessages.emit(message)
                        }
                    }
                }
            }
        }
    }

    private suspend fun ensureBridgeExists() {
        withContext(dispatchers.io) {
            try {
                val allBridges = terminalManager?.bridgesFlow?.value ?: emptyList()

                // Check if we already have a bridge for this host
                val existingBridge = allBridges.find { bridge ->
                    bridge.host.id == hostId
                }

                // If no bridge exists, create one using the service method
                if (existingBridge == null) {
                    if (hostId < 0L) {
                        // Temporary host - should already exist from MainActivity/URI handling
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Temporary connection not found",
                            )
                        }
                    } else {
                        // Permanent host - create from database
                        val bridge = terminalManager?.openConnectionForHostId(hostId)
                        if (bridge == null) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = "Failed to open connection: host not found",
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to create connection",
                    )
                }
            }
        }
    }

    private fun updateBridges(allBridges: List<TerminalBridge>) {
        // If hostId is provided, try to find the bridge for this specific host
        val filteredBridges = if (hostId != -1L) {
            allBridges.filter { bridge ->
                bridge.host.id == hostId
            }
        } else {
            allBridges
        }

        _uiState.update {
            val newBridges = filteredBridges.ifEmpty { allBridges }
            val newIndex = if (it.currentBridgeIndex >= newBridges.size) {
                // Adjust index if it's now out of range
                (newBridges.size - 1).coerceAtLeast(0)
            } else {
                it.currentBridgeIndex
            }

            // Stop loading when we have bridges, or when we're showing all bridges (hostId == -1)
            // If waiting for a specific host, keep loading until that bridge appears
            val shouldStopLoading = if (hostId != -1L) {
                filteredBridges.isNotEmpty()
            } else {
                true // Always stop loading when showing all bridges
            }

            it.copy(
                bridges = newBridges,
                currentBridgeIndex = newIndex,
                isLoading = if (shouldStopLoading) false else it.isLoading,
                error = null,
            )
        }
        publishVisibleHost()
    }

    fun selectBridge(index: Int) {
        if (index in _uiState.value.bridges.indices) {
            _uiState.update { it.copy(currentBridgeIndex = index) }
            publishVisibleHost()
        }
    }

    /**
     * Tell the manager which session is on screen so it suppresses agent
     * notifications for the one the user is already watching.
     */
    private fun publishVisibleHost() {
        val current = _uiState.value.bridges.getOrNull(_uiState.value.currentBridgeIndex)
        terminalManager?.setVisibleHost(current?.host?.id)
    }

    override fun onCleared() {
        super.onCleared()
        // Console left the screen — nothing is visible now.
        terminalManager?.setVisibleHost(null)
    }

    /**
     * Refresh the UI state to trigger recomposition.
     * This is needed because bridge state (isSessionOpen, isDisconnected, etc.)
     * changes asynchronously but doesn't trigger Compose recomposition automatically.
     */
    fun refreshMenuState() {
        _uiState.update { it.copy(revision = it.revision + 1) }
    }

    /**
     * Request a reconnection for the given bridge.
     */

    /** Secrets-free diagnostics report of [bridge], for "Copy details". */
    suspend fun diagnosticsReport(bridge: TerminalBridge): String = diagnosticsReporter.build(bridge)

    fun reconnect(bridge: TerminalBridge) {
        terminalManager?.requestReconnect(bridge)
        _uiState.update { it.copy(revision = it.revision + 1) }
    }

    /**
     * Resolve a quick-connect string to a host id the caller can navigate to
     * (navigating to console/{id} opens the connection). A bare word matches a
     * saved host by nickname first (a leading "ssh " is stripped); otherwise
     * the string is parsed as user@host[:port] and saved as a new SSH host.
     * Returns null when the input can't be turned into an SSH login.
     */
    suspend fun quickConnect(rawInput: String): Long? {
        val input = rawInput.trim().removePrefix("ssh ").trim()
        if (input.isEmpty()) return null

        hostRepository.getHosts()
            .firstOrNull { it.nickname.equals(input, ignoreCase = true) }
            ?.let { return it.id }

        val parsed = parseQuickConnect(input) ?: return null
        val (username, hostname, port) = parsed
        // SSH needs an explicit username; a bare hostname with no saved match
        // can't form a login, so send the user to the full editor instead.
        if (username.isBlank()) return null

        val host = Host(
            nickname = input,
            protocol = "ssh",
            username = username,
            hostname = hostname,
            port = port.toIntOrNull() ?: 22,
            lastConnect = System.currentTimeMillis(),
        )
        return hostRepository.saveHost(host).id
    }

    /** user@host[:port] parser, mirroring the host editor's quick-connect field. */
    private fun parseQuickConnect(value: String): Triple<String, String, String>? {
        val regex = Regex(
            "^(?:([^@]+)@)?((?:[0-9a-zA-Z._-]+)|(?:\\[[a-fA-F:0-9]+(?:%[-_.a-zA-Z0-9]+)?\\]))(?::(\\d+))?$",
        )
        val match = regex.find(value) ?: return null
        val (username, hostname, port) = match.destructured
        val isValid = hostname.isNotBlank() && (
            (hostname.startsWith("[") && hostname.endsWith("]")) ||
                hostname.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            )
        return if (isValid) Triple(username, hostname, port) else null
    }
}
