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
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.PreferenceConstants
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** What the home knows of the feed: its URL, the boards last read, when, and why the last read failed. */
data class HomeState(
    /** The feed URL from Settings; blank when none is set. */
    val url: String = "",
    val boards: HomeBoards? = null,
    /** Epoch millis of the read that gave [boards]; 0 when none did. */
    val fetchedAt: Long = 0L,
    /** Why the last read failed; null after one that worked. [boards] stay the last good ones. */
    val error: String? = null,
)

/**
 * The sessions feed on the phone: one GET of the URL the "Home feed" setting names, parsed into
 * [HomeBoards] and kept in a preferences file of its own with its time, so the home has its
 * boards before any read and after a read that failed. No URL: nothing is read and nothing is kept.
 */
@Singleton
class HomeFeed(
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
    /** A blocking GET: the body of a 2xx answer. Throws [IOException] for anything else. */
    private val fetch: (String) -> String,
    private val clock: () -> Long,
) {
    @Inject constructor(@ApplicationContext context: Context, dispatchers: CoroutineDispatchers) :
        this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE), dispatchers, ::httpGet, System::currentTimeMillis)

    private val _state = MutableStateFlow(HomeState(url = url))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val lock = Mutex()
    private var loaded = false

    /** The feed URL from Settings; blank when none is set. */
    val url: String
        get() = prefs.getString(PreferenceConstants.HOME_FEED_URL, null).orEmpty()

    /** Publish what the preferences hold, read off the main thread, once. */
    suspend fun load() = lock.withLock { ensureLoaded() }

    /** Set the feed URL (blank clears it). A different URL drops the kept boards: they came from another feed. */
    suspend fun setUrl(value: String) = lock.withLock {
        ensureLoaded()
        val next = value.trim()
        if (next == url) return@withLock
        prefs.edit {
            if (next.isEmpty()) remove(PreferenceConstants.HOME_FEED_URL) else putString(PreferenceConstants.HOME_FEED_URL, next)
            remove(PREF_BOARDS)
            remove(PREF_FETCHED)
        }
        _state.value = HomeState(url = next)
    }

    /**
     * Read the feed once. False with no URL (nothing is fetched) or when the read or the parse
     * failed ([HomeState.error] says why and the last boards stay). A URL that changed while
     * the read ran drops that answer and reads the new URL instead.
     */
    suspend fun refresh(): Boolean {
        lock.withLock { ensureLoaded() }
        while (true) {
            val target = url
            if (target.isBlank()) return false
            val read = withContext(dispatchers.io) {
                try {
                    val boards = HomeBoards.parse(fetch(target))
                    Result.success(boards to HomeBoards.encode(boards))
                } catch (e: IOException) {
                    Result.failure(e)
                } catch (e: IllegalArgumentException) {
                    Result.failure(e)
                }
            }
            val outcome = lock.withLock {
                if (url != target) return@withLock null
                read.fold(
                    onSuccess = { (boards, encoded) ->
                        val now = clock()
                        prefs.edit {
                            putString(PREF_BOARDS, encoded)
                            putLong(PREF_FETCHED, now)
                        }
                        _state.value = HomeState(target, boards, now)
                        true
                    },
                    onFailure = { e ->
                        val why = describe(e)
                        // The message only, never the throwable: a parse failure's cause quotes the input.
                        Timber.w("the home feed was not read: %s", why)
                        _state.value = _state.value.copy(error = why)
                        false
                    },
                )
            }
            if (outcome != null) return outcome
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        // Loaded only once the read landed: a load cut short is read again next time.
        _state.value = withContext(dispatchers.io) { readKept() }
        loaded = true
    }

    private fun readKept(): HomeState {
        val url = url
        if (url.isBlank()) return HomeState()
        val raw = prefs.getString(PREF_BOARDS, null) ?: return HomeState(url)
        return try {
            HomeState(url, HomeBoards.parse(raw), prefs.getLong(PREF_FETCHED, 0L))
        } catch (e: IllegalArgumentException) {
            Timber.w("the kept home boards do not parse; starting over")
            HomeState(url)
        }
    }

    companion object {
        /** The feed's own preferences file: the URL, the kept boards and their time; a read every minute rewrites it, not the app's settings. */
        internal const val PREFS_FILE = "home_feed"
        internal const val PREF_BOARDS = "homeFeedBoards"
        internal const val PREF_FETCHED = "homeFeedFetched"
        private const val TIMEOUT_MS = 10_000

        /** A feed is tens of kilobytes; a body past this is not one. */
        internal const val MAX_BYTES = 1 shl 20

        /** The platform's HTTP stack names its objects in a message ("... on com.android.okhttp.Address@e6bb7194"). */
        private val OBJECT_NAMES = Regex("""\s*\bon\s+\S+@[0-9a-f]+""")

        /** The failure as the row says it: the message without object names, else the exception's kind. */
        internal fun describe(e: Throwable): String = e.message?.replace(OBJECT_NAMES, "")?.trim()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

        /**
         * GET [url] and give its body. Blocking: call it off the main thread. A redirect is not
         * followed (the phone talks to the host that was set and nothing else) and no cache answers.
         *
         * @throws IOException when the URL is not valid or not http or https, the host does not
         * answer, the answer is not 2xx, or the body is over [MAX_BYTES].
         */
        internal fun httpGet(url: String): String {
            // URL's own message names the part it choked on ('For input string: "18http:"'), not the problem.
            val target = try {
                URL(url)
            } catch (e: MalformedURLException) {
                throw IOException("The URL is not valid")
            }
            if (target.protocol != "http" && target.protocol != "https") throw IOException("The URL is not http or https")
            val connection = target.openConnection() as? HttpURLConnection ?: throw IOException("The URL is not http or https")
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("The feed answered HTTP $code")
                return connection.inputStream.use { readCapped(it) }
            } finally {
                connection.disconnect()
            }
        }

        internal fun readCapped(stream: InputStream): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) throw IOException("The feed is over ${MAX_BYTES / 1024} KB")
                out.write(buffer, 0, n)
            }
            return out.toByteArray().decodeToString()
        }
    }
}
