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

package org.connectbot.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The phone's network as the report describes it. */
data class NetworkSummary(
    /** Transport of the default network: "Wi-Fi", "cellular", "ethernet", "none", ... */
    val type: String,
    val vpnActive: Boolean,
    /** A VPN interface holds a 100.64.0.0/10 address, the range Tailscale assigns. */
    val tailscaleLikely: Boolean = false,
    /** VPN interface and its addresses, e.g. "tun0 100.64.0.3". */
    val vpnDetail: String? = null,
)

/** One terminal session at the moment the report is taken. */
data class SessionSummary(
    val nickname: String,
    val protocol: String,
    val username: String,
    val hostname: String,
    val port: Int,
    /** "connecting", "open" or "disconnected". */
    val state: String,
    val disconnectReason: String? = null,
    val disconnectedAtMillis: Long? = null,
    val failure: Throwable? = null,
    val connectionLog: String = "",
    val transcript: String = "",
)

/** Everything a diagnostics report says, gathered by [DiagnosticsReporter]. */
data class DiagnosticsInput(
    val appName: String,
    val versionName: String,
    val applicationId: String,
    val deviceModel: String,
    val androidVersion: String,
    val network: NetworkSummary,
    val session: SessionSummary?,
    val nowMillis: Long,
    val timeZone: TimeZone,
    /** Failure to report when there is no session, or none recorded on it. */
    val failure: Throwable? = null,
    /** Known secrets (saved passwords) scrubbed verbatim wherever they appear. */
    val secrets: Collection<String> = emptyList(),
)

/**
 * Builds the plain-text, markdown-friendly report behind "Copy details": app,
 * device, network, host, time, failure, the connection log and the tail of the
 * session transcript. Pure, so it is unit tested without Android.
 *
 * It never carries passwords, passphrases or key material: those are not among
 * its inputs, and the free text it does carry (exception messages, terminal
 * output) is scrubbed of private key blocks, `password=value` pairs and the
 * known [DiagnosticsInput.secrets] before it is cut to size. Every section is
 * bounded, so the whole report stays under [MAX_REPORT_CHARS].
 */
object DiagnosticsReport {
    const val TRANSCRIPT_MAX_LINES = 300
    const val TRANSCRIPT_MAX_CHARS = 32 * 1024
    const val CONNECTION_LOG_MAX_LINES = 200
    const val CONNECTION_LOG_MAX_CHARS = 24 * 1024
    const val FAILURE_MAX_CHARS = 6 * 1024
    const val MAX_REPORT_CHARS = 72 * 1024

    private const val FIELD_MAX_CHARS = 256
    private const val STACK_FRAMES = 8
    private const val CAUSE_DEPTH = 5
    private const val MIN_SECRET_LENGTH = 4
    const val REDACTED = "[redacted]"
    const val REDACTED_KEY = "[private key redacted]"

    fun build(input: DiagnosticsInput): String {
        val secrets = input.secrets.filter { it.length >= MIN_SECRET_LENGTH }
        fun clean(text: String) = redact(text, secrets)
        fun field(text: String) = clean(text).replace('\n', ' ').take(FIELD_MAX_CHARS)

        val session = input.session
        val out = StringBuilder()
        out.append("### ").append(field(input.appName)).append(" connection report\n\n")
        out.append("- App: ").append(field("${input.appName} ${input.versionName} (${input.applicationId})")).append('\n')
        out.append("- Device: ").append(field("${input.deviceModel}, Android ${input.androidVersion}")).append('\n')
        out.append("- Network: ").append(field(describeNetwork(input.network))).append('\n')
        if (session != null) {
            out.append("- Host: ").append(field(describeHost(session))).append('\n')
            out.append("- State: ").append(field(describeState(session, input.timeZone))).append('\n')
        }
        out.append("- Time: ").append(formatTime(input.nowMillis, input.timeZone)).append('\n')

        val failure = session?.failure ?: input.failure
        if (failure != null) {
            out.append("\n#### Failure\n")
            appendBlock(out, tailChars(clean(describeFailure(failure)), FAILURE_MAX_CHARS, keepEnd = false))
        }

        if (session != null) {
            val log = clean(session.connectionLog)
            out.append("\n#### Connection log\n")
            if (log.isBlank()) {
                out.append("(nothing printed)\n")
            } else {
                appendBlock(out, tail(log, CONNECTION_LOG_MAX_LINES, CONNECTION_LOG_MAX_CHARS))
            }

            val transcript = clean(session.transcript)
            if (transcript.isNotBlank()) {
                val lines = transcript.lines()
                val kept = tail(transcript, TRANSCRIPT_MAX_LINES, TRANSCRIPT_MAX_CHARS)
                val keptLines = kept.lines().size
                out.append("\n#### Session transcript (last ").append(keptLines)
                    .append(" of ").append(lines.size).append(" lines)\n")
                appendBlock(out, kept)
            }
        }

        // Every section above is bounded; this only guards future additions.
        return if (out.length > MAX_REPORT_CHARS) out.substring(0, MAX_REPORT_CHARS) else out.toString()
    }

