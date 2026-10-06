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

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.transport.CommandOutput
import solutions.ormus.logos.herd.HerdrApi
import timber.log.Timber
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This phone's UnifiedPush endpoint and its place in each Herdr host's
 * `${XDG_CONFIG_HOME:-$HOME/.config}/mercurio/push-endpoints` (one URL per line),
 * the file the host's push watcher reads on every push.
 *
 * The endpoint comes from the distributor (the ntfy app) and lives in preferences
 * with the one before it, so a host that still lists the old URL drops it the next
 * time Mercurio connects. Null until a distributor hands one over.
 */
@Singleton
class PushEndpoints @Inject constructor(private val prefs: SharedPreferences) {
    private val _endpoint = MutableStateFlow(prefs.getString(KEY_ENDPOINT, null))

    /** The endpoint hosts should push to, null when there is none. */
    val endpoint: StateFlow<String?> = _endpoint.asStateFlow()

    /** This phone's endpoint before the current one, to remove from hosts. */
    val previous: String? get() = prefs.getString(KEY_PREVIOUS, null)

    /** The distributor handed over [url] (null: unregistered). False when [url] is not one a host may store. */
    @Synchronized
    fun update(url: String?): Boolean {
        if (url != null && !isValid(url)) return false
        val current = _endpoint.value
        if (url == current) return true
        prefs.edit {
            putString(KEY_PREVIOUS, current ?: previous)
            putString(KEY_ENDPOINT, url)
        }
        _endpoint.value = url
        return true
    }

    /**
     * Bring one host's endpoints file up to date over [exec] (an exec channel on a live
     * session). Nothing to do, or done, is true; a failure is logged and false.
     */
    fun syncTo(hostName: String, exec: (String) -> CommandOutput?): Boolean {
        val current = endpoint.value
        val stale = previous?.takeIf { it != current }
        if (current == null && stale == null) return true
        val out = try {
            exec(syncCommand(current, stale))
        } catch (e: Exception) {
            Timber.w(e, "Push endpoint not written on %s", hostName)
            null
        }
        val ok = out != null && (out.exitCode == null || out.exitCode == 0)
        if (!ok) Timber.w("Push endpoint not written on %s (exit %s)", hostName, out?.exitCode)
        return ok
    }

    companion object {
        private const val KEY_ENDPOINT = "pushEndpoint"
        private const val KEY_PREVIOUS = "pushEndpointPrevious"
        private const val MAX_URL_CHARS = 2048

        // A plain http(s) URL: no spaces, quotes, backslashes or backticks, so it stays one
        // line in the file and one word in the script.
        private val URL = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&()*+,;=%-]+")

        /** Whether [url] is an http(s) URL with a host, fit for the endpoints file. */
        fun isValid(url: String): Boolean {
            if (url.length > MAX_URL_CHARS || !URL.matches(url)) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
        }

        /**
         * The script that adds [endpoint] when it is missing (never twice) and removes
         * [stale]; either may be null. It creates the file 600, and ends a last line that
         * lacks a newline before appending.
         */
        fun syncScript(endpoint: String?, stale: String?): String {
            require(endpoint == null || isValid(endpoint)) { "Not a push endpoint" }
            require(stale == null || isValid(stale)) { "Not a push endpoint" }
            val d = '$'
            return """
                set -e
                umask 077
                dir="$d{XDG_CONFIG_HOME:-${d}HOME/.config}/mercurio"
                f="${d}dir/push-endpoints"
                mkdir -p "${d}dir"
                touch "${d}f"
                chmod 600 "${d}f"
                new=${HerdrApi.shQuote(endpoint.orEmpty())}
                old=${HerdrApi.shQuote(stale.orEmpty())}
                if [ -n "${d}old" ] && [ "${d}old" != "${d}new" ] && grep -qxF -e "${d}old" "${d}f"; then
                  grep -vxF -e "${d}old" "${d}f" > "${d}f.tmp" || true
                  mv -f "${d}f.tmp" "${d}f"
                fi
                if [ -n "${d}new" ] && ! grep -qxF -e "${d}new" "${d}f"; then
                  if [ -s "${d}f" ] && [ -n "$d(tail -c 1 "${d}f")" ]; then echo >> "${d}f"; fi
                  printf '%s\n' "${d}new" >> "${d}f"
                fi
            """.trimIndent()
        }

        /** [syncScript] through a login shell, like the Herdr commands, so XDG_CONFIG_HOME is the user's. */
        fun syncCommand(endpoint: String?, stale: String?): String = HerdrApi.loginShell(syncScript(endpoint, stale))
    }
}
