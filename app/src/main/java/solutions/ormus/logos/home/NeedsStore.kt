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

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.PreferenceConstants
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** One option of an ask: the label that is sent back, and a line that explains it. */
@Serializable
data class AskOption(val label: String, val detail: String? = null)

/** A question a session posted to the store, in the store's own shape. */
@Serializable
data class Ask(
    val id: String,
    val question: String,
    val context: String? = null,
    val options: List<AskOption> = emptyList(),
    val multi: Boolean = false,
    /** open, or the word the store uses for a closed ask (answered, withdrawn, expired). */
    val state: String = "open",
) {
    /** False once the store closed it; an unknown word counts as open, and a 409 closes it then. */
    val open: Boolean
        get() = state !in CLOSED_STATES

    private companion object {
        val CLOSED_STATES = setOf("answered", "withdrawn", "expired", "closed", "cancelled", "canceled")
    }
}

@Serializable
private data class AskList(val asks: List<Ask> = emptyList())

/** How a call to the store came back. */
sealed interface StoreReply {
    data class Asks(val asks: List<Ask>) : StoreReply

    data object Answered : StoreReply

    /** 409 or 404 on an answer: the ask was closed before it. */
    data object Closed : StoreReply

    /** 403: the store does not let this phone in. */
    data object Refused : StoreReply

    /** No answer, another status or a body that does not parse; [why] names it without its content. */
    data class Failed(val why: String) : StoreReply
}

/**
 * The questions store the sessions post their asks to (the needs-you app on the operator's own
 * host): its address from Settings, the open asks, and one answer per call over the platform's
 * HTTP stack. No address: nothing is called. Nothing a call carries reaches a log.
 */
@Singleton
class NeedsStore(
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
    /** A blocking call: the method, the URL and a JSON body or null; gives the status and the body. */
    private val call: (String, String, String?) -> Pair<Int, String>,
) {
    @Inject constructor(prefs: SharedPreferences, dispatchers: CoroutineDispatchers) : this(prefs, dispatchers, ::httpCall)

    /** The store's address from Settings; blank when none is set. */
    val address: String
        get() = addressOf(prefs)

    /** Set the address (blank clears it). */
    fun setAddress(value: String) {
        val next = value.trim()
        prefs.edit { if (next.isEmpty()) remove(PreferenceConstants.NEEDS_STORE_URL) else putString(PreferenceConstants.NEEDS_STORE_URL, next) }
    }

    /** `GET <address>/api/asks`: the asks in the store's order. */
    suspend fun asks(): StoreReply = request("GET", "/api/asks", null, closedOn = emptySet()) { body ->
        try {
            StoreReply.Asks(json.decodeFromString<AskList>(body).asks)
        } catch (e: SerializationException) {
            StoreReply.Failed("not a list of asks")
        } catch (e: IllegalArgumentException) {
            StoreReply.Failed("not a list of asks")
        }
    }

    /** `POST <address>/api/asks/<id>/answer` with [choice] or [text], and `via: mercurio`. */
    suspend fun answer(id: String, choice: List<String>? = null, text: String? = null): StoreReply {
        val body = buildJsonObject {
            if (choice != null) putJsonArray("choice") { choice.forEach { add(it) } }
            if (text != null) put("text", text)
            put("via", VIA)
        }.toString()
        return request("POST", "/api/asks/" + encode(id) + "/answer", body, closedOn = ANSWER_CLOSED) { StoreReply.Answered }
    }

    private suspend fun request(method: String, path: String, body: String?, closedOn: Set<Int>, onOk: (String) -> StoreReply): StoreReply {
        val base = address.trimEnd('/')
        if (base.isEmpty()) return StoreReply.Failed("no address")
        return withContext(dispatchers.io) {
            val (code, text) = try {
                call(method, base + path, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w("the questions store did not answer: %s", e.javaClass.simpleName)
                return@withContext StoreReply.Failed(HomeFeed.describe(e))
            } catch (e: RuntimeException) {
                // The HTTP stack can throw more than IOException; a failure here must not end the reads.
                Timber.w("the questions store call failed: %s", e.javaClass.simpleName)
                return@withContext StoreReply.Failed(HomeFeed.describe(e))
            }
            when (code) {
                in 200..299 -> onOk(text)
                403 -> StoreReply.Refused
                in closedOn -> StoreReply.Closed
                else -> StoreReply.Failed("HTTP $code")
            }
        }
    }

    companion object {
        /** What the store reads as "answered from this app"; it takes it only from the operator's phone. */
        const val VIA = "mercurio"

        /** The address [prefs] hold; blank when none is set. */
        fun addressOf(prefs: SharedPreferences): String = prefs.getString(PreferenceConstants.NEEDS_STORE_URL, null).orEmpty().trim()
        private const val TIMEOUT_MS = 10_000

        /** An answer that comes back 409 (closed before it) or 404 (the store has no such ask any more) found its ask closed. */
        private val ANSWER_CLOSED = setOf(404, 409)

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        private fun encode(id: String): String = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

        /** One call to [url]; blocking. No redirect is followed and no cache answers. */
        internal fun httpCall(method: String, url: String, body: String?): Pair<Int, String> {
            val target = try {
                URL(url)
            } catch (e: MalformedURLException) {
                throw IOException("The address is not valid")
            }
            if (target.protocol != "http" && target.protocol != "https") throw IOException("The address is not http or https")
            val connection = target.openConnection() as? HttpURLConnection ?: throw IOException("The address is not http or https")
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = method
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.encodeToByteArray()) }
                }
                val code = connection.responseCode
                if (code !in 200..299) return code to ""
                return code to connection.inputStream.use { HomeFeed.readCapped(it) }
            } finally {
                connection.disconnect()
            }
        }
    }
}
