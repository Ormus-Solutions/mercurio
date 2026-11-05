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

package solutions.ormus.logos.herd

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.connectbot.di.CoroutineDispatchers
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What each Herdr host lists in [HerdrConsoles.LIST_PATH], kept on the phone after a connect
 * so a surface can offer the host's workboards before any connection is up, the way the
 * Machines list keeps the tailnet. One JSON value in the app's preferences, by host id. The
 * host's file is the source: a connect replaces what was kept, and a host that lists nothing
 * is forgotten.
 */
@Singleton
class HerdrConsoleCache @Inject constructor(
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
) {
    private val _consoles = MutableStateFlow<Map<Long, List<HerdrConsole>>>(emptyMap())

    /** The kept consoles by host id, in each host's order; empty until [load] or a connect ran. */
    val consoles: StateFlow<Map<Long, List<HerdrConsole>>> = _consoles.asStateFlow()

    private val lock = Mutex()
    private var loaded = false

    /** Publish what the preferences hold, read off the main thread, once. */
    suspend fun load() = lock.withLock { ensureLoaded() }

    /** Keep [consoles] as what the host [hostId] lists, in place of what was kept; an empty list forgets it. */
    suspend fun remember(hostId: Long, consoles: List<HerdrConsole>) = lock.withLock {
        ensureLoaded()
        val kept = _consoles.value
        val next = if (consoles.isEmpty()) kept - hostId else kept + (hostId to consoles)
        if (next != kept) write(next)
    }

    /** Drop what was kept for [hostId]: the host is gone. */
    suspend fun forget(hostId: Long) = remember(hostId, emptyList())

    private suspend fun ensureLoaded() {
        if (loaded) return
        // Loaded only once the read landed: a load cut short (a console's scope ending mid-read)
        // is read again next time, instead of an empty map standing in for what was kept.
        _consoles.value = withContext(dispatchers.io) { read() }
        loaded = true
    }

    private suspend fun write(next: Map<Long, List<HerdrConsole>>) {
        val kept = next.map { (host, list) -> Kept(host, list.map { KeptConsole(it.session, it.workspaceLabel) }) }
        // Memory first, then the preferences, and the write not cut short: the caller is often a
        // console whose scope ends when it disconnects.
        _consoles.value = next
        withContext(dispatchers.io + NonCancellable) { prefs.edit { putString(PREF_KEY, json.encodeToString(kept)) } }
    }

    private fun read(): Map<Long, List<HerdrConsole>> {
        val raw = prefs.getString(PREF_KEY, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<List<Kept>>(raw).associate { kept ->
                kept.host to kept.consoles.map { HerdrConsole(it.session, workspaceLabel = it.label) }
            }
        }.onFailure { Timber.w(it, "the kept Herdr console lists do not parse; starting over") }.getOrDefault(emptyMap())
    }

    @Serializable
    private data class Kept(val host: Long, val consoles: List<KeptConsole>)

    @Serializable
    private data class KeptConsole(val session: String, val label: String? = null)

    companion object {
        internal const val PREF_KEY = "herdr_consoles"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
