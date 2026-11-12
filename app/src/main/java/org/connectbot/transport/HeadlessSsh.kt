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

package org.connectbot.transport

import com.trilead.ssh2.Connection
import com.trilead.ssh2.ExtendedServerHostKeyVerifier
import org.connectbot.data.entity.Host
import solutions.ormus.logos.herd.HostShell
import java.io.IOException
import java.security.KeyPair
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A short SSH login with no terminal, for work that cannot wait for the user (an
 * answer from a notification): no PTY, no shell, nothing typed anywhere; commands run
 * over exec channels. Nothing is asked either: the host key must pass [verifier]
 * without a prompt (a new or changed key is refused), and only the given keys are tried.
 */
class HeadlessSsh(
    private val host: Host,
    private val verifier: ExtendedServerHostKeyVerifier,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val kexTimeoutMs: Int = KEX_TIMEOUT_MS,
) : HeadlessSession {
    /** How the login went. */
    sealed interface Login {
        data object Ok : Login

        /** The host key is new or changed: only the user can accept it. */
        data object HostKeyRefused : Login

        /** The server took none of the keys. */
        data object KeysRefused : Login

        /** No connection, or it broke; [message] is the first line of why. */
        data class Failed(val message: String) : Login
    }

    private val connection = Connection(host.hostname, host.port)

    // trilead cannot remove a monitor once added: one monitor tells the borrowers that hold it now.
    private class Lost(val tell: (Throwable) -> Unit)

    private val lostTo = CopyOnWriteArrayList<Lost>()

    // Plain flags, so asking costs no lock (trilead's Connection methods are synchronized).
    @Volatile
    private var lost = false

    @Volatile
    private var loggedIn = false

    @Volatile
    private var closed = false

    init {
        connection.addConnectionMonitor { reason ->
            lost = true
            lostTo.forEach { runCatching { it.tell(reason) } }
        }
    }

    override val lent: Connection?
        get() = connection.takeIf { alive }

    override val alive: Boolean
        get() = loggedIn && !lost && !closed

    override fun onLost(lost: (Throwable) -> Unit): AutoCloseable {
        val entry = Lost(lost)
        lostTo += entry
        return AutoCloseable { lostTo -= entry }
    }

    /**
     * Connect and log in with the first of [keys] the server takes. Blocking. The TCP connect
     * has [connectTimeoutMs] and the key exchange [kexTimeoutMs] from the start of the connect;
     * the authentication itself has no timeout of its own.
     */
    override fun login(keys: List<KeyPair>): Login {
        var hostKeyRefused = false
        val checked = object : ExtendedServerHostKeyVerifier() {
            override fun verifyServerHostKey(hostname: String, port: Int, algorithm: String, key: ByteArray): Boolean = verifier.verifyServerHostKey(hostname, port, algorithm, key).also { hostKeyRefused = !it }

            override fun getKnownKeyAlgorithmsForHost(hostname: String, port: Int): List<String>? = verifier.getKnownKeyAlgorithmsForHost(hostname, port)

            override fun removeServerHostKey(hostname: String, port: Int, algorithm: String, key: ByteArray?) = verifier.removeServerHostKey(hostname, port, algorithm, key)

            override fun addServerHostKey(hostname: String, port: Int, algorithm: String, key: ByteArray) = verifier.addServerHostKey(hostname, port, algorithm, key)
        }
        return try {
            connection.connect(checked, connectTimeoutMs, kexTimeoutMs, SSH.parseIpVersion(host.ipVersion, host.hostname))
            val user = host.username.trim()
            if (keys.any { connection.authenticateWithPublicKey(user, it) }) Login.Ok.also { loggedIn = true } else Login.KeysRefused
        } catch (e: IOException) {
            if (hostKeyRefused) Login.HostKeyRefused else Login.Failed(firstLine(e))
        }
    }

    // Mercurio: a Windows host runs exec commands through cmd.exe; HostShell rewrites them once it says so.
    private val hostShell = HostShell()

    /** Run [command] over its own exec channel; see [SSH.execDetailed]. Blocking. */
    override fun exec(command: String): CommandOutput = hostShell.run(command) { SSH.execDetailed(connection, it) }

    override fun close() {
        closed = true
        connection.close()
    }

    private fun firstLine(e: Throwable): String = generateSequence(e) { it.cause }
        .mapNotNull { it.message?.lineSequence()?.firstOrNull { line -> line.isNotBlank() }?.trim() }
        .lastOrNull() ?: e.javaClass.simpleName

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val KEX_TIMEOUT_MS = 15_000
    }
}

/** What a login with no terminal offers: log in, run commands, log out ([HeadlessSsh]). */
interface HeadlessSession : AutoCloseable {
    /** Log in with the first of [keys] the server takes. Blocking. */
    fun login(keys: List<KeyPair>): HeadlessSsh.Login

    /** Run [command] over its own exec channel. Blocking. */
    fun exec(command: String): CommandOutput

    /**
     * The signed-in connection itself, for a console that opens its terminal on it ([SSH] borrows it
     * from [solutions.ormus.logos.herd.CoreConnections]); null when there is none to lend. A borrower never closes it.
     */
    val lent: Connection? get() = null

    /** Call [lost] when the connection drops, until the returned handle is closed. */
    fun onLost(lost: (Throwable) -> Unit): AutoCloseable = AutoCloseable {}

    /** False once the connection is known to be down (it dropped, or was closed); true while it may be up. */
    val alive: Boolean get() = true

    /** Log out; safe to call more than once. trilead waits for a connect or login in progress to end first. */
    override fun close()
}
