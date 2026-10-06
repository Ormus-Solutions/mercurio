/*
 * Copied from Termish, https://github.com/farlume/termish (commit ee9e460),
 * composeApp/src/commonMain/kotlin/dev/termish/herdr/HerdrMonitor.kt.
 * Mercurio changes: Timber logging, onSnapshot/onBlocked callbacks instead of
 * Termish notifications, notifiedBlocked clears on unblock; comments
 * translated.
 *
 * MIT License
 *
 * Copyright (c) 2026 Termish Project Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package solutions.ormus.logos.herd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.random.Random

/**
 * Herdr agent monitor (polls `herdr api snapshot`).
 *
 * One per host session: runs `herdr api snapshot` through [runCommand] on the
 * authenticated SSH connection, turns each snapshot into transition events
 * with [HerdrAgentStateMachine] and hands them to the UI (badges / panel);
 * a blocked agent confirmed on two consecutive rounds calls [onBlocked] (the
 * entry point for answering from the phone).
 *
 * Discipline:
 * - the poll interval has +/-20% jitter against thundering herds; the caller
 *   starts it only while the host has a live session and stops it when the
 *   session closes (battery)
 * - failure backoff 3s -> 15s; after 3 consecutive failures pause ~60s (no
 *   hammering a host where herdr is missing or the server is not running)
 * - blocked debounce: a pane must stay blocked on two consecutive rounds before
 *   [onBlocked] fires, so a momentary blocked (an agent asking itself) does not
 *   alert; Unblocked or the pane disappearing clears it
 */
