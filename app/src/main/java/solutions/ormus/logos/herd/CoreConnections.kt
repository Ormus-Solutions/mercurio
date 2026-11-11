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

import org.connectbot.transport.HeadlessSession
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * The signed-in links of the app, one per machine, shared by everything that works on herdr-core
 * there: the Inbox, Spaces ([CoreLinks]), and later a console. The Herdr app keeps one connection
 * per machine the same way. A link stays open while anyone holds it. When the last holder lets go,
 * it stays [lingerMs] longer: the screen a tap opens just after the Inbox leaves, or the Inbox shown
 * again, finds it signed in and makes no new login.
 *
 * A holder is any object; holding twice is holding once.
 */
@Singleton
class CoreConnections internal constructor(
    private val lingerMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val later: (Long, () -> Unit) -> Unit = ::schedule,
    private val closer: (HeadlessSession) -> Unit = ::closeAside,
) {
    @Inject
    constructor() : this(LINGER_MS)

    private class Entry(val session: HeadlessSession, var lastOk: Long) {
        val holders = HashSet<Any>()

        // Bumped by every hold and every last let-go: a linger that ends finds out if it is still the latest.
        var turn = 0
    }

    private val entries = HashMap<String, Entry>()

    /** [key]'s session, now held by [holder] too; null when none is open. */
    @Synchronized
    fun join(key: String, holder: Any): HeadlessSession? = entries[key]?.let { hold(it, holder) }

    /** Like [join], only when the session answered within [withinMs]: a link idle for long can be dead without a sign. */
    @Synchronized
    fun joinFresh(key: String, holder: Any, withinMs: Long): HeadlessSession? = entries[key]?.takeIf { clock() - it.lastOk <= withinMs }?.let { hold(it, holder) }

    /**
     * Keep [session], just signed in, as [key]'s, held by [holder]. When one is open already (two
     * logins raced), that one is kept and returned, and [session] is closed. [closer] must not block:
     * a caller may hold its own lock here.
     */
    fun put(key: String, holder: Any, session: HeadlessSession): HeadlessSession {
        val kept = synchronized(this) {
            entries[key]?.let { return@synchronized hold(it, holder) }
            entries[key] = Entry(session, clock()).also { hold(it, holder) }
            session
        }
        if (kept !== session) closer(session)
        return kept
    }

    /** [session] of [key] answered a command: it was alive now. */
    @Synchronized
    fun answered(key: String, session: HeadlessSession) {
        entries[key]?.takeIf { it.session === session }?.lastOk = clock()
    }

    /** [holder] lets go of [key]. The last one starts the linger; the session closes at its end unless held again. */
    fun release(key: String, holder: Any) {
        val (entry, turn) = synchronized(this) {
            val entry = entries[key] ?: return
            if (!entry.holders.remove(holder) || entry.holders.isNotEmpty()) return
            entry to ++entry.turn
        }
        if (lingerMs <= 0) expire(key, entry, turn) else later(lingerMs) { expire(key, entry, turn) }
    }

    /** [holder] lets go of every key it holds, as [release] does for each. */
    fun releaseAll(holder: Any) {
        val ended = synchronized(this) {
            entries.mapNotNull { (key, entry) -> if (entry.holders.remove(holder) && entry.holders.isEmpty()) Triple(key, entry, ++entry.turn) else null }
        }
        ended.forEach { (key, entry, turn) -> if (lingerMs <= 0) expire(key, entry, turn) else later(lingerMs) { expire(key, entry, turn) } }
    }

    /** [session] of [key] is dead: nobody gets it again, and it closes. False when it was not [key]'s session (any more). */
    fun drop(key: String, session: HeadlessSession): Boolean {
        val gone = synchronized(this) { entries[key]?.takeIf { it.session === session }?.also { entries.remove(key) } }
        if (gone != null) closer(session)
        return gone != null
    }

    private fun hold(entry: Entry, holder: Any): HeadlessSession {
        if (entry.holders.add(holder)) entry.turn++
        return entry.session
    }

    private fun expire(key: String, entry: Entry, turn: Int) {
        val ended = synchronized(this) {
            (entries[key] === entry && entry.holders.isEmpty() && entry.turn == turn).also { if (it) entries.remove(key) }
        }
        if (ended) closer(entry.session)
    }

    companion object {
        /** How long a link nobody holds stays open. */
        const val LINGER_MS = 60_000L

        private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "core-link-linger").apply { isDaemon = true } }

        private fun schedule(delayMs: Long, run: () -> Unit) {
            timer.schedule(run, delayMs, TimeUnit.MILLISECONDS)
        }

        // trilead's close waits for a connect or login in progress: never on the caller's thread.
        private fun closeAside(session: HeadlessSession) {
            thread(isDaemon = true, name = "core-link-close") { runCatching { session.close() } }
        }
    }
}
