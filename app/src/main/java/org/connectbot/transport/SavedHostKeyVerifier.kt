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

import com.trilead.ssh2.ExtendedServerHostKeyVerifier
import com.trilead.ssh2.KnownHosts
import org.connectbot.data.entity.KnownHost
import timber.log.Timber
import java.util.Locale

/**
 * [known] as trilead's [KnownHosts]: one entry per saved key, under every `name:port` it was
 * saved for, so a host renamed after its key was saved still matches.
 */
internal fun knownHostsOf(known: List<KnownHost>): KnownHosts {
    val hosts = KnownHosts()
    data class KeyGroup(val algo: String, val key: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyGroup) return false
            return algo == other.algo && key.contentEquals(other.key)
        }

        override fun hashCode(): Int = 31 * algo.hashCode() + key.contentHashCode()
    }
    known.groupBy { KeyGroup(it.hostKeyAlgo, it.hostKey) }.forEach { (group, entries) ->
        try {
            hosts.addHostkey(entries.map { "${it.hostname}:${it.port}" }.toTypedArray(), group.algo, group.key)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add known host key")
        }
    }
    return hosts
}

/**
 * Mercurio: the host key check of a login nobody watches (a push reading a pane). It takes only
 * a key already saved for the host, [known]; a new or changed key is the user's call, made in
 * the app, so it is refused and nothing is saved or removed.
 *
 * [algorithms] are the saved keys' algorithms, offered first so the server shows a key that
 * can match (see [org.connectbot.data.HostRepository.getHostKeyAlgorithmsForHost]).
 */
class SavedHostKeyVerifier(
    known: List<KnownHost>,
    private val algorithms: List<String>,
) : ExtendedServerHostKeyVerifier() {
    private val hosts = knownHostsOf(known)

    override fun verifyServerHostKey(hostname: String, port: Int, serverHostKeyAlgorithm: String, serverHostKey: ByteArray): Boolean = hosts.verifyHostkey(String.format(Locale.US, "%s:%d", hostname, port), serverHostKeyAlgorithm, serverHostKey) == KnownHosts.HOSTKEY_IS_OK

    override fun getKnownKeyAlgorithmsForHost(host: String, port: Int): List<String>? = algorithms.ifEmpty { null }

    override fun removeServerHostKey(host: String, port: Int, algorithm: String, hostKey: ByteArray?) = Unit

    override fun addServerHostKey(hostname: String, port: Int, algorithm: String, hostKey: ByteArray) = Unit
}
