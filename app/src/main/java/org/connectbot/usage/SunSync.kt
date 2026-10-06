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

package org.connectbot.usage

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.transport.SSH
import org.json.JSONException
import org.json.JSONObject
import solutions.ormus.logos.herd.HerdrApi
import timber.log.Timber
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The already-authenticated SSH session to Sun, as the uploader needs it: the
 * verified host key, an exec channel and an SFTP upload. No other transport exists:
 * no HTTP endpoint, no token, no third party.
 */
interface SunLink {
    /** `SHA256:...` fingerprint of the host key this session verified, or null. */
    fun hostKeyFingerprint(): String?

    /** Run [command] on Sun and return stdout; throws on failure. Blocking. */
    fun exec(command: String): String

    /** Write [bytes] to the absolute [remotePath] on Sun over SFTP. Blocking. */
    fun upload(remotePath: String, bytes: ByteArray)
}

/** [SunLink] over a connected [SSH] transport. */
class SshSunLink(private val ssh: SSH) : SunLink {
    override fun hostKeyFingerprint(): String? = ssh.hostKeyFingerprint()

    override fun exec(command: String): String = ssh.exec(command)

    override fun upload(remotePath: String, bytes: ByteArray) = ssh.uploadFile(remotePath, bytes)
}

/** Which hosts count as Sun, and whether a session really reached Sun. */
object SunMatcher {
    /** [host] is Sun when its hostname or nickname is in the comma-separated [setting]. */
    fun isSun(host: Host, setting: String): Boolean {
        val names = setting.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return names.any { it.equals(host.hostname, ignoreCase = true) || it.equals(host.nickname, ignoreCase = true) }
    }

    /** Compare `SHA256:` fingerprints, tolerating a missing prefix and base64 padding. */
    fun fingerprintsMatch(actual: String?, pinned: String): Boolean {
        val a = normalize(actual ?: return false)
        val b = normalize(pinned)
        return a.isNotEmpty() && a == b
    }

    private fun normalize(fingerprint: String): String = fingerprint.trim().removePrefix("SHA256:").trimEnd('=')
}

/** Outcome of one upload attempt. */
sealed interface UploadResult {
    /** No connected session to Sun right now. */
    data object NoSun : UploadResult

    /** The session's host key does not match the pinned Sun key: nothing sent, queue kept. */
    data object HostKeyRefused : UploadResult

    data class Uploaded(val events: Int, val dailies: Int, val inbox: Int, val closed: Int) : UploadResult

    /** Something failed; nothing was marked sent, so the next attempt retries. */
    data class Failed(val reason: String) : UploadResult
}

/**
 * Moves new usage events and the wish list to Sun over a [SunLink].
 *
 * Usage events append to `<device>.jsonl` behind a cursor file Sun keeps
 * (`<device>.cursor`): the phone reads the cursor first and sends only events after
 * it, and Sun appends only when the cursor still matches, so an event is never sent
 * twice even if the phone dies between Sun's write and its own bookkeeping. The wish
 * list is a full snapshot that replaces `<device>-wishes.jsonl`, so edits and
 * deletions land as they are. Before sending, the phone reads `closed.json`, which
 * `inbox.py close` writes, and marks those entries done.
 *
 * Sun's directory is mode 700 and its files 600, enforced on every upload.
 */
