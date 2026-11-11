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

import kotlinx.coroutines.test.runTest
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import solutions.ormus.logos.terminal.BracketedPaste

class CoreSendTest {
    private fun out(stdout: String = "", stderr: String = "", exit: Int? = 0) = CommandOutput(stdout, stderr, exit)

    private fun sh(inner: String) = HerdrApi.loginShell(inner)

    @Test fun `send is hc send json through the login shell with the text quoted`() {
        assertEquals(sh("hc send --json -- 't7' 'fix the bug'"), CoreSend.sendCommand("t7", "fix the bug"))
        assertEquals(sh("hc send --json -- 'box/t12' 'hi'"), CoreSend.sendCommand("box/t12", "hi"))
    }

    @Test fun `a single quote in the text is closed and reopened`() {
        assertEquals(sh("hc send --json -- 't7' 'it'\\''s'"), CoreSend.sendCommand("t7", "it's"))
    }

    @Test fun `shell syntax in the text stays text`() {
        assertEquals(sh("hc send --json -- 't7' '\$(rm -rf x); `id` && echo \"y\"'"), CoreSend.sendCommand("t7", "\$(rm -rf x); `id` && echo \"y\""))
    }

    @Test fun `text that starts with a dash is not read as an option`() {
        assertEquals(sh("hc send --json -- 't7' '--force'"), CoreSend.sendCommand("t7", "--force"))
    }

    @Test fun `a multi line text is one argument`() {
        assertEquals(sh("hc send --json -- 't7' 'a\nb'"), CoreSend.sendCommand("t7", "a\nb"))
    }

    @Test fun `a NUL byte is dropped and an empty text sends nothing`() {
        assertEquals(sh("hc send --json -- 't7' 'ab'"), CoreSend.sendCommand("t7", "a\u0000b"))
        assertNull(CoreSend.sendCommand("t7", ""))
        assertNull(CoreSend.sendCommand("t7", "\u0000"))
    }

    @Test fun `an address that is not a plain word sends nothing`() {
        assertNull(CoreSend.sendCommand("t7; rm x", "hi"))
        assertNull(CoreSend.sendCommand("", "hi"))
    }

    @Test fun `send never forces a blocked program`() {
        assertFalse(CoreSend.sendCommand("t7", "hi")!!.contains("--force"))
    }

    @Test fun `exit 0 is sent`() {
        assertEquals(SendResult.Sent, CoreSend.resultOf(out("{\"ok\":true}")))
    }

    @Test fun `a blocked refusal names the reason and is not a failure`() {
        val err = "{\"error\":{\"hint\":\"read it, then answer\",\"kind\":\"refused\",\"message\":\"t7 is blocked (permission), or --force\"}}"
        val result = CoreSend.resultOf(out(stderr = err, exit = 3))
        assertTrue(result is SendResult.Refused)
        assertEquals("t7 is blocked (permission), or --force. read it, then answer", (result as SendResult.Refused).reason)
    }

    @Test fun `another error is a failure with its message`() {
        val err = "{\"error\":{\"hint\":\"see `hc ls --all`\",\"kind\":\"not_found\",\"message\":\"no terminal \\\"t9\\\"\"}}"
        val result = CoreSend.resultOf(out(stderr = err, exit = 3))
        assertEquals(SendResult.Failed("no terminal \"t9\". see `hc ls --all`"), result)
    }

    @Test fun `an error after a login banner on stdout is still read`() {
        val result = CoreSend.resultOf(out(stdout = "Welcome\n{\"error\":{\"kind\":\"refused\",\"message\":\"t1 is blocked (question)\"}}", exit = 3))
        assertEquals(SendResult.Refused("t1 is blocked (question)"), result)
    }

    @Test fun `a non zero exit with no json is a failure that never counts as sent`() {
        assertEquals(SendResult.Failed("hc: not found"), CoreSend.resultOf(out(stderr = "hc: not found\n", exit = 127)))
        assertEquals(SendResult.Failed("hc send failed (exit 1)"), CoreSend.resultOf(out(exit = 1)))
        assertEquals(SendResult.Failed("hc send did not answer; look in the terminal before sending again"), CoreSend.resultOf(null))
        assertEquals(SendResult.Failed("hc send did not answer; look in the terminal before sending again"), CoreSend.resultOf(out(exit = null)))
    }

    @Test fun `put runs hc put json on the uploaded file`() {
        assertEquals(sh("hc put --json 't7' '/tmp/logos-pastes/shot-1.png'"), CoreSend.putCommand("t7", "/tmp/logos-pastes/shot-1.png"))
        assertNull(CoreSend.putCommand("t7; x", "/tmp/a.png"))
        assertEquals(sh("hc put --json 't7' '/tmp/it'\\''s.png'"), CoreSend.putCommand("t7", "/tmp/it's.png"))
    }

