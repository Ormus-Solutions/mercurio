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

package solutions.ormus.logos.push

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.ConnectionNotifier
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageTracker
import solutions.ormus.logos.herd.HerdAttention
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a host's push into the notification the live Herdr monitor would post: same
 * channel, same tag (host id and pane id), Approve and Deny where [solutions.ormus.logos.herd.HerdApproval]
 * allows. A notice already showing for that pane makes no second sound: a "Finished" is left
 * alone, a "Needs you" is put in again quietly with its pane read afresh.
 */
@Singleton
class HerdPushNotices internal constructor(
    private val hostRepository: HostRepository,
    private val notifier: ConnectionNotifier,
    private val usageTracker: UsageTracker,
    private val tail: HerdPushTail,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        hostRepository: HostRepository,
        notifier: ConnectionNotifier,
        usageTracker: UsageTracker,
        tail: HerdPushTail,
        dispatchers: CoroutineDispatchers,
    ) : this(hostRepository, notifier, usageTracker, tail, CoroutineScope(SupervisorJob() + dispatchers.io), SystemClock::elapsedRealtime)

    /**
     * Post [content]'s notice; false (and logged) when it is unusable or from an unknown host.
     * The notice is posted before this returns. For a "Needs you" the pane's question is then
     * read on its own worker within [HerdPushTail.BUDGET_MS] of [arrived] (the
     * [SystemClock.elapsedRealtime] the push came in at) and put in quietly. [lock] ([holdForRead],
     * taken when the push arrived) is released when the read ends, or before this returns when
     * nothing is read.
     */
    suspend fun post(context: Context, content: ByteArray, arrived: Long = now(), lock: PowerManager.WakeLock = holdForRead(context)): Boolean {
        var reading = false
        try {
            val alert = HerdPushAlert.parse(content)
            val notice = alert?.notice()
            val host = alert?.let { HerdPushAlert.matchHost(it.host, hostRepository.getSshHosts()) }
            if (notice == null || host == null) {
                usageTracker.log(UsageActions.PUSH_IGNORED)
                return false
            }
            val shownAt = notifier.herdNoticeShownAt(context, host, notice)
            if (notice !is HerdAttention.Notice.NeedsYou) {
                if (shownAt == null) notifier.showHerdNotification(context, host, notice)
                return true
            }
            // The push for the block a notice already shows (the live monitor posts it too, often with its
            // question) leaves it as it is. The monitor's time is the phone's poll, after the host's.
            if (shownAt != null && notice.since?.let { it <= shownAt + SAME_BLOCK_SLACK_MS } == true) return true
            // A pane that blocks again while its notice shows: that notice's text is the earlier prompt's,
            // and Approve carries no prompt check, so the text goes at once. No second sound. A read still
            // running for the earlier block can no longer put its question back.
            val posted = synchronized(blocks) {
                val postedAt = notifier.showHerdNotification(context, host, notice, quiet = shownAt != null)
                Posted(blocks.merge(paneKey(host, notice), 1L, Long::plus)!!, postedAt)
            }
            // Posted first, so a slow or failed read never delays or loses the notice. The read is
            // not waited for: a cold push runs here on the main thread.
            val budget = (HerdPushTail.BUDGET_MS - (now() - arrived)).coerceIn(0L, HerdPushTail.BUDGET_MS)
            if (budget == 0L) {
                usageTracker.log(UsageActions.PUSH_TAIL_MISSED)
                return true
            }
            val app = context.applicationContext
            scope.launch {
                try {
                    readAndRepost(app, host, notice, budget, posted)
                } finally {
                    if (lock.isHeld) lock.release()
                }
            }
            reading = true
            return true
        } finally {
            if (!reading && lock.isHeld) lock.release()
        }
    }

    // The newest block pushed per pane (one entry per pane for the life of the process): a read reposts
    // only while its block is still the newest. Held while posting, so a repost and a new block's post
    // never interleave.
    private val blocks = HashMap<String, Long>()

    // What a push posted: its block's place in [blocks], and the time it set on its notice (from the
    // notifier, not read back: a device's shown list lags the post). A notice showing with another time was
    // put in by someone else (the live monitor, for a newer block).
    private data class Posted(val generation: Long, val shownAt: Long)

    private fun paneKey(host: Host, notice: HerdAttention.Notice) = "${host.username}@${host.hostname.lowercase()}:${host.port}/${notice.session}/${notice.agent.paneId}"

    private suspend fun readAndRepost(context: Context, host: Host, notice: HerdAttention.Notice.NeedsYou, budgetMs: Long, posted: Posted) {
        val read = try {
            tail.read(host, notice, budgetMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HerdPushTail.Read.Missed
        }
        // Nothing here may end the process: a log or a repost that throws leaves the notice as pushed.
        try {
            when (read) {
                // Logging in would ask someone, or the host key was never saved: nothing was tried.
                HerdPushTail.Read.NotTried -> Unit

                HerdPushTail.Read.Missed -> usageTracker.log(UsageActions.PUSH_TAIL_MISSED)

                is HerdPushTail.Read.Text -> {
                    usageTracker.log(UsageActions.PUSH_TAIL_READ)
                    // Only onto the notice this push posted, still showing: not one dismissed, nor one for
                    // a newer block. Between this check and the repost a dismiss or a monitor post can still
                    // slip in; the window is microseconds.
                    synchronized(blocks) {
                        val ours = blocks[paneKey(host, notice)] == posted.generation && notifier.herdNoticeShownAt(context, host, notice) == posted.shownAt
                        if (ours) {
                            notifier.showHerdNotification(context, host, notice.copy(paneTail = read.text), quiet = true)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Unit
        }
    }

    internal companion object {
        /**
         * The wake lock a push holds from its arrival: the connector's own ends before onMessage.
         * It ends by itself [WAKE_LOCK_SLACK_MS] after the budget, in case a release is never reached.
         */
        fun holdForRead(context: Context): PowerManager.WakeLock = (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(HerdPushTail.BUDGET_MS + WAKE_LOCK_SLACK_MS)
            }

        const val WAKE_LOCK_TAG = "mercurio:push-tail"

        /**
         * A push whose block began no later than this after the showing notice's is that same block: the
         * host's ts is in whole seconds and its clock may run a little ahead of the phone's.
         */
        const val SAME_BLOCK_SLACK_MS = 2_000L

        /** The lock outlives the budget a little, in case the read's own close runs late. */
        const val WAKE_LOCK_SLACK_MS = 1_000L
    }
}
