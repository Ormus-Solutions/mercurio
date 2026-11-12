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

import com.trilead.ssh2.ExtendedServerHostKeyVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.Pubkey
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.HerdOfflineAnswer
import org.connectbot.transport.HeadlessSession
import org.connectbot.transport.HeadlessSsh
import org.connectbot.transport.SavedHostKeyVerifier
import org.connectbot.util.HostConstants
import org.connectbot.util.PubkeyUtils
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.CoreNotices
import solutions.ormus.logos.herd.HerdApproval
import solutions.ormus.logos.herd.HerdAttention
import java.security.KeyPair
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The end of a pushed blocked pane: the question its "Needs you" is about. The watcher on the
 * Herdr host pushes ids and labels only, never pane text, so the phone reads the pane itself:
 * a short login with no terminal, `herdr pane read`, log out. Nothing goes to any PTY.
 *
 * It logs in only when nobody has to be asked: keys that load without a passphrase or a
 * confirmation, and a host key already saved for the host ([SavedHostKeyVerifier]). The read
 * has the budget it is given; past it the result is dropped, the connection is closed and the
 * notice keeps what the push carried.
 */
@Singleton
class HerdPushTail internal constructor(
    private val hostRepository: HostRepository,
    private val pubkeyRepository: PubkeyRepository,
    dispatchers: CoroutineDispatchers,
    private val decode: (Pubkey) -> KeyPair?,
    private val open: (host: Host, verifier: ExtendedServerHostKeyVerifier, timeoutMs: Int) -> HeadlessSession,
) {
    @Inject
    constructor(hostRepository: HostRepository, pubkeyRepository: PubkeyRepository, dispatchers: CoroutineDispatchers) :
        this(
            hostRepository,
            pubkeyRepository,
            dispatchers,
            decode = { PubkeyUtils.convertToKeyPair(it, null) },
            open = ::openHeadless,
        )

    // Not a child of the caller: trilead's close() waits for a connect or login in progress (both
    // are synchronized), so a hung login is left here to end on its own timeouts.
    private val workers = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** What a read gave. */
    sealed interface Read {
        /**
         * The pane's last lines worth reading, and [prompt]: [CoreNotices.fingerprint] of the whole read, not of
         * these lines. Never logged, so not in [toString] either.
         */
        data class Text(val text: String, val prompt: Int? = null) : Read {
            override fun toString() = "Text(${text.length} chars)"
        }

        /** Tried, and the login, the command or the time ran out. */
        data object Missed : Read

        /** Not tried: logging in would ask someone (no usable key, a jump host), or the host key was never saved. */
        data object NotTried : Read
    }

    /** [notice]'s pane end on [host], read within [budgetMs] from this call. */
    suspend fun read(host: Host, notice: HerdAttention.Notice.NeedsYou, budgetMs: Long): Read {
        val started = System.nanoTime()
        val core = CoreNotices.isCore(notice.session)
        // The address came off the wire: only one `hc` could have given reaches a shell.
        if (core && !CoreNotices.isAddress(notice.agent.paneId)) return Read.NotTried
        if (!HerdOfflineAnswer.logsInAlone(host)) return Read.NotTried
        val stored = if (host.pubkeyId >= 0) pubkeyRepository.getById(host.pubkeyId) else null
        val keys = keys(host, pubkeyRepository.getStartupKeys(), stored, decode)
        if (keys.isEmpty()) return Read.NotTried
        val known = hostRepository.getKnownHostsForHost(host.id)
        // A host never connected from this phone has no saved key: nothing could pass the check.
        if (known.isEmpty()) return Read.NotTried
        val verifier = SavedHostKeyVerifier(known, hostRepository.getHostKeyAlgorithmsForHost(host.id))
        val left = budgetMs - (System.nanoTime() - started) / 1_000_000
        if (left <= 0) return Read.Missed
        val session = open(host, verifier, left.toInt())
        val text = withinBudget(left, close = session::close) {
            session.use {
                if (it.login(keys) != HeadlessSsh.Login.Ok) return@use null
                val out = it.exec(
                    if (core) CoreNotices.getCommand(notice.agent.paneId) else AgentsBoard.readCommand(notice.session, notice.agent.paneId, HerdApproval.READ_LINES),
                )
                // No exit status means the channel closed early: the output may be cut. A core terminal
                // answers `hc get --json`; its screen is what Herdr's `pane read` gives for a pane.
                val whole = when {
                    out.exitCode != 0 -> null
                    core -> CoreNotices.screenOf(out.stdout)
                    else -> out.stdout
                }
                whole?.let { screen -> HerdApproval.tail(screen)?.let { tail -> Read.Text(tail, CoreNotices.fingerprint(screen)) } }
            }
        }
        return text ?: Read.Missed
    }

    /**
     * [block] on a worker thread, waited for at most [budgetMs]. Past it the result is dropped
     * and [close] runs on another worker, which ends a blocked socket read at once; a connect
     * or login trilead holds ends on its own timeouts. Whatever [block] throws is null.
     */
    internal suspend fun <T : Any> withinBudget(budgetMs: Long, close: () -> Unit, block: () -> T?): T? {
        if (budgetMs <= 0) return null
        val worker = workers.async {
            try {
                block()
            } catch (_: Exception) {
                null
            }
        }
        try {
            return withTimeoutOrNull(budgetMs) { worker.await() }
        } finally {
            if (!worker.isCompleted) {
                worker.cancel()
                workers.launch { runCatching(close) }
            }
        }
    }

    companion object {
        /** The push service lives about five seconds after a push; the notice is posted before the read. */
        const val BUDGET_MS = 4_000L

        /** The login the app reads with: connect and key exchange each get [timeoutMs], the budget left. */
        internal fun openHeadless(host: Host, verifier: ExtendedServerHostKeyVerifier, timeoutMs: Int): HeadlessSession = HeadlessSsh(host, verifier, connectTimeoutMs = timeoutMs, kexTimeoutMs = timeoutMs)

        /**
         * The keys [host] logs in with when nobody can be asked: [startup] are the keys marked
         * "load at startup" (what "any key" means before the user unlocks more), [stored] the
         * host's own key when it names one; [decode] reads an unencrypted key. Encrypted keys,
         * keys that ask for confirmation and keys in the Android keystore (which may ask for a
         * fingerprint) are left out, as are jump hosts and hosts set to use no key.
         */
        fun keys(host: Host, startup: List<Pubkey>, stored: Pubkey?, decode: (Pubkey) -> KeyPair?): List<KeyPair> {
            if (!HerdOfflineAnswer.logsInAlone(host)) return emptyList()
            val candidates = if (host.pubkeyId == HostConstants.PUBKEYID_ANY) startup else listOfNotNull(stored)
            return candidates
                .filter { !it.encrypted && !it.confirmation && !it.isBiometric }
                .mapNotNull { runCatching { decode(it) }.getOrNull() }
        }
    }
}
