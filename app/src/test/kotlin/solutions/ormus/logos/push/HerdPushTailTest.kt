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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.KnownHost
import org.connectbot.data.entity.Pubkey
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.transport.CommandOutput
import org.connectbot.transport.HeadlessSession
import org.connectbot.transport.HeadlessSsh
import org.connectbot.transport.SavedHostKeyVerifier
import org.connectbot.util.HostConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.CoreFacts
import solutions.ormus.logos.herd.CoreNotices
import solutions.ormus.logos.herd.HerdApproval
import solutions.ormus.logos.herd.HerdAttention
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HerdPushTailTest {
    private val pair: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private val decoded = mutableListOf<String>()
    private val decode: (Pubkey) -> KeyPair? = {
        decoded += it.nickname
        pair
    }

    private fun pubkey(
        nickname: String,
        encrypted: Boolean = false,
        confirmation: Boolean = false,
        storage: KeyStorageType = KeyStorageType.EXPORTABLE,
    ) = Pubkey(id = 3, nickname = nickname, type = "RSA", privateKey = byteArrayOf(0), publicKey = byteArrayOf(0), encrypted = encrypted, startup = true, confirmation = confirmation, createdDate = 0L, storageType = storage)

    private val anyKey = Host(id = 28, nickname = "sg", hostname = "sg.example.ts.net", pubkeyId = HostConstants.PUBKEYID_ANY)
    private val dispatchers = CoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default)

    @Test
    fun anyKey_usesTheStartupKeysThatAskNothing() {
        val startup = listOf(
            pubkey("plain"),
            pubkey("locked", encrypted = true),
            pubkey("asks", confirmation = true),
            pubkey("keystore", storage = KeyStorageType.ANDROID_KEYSTORE),
        )

        val keys = HerdPushTail.keys(anyKey, startup, stored = null, decode)

        assertEquals(1, keys.size)
        assertEquals(listOf("plain"), decoded)
    }

    @Test
    fun aNamedKey_isTheHostsOwn_whenItAsksNothing() {
        val named = anyKey.copy(pubkeyId = 3)

        assertEquals(1, HerdPushTail.keys(named, listOf(pubkey("startup")), stored = pubkey("own"), decode).size)
        assertEquals(listOf("own"), decoded)
        assertTrue(HerdPushTail.keys(named, emptyList(), stored = pubkey("own", encrypted = true), decode).isEmpty())
        assertTrue(HerdPushTail.keys(named, emptyList(), stored = null, decode).isEmpty())
    }

    @Test
    fun noKey_jumpHost_orNotSsh_logsInWithNothing() {
        val startup = listOf(pubkey("plain"))

        assertTrue(HerdPushTail.keys(anyKey.copy(pubkeyId = HostConstants.PUBKEYID_NEVER), startup, null, decode).isEmpty())
        assertTrue(HerdPushTail.keys(anyKey.copy(jumpHostId = 5), startup, null, decode).isEmpty())
        assertTrue(HerdPushTail.keys(anyKey.copy(protocol = "telnet"), startup, null, decode).isEmpty())
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun aKeyThatFailsToDecode_isLeftOut() {
        assertTrue(HerdPushTail.keys(anyKey, listOf(pubkey("bad")), null) { throw IllegalArgumentException("bad") }.isEmpty())
    }

    @Test
    fun aHostNeverConnectedFromThePhone_isNotLoggedInto() = runBlocking {
        val hosts = mock<HostRepository> { onBlocking { getKnownHostsForHost(28) } doReturn emptyList() }
        val pubkeys = mock<PubkeyRepository> { onBlocking { getStartupKeys() } doReturn listOf(pubkey("plain")) }
        val agent = HerdrAgentInfo(paneId = "w1:p3", workspaceId = "w1", tabId = "w1:t2", agent = "claude", agentStatus = HerdrAgentStatus.BLOCKED)

        val opened = mutableListOf<Host>()
        val tail = HerdPushTail(hosts, pubkeys, dispatchers, decode = { pair }, open = { host, _, _ ->
            opened += host
            error("never opened")
        })

        assertEquals(HerdPushTail.Read.NotTried, tail.read(anyKey, HerdAttention.Notice.NeedsYou(agent, null, null), 1_000))
        // The key decodes, so only the missing host key stops the login.
        assertTrue(opened.isEmpty())
    }

    @Test
    fun withinBudget_closesAHungRead_andGivesNull() = runBlocking {
        val tail = HerdPushTail(mock(), mock(), dispatchers)
        val closed = CountDownLatch(1)
        val started = System.nanoTime()

        // A blocking socket read that only returns once the connection is closed, as trilead's does.
        val read = tail.withinBudget(100, close = { closed.countDown() }) {
            check(closed.await(5, TimeUnit.SECONDS)) { "never closed" }
            throw IOException("socket closed")
        }

        assertNull(read)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
    }

    @Test
    fun withinBudget_aReadInTime_isKept_andNotClosed() = runBlocking {
        val tail = HerdPushTail(mock(), mock(), dispatchers)
        var closes = 0

        assertEquals("Allow edit?", tail.withinBudget(1_000, close = { closes++ }) { "Allow edit?" })
        assertEquals(0, closes)
    }

    @Test
    fun withinBudget_anyThrow_isNull() = runBlocking {
        val tail = HerdPushTail(mock(), mock(), dispatchers)

        assertNull(tail.withinBudget<String>(1_000, close = {}) { throw IllegalStateException("Connection is closed") })
    }

    @Test
    fun withinBudget_aServerThatNeverSpeaks_returnsInTime() = runBlocking {
        // trilead's close() waits for connect() (both synchronized), so closing alone cannot end a hung login.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val silent = thread { runCatching { server.accept().use { Thread.sleep(20_000) } } }
            val host = Host(id = 28, nickname = "silent", hostname = "127.0.0.1", port = server.localPort, pubkeyId = HostConstants.PUBKEYID_ANY)
            val ssh = HeadlessSsh(host, SavedHostKeyVerifier(emptyList(), emptyList()))
            val tail = HerdPushTail(mock(), mock(), dispatchers)
            val started = System.nanoTime()

            val login = tail.withinBudget(200, close = ssh::close) { ssh.login(listOf(pair)) }

            assertNull(login)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            silent.interrupt()
        }
    }

    @Test
    fun aNamedKey_thatAsksForConfirmation_orLivesInTheKeystore_isNotUsed() {
        val named = anyKey.copy(pubkeyId = 3)

        assertTrue(HerdPushTail.keys(named, emptyList(), stored = pubkey("own", confirmation = true), decode).isEmpty())
        assertTrue(HerdPushTail.keys(named, emptyList(), stored = pubkey("own", storage = KeyStorageType.ANDROID_KEYSTORE), decode).isEmpty())
        assertTrue(decoded.isEmpty())
    }

    /** A login that answers as told, and touches the host key check the way a real one could. */
    private class FakeSession(
        private val login: HeadlessSsh.Login = HeadlessSsh.Login.Ok,
        private val exec: () -> CommandOutput = { CommandOutput("", "", 0) },
    ) : HeadlessSession {
        var verifier: com.trilead.ssh2.ExtendedServerHostKeyVerifier? = null
        val commands = mutableListOf<String>()
        var closes = 0

        override fun login(keys: List<KeyPair>): HeadlessSsh.Login {
            verifier?.run {
                addServerHostKey("sg.example.ts.net", 22, "ssh-ed25519", byteArrayOf(9))
                removeServerHostKey("sg.example.ts.net", 22, "ssh-ed25519", byteArrayOf(9))
            }
            return login
        }

        override fun exec(command: String): CommandOutput {
            commands += command
            return exec.invoke()
        }

        override fun close() {
            closes++
        }
    }

    private val agent = HerdrAgentInfo(paneId = "w1:p3", workspaceId = "w1", tabId = "w1:t2", agent = "claude", agentStatus = HerdrAgentStatus.BLOCKED)
    private val notice = HerdAttention.Notice.NeedsYou(agent, null, null)
    private val saved = KnownHost(hostId = 28, hostname = "sg.example.ts.net", port = 22, hostKeyAlgo = "ssh-ed25519", hostKey = byteArrayOf(1))
    private val savedHosts = mock<HostRepository> {
        onBlocking { getKnownHostsForHost(28) } doReturn listOf(saved)
        onBlocking { getHostKeyAlgorithmsForHost(28) } doReturn listOf("ssh-ed25519")
    }
    private val startupKeys = mock<PubkeyRepository> { onBlocking { getStartupKeys() } doReturn listOf(pubkey("plain")) }

    private fun reading(session: FakeSession, opened: MutableList<Int> = mutableListOf()) = HerdPushTail(savedHosts, startupKeys, dispatchers, decode = { pair }, open = { _, verifier, timeoutMs ->
        opened += timeoutMs
        session.verifier = verifier
        session
    })

    @Test
    fun read_aGoodLogin_readsThePane_givesItsEnd_closes_andOnlyReadsTheRepository() = runBlocking {
        val session = FakeSession(exec = { CommandOutput("old line\n\nEdit build.gradle?\n1. Yes\n2. No\n", "", 0) })

        val read = reading(session).read(anyKey, notice, 1_000)

        assertEquals(HerdPushTail.Read.Text(HerdApproval.tail("old line\n\nEdit build.gradle?\n1. Yes\n2. No\n")!!), read)
        assertEquals(listOf(AgentsBoard.readCommand(notice.session, "w1:p3", HerdApproval.READ_LINES)), session.commands)
        assertEquals(1, session.closes)
        // The read itself writes nothing: the repository saw only the two reads. (The verifier holds
        // a copy of the saved keys, so its add and remove cannot reach the repository at all.)
        verify(savedHosts).getKnownHostsForHost(28)
        verify(savedHosts).getHostKeyAlgorithmsForHost(28)
        verifyNoMoreInteractions(savedHosts)
    }

    // Three options each wrapped to four lines, then "No": the last 12 lines hold no "1." and no question any more.
    private fun wrapped(command: String) = buildString {
        appendLine("Bash command")
        appendLine("  $command")
        appendLine("Do you want to proceed?")
        appendLine("❯ 1. Yes")
        repeat(3) { appendLine("    wrapped option one line $it") }
        appendLine("  2. Yes, and always allow this command")
        repeat(3) { appendLine("    wrapped option two line $it") }
        appendLine("  3. Yes, and allow the whole project")
        repeat(3) { appendLine("    wrapped option three line $it") }
        appendLine("  4. No")
        appendLine("Esc to cancel")
        appendLine("Sonnet 5.5  ctx 4% (40k/1M)")
    }

    @Test
    fun read_theHashIsOfTheWholeRead_soWrappedOptionsCannotPushThePromptOutOfIt() = runBlocking {
        val full = wrapped("make")
        val session = FakeSession(exec = { CommandOutput(full, "", 0) })

        val read = reading(session).read(anyKey, notice, 1_000) as HerdPushTail.Read.Text

        assertTrue("the 12-line tail alone shows no prompt", CoreNotices.fingerprint(read.text) == null)
        assertEquals(CoreNotices.fingerprint(full), read.prompt)
        assertTrue(read.prompt != null)
    }

    private val coreAgent = HerdrAgentInfo(paneId = "t15", workspaceId = "alpha", tabId = "alpha / build", agent = "claude-code", agentStatus = HerdrAgentStatus.BLOCKED, core = CoreFacts(null, 4))
    private val coreNotice = HerdAttention.Notice.NeedsYou(coreAgent, null, null, session = CoreNotices.SESSION)

    @Test
    fun read_aCoreNotice_asksHcGetThroughTheLoginShell_andGivesTheScreensEnd() = runBlocking {
        val get = """{"address":"t15","screen":"old line\n\nEdit build.gradle?\n1. Yes\n2. No\n","state":"live"}"""
        val session = FakeSession(exec = { CommandOutput(get, "", 0) })

        val read = reading(session).read(anyKey, coreNotice, 1_000)

        assertEquals(HerdPushTail.Read.Text(HerdApproval.tail("old line\n\nEdit build.gradle?\n1. Yes\n2. No\n")!!), read)
        assertEquals(listOf(CoreNotices.getCommand("t15")), session.commands)
        assertEquals(1, session.closes)
    }

    @Test
    fun read_aCoreNoticeWhoseAnswerHasNoScreen_isAMiss() = runBlocking {
        val session = FakeSession(exec = { CommandOutput("""{"address":"t15"}""", "", 0) })

        assertEquals(HerdPushTail.Read.Missed, reading(session).read(anyKey, coreNotice, 1_000))
    }

    @Test
    fun read_aCoreNoticeWithAnOddAddress_runsNothing() = runBlocking {
        val odd = coreNotice.copy(agent = coreAgent.copy(paneId = "t15; rm -rf ~"))
        val session = FakeSession(exec = { error("must not run") })

        assertEquals(HerdPushTail.Read.NotTried, reading(session).read(anyKey, odd, 1_000))
        assertTrue(session.commands.isEmpty())
    }

    @Test
    fun read_aLoginThatIsNotOk_isAMiss_runsNothing_andCloses() = runBlocking {
        for (login in listOf(HeadlessSsh.Login.KeysRefused, HeadlessSsh.Login.HostKeyRefused, HeadlessSsh.Login.Failed("Connection refused"))) {
            val session = FakeSession(login = login)

            assertEquals(login.toString(), HerdPushTail.Read.Missed, reading(session).read(anyKey, notice, 1_000))
            assertTrue(session.commands.isEmpty())
            assertEquals(1, session.closes)
        }
    }

    @Test
    fun read_aFailedCut_orThrowingCommand_isAMiss_andCloses() = runBlocking {
        val outputs = listOf<() -> CommandOutput>(
            { CommandOutput("Allow?", "no such pane", 1) },
            { CommandOutput("Allo", "", null) },
            { throw IOException("channel closed") },
        )
        for (out in outputs) {
            val session = FakeSession(exec = out)

            assertEquals(HerdPushTail.Read.Missed, reading(session).read(anyKey, notice, 1_000))
            assertEquals(1, session.closes)
        }
    }

    @Test
    fun read_theLoginGetsOnlyTheBudgetLeft_andASpentBudgetOpensNothing() = runBlocking {
        val opened = mutableListOf<Int>()

        reading(FakeSession(), opened).read(anyKey, notice, 1_000)
        assertEquals(1, opened.size)
        assertTrue("timeout ${opened.single()} is more than the budget", opened.single() in 1..1_000)

        assertEquals(HerdPushTail.Read.Missed, reading(FakeSession(), opened).read(anyKey, notice, 0))
        assertEquals(1, opened.size)
    }

    @Test
    fun read_aLoginThatHangsPastTheBudget_isAMiss_inTime_andIsClosed() = runBlocking {
        val closed = CountDownLatch(1)
        val hung = object : HeadlessSession {
            override fun login(keys: List<KeyPair>): HeadlessSsh.Login {
                check(closed.await(5, TimeUnit.SECONDS)) { "never closed" }
                return HeadlessSsh.Login.Failed("socket closed")
            }

            override fun exec(command: String): CommandOutput = error("never run")

            override fun close() = closed.countDown()
        }
        val tail = HerdPushTail(savedHosts, startupKeys, dispatchers, decode = { pair }, open = { _, _, _ -> hung })
        val started = System.nanoTime()

        assertEquals(HerdPushTail.Read.Missed, tail.read(anyKey, notice, 200))

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
        assertTrue("the hung login was never closed", closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun theOpenerTheAppUses_givesUpOnASilentServer_atTheTimeoutItIsGiven() {
        // The budget-left timeout must reach trilead: with its own defaults a silent host keeps the login 15 s.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val silent = thread { runCatching { server.accept().use { Thread.sleep(20_000) } } }
            val host = Host(id = 28, nickname = "silent", hostname = "127.0.0.1", port = server.localPort, pubkeyId = HostConstants.PUBKEYID_ANY)
            val started = System.nanoTime()

            val login = HerdPushTail.openHeadless(host, SavedHostKeyVerifier(emptyList(), emptyList()), 300).use { it.login(listOf(pair)) }

            assertTrue("login was $login", login is HeadlessSsh.Login.Failed)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            silent.interrupt()
        }
    }

    @Test
    fun aReadsText_neverShowsInItsToString() {
        val read = HerdPushTail.Read.Text("rm -rf /srv/secret-project?")

        assertTrue(read.toString(), !read.toString().contains("secret"))
        assertEquals(HerdPushTail.Read.Text("same"), HerdPushTail.Read.Text("same"))
    }
}
