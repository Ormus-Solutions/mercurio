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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** This phone's push endpoint: which URLs a host may store, and the script that keeps a host's file right. */
@RunWith(AndroidJUnit4::class)
class PushEndpointsTest {
    private val ntfy = "http://100.64.0.1:2586/upAbC123xyz?up=1"
    private val ntfyNext = "http://100.64.0.1:2586/upNEW456?up=1"

    private lateinit var endpoints: PushEndpoints

    @Before
    fun setUp() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("push-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        endpoints = PushEndpoints(prefs)
    }

    @Test
    fun isValid_takesPlainHttpUrlsOnly() {
        assertTrue(PushEndpoints.isValid(ntfy))
        assertTrue(PushEndpoints.isValid("https://ntfy.example.org/upXyz"))
        assertFalse("no scheme", PushEndpoints.isValid("100.64.0.1:2586/upX"))
        assertFalse("not http", PushEndpoints.isValid("file:///etc/passwd"))
        assertFalse("no host", PushEndpoints.isValid("http:///upX"))
        assertFalse("two lines", PushEndpoints.isValid("$ntfy\nhttp://evil/x"))
        assertFalse("quote", PushEndpoints.isValid("http://h/x'y"))
        assertFalse("space", PushEndpoints.isValid("http://h/x y"))
        assertFalse("backtick", PushEndpoints.isValid("http://h/`id`"))
        assertFalse("too long", PushEndpoints.isValid("http://h/" + "a".repeat(3000)))
    }

    @Test
    fun update_keepsTheEndpointBeforeIt_andRefusesBadUrls() {
        assertNull(endpoints.endpoint.value)
        assertTrue(endpoints.update(ntfy))
        assertEquals(ntfy, endpoints.endpoint.value)
        assertNull(endpoints.previous)

        assertTrue(endpoints.update(ntfyNext))
        assertEquals(ntfyNext, endpoints.endpoint.value)
        assertEquals(ntfy, endpoints.previous)

        assertFalse(endpoints.update("http://h/x y"))
        assertEquals(ntfyNext, endpoints.endpoint.value)

        // Unregistered: the dead endpoint becomes the one to remove.
        assertTrue(endpoints.update(null))
        assertNull(endpoints.endpoint.value)
        assertEquals(ntfyNext, endpoints.previous)
    }

    @Test
    fun syncTo_sendsOneLoginShellCommand_andOnlyWhenThereIsSomethingToDo() {
        val sent = mutableListOf<String>()
        val exec = { command: String ->
            sent += command
            CommandOutput("", "", 0)
        }
        assertTrue(endpoints.syncTo("Sun", exec))
        assertTrue("nothing to write yet", sent.isEmpty())

        endpoints.update(ntfy)
        assertTrue(endpoints.syncTo("Sun", exec))
        assertEquals(PushEndpoints.syncCommand(ntfy, null), sent.single())
        assertTrue(sent.single().startsWith("bash -lc '"))

        assertFalse("exit 1", endpoints.syncTo("Sun") { CommandOutput("", "denied", 1) })
        assertFalse("no session", endpoints.syncTo("Sun") { null })
        assertFalse("dropped", endpoints.syncTo("Sun") { throw IllegalStateException("Not connected") })
    }

    @Test
    fun syncScript_refusesWhatIsNotAnEndpoint() {
        assertTrue(runCatching { PushEndpoints.syncScript("http://h/x'; rm -rf ~; '", null) }.isFailure)
        assertTrue(runCatching { PushEndpoints.syncScript(ntfy, "nope") }.isFailure)
    }

    /** Runs the real script under /bin/sh against a scratch XDG_CONFIG_HOME. */
    @Test
    fun syncScript_addsOnce_replacesThisPhonesOldUrl_andKeepsOthers() {
        assumeTrue(File("/bin/sh").canExecute())
        val config = Files.createTempDirectory("push-endpoints").toFile()
        val file = File(config, "mercurio/push-endpoints")
        fun run(endpoint: String?, stale: String?) {
            val process = ProcessBuilder("/bin/sh", "-c", PushEndpoints.syncScript(endpoint, stale))
                .apply { environment()["XDG_CONFIG_HOME"] = config.path }
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().decodeToString()
            assertEquals(output, 0, process.waitFor())
        }

        run(ntfy, null)
        assertEquals("$ntfy\n", file.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))

        run(ntfy, null)
        assertEquals("never twice", "$ntfy\n", file.readText())

        // Another phone's line and a comment, the last line without its newline.
        file.appendText("# laptop\nhttps://ntfy.example.org/upOther")
        run(ntfyNext, ntfy)
        assertEquals("# laptop\nhttps://ntfy.example.org/upOther\n$ntfyNext\n", file.readText())

        // Unregistered: only this phone's line goes.
        run(null, ntfyNext)
        assertEquals("# laptop\nhttps://ntfy.example.org/upOther\n", file.readText())
        config.deleteRecursively()
    }

    @Test
    fun pick_prefersNtfy() {
        assertEquals(PushRegistration.NTFY, PushRegistration.pick(listOf("org.example.distributor", PushRegistration.NTFY)))
        assertEquals("org.example.distributor", PushRegistration.pick(listOf("org.example.distributor")))
        assertNull(PushRegistration.pick(emptyList()))
    }
}
