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

import kotlinx.coroutines.test.runTest
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.Pubkey
import org.connectbot.service.HerdOfflineAnswer.Plan
import org.connectbot.util.HostConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator

/** Approve or Deny with no live session: when Mercurio may log in on its own, and with which keys. */
class HerdOfflineAnswerTest {
    private val pair: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private val other: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }

    private val sun = Host(id = 7, nickname = "Sun", hostname = "100.100.1.1", username = "alice", pubkeyId = 3)

    private fun pubkey(
        nickname: String = "phone",
        encrypted: Boolean = false,
        confirmation: Boolean = false,
        storage: KeyStorageType = KeyStorageType.EXPORTABLE,
    ) = Pubkey(
        id = 3,
        nickname = nickname,
        type = "RSA",
        privateKey = byteArrayOf(0),
        publicKey = byteArrayOf(0),
        encrypted = encrypted,
        startup = false,
        confirmation = confirmation,
        createdDate = 0L,
        storageType = storage,
    )

    private fun holder(key: KeyPair, pubkey: Pubkey? = null, biometric: Boolean = false) = TerminalManager.KeyHolder().apply {
        this.pair = key
        this.pubkey = pubkey
        this.isBiometricKey = biometric
    }

    @Test
    fun plan_liveSessionIsUsed_andKeysAreNotEvenRead() = runTest {
        var read = false
        assertEquals(
            Plan.UseSession,
            HerdOfflineAnswer.plan(liveSession = true, host = sun) {
                read = true
                listOf(pair)
            },
        )
        assertTrue("no key work with a live session", !read)
    }

    @Test
    fun plan_connectsOnlyWhenAKeyIsReadyWithoutAPrompt() = runTest {
        assertEquals(Plan.Connect(listOf(pair)), HerdOfflineAnswer.plan(false, sun) { listOf(pair) })
        assertEquals("no key: a password would be asked", Plan.NeedsApp, HerdOfflineAnswer.plan(false, sun) { emptyList() })
        assertEquals(Plan.NeedsApp, HerdOfflineAnswer.plan(false, sun.copy(pubkeyId = HostConstants.PUBKEYID_NEVER)) { listOf(pair) })
        assertEquals("jump hosts can prompt", Plan.NeedsApp, HerdOfflineAnswer.plan(false, sun.copy(jumpHostId = 2)) { listOf(pair) })
        assertEquals(Plan.NeedsApp, HerdOfflineAnswer.plan(false, sun.copy(protocol = "telnet")) { listOf(pair) })
    }

    @Test
    fun keys_hostsOwnKey_unencryptedIsDecoded_loadedIsReused() {
        assertEquals(listOf(pair), HerdOfflineAnswer.keys(sun, emptyMap(), pubkey()) { pair })
        // Already unlocked in memory (a passphrase given earlier): no decode needed.
        assertEquals(listOf(other), HerdOfflineAnswer.keys(sun, mapOf("phone" to holder(other)), pubkey(encrypted = true)) { pair })
        assertEquals("unreadable key", emptyList<KeyPair>(), HerdOfflineAnswer.keys(sun, emptyMap(), pubkey()) { throw IllegalStateException() })
        assertEquals("key deleted", emptyList<KeyPair>(), HerdOfflineAnswer.keys(sun, emptyMap(), null) { pair })
    }

    @Test
    fun keys_neverOneThatWouldPrompt() {
        val none = emptyList<KeyPair>()
        assertEquals("passphrase", none, HerdOfflineAnswer.keys(sun, emptyMap(), pubkey(encrypted = true)) { pair })
        assertEquals("biometric", none, HerdOfflineAnswer.keys(sun, emptyMap(), pubkey(storage = KeyStorageType.ANDROID_KEYSTORE)) { pair })
        assertEquals("confirm before use", none, HerdOfflineAnswer.keys(sun, mapOf("phone" to holder(pair)), pubkey(confirmation = true)) { pair })
        assertEquals("biometric in memory", none, HerdOfflineAnswer.keys(sun, mapOf("phone" to holder(pair, biometric = true)), pubkey(encrypted = true)) { pair })
    }

    @Test
    fun keys_anyKeyHost_triesTheUnlockedKeysThatNeedNoConfirmation() {
        val any = sun.copy(pubkeyId = HostConstants.PUBKEYID_ANY)
        val loaded = mapOf(
            "plain" to holder(pair),
            "confirm" to holder(other, pubkey(nickname = "confirm", confirmation = true)),
            "bio" to holder(other, biometric = true),
        )
        assertEquals(listOf(pair), HerdOfflineAnswer.keys(any, loaded, null) { other })
        assertEquals(emptyList<KeyPair>(), HerdOfflineAnswer.keys(any, emptyMap(), null) { other })
    }
}
