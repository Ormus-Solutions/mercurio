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
import org.connectbot.service.TerminalBridge
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
 * plain shell), so five rows can be one machine. The Inbox and Spaces read herdr-core per Herdr machine only.
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

    /** Whether a saved host of [machine] starts Herdr at login: its post-login command is `herdr`, after `exec` or not. */
    fun marked(machine: Machine): Boolean = machine.hosts.any { startsHerdr(it.postLogin) }

    // The command is herdr (a path to it, or after exec): `cd ~/herdr && claude`, `tail -f /var/log/herdr` and `ssh m herdr` are not.
    private val STARTS_HERDR = Regex("""^(exec\s+)?(\S*/)?herdr(?=[\s;&|)]|$)""")

    internal fun startsHerdr(postLogin: String?): Boolean {
        val command = postLogin?.trim().orEmpty()
        return STARTS_HERDR.containsMatchIn(command) && HerdrOpen.attached(command) != null
    }

    /** The hosts of the consoles that found Herdr: the Herd session saw a Herdr snapshot, or its herdr-core read returned agents. */
    fun liveHosts(bridges: List<TerminalBridge>): List<Host> = bridges
        .filter { b -> b.herd?.let { it.herdrAnswered || it.coreRead.value?.result is CoreSource.Result.Agents } == true }
        .map { it.host }

    /** The machine [host] logs in to: hostname, port and user. */
    fun keyOf(host: Host) = "${host.hostname.trim().lowercase(Locale.ROOT)}:${host.port}:${host.username.trim()}"

    private const val PLAIN_HERDR = "herdr"
}

/**
 * One headless login per machine, held while a screen shows and let go when it leaves. Commands
 * run over exec channels of that one connection, so a poll costs no login. Nothing is asked: a
 * saved host key and keys that open without a passphrase, as [HerdPushTail] reads a pane. A login
 * that cannot be made is not retried before [RETRY_MS].
 *
 * The logins live in [pool], shared by every screen: the Inbox and Spaces of one machine use one,
 * and a login let go stays open for [CoreConnections.LINGER_MS], so the next screen finds it.
 */
class CoreLinks internal constructor(
    private val hosts: suspend () -> List<Host>,
    private val login: suspend (CoreMachines.Machine) -> Opened,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pool: CoreConnections = CoreConnections(lingerMs = 0),
    private val learned: HerdrHosts = HerdrHosts.inMemory(),
) : AutoCloseable {
    @Inject
    constructor(hostRepository: HostRepository, pubkeyRepository: PubkeyRepository, pool: CoreConnections, learned: HerdrHosts) :
        this({ hostRepository.getHosts() }, { openHeadless(it, hostRepository, pubkeyRepository) }, pool = pool, learned = learned)

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

    // What this holds in the pool as, one per visit: a let-go of an old visit cannot touch a newer one.
    private var holder = Any()
    private val failed = ConcurrentHashMap<String, Pair<Long, String>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val state = Any()
    private var generation = 0
    private var open = true

    /**
     * The Herdr machines among the saved hosts and [live] (hosts with a console that found Herdr, saved or not):
     * a machine a saved host marks, one in [HerdrHosts], or one of [live], which is added to the list so it
     * is still looked at when its console closes. Any other saved host gets no background login. The list is
     * tidied here too ([HerdrHosts.reconcile]): the key of a deleted host goes, and so does the key of a machine
     * whose saved host was edited to stop starting Herdr.
     */
    suspend fun machines(live: List<Host> = emptyList()): List<CoreMachines.Machine> {
        val all = CoreMachines.group(hosts() + live)
        val keys = all.map { it.key }.toSet()
        val marked = all.filter(CoreMachines::marked).map { it.key }.toSet()
        val liveKeys = live.map(CoreMachines::keyOf).filter { it in keys }.toSet()
        val known = learned.reconcile(keys, marked, liveKeys)
        return all.filter { it.key in marked || it.key in known }
    }

    /** Herdr is not on [machine]: it is not looked at again until a console to it finds Herdr. A marked machine stays marked. */
    fun forget(machine: CoreMachines.Machine) = learned.forget(machine.key)

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
        // Held in one step with close(): a screen that left holds nothing.
        val started = synchronized(state) {
            if (!open) return Link.Down(LEFT)
            pool.join(machine.key, holder)?.let { return up(machine, it) }
            generation
        }
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
                val kept = synchronized(state) {
                    if (open && generation == started) pool.put(machine.key, holder, opened.session) else null
                }
                if (kept == null) {
                    closeAside(opened.session)
                    return Link.Down(LEFT)
                }
                failed.remove(machine.key)
                up(machine, kept)
            }
        }
    }

    private fun up(machine: CoreMachines.Machine, session: HeadlessSession) = Link.Up { command ->
        val out = try {
            session.exec(command)
        } catch (e: Exception) {
            failed(machine, session)
            throw e
        }
        // No exit status: the channel closed early, so the connection is dead; the next read logs in again.
        if (out.exitCode == null) failed(machine, session) else pool.answered(machine.key, session)
        out
    }

    /**
     * A command failed on [session]: nobody gets it again, so the next read logs in again
     * ([CoreConnections.retire]: the terminals that run on it keep it until they let go). A late answer
     * from an older session retires nothing (it is not the key's any more).
     */
    private fun failed(machine: CoreMachines.Machine, session: HeadlessSession) {
        pool.retire(machine.key, session)
    }

    /** Let go of every machine, and make no more links until [reopen] (the next visit). A login that could not be made stays remembered. */
    override fun close() {
        val left = synchronized(state) {
            open = false
            generation++
            holder.also { holder = Any() }
        }
        pool.releaseAll(left)
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
