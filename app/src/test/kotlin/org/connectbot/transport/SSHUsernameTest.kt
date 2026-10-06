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
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.util.HostConstants
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class SSHUsernameTest {
    // A keyboard autocomplete can leave a space after the user name. Tailscale SSH
    // then refused the name with the space (issue #84), so the login sends it trimmed.
    @Test
    fun authenticate_savedUsernameWithSpaces_logsInTrimmed() {
        val bridge = mock(TerminalBridge::class.java)
        val connection = mock(Connection::class.java)
        val ssh = SSH().apply {
            setHost(
                Host(
                    nickname = "target",
                    username = " alice ",
                    hostname = "example.com",
                    pubkeyId = HostConstants.PUBKEYID_NEVER,
                ),
            )
            setBridge(bridge)
            setConnectionForTesting(connection)
        }

        ssh.authenticate()

        verify(connection).authenticateWithNone("alice")
    }
}
