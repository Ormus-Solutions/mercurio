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

package org.connectbot.ui.workspaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.connectbot.data.HostRepository
import solutions.ormus.logos.herd.HerdrConsoleCache
import solutions.ormus.logos.herd.HerdrWorkspace
import solutions.ormus.logos.herd.HerdrWorkspaces
import javax.inject.Inject

/** Backs the Workspaces section of the host list: the saved hosts' kept console lists, as rows. */
@HiltViewModel
class WorkspacesViewModel @Inject constructor(
    hostRepository: HostRepository,
    private val consoles: HerdrConsoleCache,
) : ViewModel() {
    /** The workboards to offer, following the saved hosts and what was kept for each. */
    val workspaces: StateFlow<List<HerdrWorkspace>> = combine(hostRepository.observeHosts(), consoles.consoles) { hosts, kept ->
        HerdrWorkspaces.rows(hosts, kept)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { consoles.load() }
    }
}
