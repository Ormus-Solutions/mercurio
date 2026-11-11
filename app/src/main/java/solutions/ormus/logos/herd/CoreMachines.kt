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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.service.HerdOfflineAnswer
import org.connectbot.transport.CommandOutput
import org.connectbot.transport.HeadlessSession
import org.connectbot.transport.HeadlessSsh
import org.connectbot.transport.SavedHostKeyVerifier
import org.connectbot.util.PubkeyUtils
import solutions.ormus.logos.push.HerdPushTail
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.concurrent.thread

/**
 * The machines behind the saved hosts. The Herdr app keeps one connection per machine and shows
 * all of them at once; Mercurio saves one host per login (`herdr`, `herdr session attach 'x'`, a
 * plain shell), so five rows can be one machine. The Inbox and Spaces read herdr-core per machine.
 */
object CoreMachines {
    /** One machine: [hosts] are its saved logins, [primary] first. */
    data class Machine(val key: String, val hosts: List<Host>) {
        /** The login a tap opens: the plain `herdr` post-login row when there is one, else the first saved. */
        val primary: Host get() = hosts.first()

        /** What the user calls it: the primary's nickname. */
        val name: String get() = primary.nickname.ifBlank { primary.hostname }
    }

    /** The machines of [hosts]: SSH only, one per (hostname, port, username), in the order first met. */
    fun group(hosts: List<Host>): List<Machine> = hosts
        .filter { it.protocol == "ssh" && it.hostname.isNotBlank() }
        .distinctBy { it.id }
        .groupBy { keyOf(it) }
        .map { (key, same) -> Machine(key, same.sortedBy { if (it.postLogin?.trim() == PLAIN_HERDR) 0 else 1 }) }

    private fun keyOf(host: Host) = "${host.hostname.trim().lowercase(Locale.ROOT)}:${host.port}:${host.username.trim()}"

    private const val PLAIN_HERDR = "herdr"
}

/**
 * One headless login per machine, held while a screen shows and closed when it leaves. Commands
 * run over exec channels of that one connection, so a poll costs no login. Nothing is asked: a
 * saved host key and keys that open without a passphrase, as [HerdPushTail] reads a pane. A login
 * that cannot be made is not retried before [RETRY_MS].
 */
