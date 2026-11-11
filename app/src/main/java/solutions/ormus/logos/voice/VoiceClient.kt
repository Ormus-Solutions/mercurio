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

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The rewrite style sent as the style query; [SERVER] sends none, so the server picks its own. */
enum class VoiceStyle(val key: String?) {
    SERVER(null),
    PROMPT("prompt"),
    FORMAL("formal"),
    CASUAL("casual"),
    VERBATIM("verbatim"),
    ;

    companion object {
        fun fromKey(key: String?): VoiceStyle = entries.firstOrNull { it.key != null && it.key == key } ?: SERVER
    }
}

/** Where the voice server is, from the setting and the session's own host. */
object VoiceServer {
    const val PORT: Int = 8097

    /** The base URL ("http://host:8097"); a blank [setting] falls back to [sessionHost]; null when neither is usable. */
    fun baseUrl(setting: String?, sessionHost: String?): String? {
        val typed = setting?.trim().orEmpty()
        val wanted = typed.ifEmpty { sessionHost?.trim().orEmpty() }
        if (wanted.isEmpty() || wanted.any { it.isWhitespace() }) return null
        val scheme = if (wanted.startsWith("https://")) "https" else "http"
        val rest = wanted.removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (rest.isEmpty() || rest.contains('/')) return null
        // A bare host gets the contract's port; an https URL keeps the port it has (usually none).
        return if (rest.contains(':') || scheme == "https") "$scheme://$rest" else "$scheme://$rest:$PORT"
    }

    /** Plain http is sent only inside a tailnet, a LAN or loopback; anything else must be https. */
    fun cleartextAllowed(host: String): Boolean {
        // Only plain DNS names and strict dotted-decimal addresses: no brackets, no integer or octal forms.
        if (!NAME.matches(host)) return false
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { OCTET.matches(it) && it.toInt() <= 255 }) {
            val (a, b) = parts.map { it.toInt() }
            return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
        }
        // A name must start with a letter. A single label is a MagicDNS short name; *.ts.net is the tailnet's own domain.
        if (!host[0].isLetter()) return false
        return parts.size == 1 || host.endsWith(".ts.net")
    }

    private val NAME = Regex("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?")
    private val OCTET = Regex("0|[1-9][0-9]{0,2}")
}

data class VoiceRequest(val url: String, val contentType: String?) {
    companion object {
        fun health(baseUrl: String): VoiceRequest = VoiceRequest("$baseUrl/v1/health", null)

        fun dictate(baseUrl: String, style: VoiceStyle, contentType: String = "audio/wav"): VoiceRequest {
            val query = style.key?.let { "?style=$it" }.orEmpty()
            return VoiceRequest("$baseUrl/v1/dictate$query", contentType)
        }
    }
}

data class HttpReply(val code: Int, val body: String)

/** One HTTP exchange; throws java.io.IOException when the server cannot be reached. */
interface VoiceTransport {
    suspend fun get(request: VoiceRequest): HttpReply

    suspend fun post(request: VoiceRequest, body: File): HttpReply
}

/** What a take came back as. */
sealed interface Dictation {
    data class Text(val text: String, val rewriteFailed: Boolean = false) : Dictation

    /** The server heard no speech worth keeping. */
    data object Junk : Dictation

    data class Failed(val kind: String, val message: String) : Dictation {
        /** Sending the same take again can work. */
        val retryable: Boolean get() = kind != "bad_audio" && kind != "too_large" && kind != "bad_request"
    }
}

object VoiceReply {
    fun parseDictation(code: Int, body: String): Dictation {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            null
        }
        if (code !in 200..299) {
            val kind = json?.optString("error").orEmpty()
            return if (kind.isNotEmpty()) {
                Dictation.Failed(kind, json?.optString("message").orEmpty())
            } else {
                Dictation.Failed("server", "The voice server answered $code")
            }
        }
        if (json == null || !json.has("text")) return Dictation.Failed("bad_reply", "The voice server's answer could not be read")
        val text = json.optString("text").trim()
        if (text.isEmpty()) return Dictation.Junk
        return Dictation.Text(text, rewriteFailed = json.optString("rewrite") == "failed")
    }

    fun parseHealth(code: Int, body: String): Boolean = code == 200 &&
        try {
            JSONObject(body).optString("status") == "ok"
        } catch (_: JSONException) {
            false
        }
}

class VoiceClient(private val transport: VoiceTransport) {
    suspend fun health(baseUrl: String): Boolean = try {
        val reply = transport.get(VoiceRequest.health(baseUrl))
        VoiceReply.parseHealth(reply.code, reply.body)
    } catch (_: IOException) {
        false
    } catch (e: RuntimeException) {
        if (e is CancellationException) throw e
        false
    }

    suspend fun dictate(baseUrl: String, style: VoiceStyle, take: File, contentType: String = "audio/wav"): Dictation = try {
        val reply = transport.post(VoiceRequest.dictate(baseUrl, style, contentType), take)
        VoiceReply.parseDictation(reply.code, reply.body)
    } catch (e: IOException) {
        Dictation.Failed("unreachable", e.message ?: "The voice server could not be reached")
    } catch (e: RuntimeException) {
        // A bad address or an odd reply must never end the app: it is one more failed take.
        if (e is CancellationException) throw e
        Dictation.Failed("unreachable", "The voice server could not be reached")
    }
}
