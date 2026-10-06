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

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.IOException

/** [SSH.exec] and [SSH.execDetailed] over a mocked exec channel. */
class SSHExecTest {
    private fun sshRunning(stdout: String, stderr: String, exit: Int?, condition: Int = ChannelCondition.EXIT_STATUS): Pair<SSH, Session> {
        val session = mock(Session::class.java)
        `when`(session.stdout).thenReturn(stdout.byteInputStream())
        `when`(session.stderr).thenReturn(stderr.byteInputStream())
        `when`(session.waitForCondition(anyInt(), anyLong())).thenReturn(condition)
        `when`(session.exitStatus).thenReturn(exit)
        val connection = mock(Connection::class.java)
        `when`(connection.openSession()).thenReturn(session)
        val ssh = SSH()
        ssh.setConnectionForTesting(connection)
        return ssh to session
    }

    @Test
    fun execDetailed_returnsStdoutStderrAndExitTogether() {
        val (ssh, session) = sshRunning(
            stdout = "",
            stderr = """{"error":{"code":"pane_not_found","message":"pane w9:p9 not found"},"id":"cli:pane:close"}""",
            exit = 1,
        )

        val out = ssh.execDetailed("herdr pane close w9:p9")

        assertEquals(
            CommandOutput(
                stdout = "",
                stderr = """{"error":{"code":"pane_not_found","message":"pane w9:p9 not found"},"id":"cli:pane:close"}""",
                exitCode = 1,
            ),
            out,
        )
        verify(session).execCommand("herdr pane close w9:p9")
        verify(session).close()
    }

    @Test
    fun execDetailed_success() {
        val (ssh, _) = sshRunning(stdout = "herdr 0.9.0\n", stderr = "", exit = 0)

        assertEquals(CommandOutput("herdr 0.9.0\n", "", 0), ssh.execDetailed("herdr --version"))
    }

    @Test
    fun execDetailed_timeoutThrows() {
        val (ssh, session) = sshRunning(stdout = "", stderr = "", exit = null, condition = ChannelCondition.TIMEOUT)

        assertThrows(IOException::class.java) { ssh.execDetailed("sleep 60", timeoutMs = 10) }
        verify(session).close()
    }

    @Test
    fun execDetailed_notConnectedThrows() {
        assertThrows(IllegalStateException::class.java) { SSH().execDetailed("true") }
    }

    @Test
    fun exec_stillThrowsOnNonZeroExitWithStderr() {
        val (ssh, _) = sshRunning(stdout = "", stderr = "boom\n", exit = 2)

        val e = assertThrows(IOException::class.java) { ssh.exec("false") }
        assertTrue(e.message!!.contains("Exit 2"))
        assertTrue(e.message!!.contains("boom"))
    }

    @Test
    fun exec_returnsStdout() {
        val (ssh, _) = sshRunning(stdout = "ok", stderr = "", exit = 0)

        assertEquals("ok", ssh.exec("echo ok"))
    }
}
