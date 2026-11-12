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
import org.connectbot.service.DisconnectReason
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.terminal.ProgressState
import org.connectbot.ui.components.ComposeDraftStore
import org.connectbot.ui.components.DictationDraft
import org.connectbot.ui.navigation.NavArgs
import org.connectbot.util.NotificationPermissionHelper
import org.connectbot.util.PreferenceConstants
import solutions.ormus.logos.herd.CoreWatch
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdView
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.HerdrSessions
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
    // The Herdr view a tap asked for over [openOn], a console this screen shows, until the screen opens it (viewOpened).
    val openView: HerdView? = null,
    val openOn: TerminalBridge? = null,
)

@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val dispatchers: CoroutineDispatchers,
    private val prefs: SharedPreferences,
    private val notificationPermissionHelper: NotificationPermissionHelper,
    private val hostRepository: HostRepository,
    private val diagnosticsReporter: DiagnosticsReporter,
    private val composeDrafts: ComposeDraftStore,
) : ViewModel() {
    /** The injected IO dispatcher, for the voice key's server calls the screen starts. */
    val io: kotlinx.coroutines.CoroutineDispatcher get() = dispatchers.io

    private val hostId: Long = savedStateHandle.get<Long>("hostId") ?: -1L

    // The Herdr console this screen was asked to show (a Workspaces key); null for the host's own.
    private val requested: HerdrConsole? = savedStateHandle.get<String>(NavArgs.SESSION)
        ?.takeIf(HerdrSessions::isName)
        ?.let { HerdrConsole(it, workspaceLabel = savedStateHandle.get<String>(NavArgs.LABEL)) }

    // The core terminal this screen was asked to watch (the Inbox); null for any other console.
    private val watchRequested: CoreWatchTarget? = savedStateHandle.get<String>(NavArgs.WATCH)
        ?.takeIf(CoreWatch::isSafe)
        ?.let { CoreWatchTarget(it, savedStateHandle.get<String>(NavArgs.WATCH_SPACE).orEmpty(), savedStateHandle.get<String>(NavArgs.WATCH_TAB).orEmpty()) }

    init {
        watchRequested?.let { CoreView.viewerJoined(hostId, it) }
    }

    // Whether the requested console was selected once it appeared; later changes keep the user's pick.
    private var requestedShown = false
    private var terminalManager: TerminalManager? = null

    private val _uiState = MutableStateFlow(ConsoleUiState())
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    // Every open bridge across all hosts (NOT filtered to this screen's hostId),
    // for the session drawer. uiState.bridges stays host-scoped for the terminal.
    private val _allBridges = MutableStateFlow<List<TerminalBridge>>(emptyList())
    val allBridges: StateFlow<List<TerminalBridge>> = _allBridges.asStateFlow()

    private val _networkStatusMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val networkStatusMessages: SharedFlow<String> = _networkStatusMessages.asSharedFlow()

    /** The compose draft of [bridge]'s terminal: unsent text comes back when its console opens again. */
    fun draftFor(bridge: TerminalBridge): DictationDraft = composeDrafts.draft(ComposeDraftStore.key(bridge.host.id, bridge.watch, bridge.console))

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

            // A console a tap asked to show while this screen is up: select it ([updateBridges]).
            viewModelScope.launch {
                manager.showConsole.collect { if (it != null) updateBridges(_allBridges.value) }
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

                // Check if we already have a bridge for this host (and, when one was asked, that console of it)
                val existingBridge = allBridges.find { bridge ->
                    bridge.host.id == hostId && (requested == null || bridge.console?.shows(requested) == true) &&
                        (if (watchRequested != null) bridge.watch?.address == watchRequested.address && !bridge.isDisconnected else bridge.watch == null)
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
                        // Permanent host - create from database; a requested console opens as its own
                        // bridge, beside the host's others or as its first.
                        val bridge = if (watchRequested != null) {
                            terminalManager?.openCoreWatchForHostId(hostId, watchRequested)
                        } else if (requested != null) {
                            terminalManager?.openHerdrConsoleForHostId(hostId, requested)
                        } else {
                            terminalManager?.openConnectionForHostId(hostId)
                        }
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
        // A screen opened for a core terminal shows that console alone, and none while it is not up yet.
        // If hostId is provided, try to find the bridge for this specific host
        val filteredBridges = if (watchRequested != null) {
            CoreView.consolesOf(allBridges, hostId, watchRequested)
        } else if (hostId != -1L) {
            allBridges.filter { bridge ->
                bridge.host.id == hostId
            }
        } else {
            allBridges
        }

        // The requested console, the first time it is in the list: show it. Only this host's
        // consoles count: another host's console of the same name is not it.
        val asked = requested?.takeIf { !requestedShown }?.let { console ->
            filteredBridges.indexOfFirst { it.console?.shows(console) == true }.takeIf { it >= 0 }
        }
        val watched = watchRequested?.takeIf { !requestedShown }?.let { target ->
            filteredBridges.indexOfFirst { it.watch?.address == target.address }.takeIf { it >= 0 }
        }
        if (asked != null || watched != null) requestedShown = true
        // A console a tap asked for (TerminalManager.showConsole), once it is in this host's list: shown, and the
        // view the tap asked for over it goes to this screen through the state, so that only the screen that shows
        // the console opens it (a view asked of the session itself could be taken by a console screen on its way out).
        val request = terminalManager?.let { manager ->
            manager.showConsole.value?.let { request ->
                filteredBridges.indexOfFirst { it.consoleId == request.consoleId }.takeIf { it >= 0 }?.let { index ->
                    manager.consoleShown(request.consoleId)
                    index to request.view
                }
            }
        }
        val shown = request?.first

        _uiState.update {
            val newBridges = if (watchRequested != null) filteredBridges else filteredBridges.ifEmpty { allBridges }
            val newIndex = shown ?: asked ?: watched ?: if (it.currentBridgeIndex >= newBridges.size) {
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
                openView = request?.second ?: it.openView,
                openOn = if (request?.second != null) filteredBridges[request.first] else it.openOn,
            )
        }
        publishVisibleHost()
    }

    /** The screen opened the view a tap asked for ([ConsoleUiState.openView]). */
    fun viewOpened() {
        _uiState.update { it.copy(openView = null, openOn = null) }
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
        // Leaving a core terminal's screen ends its watch: the session closes (the drawer's close, which
        // also stops AutoReconnect) and herdr-core stops counting the phone as a viewer.
        watchRequested?.let { watch ->
            // Another screen for the same terminal (a quick second tap) still shows it: that one closes it.
            if (!CoreView.viewerLeft(hostId, watch)) return@let
            terminalManager?.bridgesFlow?.value
                ?.let { CoreView.consolesOf(it, hostId, watch) }
                ?.forEach { it.dispatchDisconnect(DisconnectReason.USER_REQUESTED) }
        }
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

    /**
     * Open [console] on [host] in a console of its own beside the open ones (the one already
     * open on it when there is one); null without the service. Off the main thread: a
     * console reads its profile from the database as it starts.
     */
    suspend fun openHerdrConsole(host: Host, console: HerdrConsole): TerminalBridge? = withContext(dispatchers.io) {
        terminalManager?.openHerdrConsole(host, console)
    }

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
