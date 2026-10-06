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

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package org.connectbot.usage

import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.connectbot.di.CoroutineDispatchers
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Records that a control was used. One call per click handler. */
fun interface UsageLog {
    fun log(actionId: String)

    /** Whether the console's active session runs Herdr; null when no session shows. */
    fun setHerdr(value: Boolean?) = Unit
}

/** Records nothing; the default outside the app (previews, tests). */
object NoUsageLog : UsageLog {
    override fun log(actionId: String) = Unit
}

/**
 * The app's [UsageLog], provided at the activity root so any control can log with
 * `LocalUsageLog.current.log(UsageActions.X)` without new parameters.
 */
val LocalUsageLog = staticCompositionLocalOf<UsageLog> { NoUsageLog }

/** Wall clock, injectable so tests control time. */
fun interface UsageClock {
    fun now(): Long
}

/** Settings keys for the usage log and the Sun upload. */
object UsageSettings {
    const val KEY_RECORD = "usageRecord"
    const val KEY_SUN_HOSTS = "usageSunHosts"
    const val KEY_SUN_HOST_KEY = "usageSunHostKey"

    /** "Record my usage" defaults on: Ormus asked for it. */
    const val RECORD_DEFAULT = true

    /** No upload host by default: usage and wishes stay on the phone until one is set. */
    const val SUN_HOSTS_DEFAULT = ""

    /** No pinned host key by default; an upload needs one that matches. */
    const val SUN_HOST_KEY_DEFAULT = ""
}

/**
 * Appends usage events to the [UsageStore] off the main thread, in call order.
 *
 * [log] is cheap and safe from a click handler: it checks the "Record my usage"
 * setting, drops ids missing from [UsageActions], stamps the current screen and
 * host type, and queues the write.
 */
@Singleton
class UsageTracker @Inject constructor(
    private val store: UsageStore,
    private val prefs: SharedPreferences,
    dispatchers: CoroutineDispatchers,
    private val clock: UsageClock,
) : UsageLog {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val queue = Channel<Draft>(Channel.UNLIMITED)

    @Volatile
    private var screen: String? = null

    @Volatile
    private var herdr: Boolean? = null

    private data class Draft(
        val ts: Long,
        val action: String,
        val screen: String?,
        val herdr: Boolean?,
        val durationMs: Long?,
        val reason: String?,
    )

    init {
        scope.launch {
            store.rollUp(clock.now())
            for (draft in queue) {
                store.append(draft.ts, draft.action, draft.screen, draft.herdr, draft.durationMs, draft.reason)
            }
        }
    }

    val enabled: Boolean
        get() = prefs.getBoolean(UsageSettings.KEY_RECORD, UsageSettings.RECORD_DEFAULT)

    /** The screen now showing, from its navigation route. */
    fun setScreen(route: String?) {
        screen = UsageActions.screenToken(route)
        if (screen != CONSOLE_SCREEN) herdr = null
    }

    override fun setHerdr(value: Boolean?) {
        herdr = value
    }

    /** Current screen and host type, for stamping a wish. */
    fun context(): Pair<String?, Boolean?> = screen to herdr

    override fun log(actionId: String) = record(actionId, herdr, null, null)

    // Session lifecycle, keyed by the session object (a terminal bridge). Weak keys:
    // a session the service forgets never pins memory here.
    private enum class SessionState { CONNECTING, OPEN }

    private val sessions = WeakHashMap<Any, Pair<SessionState, Long>>()
    private val seenSessions = WeakHashMap<Any, Boolean>()

    /** A session starts connecting; a second attempt on the same session is a reconnect. */
    fun sessionConnecting(session: Any, herdr: Boolean?) {
        val again = synchronized(sessions) {
            sessions[session] = SessionState.CONNECTING to clock.now()
            seenSessions.put(session, true) != null
        }
        if (again) record(UsageActions.SESSION_RECONNECT, herdr, null, null)
    }

    fun sessionOpened(session: Any, herdr: Boolean?) {
        synchronized(sessions) { sessions[session] = SessionState.OPEN to clock.now() }
        record(UsageActions.SESSION_START, herdr, null, null)
    }

    /**
     * A session ends. An open one records its duration; one that never opened records
     * a failure. [reason] is a reason class such as a disconnect enum name.
     */
    fun sessionClosed(session: Any, herdr: Boolean?, reason: String) {
        val (state, since) = synchronized(sessions) { sessions.remove(session) } ?: return
        when (state) {
            SessionState.OPEN -> record(UsageActions.SESSION_END, herdr, clock.now() - since, reason)
            SessionState.CONNECTING -> record(UsageActions.SESSION_FAIL, herdr, null, reason)
        }
    }

    private fun record(actionId: String, herdr: Boolean?, durationMs: Long?, reason: String?) {
        if (!enabled || !UsageActions.isKnown(actionId)) return
        val reasonClass = reason?.takeIf { REASON.matches(it) }
        queue.trySend(Draft(clock.now(), actionId, screen, herdr, durationMs, reasonClass))
    }

    companion object {
        const val CONSOLE_SCREEN = "console"

        // Reason classes are identifiers (enum or exception class names), never messages.
        private val REASON = Regex("[A-Za-z0-9_]{1,64}")
    }
}