class HerdrMonitor(
    private val hostName: String,
    /** Runs a control-plane command on the authenticated connection (SSH.execDetailed). */
    private val runCommand: (String) -> String?,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 4_000,
    /** Transition events callback (UI badge / panel refresh). */
    private val onEvents: (List<HerdrAgentEvent>) -> Unit = {},
    /** The full agents list after every snapshot (UI panel data source; includes unchanged rounds). */
    private val onAgents: (List<HerdrAgentInfo>) -> Unit = {},
    /** Mercurio: the whole snapshot after every successful poll (the Herd screen reads tabs and workspaces too). */
    private val onSnapshot: (HerdrSessionSnapshot) -> Unit = {},
    /** Mercurio: a blocked agent confirmed on two rounds (Termish posted its own notification here). */
    private val onBlocked: (HerdrAgentInfo) -> Unit = {},
) {
    companion object {
        /**
         * Snapshot command candidates (rotated on failure, pinned once one works).
         * The single source of truth is [HerdrApi.SNAPSHOT_CMD_CANDIDATES].
         */
        private val SNAPSHOT_CMD_CANDIDATES = HerdrApi.SNAPSHOT_CMD_CANDIDATES
        private const val MIN_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 15_000L

        /** Pause rounds after repeated failures (~60s at a 4s poll): pure delay, so tests can advance virtual time. */
        private const val PAUSE_ROUNDS = 15
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /** Blocked confirmation rounds: a pane must still be blocked on two consecutive rounds. */
        private const val BLOCKED_CONFIRM_ROUNDS = 2
    }

    private val machine = HerdrAgentStateMachine()
    private var job: Job? = null

    /** pane_id -> rounds confirmed blocked (removed once it reaches the threshold and fires). */
    private val pendingNotify = HashMap<String, Int>()

    /** Panes already reported blocked (no repeats; cleared when the pane leaves blocked). */
    private val notifiedBlocked = HashSet<String>()

    /** Index of the herdr command candidate in use (switched when a poll fails). */
    private var cmdIndex = 0

    /** Whether polling is running. */
    fun isRunning(): Boolean = job?.isActive == true

    /** Current per-pane states (UI reads). */
    fun currentStatus(): Map<String, HerdrAgentStatus> = machine.current()

    fun start() {
        if (job?.isActive == true) return
        machine.reset()
        pendingNotify.clear()
        notifiedBlocked.clear()
        Timber.i("herdr monitor start %s", hostName)
        job =
            scope.launch {
                var backoffMs = MIN_BACKOFF_MS
                var failures = 0
                var pauseRounds = 0
                while (isActive) {
                    // Pause after repeated failures: do not hammer the host (herdr missing / not started / disconnected)
                    if (pauseRounds > 0) {
                        pauseRounds--
                        delay(jitter(pollIntervalMs))
                        continue
                    }
                    val raw = runCommand(currentCmd())
                    val snapshot = raw?.let { parseHerdrSnapshot(it) }
                    if (snapshot == null) {
                        // Failure backoff: herdr missing / not started / disconnected; try the next candidate (round robin)
                        cmdIndex = (cmdIndex + 1) % SNAPSHOT_CMD_CANDIDATES.size
                        failures++
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        Timber.d("herdr poll failed %s #%d: %s", hostName, failures, raw?.take(80) ?: "null")
                        if (failures >= MAX_CONSECUTIVE_FAILURES) {
                            pauseRounds = PAUSE_ROUNDS
                            failures = 0
                            backoffMs = MIN_BACKOFF_MS
                        }
                        delay(backoffMs)
                        continue
                    }
                    failures = 0
                    backoffMs = MIN_BACKOFF_MS
                    val events = machine.update(snapshot)
                    if (events.isNotEmpty()) {
                        Timber.d("herdr events %s: %s", hostName, events.joinToString { it::class.simpleName ?: "?" })
                        // Mercurio fix: a pane that leaves blocked may alert again the next
                        // time it blocks. Termish only cleared this set on stop(), so the
                        // second question from the same agent never reached the phone.
                        events.filterIsInstance<HerdrAgentEvent.Unblocked>().forEach {
                            notifiedBlocked.remove(it.paneId)
                            pendingNotify.remove(it.paneId)
                        }
                        onEvents(events)
                    }
                    onAgents(snapshot.agents)
                    onSnapshot(snapshot)
                    confirmBlocked(snapshot)
                    delay(jitter(pollIntervalMs))
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        machine.reset()
        pendingNotify.clear()
        notifiedBlocked.clear()
        Timber.i("herdr monitor stop %s", hostName)
    }

    /**
     * Blocked confirmation: a new Blocked enters the confirmation queue; a pane
     * confirmed for >= 2 rounds that is still blocked calls [onBlocked]
     * (Unblocked / leaving the state is cleared on the events path above).
     */
    private fun confirmBlocked(snapshot: HerdrSessionSnapshot) {
        val current = machine.current()
        // Panes that left blocked leave the confirmation queue
        pendingNotify.keys.filter { current[it] != HerdrAgentStatus.BLOCKED }.forEach { pendingNotify.remove(it) }
        for (a in snapshot.agents) {
            if (a.agentStatus != HerdrAgentStatus.BLOCKED) continue
            val rounds = (pendingNotify[a.paneId] ?: 0) + 1
            if (rounds >= BLOCKED_CONFIRM_ROUNDS) {
                pendingNotify.remove(a.paneId)
                // No repeats: a pane that stays blocked is reported once (leaving blocked resets it)
                if (notifiedBlocked.add(a.paneId)) {
                    postBlockedNotification(a)
                }
            } else {
                pendingNotify[a.paneId] = rounds
            }
        }
    }

    private fun postBlockedNotification(a: HerdrAgentInfo) {
        Timber.w("herdr agent BLOCKED %s %s agent=%s title=%s", hostName, a.paneId, a.agent, a.title())
        onBlocked(a)
    }

    /** Notification body (a standalone pure function for tests). */
    internal fun blockedNotificationText(
        hostName: String,
        a: HerdrAgentInfo,
    ): String {
        val agent = a.agent?.takeIf { it.isNotBlank() } ?: "agent"
        return "$agent on $hostName is waiting for you: ${a.title() ?: a.cwd ?: "open the session"}"
    }

    private fun currentCmd(): String = SNAPSHOT_CMD_CANDIDATES[cmdIndex]

    private fun HerdrAgentInfo.title(): String? = terminalTitleStripped ?: terminalTitle

    private fun jitter(base: Long): Long = (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
}
