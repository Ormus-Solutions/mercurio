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

import org.json.JSONObject

/**
 * One recorded use of a control or feature.
 *
 * The schema holds no free text by design: [action] is a listed id from
 * [UsageActions], [screen] a sanitized route token, [reason] a disconnect reason
 * enum name. Typed or dictated text, terminal output, hostnames, usernames, keys
 * and passwords have no field to land in.
 */
data class UsageEvent(
    /** Per-device sequence number; the upload cursor on Sun counts these. */
    val seq: Long,
    /** Epoch milliseconds. */
    val ts: Long,
    val action: String,
    val screen: String? = null,
    /** Whether the active host's session runs Herdr; null off the console. */
    val herdr: Boolean? = null,
    /** Session length, on [UsageActions.SESSION_END] only. */
    val durationMs: Long? = null,
    /** Disconnect reason class (an enum name), on session end and failure only. */
    val reason: String? = null,
) {
    fun toJson(deviceId: String? = null): JSONObject = JSONObject().apply {
        put("v", SCHEMA_VERSION)
        put("type", "event")
        deviceId?.let { put("device", it) }
        put("seq", seq)
        put("ts", ts)
        put("action", action)
        put("screen", screen ?: JSONObject.NULL)
        put("herdr", herdr ?: JSONObject.NULL)
        put("duration_ms", durationMs ?: JSONObject.NULL)
        put("reason", reason ?: JSONObject.NULL)
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(json: JSONObject): UsageEvent = UsageEvent(
            seq = json.getLong("seq"),
            ts = json.getLong("ts"),
            action = json.getString("action"),
            screen = json.optStringOrNull("screen"),
            herdr = if (json.isNull("herdr")) null else json.optBoolean("herdr"),
            durationMs = if (json.isNull("duration_ms")) null else json.optLong("duration_ms"),
            reason = json.optStringOrNull("reason"),
        )
    }
}

/**
 * Raw events older than the retention window roll up into one row per day, action,
 * screen and host type. Same no-text rule as [UsageEvent].
 */
data class DailyCount(
    /** Stable row id, so a re-sent row never counts twice on Sun. */
    val id: String,
    /** Local calendar day, `yyyy-MM-dd`. */
    val day: String,
    val action: String,
    val screen: String?,
    val herdr: Boolean?,
    val count: Long,
    /** Sum of session durations folded into this row. */
    val durationMs: Long = 0,
    /** True once Sun holds every event in this row; such rows stay local. */
    val uploaded: Boolean = false,
) {
    fun sameBucket(day: String, action: String, screen: String?, herdr: Boolean?, uploaded: Boolean): Boolean = this.day == day && this.action == action && this.screen == screen && this.herdr == herdr &&
        this.uploaded == uploaded

    fun toJson(deviceId: String? = null): JSONObject = JSONObject().apply {
        put("v", UsageEvent.SCHEMA_VERSION)
        put("type", "daily")
        deviceId?.let { put("device", it) }
        put("id", id)
        put("day", day)
        put("action", action)
        put("screen", screen ?: JSONObject.NULL)
        put("herdr", herdr ?: JSONObject.NULL)
        put("count", count)
        put("duration_ms", durationMs)
        put("uploaded", uploaded)
    }

    companion object {
        fun fromJson(json: JSONObject): DailyCount = DailyCount(
            id = json.getString("id"),
            day = json.getString("day"),
            action = json.getString("action"),
            screen = json.optStringOrNull("screen"),
            herdr = if (json.isNull("herdr")) null else json.optBoolean("herdr"),
            count = json.getLong("count"),
            durationMs = json.optLong("duration_ms", 0),
            uploaded = json.optBoolean("uploaded", false),
        )
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
