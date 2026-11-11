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

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectTextTest {
    @Test fun `a console that is not quiet shows every line at once`() {
        val text = ConnectText(quiet = false)
        assertEquals(listOf("Trying to authenticate"), text.line("Trying to authenticate"))
        assertEquals(emptyList<String>(), text.reveal())
    }

    @Test fun `a quiet console shows no sign-in line while it logs in`() {
        val text = ConnectText(quiet = true)
        assertEquals(emptyList<String>(), text.line("Verified host key"))
        assertEquals(emptyList<String>(), text.line("Trying to authenticate"))
    }

    @Test fun `a failure or a prompt shows the held lines once, in order, and later lines at once`() {
        val text = ConnectText(quiet = true)
        text.line("Verified host key")
        text.line("Trying to authenticate")
        assertEquals(listOf("Verified host key", "Trying to authenticate"), text.reveal())
        assertEquals(emptyList<String>(), text.reveal())
        assertEquals(listOf("Authentication failed"), text.line("Authentication failed"))
    }

    @Test fun `once the session opens the held lines are dropped, never shown on the terminal`() {
        val text = ConnectText(quiet = true)
        text.line("Trying to authenticate")
        text.opened()
        assertEquals(emptyList<String>(), text.reveal())
    }

    @Test fun `a reconnect holds its sign-in lines again`() {
        val text = ConnectText(quiet = true)
        text.line("Trying to authenticate")
        text.opened()
        text.restart()
        assertEquals(emptyList<String>(), text.line("Trying to authenticate"))
        assertEquals(listOf("Trying to authenticate"), text.reveal())
    }
}
