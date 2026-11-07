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

package solutions.ormus.logos.home

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.PreferenceConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.IOException

/** The sessions feed on the phone: read, kept, read back, and what a failed read leaves. */
@RunWith(AndroidJUnit4::class)
class HomeFeedTest {
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(testDispatcher, testDispatcher, testDispatcher)
    private lateinit var prefs: SharedPreferences

    private val fixture = checkNotNull(javaClass.getResourceAsStream("/home/sessions.json")).readBytes().decodeToString()
    private val fetched = mutableListOf<String>()
    private var answer: () -> String = { fixture }
    private var now = 5_000L

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("home-feed-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun feed() = HomeFeed(prefs, dispatchers, fetch = { url ->
        fetched += url
        answer()
    }, clock = { now })

    private fun setUrl(url: String) {
        prefs.edit().putString(PreferenceConstants.HOME_FEED_URL, url).commit()
    }

    @Test
    fun refresh_withNoUrl_fetchesNothing() = runTest(testDispatcher) {
        val feed = feed()

        assertFalse(feed.refresh())
        assertEquals(emptyList<String>(), fetched)
        assertEquals(HomeState(), feed.state.value)
        assertEquals("", feed.url)
        feed.load()
        assertEquals(HomeState(), feed.state.value)
    }

    @Test
    fun refresh_readsTheFeed_publishesItWithItsTime_andKeepsIt() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()

        assertTrue(feed.refresh())

        assertEquals(listOf(URL), fetched)
        val state = feed.state.value
        assertEquals(URL, state.url)
        assertEquals(HomeBoards.parse(fixture), state.boards)
        assertEquals(5_000L, state.fetchedAt)
        assertNull(state.error)
        assertEquals(5_000L, prefs.getLong(HomeFeed.PREF_FETCHED, 0L))
        assertNotNull(prefs.getString(HomeFeed.PREF_BOARDS, null))
    }

    @Test
    fun load_readsBackWhatAnEarlierLaunchKept() = runTest(testDispatcher) {
        setUrl(URL)
        feed().refresh()

        val relaunched = feed()
        // The URL is there from the start; the boards once the read off the main thread landed.
        assertEquals(HomeState(url = URL), relaunched.state.value)
        relaunched.load()

        assertEquals(HomeState(URL, HomeBoards.parse(fixture), 5_000L), relaunched.state.value)
        // Nothing was fetched for it.
        assertEquals(1, fetched.size)
    }

    @Test
    fun refresh_aReadThatFails_namesIt_andKeepsTheLastBoards() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()
        feed.refresh()

        answer = { throw IOException("The feed answered HTTP 500") }
        now = 9_000L
        assertFalse(feed.refresh())

        val failed = feed.state.value
        assertEquals(HomeBoards.parse(fixture), failed.boards)
        assertEquals(5_000L, failed.fetchedAt)
        assertEquals("The feed answered HTTP 500", failed.error)
        assertEquals(5_000L, prefs.getLong(HomeFeed.PREF_FETCHED, 0L))

        // The next read that works clears the failure.
        answer = { fixture }
        assertTrue(feed.refresh())
        assertEquals(HomeState(URL, HomeBoards.parse(fixture), 9_000L), feed.state.value)
    }

    @Test
    fun refresh_anAnswerThatIsNotAFeed_namesIt_andKeepsTheLastBoards() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()
        feed.refresh()

        answer = { "<html>no</html>" }
        assertFalse(feed.refresh())

        assertEquals(HomeBoards.parse(fixture), feed.state.value.boards)
        assertTrue(feed.state.value.error.orEmpty(), feed.state.value.error.orEmpty().startsWith("not a sessions feed"))
    }

    @Test
    fun describe_dropsTheObjectNamesThePlatformPutsInAMessage() {
        assertEquals("unexpected end of stream", HomeFeed.describe(IOException("unexpected end of stream on com.android.okhttp.Address@e6bb7194")))
        assertEquals("Unable to resolve host \"feed.example\": No address associated with hostname", HomeFeed.describe(IOException("Unable to resolve host \"feed.example\": No address associated with hostname")))
        assertEquals("The feed answered HTTP 503", HomeFeed.describe(IOException("The feed answered HTTP 503")))
        assertEquals("IOException", HomeFeed.describe(IOException(" ")))
    }

    @Test
    fun refresh_aFailureWithNoMessage_isNamedByItsKind() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()

        answer = { throw IOException() }
        assertFalse(feed.refresh())

        assertEquals("IOException", feed.state.value.error)
    }

    @Test
    fun setUrl_aDifferentUrl_dropsTheKeptBoards_andTheNextReadFillsThem() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()
        feed.refresh()

        feed.setUrl(" $OTHER ")

        assertEquals(OTHER, feed.url)
        assertEquals(HomeState(url = OTHER), feed.state.value)
        assertNull(prefs.getString(HomeFeed.PREF_BOARDS, null))
        assertFalse(prefs.contains(HomeFeed.PREF_FETCHED))

        assertTrue(feed.refresh())
        assertEquals(listOf(URL, OTHER), fetched)
        assertEquals(HomeBoards.parse(fixture), feed.state.value.boards)
    }

    @Test
    fun setUrl_blank_clearsTheUrl_andNothingIsReadAgain() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()
        feed.refresh()

        feed.setUrl("  ")

        assertEquals("", feed.url)
        assertFalse(prefs.contains(PreferenceConstants.HOME_FEED_URL))
        assertEquals(HomeState(), feed.state.value)
        assertFalse(feed.refresh())
        assertEquals(1, fetched.size)
    }

    @Test
    fun setUrl_theSameUrl_keepsTheBoards() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()
        feed.refresh()

        feed.setUrl(" $URL ")

        assertEquals(HomeState(URL, HomeBoards.parse(fixture), 5_000L), feed.state.value)
        assertNotNull(prefs.getString(HomeFeed.PREF_BOARDS, null))
    }

    @Test
    fun refresh_whenTheUrlChangesDuringTheRead_dropsThatAnswer_andReadsTheNewUrl() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()
        answer = {
            if (fetched.size == 1) prefs.edit().putString(PreferenceConstants.HOME_FEED_URL, OTHER).commit()
            fixture
        }

        assertTrue(feed.refresh())

        assertEquals(listOf(URL, OTHER), fetched)
        assertEquals(HomeState(OTHER, HomeBoards.parse(fixture), 5_000L), feed.state.value)
    }

    @Test
    fun refresh_whenTheUrlIsClearedDuringTheRead_dropsThatAnswer_andReadsNothingMore() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()
        answer = {
            prefs.edit().remove(PreferenceConstants.HOME_FEED_URL).commit()
            fixture
        }

        assertFalse(feed.refresh())

        assertEquals(listOf(URL), fetched)
        assertNull(feed.state.value.boards)
        assertNull(prefs.getString(HomeFeed.PREF_BOARDS, null))
    }

    @Test
    fun refresh_aFeedCutShort_logsNoneOfItsText() = runTest(testDispatcher) {
        setUrl(URL)
        val feed = feed()
        val logs = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += message
                t?.let { logs += it.stackTraceToString() }
            }
        }
        Timber.plant(tree)
        try {
            answer = { fixture.substringBefore("nobody") }
            assertFalse(feed.refresh())
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(logs.toString(), logs.any { it.startsWith("the home feed was not read") })
        assertFalse(logs.toString(), logs.any { it.contains("a line") })
        assertFalse(feed.state.value.error.orEmpty(), feed.state.value.error.orEmpty().contains("a line"))
    }

    @Test
    fun load_keptBoardsThatDoNotParse_startOver() = runTest(testDispatcher) {
        setUrl(URL)
        prefs.edit().putString(HomeFeed.PREF_BOARDS, "junk").putLong(HomeFeed.PREF_FETCHED, 1L).commit()

        val feed = feed()
        feed.load()

        assertEquals(HomeState(url = URL), feed.state.value)
    }

    @Test
    fun load_withNoUrl_ignoresWhatWasKept() = runTest(testDispatcher) {
        prefs.edit().putString(HomeFeed.PREF_BOARDS, HomeBoards.encode(HomeBoards.parse(fixture))).putLong(HomeFeed.PREF_FETCHED, 1L).commit()

        val feed = feed()
        feed.load()

        assertEquals(HomeState(), feed.state.value)
    }

    @Test
    fun httpGet_givesTheBodyOfA2xx() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(fixture))

            assertEquals(fixture, HomeFeed.httpGet(server.url("/feeds/sessions.json").toString()))

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/feeds/sessions.json", request.path)
            assertEquals("application/json", request.getHeader("Accept"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpGet_refusesAnAnswerThatIsNot2xx_namingTheCode() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("later"))

            val e = assertThrows(IOException::class.java) { HomeFeed.httpGet(server.url("/f.json").toString()) }
            assertEquals("The feed answered HTTP 503", e.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpGet_doesNotFollowARedirect() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()))
            server.enqueue(MockResponse().setBody(fixture))

            val e = assertThrows(IOException::class.java) { HomeFeed.httpGet(server.url("/f.json").toString()) }

            assertEquals("The feed answered HTTP 302", e.message)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpGet_refusesABodyOverTheCap() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("x".repeat(HomeFeed.MAX_BYTES + 1)))

            val e = assertThrows(IOException::class.java) { HomeFeed.httpGet(server.url("/f.json").toString()) }
            assertEquals("The feed is over 1024 KB", e.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpGet_refusesAUrlThatIsNotHttp_orNotValid() {
        assertEquals("The URL is not http or https", assertThrows(IOException::class.java) { HomeFeed.httpGet("file:///etc/hosts") }.message)
        assertEquals("The URL is not valid", assertThrows(IOException::class.java) { HomeFeed.httpGet("") }.message)
        assertEquals("The URL is not valid", assertThrows(IOException::class.java) { HomeFeed.httpGet("feeds/sessions.json") }.message)
        // A URL typed into another: the platform's own message would have been 'For input string: "18http:"'.
        assertEquals("The URL is not valid", assertThrows(IOException::class.java) { HomeFeed.httpGet("http://127.0.0.1:18http://127.0.0.1:18765/down65/f.json") }.message)
    }

    private companion object {
        const val URL = "https://feed.example/feeds/sessions.json"
        const val OTHER = "https://other.example/feeds/sessions.json"
    }
}
