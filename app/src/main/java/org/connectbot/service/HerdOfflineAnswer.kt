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

package org.connectbot.service

import org.connectbot.data.entity.Host
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.Pubkey
import org.connectbot.util.HostConstants
import java.security.KeyPair

/**
 * Approve or Deny from a notification when no session to the host is up: whether
 * Mercurio may log in on its own to send it. Only when nothing would ask the user
 * anything: a saved key that is unlocked or unencrypted, no password, passphrase,
 * biometric or confirm-before-use key, no jump host. Otherwise the answer fails and
 * says to open Mercurio.
 */
object HerdOfflineAnswer {
    /** What a notification answer does. */
    sealed interface Plan {
        /** A live Herdr session is up: answer over it. */
        data object UseSession : Plan

        /** Log in in the background with [keys], answer, then log out. */
        data class Connect(val keys: List<KeyPair>) : Plan

        /** Logging in would ask the user something. */
        data object NeedsApp : Plan
    }

    /** [keys] are only read when there is no live session. */
    suspend fun plan(liveSession: Boolean, host: Host, keys: suspend () -> List<KeyPair>): Plan {
        if (liveSession) return Plan.UseSession
        if (host.protocol != "ssh" || (host.jumpHostId ?: 0L) > 0L || host.pubkeyId == HostConstants.PUBKEYID_NEVER) return Plan.NeedsApp
        return keys().takeIf { it.isNotEmpty() }?.let { Plan.Connect(it) } ?: Plan.NeedsApp
    }

    /**
     * The keys [host] would log in with without a prompt. [loaded] are the keys unlocked in
     * memory (by nickname); [stored] is the host's own key when it names one; [decode]
     * reads an unencrypted stored key.
     */
    fun keys(host: Host, loaded: Map<String, TerminalManager.KeyHolder>, stored: Pubkey?, decode: (Pubkey) -> KeyPair?): List<KeyPair> {
        if (host.pubkeyId == HostConstants.PUBKEYID_ANY) {
            return loaded.values.filter { it.pubkey?.confirmation != true && !it.isBiometricKey }.mapNotNull { it.pair }
        }
        val key = stored ?: return emptyList()
        if (key.confirmation) return emptyList()
        loaded[key.nickname]?.takeIf { !it.isBiometricKey }?.pair?.let { return listOf(it) }
        if (key.encrypted || key.storageType == KeyStorageType.ANDROID_KEYSTORE) return emptyList()
        return listOfNotNull(runCatching { decode(key) }.getOrNull())
    }
}