    @Test fun `put reports the path where the file landed`() {
        assertEquals("/run/x/t7/shot-1.png", CoreSend.landedPath(out("{\"path\":\"/run/x/t7/shot-1.png\"}")))
        assertEquals("/run/x/t7/shot-1.png", CoreSend.landedPath(out("Welcome\n/run/x/t7/shot-1.png\n")))
        assertNull(CoreSend.landedPath(out(stderr = "{\"error\":{\"message\":\"too big\"}}", exit = 3)))
        assertNull(CoreSend.landedPath(out("")))
    }

    @Test fun `put takes a file up to 8 MB`() {
        assertTrue(CoreSend.fitsPut(8L * 1024 * 1024))
        assertFalse(CoreSend.fitsPut(8L * 1024 * 1024 + 1))
    }

    // The sequence a compose Send on a watching console makes.
    private class Log {
        val events = mutableListOf<String>()
    }

    private fun compose(log: Log, state: CoreWatchState = CoreWatchState(), result: CommandOutput? = out(), fail: Boolean = false) = CoreCompose(
        state = state,
        takeOver = {
            log.events += "takeover"
            state.takeOverDone()
        },
        exec = { cmd ->
            log.events += "exec:" + cmd
            if (fail) throw java.io.IOException("timeout")
            result
        },
        settle = { log.events += "settle" },
        write = { log.events += "write:" + it },
    )

    @Test fun `send takes the watch over before hc send runs`() = runTest {
        val log = Log()
        val result = compose(log).send("t7", "hello")
        assertEquals(SendResult.Sent, result)
        assertEquals(listOf("takeover", "settle", "exec:" + CoreSend.sendCommand("t7", "hello")), log.events)
    }

    @Test fun `a second send does not take over again`() = runTest {
        val log = Log()
        val c = compose(log)
        c.send("t7", "a")
        c.send("t7", "b")
        assertEquals(1, log.events.count { it == "takeover" })
        assertEquals(2, log.events.count { it.startsWith("exec:") })
    }

    @Test fun `a refused send reports why and still ran no second command`() = runTest {
        val log = Log()
        val err = out(stderr = "{\"error\":{\"kind\":\"refused\",\"message\":\"t7 is blocked (permission)\"}}", exit = 3)
        val result = compose(log, result = err).send("t7", "hello")
        assertEquals(SendResult.Refused("t7 is blocked (permission)"), result)
        assertEquals(3, log.events.size)
    }

    @Test fun `an address that cannot be sent to runs nothing and does not take over`() = runTest {
        val log = Log()
        assertTrue(compose(log).send("t7; x", "hello") is SendResult.Failed)
        assertTrue(log.events.isEmpty())
    }

    @Test fun `a paste takes the watch over, settles, then writes`() = runTest {
        val log = Log()
        compose(log).type("hello", bracketed = false)
        assertEquals(listOf("takeover", "settle", "write:hello"), log.events)
    }

    @Test fun `a paste after the takeover writes at once`() = runTest {
        val log = Log()
        val c = compose(log)
        c.type("a", false)
        log.events.clear()
        c.type("b", false)
        assertEquals(listOf("settle", "write:b"), log.events)
    }

    @Test fun `a long press Send types the text without Enter`() = runTest {
        val log = Log()
        compose(log).type("hello world", bracketed = true)
        val written = log.events.last { it.startsWith("write:") }
        assertEquals("write:" + BracketedPaste.wrap("hello world", true), written)
        assertFalse(written.contains("\r") || written.contains("\n"))
    }

    @Test fun `a paste while the takeover runs waits for it`() = runTest {
        val log = Log()
        val state = CoreWatchState()
        val c = compose(log, state)
        state.onInput(byteArrayOf(0x61))
        c.type("x", false)
        assertEquals(listOf("settle", "write:x"), log.events)
    }

    @Test fun `a send whose exec times out is a failure, not a crash`() = runTest {
        val result = compose(Log(), fail = true).send("t7", "hello")
        assertTrue(result is SendResult.Failed)
    }

    @Test fun `a send right after a takeover also waits for the attach`() = runTest {
        val log = Log()
        val c = compose(log)
        c.send("t7", "a")
        log.events.clear()
        c.send("t7", "b")
        assertEquals("settle", log.events.first())
    }

    @Test fun `a not found message that mentions blocked is still a failure`() {
        val err = "{\"error\":{\"kind\":\"not_found\",\"message\":\"no terminal \\\"blocked-x\\\"\"}}"
        assertTrue(CoreSend.resultOf(out(stderr = err, exit = 3)) is SendResult.Failed)
    }

    @Test fun `kind refused is a refusal`() {
        val err = "{\"error\":{\"kind\":\"refused\",\"message\":\"will not type\"}}"
        assertEquals(SendResult.Refused("will not type"), CoreSend.resultOf(out(stderr = err, exit = 3)))
    }
}
