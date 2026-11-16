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

import kotlinx.coroutines.runBlocking
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.KnownHost
import org.connectbot.data.entity.Pubkey
import org.connectbot.transport.CommandOutput
import org.connectbot.transport.HeadlessSession
import org.connectbot.transport.HeadlessSsh
import org.connectbot.util.HostConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * The login of a machine (`CoreLinks.openHeadless`) tries each saved login of it in turn. The saved logins of one machine are one
 * sshd and one user, so a key it refused once is refused on every row: it is offered once per machine, not once per row. With a
 * stand-in for each connection, the way the Inbox's login does for a console in `InboxViewModelTest`. Synthetic keys and hosts.
 * Measured on a throwaway sshd on loopback before this change (the PR that added this test has the table): N saved logins and
 * a refused key made N connections and N `Failed publickey` lines.
 */
class CoreLinksOpenHeadlessTest {
    private val pairs = mutableMapOf<String, KeyPair>()

    private fun pair(name: String) = pairs.getOrPut(name) { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }

    private fun pubkey(id: Long, name: String) = Pubkey(id = id, nickname = name, type = "RSA", privateKey = pair(name).private.encoded, publicKey = pair(name).public.encoded, encrypted = false, startup = true, confirmation = false, createdDate = 0L)

    private fun id(key: KeyPair) = Base64.getEncoder().encodeToString(key.public.encoded)

    private fun row(id: Long, pubkeyId: Long = HostConstants.PUBKEYID_ANY) = Host(id = id, nickname = "row$id", hostname = "box.example", username = "u", postLogin = "herdr", pubkeyId = pubkeyId)

    private val known = KnownHost(hostId = 1, hostname = "box.example", port = 22, hostKeyAlgo = "ssh-ed25519", hostKey = byteArrayOf(1))

    private val hostRepo = mock<HostRepository> {
        onBlocking { getKnownHostsForHost(org.mockito.kotlin.any()) } doReturn listOf(known)
        onBlocking { getHostKeyAlgorithmsForHost(org.mockito.kotlin.any()) } doReturn emptyList()
    }

    /** What the stand-in connections were offered: the host row, and the keys (by name) of each login. */
    private val offered = mutableListOf<Pair<Long, List<String>>>()
    private var connections = 0

    private fun names(keys: List<KeyPair>) = keys.map { k -> pairs.entries.first { id(it.value) == id(k) }.key }

    private fun open(rows: List<Host>, startup: List<Pubkey>, byId: Map<Long, Pubkey> = emptyMap(), outcome: (Long, List<String>) -> HeadlessSsh.Login): CoreLinks.Opened = runBlocking {
        val keyRepo = mock<PubkeyRepository> {
            onBlocking { getStartupKeys() } doReturn startup
            byId.forEach { (pubkeyId, key) -> onBlocking { getById(pubkeyId) } doReturn key }
        }
        val machine = CoreMachines.group(rows).single()
        CoreLinks.openHeadless(machine, hostRepo, keyRepo) { host, _ ->
            connections++
            object : HeadlessSession {
                override fun login(keys: List<KeyPair>): HeadlessSsh.Login {
                    val asked = names(keys)
                    offered += host.id to asked
                    return outcome(host.id, asked)
                }

                override fun exec(command: String) = CommandOutput("", "", 0)

                override fun close() {}
            }
        }
    }

    @Test
    fun fiveSavedLoginsOfOneMachine_thatTakeNoKey_makeOneConnection_notFive() {
        val opened = open((1L..5L).map { row(it) }, listOf(pubkey(3, "a"))) { _, _ -> HeadlessSsh.Login.KeysRefused }
        assertEquals("one connection, one offer of the key: $offered", listOf(1L to listOf("a")), offered)
        assertEquals(1, connections)
        assertEquals("it took none of the keys", (opened as CoreLinks.Opened.Skipped).reason)
    }

    @Test
    fun aRowThatOffersTheRefusedKeyAndAnotherOne_isOfferedOnlyTheOtherOne() {
        // Row 1 names key a, and the host refuses it. Row 2 offers every startup key (a and b): only b is tried there.
        val rows = listOf(row(1, pubkeyId = 3), row(2))
        val opened = open(rows, listOf(pubkey(3, "a"), pubkey(4, "b")), byId = mapOf(3L to pubkey(3, "a"))) { _, _ -> HeadlessSsh.Login.KeysRefused }
        assertEquals(listOf(1L to listOf("a"), 2L to listOf("b")), offered)
        assertEquals("it took none of the keys", (opened as CoreLinks.Opened.Skipped).reason)
    }