    /**
     * Scrub secrets out of free text: PEM/OpenSSH/PuTTY private key blocks
     * (and orphaned base64 key bodies whose header scrolled away),
     * `password: value` style pairs, and each of [secrets] verbatim.
     */
    fun redact(text: String, secrets: Collection<String> = emptyList()): String {
        if (text.isEmpty()) return text
        var result = PEM_PRIVATE_KEY.replace(text, REDACTED_KEY)
        result = PUTTY_PRIVATE_KEY.replace(result, REDACTED_KEY)
        result = KEY_BODY_RUN.replace(result) { REDACTED_KEY + "\n" }
        result = SECRET_ASSIGNMENT.replace(result) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
        for (secret in secrets.filter { it.length >= MIN_SECRET_LENGTH }.sortedByDescending { it.length }) {
            result = result.replace(secret, REDACTED)
        }
        return result
    }

    private fun describeNetwork(network: NetworkSummary): String = buildString {
        append(network.type)
        if (network.vpnActive) {
            append("; VPN active")
            if (network.tailscaleLikely) append(" (Tailscale likely)")
            network.vpnDetail?.let { append(": ").append(it) }
        } else {
            append("; no VPN")
        }
    }

    private fun describeHost(session: SessionSummary): String = buildString {
        append(session.nickname).append(" (").append(session.protocol).append(") ")
        if (session.username.isNotEmpty()) append(session.username).append('@')
        append(session.hostname).append(':').append(session.port)
        if (isTailscaleTarget(session.hostname)) append(" [Tailscale address]")
    }

    private fun describeState(session: SessionSummary, timeZone: TimeZone): String = buildString {
        append(session.state)
        session.disconnectReason?.let { append(", reason ").append(it) }
        session.disconnectedAtMillis?.let { append(", at ").append(formatTime(it, timeZone)) }
    }

    /** Exception class, message and a short stack excerpt for it and its causes. */
    fun describeFailure(failure: Throwable): String = buildString {
        var t: Throwable? = failure
        var depth = 0
        val seen = mutableSetOf<Throwable>()
        while (t != null && depth < CAUSE_DEPTH && seen.add(t)) {
            if (depth > 0) append("Caused by: ")
            append(t.javaClass.name)
            t.message?.let { append(": ").append(it) }
            append('\n')
            val frames = t.stackTrace
            frames.take(STACK_FRAMES).forEach { append("    at ").append(it).append('\n') }
            if (frames.size > STACK_FRAMES) append("    ... ").append(frames.size - STACK_FRAMES).append(" more\n")
            t = t.cause
            depth++
        }
    }.trimEnd()

    /** A hostname inside 100.64.0.0/10 or under ts.net: reached through Tailscale. */
    fun isTailscaleTarget(hostname: String): Boolean = hostname.endsWith(".ts.net", ignoreCase = true) || isCgnat(hostname)

    /** True for an IPv4 literal in 100.64.0.0/10, the range Tailscale assigns. */
    fun isCgnat(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }

    private fun formatTime(millis: Long, timeZone: TimeZone): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz (Z)", Locale.US)
        format.timeZone = timeZone
        return "${format.format(Date(millis))} ${timeZone.id}"
    }

    /** The last [maxLines] lines of [text], then cut to its last [maxChars]. */
    private fun tail(text: String, maxLines: Int, maxChars: Int): String {
        val lines = text.trimEnd().lines()
        val kept = lines.takeLast(maxLines).joinToString("\n")
        return tailChars(kept, maxChars, keepEnd = true)
    }

    private fun tailChars(text: String, maxChars: Int, keepEnd: Boolean): String = when {
        text.length <= maxChars -> text
        keepEnd -> text.substring(text.length - maxChars).substringAfter('\n')
        else -> text.substring(0, maxChars)
    }

    /** A fenced block whose fence is longer than any backtick run inside it. */
    private fun appendBlock(out: StringBuilder, body: String) {
        val longestRun = BACKTICK_RUN.findAll(body).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        out.append(fence).append("text\n").append(body.trimEnd()).append('\n').append(fence).append('\n')
    }

    private val BACKTICK_RUN = Regex("`+")

    // BEGIN ... PRIVATE KEY through its END line, or to the end when cut off.
    private val PEM_PRIVATE_KEY = Regex(
        "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|\\z)",
    )
    private val PUTTY_PRIVATE_KEY = Regex(
        "PuTTY-User-Key-File-\\d+:[\\s\\S]*?(?:Private-MAC: *\\S+|\\z)",
    )

    // Three or more lines of nothing but base64, the body of a key whose
    // BEGIN line already scrolled out of the transcript.
    private val KEY_BODY_RUN = Regex("(?m)(?:^[A-Za-z0-9+/=]{40,}\\r?$\\n?){3,}")

    // "password: value", "DB_PASSWORD=value", "passphrase: \"a b c\"" on one line.
    private val SECRET_ASSIGNMENT = Regex(
        "(?i)(?<![a-z])(pass(?:word|phrase|wd)?|secret|token|api[_-]?key)([ \\t]*[=:][ \\t]*)" +
            "(?:\"[^\"\\n]*\"|'[^'\\n]*'|[^\\s'\"]+)",
    )
}
