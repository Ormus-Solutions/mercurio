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

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Raw events plus the daily roll-ups of older ones. */
data class UsageSnapshot(
    val events: List<UsageEvent>,
    val dailies: List<DailyCount>,
)

/** What one upload sends: raw events after Sun's cursor, and roll-ups Sun lacks. */
data class PendingBatch(
    val events: List<UsageEvent>,
    val dailies: List<DailyCount>,
    /** Sun's cursor after this batch lands. */
    val cursor: Long,
) {
    val isEmpty: Boolean get() = events.isEmpty() && dailies.isEmpty()
}

/**
 * Bounded, append-only usage log in app-private storage.
 *
 * `events.jsonl` holds raw events, one JSON object per line, appended as they
 * happen. `state.json` holds the device id, the sequence counter, the upload
 * cursor and the daily roll-ups. Raw events older than [RETENTION_MS], or beyond
 * the newest [MAX_RAW], fold into [DailyCount] rows so the store never grows
 * without bound.
 *
 * Not thread-safe on its own beyond its monitor; every method does disk I/O, so
 * callers stay off the main thread.
 */
class UsageStore(
    private val dir: File,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    // java.time needs API 26; minSdk is 24.
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }

    private val eventsFile get() = File(dir, EVENTS_FILE)
    private val stateFile get() = File(dir, STATE_FILE)

    private var loaded = false
    private val events = ArrayList<UsageEvent>()
    private val dailies = ArrayList<DailyCount>()
    private var deviceIdValue = ""
    private var nextSeq = 1L
    private var uploadedThrough = 0L
    private var lastUploadAt = 0L

    @Synchronized
    fun deviceId(): String {
        load()
        return deviceIdValue
    }

    @Synchronized
    fun lastUploadAt(): Long {
        load()
        return lastUploadAt
    }

    /** Highest event sequence number Sun is known to hold. */
    @Synchronized
    fun uploadedThrough(): Long {
        load()
        return uploadedThrough
    }

    @Synchronized
    fun append(
        ts: Long,
        action: String,
        screen: String? = null,
        herdr: Boolean? = null,
        durationMs: Long? = null,
        reason: String? = null,
    ): UsageEvent {
        load()
        val event = UsageEvent(nextSeq++, ts, action, screen, herdr, durationMs, reason)
        events.add(event)
        PrivateFiles.appendLine(eventsFile, event.toJson().toString())
        if (events.size > MAX_RAW + ROLL_UP_SLACK) rollUp(ts)
        return event
    }

    @Synchronized
    fun snapshot(): UsageSnapshot {
        load()
        return UsageSnapshot(events.toList(), dailies.toList())
    }

    /**
     * Fold raw events older than [retentionMs], and the oldest beyond [maxRaw], into
     * daily counts. Events Sun already holds land in local-only rows; events it lacks
     * land in rows that still upload.
     */
    @Synchronized
    fun rollUp(now: Long, maxRaw: Int = MAX_RAW, retentionMs: Long = RETENTION_MS) {
        load()
        val cutoff = now - retentionMs
        val overflow = (events.size - maxRaw).coerceAtLeast(0)
        val (old, keep) = events.withIndex().partition { (index, event) -> index < overflow || event.ts < cutoff }
        if (old.isEmpty()) return
        for ((_, event) in old) {
            val day = dayFormat.format(Date(event.ts))
            val uploaded = event.seq <= uploadedThrough
            val index = dailies.indexOfFirst { it.sameBucket(day, event.action, event.screen, event.herdr, uploaded) }
            if (index >= 0) {
                val row = dailies[index]
                dailies[index] = row.copy(count = row.count + 1, durationMs = row.durationMs + (event.durationMs ?: 0))
            } else {
                dailies.add(
                    DailyCount(
                        id = "d-" + UUID.randomUUID().toString(),
                        day = day,
                        action = event.action,
                        screen = event.screen,
                        herdr = event.herdr,
                        count = 1,
                        durationMs = event.durationMs ?: 0,
                        uploaded = uploaded,
                    ),
                )
            }
        }
        events.clear()
        events.addAll(keep.map { it.value })
        rewriteEvents()
        saveState()
    }

    /** Forget every event and roll-up. The device id and counters stay, so Sun's cursor stays valid. */
    @Synchronized
    fun clear() {
        load()
        events.clear()
        dailies.clear()
        rewriteEvents()
        saveState()
    }

    /** Events after [serverCursor] (at most [max]) plus every roll-up Sun lacks. */
    @Synchronized
    fun pending(serverCursor: Long, max: Int = MAX_BATCH): PendingBatch {
        load()
        val batchEvents = events.filter { it.seq > serverCursor }.take(max)
        val cursor = maxOf(serverCursor, batchEvents.maxOfOrNull { it.seq } ?: serverCursor)
        return PendingBatch(batchEvents, dailies.filter { !it.uploaded }, cursor)
    }

    /** Record that Sun now holds every event through [cursor] and the roll-ups in [dailyIds]. */
    @Synchronized
    fun markUploaded(cursor: Long, dailyIds: Set<String>, at: Long) {
        load()
        uploadedThrough = maxOf(uploadedThrough, cursor)
        for (i in dailies.indices) {
            if (dailies[i].id in dailyIds) dailies[i] = dailies[i].copy(uploaded = true)
        }
        lastUploadAt = at
        saveState()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        PrivateFiles.ensureDir(dir)
        if (stateFile.exists()) {
            try {
                val state = JSONObject(stateFile.readText())
                deviceIdValue = state.optString("device_id")
                nextSeq = state.optLong("next_seq", 1)
                uploadedThrough = state.optLong("uploaded_through", 0)
                lastUploadAt = state.optLong("last_upload_at", 0)
                val rows = state.optJSONArray("dailies") ?: JSONArray()
                for (i in 0 until rows.length()) dailies.add(DailyCount.fromJson(rows.getJSONObject(i)))
            } catch (e: JSONException) {
                Timber.w(e, "Usage state unreadable; starting fresh")
            }
        }
        if (eventsFile.exists()) {
            eventsFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    events.add(UsageEvent.fromJson(JSONObject(line)))
                } catch (e: JSONException) {
                    // A torn last line after a crash; skip it.
                    Timber.w(e, "Skipping unreadable usage line")
                }
            }
        }
        nextSeq = maxOf(nextSeq, (events.maxOfOrNull { it.seq } ?: 0) + 1)
        if (!DEVICE_ID.matches(deviceIdValue)) {
            deviceIdValue = UUID.randomUUID().toString()
            saveState()
        }
    }

    private fun rewriteEvents() {
        PrivateFiles.writeAtomically(eventsFile, events.joinToString("") { it.toJson().toString() + "\n" })
    }

    private fun saveState() {
        val state = JSONObject().apply {
            put("device_id", deviceIdValue)
            put("next_seq", nextSeq)
            put("uploaded_through", uploadedThrough)
            put("last_upload_at", lastUploadAt)
            put("dailies", JSONArray().apply { dailies.forEach { put(it.toJson()) } })
        }
        PrivateFiles.writeAtomically(stateFile, state.toString())
    }

    companion object {
        const val EVENTS_FILE = "events.jsonl"
        const val STATE_FILE = "state.json"

        /** Newest raw events kept before roll-up. */
        const val MAX_RAW = 5000

        /** Raw events kept this long before roll-up. */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /** Most raw events in one upload. */
        const val MAX_BATCH = 5000

        // Roll up in chunks instead of on every append once the cap is reached.
        private const val ROLL_UP_SLACK = 500

        /** Device ids are UUIDs: safe in a file name and in a shell word. */
        val DEVICE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
