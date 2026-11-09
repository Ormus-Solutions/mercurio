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

import com.trilead.ssh2.KnownHosts
import org.connectbot.data.entity.KnownHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class SavedHostKeyVerifierTest {
    // An ssh-ed25519 public key blob: the algorithm name, then the 32-byte key, each length-prefixed.
    private fun ed25519(fill: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            val name = "ssh-ed25519".toByteArray()
            data.writeInt(name.size)
            data.write(name)
            data.writeInt(32)
            data.write(ByteArray(32) { (fill + it).toByte() })
        }
        return out.toByteArray()
    }

    private val saved = ed25519(1)
    private val other = ed25519(2)

    private fun known(hostname: String = "sg.example.ts.net", port: Int = 22, key: ByteArray = saved) = KnownHost(hostId = 28, hostname = hostname, port = port, hostKeyAlgo = "ssh-ed25519", hostKey = key)

    @Test
    fun theSavedKey_passes() {
        val verifier = SavedHostKeyVerifier(listOf(known()), listOf("ssh-ed25519"))

        assertTrue(verifier.verifyServerHostKey("sg.example.ts.net", 22, "ssh-ed25519", saved))
    }

    @Test
    fun aChangedKey_isRefused_andNothingIsSaved() {
        val list = listOf(known())
        val verifier = SavedHostKeyVerifier(list, listOf("ssh-ed25519"))

        assertFalse(verifier.verifyServerHostKey("sg.example.ts.net", 22, "ssh-ed25519", other))
        verifier.addServerHostKey("sg.example.ts.net", 22, "ssh-ed25519", other)
        // Still refused: a background login never takes a key on the user's behalf.
        assertFalse(verifier.verifyServerHostKey("sg.example.ts.net", 22, "ssh-ed25519", other))
    }

    @Test
    fun aKeyForAnotherNameOrPort_isNew_andRefused() {
        val verifier = SavedHostKeyVerifier(listOf(known()), listOf("ssh-ed25519"))

        assertFalse(verifier.verifyServerHostKey("sun.example.ts.net", 22, "ssh-ed25519", saved))
        assertFalse(verifier.verifyServerHostKey("sg.example.ts.net", 2222, "ssh-ed25519", saved))
    }

    @Test
    fun aRenamedHost_matchesUnderEitherName() {
        val verifier = SavedHostKeyVerifier(listOf(known("sg"), known("sg.example.ts.net")), listOf("ssh-ed25519"))

        assertTrue(verifier.verifyServerHostKey("sg", 22, "ssh-ed25519", saved))
        assertTrue(verifier.verifyServerHostKey("sg.example.ts.net", 22, "ssh-ed25519", saved))
    }

    @Test
    fun theSavedAlgorithms_areOffered_noneIsNull() {
        assertEquals(listOf("ssh-ed25519"), SavedHostKeyVerifier(listOf(known()), listOf("ssh-ed25519")).getKnownKeyAlgorithmsForHost("sg", 22))
        assertNull(SavedHostKeyVerifier(emptyList(), emptyList()).getKnownKeyAlgorithmsForHost("sg", 22))
    }

    @Test
    fun knownHostsOf_groupsOneKeyUnderAllItsNames() {
        val hosts = knownHostsOf(listOf(known("a"), known("b", port = 2222), known("c", key = other)))

        assertEquals(KnownHosts.HOSTKEY_IS_OK, hosts.verifyHostkey("a:22", "ssh-ed25519", saved))
        assertEquals(KnownHosts.HOSTKEY_IS_OK, hosts.verifyHostkey("b:2222", "ssh-ed25519", saved))
        assertEquals(KnownHosts.HOSTKEY_HAS_CHANGED, hosts.verifyHostkey("c:22", "ssh-ed25519", saved))
        assertEquals(KnownHosts.HOSTKEY_IS_NEW, hosts.verifyHostkey("d:22", "ssh-ed25519", saved))
    }
}
