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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.util.PreferenceConstants
import solutions.ormus.logos.herd.CoreSpaces
import solutions.ormus.logos.home.HomeStart
import javax.inject.Inject

/** One machine's spaces ([hostId] tells two hosts of one nickname apart), or why they could not be read; what [SpacesContent] draws under the machine's header. */
data class SpacesSection(val hostId: Long, val machine: String, val result: CoreSpaces.Result)

/**
 * Backs the Spaces list: the spaces document of every connected host, one read per host at a time,
 * only while the herdr-core setting is on. Read only; nothing is written back to the host.
 */
@HiltViewModel
class SpacesViewModel @Inject constructor(
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    private val _sections = MutableStateFlow<List<SpacesSection>>(emptyList())
    val sections: StateFlow<List<SpacesSection>> = _sections.asStateFlow()

    private var manager: TerminalManager? = null

    // What each host's last read gave, by host id, and the hosts being read now. Main thread only.
    private val read = LinkedHashMap<Long, SpacesSection>()
    private val reading = HashSet<Long>()

    fun setTerminalManager(value: TerminalManager) {
        manager = value
    }

    /** Read now and again every [PERIOD_MS], for as long as the caller stays: the screen while it shows. */
    suspend fun watch() {
        while (true) {
            refresh()
            delay(PERIOD_MS)
        }
    }

    internal fun refresh() {
        val bridges = manager?.bridgesFlow?.value ?: return
        val enabled = HomeStart.coreOn(prefs)
        read.keys.retainAll(bridges.map { it.host.id }.toSet())
        if (!enabled) read.clear()
        _sections.value = read.values.toList()
        if (!enabled) return
        for (bridge in bridges) {
            val herd = bridge.herd ?: continue
            val host = bridge.host
            if (!reading.add(host.id)) continue
            viewModelScope.launch {
                try {
                    val result = try {
                        withContext(dispatchers.io) { CoreSpaces.read(true, herd::exec) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        CoreSpaces.Result.Unavailable(e.message ?: e::class.simpleName.orEmpty())
                    }
                    if (manager?.bridgesFlow?.value?.any { it.host.id == host.id } == true) read[host.id] = SpacesSection(host.id, host.nickname, result)
                } finally {
                    reading.remove(host.id)
                    _sections.value = read.values.toList()
                }
            }
        }
    }

    companion object {
        /** How long the Spaces list waits between two reads while it shows. */
        const val PERIOD_MS = 10_000L
    }
}
