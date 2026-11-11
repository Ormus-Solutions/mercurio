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
import com.trilead.ssh2.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException

/** [SSH.read] at the end of a session: the last bytes come before the end. */
class SSHReadTest {
    private fun sshWith(stdout: String, vararg conditions: Int): SSH {
        val session = mock(Session::class.java)
        `when`(session.stdout).thenReturn(stdout.byteInputStream())
        `when`(session.stderr).thenReturn("".byteInputStream())
        val first = conditions.first()
        val rest = conditions.drop(1).toTypedArray()
        `when`(session.waitForCondition(anyInt(), anyLong())).thenReturn(first, *rest)
        return SSH().also { it.setSessionForTesting(session) }
    }

    @Test
    fun theLastBytesThatCameWithTheEndAreReadBeforeTheEndIsReported() {
        // A program that prints its last line and exits (a finished attach, `hc: command not found`)
        // can send its bytes and the end together: they must reach the screen.
        val ssh = sshWith("[t7 finished: exit 0]\r\n", ChannelCondition.STDOUT_DATA or ChannelCondition.EOF, ChannelCondition.EOF)
        val buffer = ByteArray(64)

        val n = ssh.read(buffer, 0, buffer.size)

        assertEquals("[t7 finished: exit 0]\r\n", String(buffer, 0, n))
        assertThrows(IOException::class.java) { ssh.read(buffer, 0, buffer.size) }
    }

    @Test
    fun anEndWithNoBytesIsReportedAtOnce() {
        val ssh = sshWith("", ChannelCondition.EOF)

        assertThrows(IOException::class.java) { ssh.read(ByteArray(8), 0, 8) }
    }
}
