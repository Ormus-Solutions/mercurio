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

package solutions.ormus.logos.commands

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.transport.CommandOutput
import timber.log.Timber
import java.io.File
import java.io.IOException

/** Ormus's own commands as last read from one host. */
data class OwnCommandsState(
    val commands: List<OwnCommand> = emptyList(),
    /** Epoch millis of the last good read; 0 when never read. */
    val fetchedAt: Long = 0L,
    /** The working directory whose project commands are included, if any. */
    val cwd: String? = null,
    val refreshing: Boolean = false,
    /** The last read failed (no SSH session, a timeout, a non-zero exit). */
    val failed: Boolean = false,
) {
    fun forAgent(agent: AgentKind): List<SlashCommand> = commands.filter { it.agent == agent }.map { it.command }
}

/**
 * Ormus's own slash commands per host: read over the session's SSH exec channel
 * with [OwnCommands.fetchCommand], cached in memory and in [dir] (one file per
 * host, the raw listing) so the menu fills at once on the next open. Also
 * remembers which agent was picked on each host.
 *
 * Disk and SSH work runs on the injected IO dispatcher; callers never block.
 */
class SlashCommandRepository(
    private val dir: File,
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val states = HashMap<Long, MutableStateFlow<OwnCommandsState>>()
    private val loaded = HashSet<Long>()

    private fun flowFor(hostId: Long): MutableStateFlow<OwnCommandsState> = synchronized(states) {
        states.getOrPut(hostId) { MutableStateFlow(OwnCommandsState()) }
    }

    /** The host's own commands; empty until [load] or [refresh] fills them. */
    fun state(hostId: Long): StateFlow<OwnCommandsState> = flowFor(hostId).asStateFlow()

    /** Read the host's cached listing from disk, once per host. */
    suspend fun load(hostId: Long) {
        if (!synchronized(loaded) { loaded.add(hostId) }) return
        val cached = withContext(dispatchers.io) { readCache(hostId) } ?: return
        flowFor(hostId).update { current -> if (current.fetchedAt == 0L) cached else current }
    }

    /**
     * Read the host's own commands again through [runner] (SSH execDetailed;
     * null when no session is up), with [cwd]'s project commands. Keeps the old
     * list on failure. A second call while one runs does nothing.
     */
    suspend fun refresh(hostId: Long, cwd: String?, runner: (String) -> CommandOutput?) {
        val flow = flowFor(hostId)
        while (true) {
            val current = flow.value
            if (current.refreshing) return
            if (flow.compareAndSet(current, current.copy(refreshing = true, failed = false))) break
        }
        val output = withContext(dispatchers.io) {
            try {
                runner(OwnCommands.fetchCommand(cwd))?.takeIf { it.exitCode == null || it.exitCode == 0 }?.stdout
            } catch (e: IOException) {
                Timber.w(e, "Reading own commands failed")
                null
            } catch (e: IllegalStateException) {
                Timber.w(e, "Reading own commands failed")
                null
            }
        }
        if (output == null) {
            flow.update { it.copy(refreshing = false, failed = true) }
            return
        }
        val fresh = OwnCommandsState(OwnCommands.parse(output), clock(), cwd)
        withContext(dispatchers.io) { writeCache(hostId, fresh, output) }
        flow.value = fresh
    }

    /** The agent last picked on [hostId], or null. */
    fun rememberedAgent(hostId: Long): AgentKind? = AgentKind.fromId(prefs.getString(agentKey(hostId), null))

    fun rememberAgent(hostId: Long, agent: AgentKind) {
        prefs.edit { putString(agentKey(hostId), agent.id) }
    }

    private fun agentKey(hostId: Long) = "$AGENT_KEY_PREFIX$hostId"

    private fun cacheFile(hostId: Long) = File(dir, "own-$hostId.txt")

    private fun readCache(hostId: Long): OwnCommandsState? {
        val file = cacheFile(hostId)
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            val header = text.substringBefore('\n')
            val parts = header.split(' ', limit = 4)
            if (parts.size < 3 || parts[0] != CACHE_MAGIC) return null
            val at = parts[1].toLongOrNull() ?: return null
            val cwd = parts.getOrNull(3)?.ifEmpty { null }
            OwnCommandsState(OwnCommands.parse(text.substringAfter('\n', "")), at, cwd)
        } catch (e: IOException) {
            Timber.w(e, "Own commands cache unreadable")
            null
        }
    }

    private fun writeCache(hostId: Long, state: OwnCommandsState, raw: String) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "own-$hostId.tmp")
            tmp.writeText("$CACHE_MAGIC ${state.fetchedAt} v1 ${state.cwd.orEmpty()}\n$raw")
            if (!tmp.renameTo(cacheFile(hostId))) {
                cacheFile(hostId).delete()
                tmp.renameTo(cacheFile(hostId))
            }
        } catch (e: IOException) {
            Timber.w(e, "Own commands cache not written")
        }
    }

    companion object {
        private const val CACHE_MAGIC = "#mercurio-own-commands"
        const val AGENT_KEY_PREFIX = "slashAgent."
    }
}