    @Test
    fun aRowWhoseKeysWereAllRefusedAlready_isNotConnectedTo() {
        // Row 2 names key a, which row 1 offered and the host refused: no connection for it.
        val rows = listOf(row(1), row(2, pubkeyId = 3))
        open(rows, listOf(pubkey(3, "a")), byId = mapOf(3L to pubkey(3, "a"))) { _, _ -> HeadlessSsh.Login.KeysRefused }
        assertEquals(1, connections)
    }

    @Test
    fun aKeyThatRowOneOfferedAndTheHostTook_isTheLoginOfTheMachine_andNothingElseIsTried() {
        val opened = open((1L..5L).map { row(it) }, listOf(pubkey(3, "a"))) { _, _ -> HeadlessSsh.Login.Ok }
        assertTrue(opened is CoreLinks.Opened.Session)
        assertEquals(1, connections)
    }

    @Test
    fun aRowThatFailedToConnect_doesNotMakeItsKeyRefused_theNextRowOffersItAgain() {
        val opened = open((1L..3L).map { row(it) }, listOf(pubkey(3, "a"))) { host, _ -> if (host == 1L) HeadlessSsh.Login.Failed("connect timed out") else HeadlessSsh.Login.Ok }
        assertEquals(listOf(1L to listOf("a"), 2L to listOf("a")), offered)
        assertTrue(opened is CoreLinks.Opened.Session)
    }

    @Test
    fun aRowWhoseHostKeyChanged_doesNotMakeItsKeyRefused_theNextRowOffersItAgain() {
        val opened = open((1L..3L).map { row(it) }, listOf(pubkey(3, "a"))) { host, _ -> if (host == 1L) HeadlessSsh.Login.HostKeyRefused else HeadlessSsh.Login.Ok }
        assertEquals(listOf(1L to listOf("a"), 2L to listOf("a")), offered)
        assertTrue(opened is CoreLinks.Opened.Session)
    }

    @Test
    fun aKeyThatTheLaterRowHasToItself_isTriedAfterTheRefusedOneOfTheFirst_andOpens() {
        // Row 2 has key c, which the host takes; key a (row 1) is refused.
        val rows = listOf(row(1), row(2, pubkeyId = 9))
        val opened = open(rows, listOf(pubkey(3, "a")), byId = mapOf(9L to pubkey(9, "c"))) { _, keys -> if ("c" in keys) HeadlessSsh.Login.Ok else HeadlessSsh.Login.KeysRefused }
        assertEquals(listOf(1L to listOf("a"), 2L to listOf("c")), offered)
        assertTrue(opened is CoreLinks.Opened.Session)
    }

    // T1 of the gate lane: a connection that was refused two or more keys marks all of them, not only the first. With "use any key"
    // and several keys loaded at start (the usual case) one marked key would bring back one connection per start-up key.
    @Test
    fun threeAnyKeyRows_withTwoStartupKeys_thatTheHostRefuses_makeOneConnection_thatOffersBothKeys() {
        val opened = open((1L..3L).map { row(it) }, listOf(pubkey(3, "a"), pubkey(4, "b"))) { _, _ -> HeadlessSsh.Login.KeysRefused }
        assertEquals(listOf(1L to listOf("a", "b")), offered)
        assertEquals(1, connections)
        assertEquals("it took none of the keys", (opened as CoreLinks.Opened.Skipped).reason)
    }

    // N1: the reason of a row that is passed over is the refusal, not the reason of the row before it, and its saved host keys are
    // not read (nothing is connected to, so there is nothing to check them against).
    @Test
    fun aRowPassedOver_endsAs_itTookNoneOfTheKeys_evenAfterARowThatFailedToConnect_andReadsNoSavedHostKeys() {
        val rows = listOf(row(1), row(2, pubkeyId = 9), row(3))
        val opened = open(rows, listOf(pubkey(3, "a")), byId = mapOf(9L to pubkey(9, "c"))) { host, _ -> if (host == 1L) HeadlessSsh.Login.KeysRefused else HeadlessSsh.Login.Failed("connect timed out") }
        assertEquals(listOf(1L to listOf("a"), 2L to listOf("c")), offered)
        assertEquals("it took none of the keys", (opened as CoreLinks.Opened.Skipped).reason)
        org.mockito.kotlin.verifyBlocking(hostRepo, org.mockito.kotlin.never()) { getKnownHostsForHost(3L) }
    }
}
