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

package solutions.ormus.logos.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** The transport does its network work on the dispatcher it is given, so a test can control it. */
class HttpVoiceTransportDispatcherTest {
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            Dispatchers.IO.dispatch(context, block)
        }
    }

    @Test
    fun theExchangeRunsOnTheInjectedDispatcher() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("{}"))
            val io = CountingDispatcher()
            val transport = HttpVoiceTransport(io = io)
            runBlocking { transport.get(VoiceRequest.health("http://127.0.0.1:${server.port}")) }
            assertTrue(io.dispatched > 0)
        } finally {
            server.shutdown()
        }
    }
}