class CoreLinks internal constructor(
    private val hosts: suspend () -> List<Host>,
    private val login: suspend (CoreMachines.Machine) -> Opened,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    @Inject
    constructor(hostRepository: HostRepository, pubkeyRepository: PubkeyRepository) :
        this({ hostRepository.getHosts() }, { openHeadless(it, hostRepository, pubkeyRepository) })

    /** What a login came to. */
    sealed interface Opened {
        class Session(val session: HeadlessSession) : Opened

        class Skipped(val reason: String) : Opened
    }

    /** A way to run commands on a machine, or why there is none. */
    sealed interface Link {
        class Up(val run: (String) -> CommandOutput?) : Link

        class Down(val reason: String) : Link
    }

    private val held = ConcurrentHashMap<String, HeadlessSession>()
    private val failed = ConcurrentHashMap<String, Pair<Long, String>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val state = Any()
    private var generation = 0
    private var open = true

    /** The saved hosts, and [open] (hosts with a console that may not be saved), as machines. */
    suspend fun machines(open: List<Host> = emptyList()): List<CoreMachines.Machine> = CoreMachines.group(hosts() + open)

    /** Links can be made again: the screen shows. */
    fun reopen() {
        synchronized(state) { open = true }
    }

    /**
     * [machine]'s link: [viaOpen] when a console to it is open, else the held login (made now if
     * there is none; readers of one machine share one login). After [close] there is none until [reopen].
     */
    suspend fun link(machine: CoreMachines.Machine, viaOpen: ((String) -> CommandOutput?)? = null): Link {
        if (viaOpen != null) return Link.Up(viaOpen)
        return locks.getOrPut(machine.key) { Mutex() }.withLock { linkLocked(machine) }
    }

    private suspend fun linkLocked(machine: CoreMachines.Machine): Link {
        held[machine.key]?.let { return up(machine, it) }
        val started = synchronized(state) { if (open) generation else return Link.Down(LEFT) }
        failed[machine.key]?.let { (at, why) -> if (clock() - at < RETRY_MS) return Link.Down(why) }
        val opened = try {
            login(machine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = e.message ?: e::class.simpleName.orEmpty()
            failed[machine.key] = clock() to why
            throw e
        }
        return when (opened) {
            is Opened.Skipped -> {
                failed[machine.key] = clock() to opened.reason
                Link.Down(opened.reason)
            }

            is Opened.Session -> {
                // Held or refused in one step with close(): a screen that left cannot be left holding a login.
                val kept = synchronized(state) { (open && generation == started).also { if (it) held[machine.key] = opened.session } }
                if (!kept) {
                    closeAside(opened.session)
                    return Link.Down(LEFT)
                }
                failed.remove(machine.key)
                up(machine, opened.session)
            }
        }
    }

    private fun up(machine: CoreMachines.Machine, session: HeadlessSession) = Link.Up { command ->
        val out = try {
            session.exec(command)
        } catch (e: Exception) {
            drop(machine, session)
            throw e
        }
        // No exit status: the channel closed early, so the connection is dead; the next read logs in again.
        if (out.exitCode == null) drop(machine, session)
        out
    }

    private fun drop(machine: CoreMachines.Machine, session: HeadlessSession) {
        if (held.remove(machine.key, session)) closeAside(session)
    }

    /** Log out of every machine, and make no more links until [reopen] (the next visit). A login that could not be made stays remembered. */
    override fun close() {
        val all = synchronized(state) {
            open = false
            generation++
            held.values.toList().also { held.clear() }
        }
        all.forEach(::closeAside)
    }

    // trilead's close waits for a connect or login in progress: never on the caller's thread.
    private fun closeAside(session: HeadlessSession) {
        thread(isDaemon = true, name = "core-link-close") { runCatching { session.close() } }
    }

    companion object {
        /** How long a login that could not be made is left alone. */
        const val RETRY_MS = 30_000L
        const val LEFT = "the screen was left"

        /** The login the app uses: each saved host of the machine in turn, the first that opens with nobody asked. */
        internal suspend fun openHeadless(machine: CoreMachines.Machine, hostRepository: HostRepository, pubkeyRepository: PubkeyRepository): Opened {
            var why = "no saved login for it"
            for (host in machine.hosts) {
                if (!HerdOfflineAnswer.logsInAlone(host)) {
                    why = "its login asks for something Mercurio cannot answer alone"
                    continue
                }
                val stored = if (host.pubkeyId >= 0) pubkeyRepository.getById(host.pubkeyId) else null
                val keys = HerdPushTail.keys(host, pubkeyRepository.getStartupKeys(), stored) { PubkeyUtils.convertToKeyPair(it, null) }
                if (keys.isEmpty()) {
                    why = "no key that opens without asking"
                    continue
                }
                val known = hostRepository.getKnownHostsForHost(host.id)
                if (known.isEmpty()) {
                    why = "open it once in Mercurio so its host key is saved"
                    continue
                }
                val session = HeadlessSsh(host, SavedHostKeyVerifier(known, hostRepository.getHostKeyAlgorithmsForHost(host.id)))
                val result = try {
                    session.login(keys)
                } catch (e: Exception) {
                    HeadlessSsh.Login.Failed(e.message ?: e::class.simpleName.orEmpty())
                }
                if (result == HeadlessSsh.Login.Ok) return Opened.Session(session)
                runCatching { session.close() }
                why = when (result) {
                    HeadlessSsh.Login.HostKeyRefused -> "its host key changed: open it in Mercurio"
                    HeadlessSsh.Login.KeysRefused -> "it took none of the keys"
                    is HeadlessSsh.Login.Failed -> "could not log in: ${result.message}"
                    HeadlessSsh.Login.Ok -> why
                }
            }
            return Opened.Skipped(why)
        }
    }
}
