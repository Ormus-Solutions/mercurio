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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.UUID

/** What an inbox entry is: a wish he typed or spoke, or a connection error he sent. */
enum class InboxKind(val wire: String) {
    WISH("wish"),
    ERROR("error"),
    ;

    companion object {
        fun fromWire(value: String?): InboxKind = entries.firstOrNull { it.wire == value } ?: WISH
    }
}

/**
 * One wish-list entry. Unlike [UsageEvent] this deliberately holds text: a wish is
 * text he chose to write down, and an error entry holds the connection diagnostic
 * report, which is secrets-free by construction.
 */
data class InboxEntry(
    val id: String,
    val kind: InboxKind,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val screen: String?,
    val herdr: Boolean?,
    val done: Boolean = false,
    /** Closing note, from Settings or from `inbox.py close` on Sun. */
    val note: String? = null,
    /** Deleted on the phone; kept as a tombstone until Sun has the deletion. */
    val deleted: Boolean = false,
    /** Bumped on every change. */
    val rev: Int = 1,
    /** The [rev] Sun holds; equal to [rev] once synced. */
    val sentRev: Int = 0,
) {
    val synced: Boolean get() = rev == sentRev

    /** The line written to Sun. Carries no local bookkeeping. */
    fun toWireJson(deviceId: String): JSONObject = JSONObject().apply {
        put("v", UsageEvent.SCHEMA_VERSION)
        put("type", kind.wire)
        put("device", deviceId)
        put("id", id)
        put("rev", rev)
        put("text", text)
        put("created", createdAt)
        put("updated", updatedAt)
        put("screen", screen ?: JSONObject.NULL)
        put("herdr", herdr ?: JSONObject.NULL)
        put("done", done)
        put("note", note ?: JSONObject.NULL)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.wire)
        put("text", text)
        put("created", createdAt)
        put("updated", updatedAt)
        put("screen", screen ?: JSONObject.NULL)
        put("herdr", herdr ?: JSONObject.NULL)
        put("done", done)
        put("note", note ?: JSONObject.NULL)
        put("deleted", deleted)
        put("rev", rev)
        put("sent_rev", sentRev)
    }

    companion object {
        fun fromJson(json: JSONObject): InboxEntry = InboxEntry(
            id = json.getString("id"),
            kind = InboxKind.fromWire(json.optStringOrNull("kind")),
            text = json.optString("text"),
            createdAt = json.optLong("created"),
            updatedAt = json.optLong("updated"),
            screen = json.optStringOrNull("screen"),
            herdr = if (json.isNull("herdr")) null else json.optBoolean("herdr"),
            done = json.optBoolean("done"),
            note = json.optStringOrNull("note"),
            deleted = json.optBoolean("deleted"),
            rev = json.optInt("rev", 1),
            sentRev = json.optInt("sent_rev", 0),
        )
    }
}

/** One `inbox.py close` marker from Sun's `closed.json`. */
data class ClosedMarker(
    val closedAt: Long,
    val note: String?,
)

/** The inbox as Sun should hold it, and the revisions that snapshot covers. */
data class InboxUpload(
    val entries: List<InboxEntry>,
    val revs: Map<String, Int>,
)

/**
 * The wish list: wishes and error reports, queued in app-private storage until a
 * connection to Sun carries them over. Kept apart from the usage log so usage
 * events stay text-free.
 *
 * Every method does disk I/O; call off the main thread. [entries] is safe to
 * collect anywhere.
 */
class InboxStore(private val dir: File) {
    private val file get() = File(dir, INBOX_FILE)
    private var loaded = false
    private val items = ArrayList<InboxEntry>()
    private val _entries = MutableStateFlow<List<InboxEntry>>(emptyList())

    /** Live, non-deleted entries, newest first. Empty until the first call loads the file. */
    val entries: StateFlow<List<InboxEntry>> = _entries.asStateFlow()

    @Synchronized
    fun all(): List<InboxEntry> {
        load()
        return visible()
    }