class UsageUploader(
    private val store: UsageStore,
    private val inbox: InboxStore,
    private val log: UsageLog,
    private val clock: UsageClock,
) {
    /** Blocking; call off the main thread. */
    fun upload(link: SunLink, pinnedHostKey: String): UploadResult {
        if (!SunMatcher.fingerprintsMatch(link.hostKeyFingerprint(), pinnedHostKey)) {
            log.log(UsageActions.UPLOAD_REFUSED_HOSTKEY)
            return UploadResult.HostKeyRefused
        }
        val device = store.deviceId()
        return try {
            val (dir, cursor, closed) = parsePrepare(link.exec(prepareCommand(device)))
            val closedCount = inbox.applyClosed(closed, clock.now())
            val batch = store.pending(cursor)
            val wishes = if (inbox.hasPending()) inbox.uploadSnapshot() else null
            if (!batch.isEmpty) {
                val lines = batch.events.map { it.toJson(device) } + batch.dailies.map { it.toJson(device) }
                link.upload("$dir/.incoming-$device.jsonl", lines.joinToString("") { "$it\n" }.toByteArray())
            }
            if (wishes != null) {
                val lines = wishes.entries.map { it.toWireJson(device) }
                link.upload("$dir/.incoming-$device-wishes.jsonl", lines.joinToString("") { "$it\n" }.toByteArray())
            }
            val landed = link.exec(commitCommand(device, cursor, batch.cursor)).trim().toLongOrNull()
            if (landed != batch.cursor) return UploadResult.Failed("cursor $landed, expected ${batch.cursor}")
            store.markUploaded(batch.cursor, batch.dailies.map { it.id }.toSet(), clock.now())
            wishes?.let { inbox.markSent(it.revs) }
            UploadResult.Uploaded(batch.events.size, batch.dailies.size, wishes?.entries?.size ?: 0, closedCount)
        } catch (e: Exception) {
            Timber.w(e, "Usage upload to Sun failed")
            UploadResult.Failed(e.javaClass.simpleName)
        }
    }

    private data class Prepared(val dir: String, val cursor: Long, val closed: Map<String, ClosedMarker>)

    private fun parsePrepare(output: String): Prepared {
        val lines = output.split('\n')
        val dir = lines.getOrNull(0)?.trim().orEmpty()
        require(dir.startsWith("/") && dir.endsWith("/$REMOTE_DIR_NAME")) { "Unexpected usage dir" }
        val cursor = lines.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
        return Prepared(dir, cursor, parseClosed(lines.drop(2).joinToString("\n")))
    }

    companion object {
        const val REMOTE_DIR_NAME = "mercurio-usage"

        /** Shell expression for the directory on Sun. */
        private const val REMOTE_DIR = "\"\$HOME/.local/share/$REMOTE_DIR_NAME\""

        /**
         * Create the directory (700), drop stale staging files, then print the absolute
         * directory, this device's cursor and `closed.json`.
         */
        fun prepareCommand(device: String): String {
            require(UsageStore.DEVICE_ID.matches(device))
            return sh(
                """
                set -e
                umask 077
                d=$REMOTE_DIR
                mkdir -p "${'$'}d"
                chmod 700 "${'$'}d"
                rm -f "${'$'}d/.incoming-$device.jsonl" "${'$'}d/.incoming-$device-wishes.jsonl"
                printf '%s\n' "${'$'}d"
                if [ -f "${'$'}d/$device.cursor" ]; then cat "${'$'}d/$device.cursor"; else echo 0; fi
                if [ -f "${'$'}d/closed.json" ]; then cat "${'$'}d/closed.json"; fi
                """.trimIndent(),
            )
        }

        /**
         * Append staged events only if Sun's cursor still equals [expected], move the
         * staged wish snapshot into place, enforce 600 on every file, and print the
         * cursor Sun ends with.
         */
        fun commitCommand(device: String, expected: Long, next: Long): String {
            require(UsageStore.DEVICE_ID.matches(device))
            return sh(
                """
                set -e
                umask 077
                d=$REMOTE_DIR
                chmod 700 "${'$'}d"
                c="${'$'}d/$device.cursor"
                s="${'$'}d/.incoming-$device.jsonl"
                w="${'$'}d/.incoming-$device-wishes.jsonl"
                cur=${'$'}(cat "${'$'}c" 2>/dev/null || echo 0)
                if [ -f "${'$'}s" ]; then
                  if [ "${'$'}cur" = "$expected" ]; then
                    cat "${'$'}s" >> "${'$'}d/$device.jsonl"
                    printf '%s\n' "$next" > "${'$'}c"
                  fi
                  rm -f "${'$'}s"
                fi
                if [ -f "${'$'}w" ]; then mv -f "${'$'}w" "${'$'}d/$device-wishes.jsonl"; fi
                find "${'$'}d" -maxdepth 1 -type f -exec chmod 600 {} +
                cat "${'$'}c" 2>/dev/null || echo "${'$'}cur"
                """.trimIndent(),
            )
        }

        /** Run through POSIX sh: the account's login shell may not be sh-compatible. */
        private fun sh(script: String): String = "sh -c " + HerdrApi.shQuote(script)

        /** `closed.json`: `{ "<id>": { "closed_at_ms": 0, "note": "..." } }`. Unreadable means none. */
        fun parseClosed(text: String): Map<String, ClosedMarker> {
            if (text.isBlank()) return emptyMap()
            return try {
                val json = JSONObject(text)
                json.keys().asSequence().mapNotNull { id ->
                    val entry = json.optJSONObject(id) ?: return@mapNotNull null
                    id to ClosedMarker(entry.optLong("closed_at_ms", Long.MAX_VALUE), entry.optStringOrNull("note"))
                }.toMap()
            } catch (e: JSONException) {
                Timber.w(e, "closed.json unreadable")
                emptyMap()
            }
        }
    }
}

/**
 * Decides when to upload: when a session to Sun opens, at most once a day for usage
 * (sooner when wish-list entries wait), and on demand from Settings. Uploads run on
 * the IO dispatcher, one at a time, and never block the UI.
 */
@Singleton
class SunSync @Inject constructor(
    store: UsageStore,
    private val inbox: InboxStore,
    tracker: UsageTracker,
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
    private val clock: UsageClock,
) {
    private val usageStore = store
    private val uploader = UsageUploader(store, inbox, tracker, clock)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val mutex = Mutex()

    @Volatile
    private var current: Pair<WeakReference<Any>, SunLink>? = null

    private val sunHosts: String
        get() = prefs.getString(UsageSettings.KEY_SUN_HOSTS, null) ?: UsageSettings.SUN_HOSTS_DEFAULT

    private val pinnedHostKey: String
        get() = prefs.getString(UsageSettings.KEY_SUN_HOST_KEY, null)?.takeIf { it.isNotBlank() }
            ?: UsageSettings.SUN_HOST_KEY_DEFAULT

    /** A session opened. If it is to Sun, remember its link and upload when due. */
    fun onSessionOpen(session: Any, host: Host, link: SunLink?) {
        if (link == null || !SunMatcher.isSun(host, sunHosts)) return
        current = WeakReference(session) to link
        scope.launch { if (isDue()) uploadLocked(link) }
    }

    fun onSessionClosed(session: Any) {
        if (current?.first?.get() === session) current = null
    }

    /** Upload now over the open Sun session, if there is one. */
    suspend fun uploadNow(): UploadResult = withContext(dispatchers.io) {
        val link = current?.second ?: return@withContext UploadResult.NoSun
        uploadLocked(link)
    }

    /** Whether a Sun session is open to upload over. */
    val hasSun: Boolean get() = current?.first?.get() != null

    private suspend fun uploadLocked(link: SunLink): UploadResult = mutex.withLock { uploader.upload(link, pinnedHostKey) }

    private fun isDue(): Boolean = clock.now() - usageStore.lastUploadAt() >= UPLOAD_INTERVAL_MS || inbox.hasPending()

    companion object {
        const val UPLOAD_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