    @Synchronized
    fun add(kind: InboxKind, text: String, now: Long, screen: String?, herdr: Boolean?): InboxEntry? {
        load()
        val clean = text.trim().take(if (kind == InboxKind.ERROR) MAX_ERROR_CHARS else MAX_WISH_CHARS)
        if (clean.isEmpty()) return null
        val entry = InboxEntry(
            id = UUID.randomUUID().toString(),
            kind = kind,
            text = clean,
            createdAt = now,
            updatedAt = now,
            screen = screen,
            herdr = herdr,
        )
        items.add(entry)
        save()
        return entry
    }

    @Synchronized
    fun edit(id: String, text: String, now: Long) {
        val clean = text.trim().take(MAX_WISH_CHARS)
        if (clean.isEmpty()) return
        change(id) { it.copy(text = clean, updatedAt = now) }
    }

    @Synchronized
    fun setDone(id: String, done: Boolean, now: Long, note: String? = null) {
        change(id) { it.copy(done = done, note = if (done) note ?: it.note else null, updatedAt = now) }
    }

    /** Clears the text right away; the tombstone goes once Sun has the deletion. */
    @Synchronized
    fun delete(id: String, now: Long) {
        change(id) { it.copy(deleted = true, text = "", note = null, updatedAt = now) }
    }

    /** True when Sun lacks a change: a new, edited, closed or deleted entry. */
    @Synchronized
    fun hasPending(): Boolean {
        load()
        return items.any { !it.synced }
    }

    @Synchronized
    fun pendingCount(): Int {
        load()
        return items.count { !it.synced }
    }

    /** The full live inbox for Sun, plus the revisions it covers. */
    @Synchronized
    fun uploadSnapshot(): InboxUpload {
        load()
        return InboxUpload(
            entries = items.filter { !it.deleted }.sortedBy { it.createdAt },
            revs = items.associate { it.id to it.rev },
        )
    }

    /** Sun now holds [revs]: mark those revisions synced and drop synced tombstones. */
    @Synchronized
    fun markSent(revs: Map<String, Int>) {
        load()
        val updated = items.mapNotNull { entry ->
            val sent = revs[entry.id] ?: return@mapNotNull entry
            when {
                entry.deleted && entry.rev == sent -> null
                else -> entry.copy(sentRev = maxOf(entry.sentRev, sent))
            }
        }
        items.clear()
        items.addAll(updated)
        save()
    }

    /**
     * Apply `closed.json` from Sun: entries closed there with `inbox.py close` turn
     * done here, with the closing note. An entry reopened on the phone after its
     * close stays open. Returns how many changed.
     */
    @Synchronized
    fun applyClosed(closed: Map<String, ClosedMarker>, now: Long): Int {
        load()
        var changed = 0
        for (i in items.indices) {
            val entry = items[i]
            val marker = closed[entry.id] ?: continue
            if (entry.deleted || entry.done || entry.updatedAt > marker.closedAt) continue
            items[i] = entry.copy(done = true, note = marker.note, updatedAt = now, rev = entry.rev + 1)
            changed++
        }
        if (changed > 0) save()
        return changed
    }

    private fun change(id: String, transform: (InboxEntry) -> InboxEntry) {
        load()
        val index = items.indexOfFirst { it.id == id && !it.deleted }
        if (index < 0) return
        val before = items[index]
        items[index] = transform(before).copy(rev = before.rev + 1)
        save()
    }

    private fun visible(): List<InboxEntry> = items.filter { !it.deleted }.sortedByDescending { it.createdAt }

    private fun load() {
        if (loaded) return
        loaded = true
        PrivateFiles.ensureDir(dir)
        if (file.exists()) {
            try {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) items.add(InboxEntry.fromJson(array.getJSONObject(i)))
            } catch (e: JSONException) {
                Timber.w(e, "Wish list unreadable")
            }
        }
        _entries.value = visible()
    }

    private fun save() {
        PrivateFiles.writeAtomically(file, JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
        _entries.value = visible()
    }

    companion object {
        const val INBOX_FILE = "inbox.json"
        const val MAX_WISH_CHARS = 4000
        const val MAX_ERROR_CHARS = 20000
    }
}
