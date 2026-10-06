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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.util.TimeZone

class DiagnosticsReportTest {

    private val panama = TimeZone.getTimeZone("America/Panama")

    // 2026-10-04 15:15:03 UTC = 10:15:03 in Panama (UTC-5, no DST).
    private val now = 1_791_126_903_000L

    private fun input(
        session: SessionSummary? = session(),
        secrets: Collection<String> = emptyList(),
        failure: Throwable? = null,
    ) = DiagnosticsInput(
        appName = "Mercurio",
        versionName = "1.4.0-oss",
        applicationId = "solutions.ormus.logos.debug",
        deviceModel = "Google Pixel 8",
        androidVersion = "15 (API 35)",
        network = NetworkSummary(
            type = "Wi-Fi",
            vpnActive = true,
            tailscaleLikely = true,
            vpnDetail = "tun0 100.100.2.3",
        ),
        session = session,
        nowMillis = now,
        timeZone = panama,
        failure = failure,
        secrets = secrets,
    )

    private fun session(
        transcript: String = "",
        connectionLog: String = "Connecting to sun.example:22 via ssh\nTrying password authentication",
        failure: Throwable? = null,
    ) = SessionSummary(
        nickname = "Sun",
        protocol = "ssh",
        username = "alice",
        hostname = "100.100.0.10",
        port = 22,
        state = "disconnected",
        disconnectReason = "IO_ERROR",
        disconnectedAtMillis = now - 60_000,
        failure = failure,
        connectionLog = connectionLog,
        transcript = transcript,
    )

    @Test
    fun reportCarriesEveryField() {
        val failure = IOException("Connection failed", ConnectException("failed to connect to /100.100.0.10 (port 22)"))
        val report = DiagnosticsReport.build(
            input(session(transcript = "Welcome to Sun\nalice@sun:~$ claude", failure = failure)),
        )

        listOf(
            "Mercurio 1.4.0-oss (solutions.ormus.logos.debug)",
            "Google Pixel 8, Android 15 (API 35)",
            "Wi-Fi; VPN active (Tailscale likely): tun0 100.100.2.3",
            "Sun (ssh) alice@100.100.0.10:22 [Tailscale address]",
            "disconnected, reason IO_ERROR, at 2026-10-04 10:14:03",
            "2026-10-04 10:15:03",
            "-0500",
            "America/Panama",
            "java.io.IOException: Connection failed",
            "Caused by: java.net.ConnectException: failed to connect to /100.100.0.10 (port 22)",
            "    at org.connectbot.service.DiagnosticsReportTest",
            "#### Connection log",
            "Connecting to sun.example:22 via ssh",
            "Trying password authentication",
            "#### Session transcript (last 2 of 2 lines)",
            "alice@sun:~$ claude",
        ).forEach { assertTrue("missing: $it\n$report", report.contains(it)) }
    }

    @Test
    fun sessionThatNeverOpenedHasNoTranscriptSection() {
        val report = DiagnosticsReport.build(input(session(transcript = "")))
        assertTrue(report.contains("#### Connection log"))
        assertFalse(report.contains("Session transcript"))
    }

    @Test
    fun failureWithoutSessionIsReported() {
        val report = DiagnosticsReport.build(input(session = null, failure = IllegalStateException("no transport")))
        assertTrue(report.contains("java.lang.IllegalStateException: no transport"))
        assertFalse(report.contains("- Host:"))
    }

    @Test
    fun secretsNeverAppear() {
        val savedPassword = "hunter2-Saved!"
        val pem = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACDZ1q1dJsmtQ7vZ8q3h3JbC1u9oZK3q8z0q9l2d3lQ7QAAAJgIbXz0CG18
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
        val rsa = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAsecretbody\n-----END RSA PRIVATE KEY-----"
        val putty = "PuTTY-User-Key-File-3: ssh-ed25519\nPrivate-Lines: 1\nAAAAIPuttySecretBody\nPrivate-MAC: abcdef0123"
        val transcript = listOf(
            "alice@sun:~$ cat ~/.ssh/id_ed25519",
            pem,
            "alice@sun:~$ cat old.pem",
            rsa,
            putty,
            "alice@sun:~$ echo $savedPassword",
            savedPassword,
            "export DB_PASSWORD=sup3rs3cret",
            "passphrase: \"correct horse battery\"",
            "api_key=sk-live-123456",
        ).joinToString("\n")
        val failure = IOException("auth with $savedPassword rejected")

        val report = DiagnosticsReport.build(
            input(session(transcript = transcript, failure = failure), secrets = listOf(savedPassword)),
        )

        listOf(
            savedPassword,
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW",
            "MIIEowIBAAKCAQEAsecretbody",
            "AAAAIPuttySecretBody",
            "BEGIN OPENSSH PRIVATE KEY",
            "BEGIN RSA PRIVATE KEY",
            "sup3rs3cret",
            "correct",
            "sk-live-123456",
        ).forEach { assertFalse("leaked: $it\n$report", report.contains(it)) }
        assertTrue(report.contains(DiagnosticsReport.REDACTED_KEY))
        assertTrue(report.contains("auth with ${DiagnosticsReport.REDACTED} rejected"))
        assertTrue(report.contains("DB_PASSWORD=${DiagnosticsReport.REDACTED}"))
        // Ordinary output around the secrets survives.
        assertTrue(report.contains("alice@sun:~$ cat ~/.ssh/id_ed25519"))
    }

    @Test
    fun keyBodyWhoseHeaderScrolledAwayIsRedacted() {
        val body = List(5) { "AAAAB3NzaC1yc2EAAAADAQABAAABAQC7b8Xb1Q2w3e4r5t6y7u8i9o0p1a2s3d4f5g6h$it" }
        val transcript = (body + "-----END RSA PRIVATE KEY-----" + "alice@sun:~$").joinToString("\n")
        val report = DiagnosticsReport.build(input(session(transcript = transcript)))
        body.forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains(DiagnosticsReport.REDACTED_KEY))
    }

    @Test
    fun reportIsBoundedAndKeepsTheNewestOutput() {
        val longLine = "x".repeat(200)
        val transcript = (1..5000).joinToString("\n") { "line $it $longLine" }
        val log = (1..1000).joinToString("\n") { "log $it" }
        val deep = (1..40).fold(IOException("root") as Throwable) { cause, i -> IOException("wrap $i", cause) }

        val report = DiagnosticsReport.build(input(session(transcript = transcript, connectionLog = log, failure = deep)))

        assertTrue("report too long: ${report.length}", report.length <= DiagnosticsReport.MAX_REPORT_CHARS)
        assertTrue(report.contains("line 5000 "))
        assertFalse(report.contains("line 1 "))
        assertTrue(report.contains("log 1000"))
        assertFalse(report.contains("log 1\n"))
        val header = Regex("Session transcript \\(last (\\d+) of 5000 lines\\)").find(report)
        val kept = header!!.groupValues[1].toInt()
        assertTrue(kept in 1..DiagnosticsReport.TRANSCRIPT_MAX_LINES)
    }

    @Test
    fun codeFencesInsideOutputDoNotBreakTheBlock() {
        val report = DiagnosticsReport.build(input(session(transcript = "```kotlin\nval x = 1\n```")))
        assertTrue(report.contains("````text\n```kotlin"))
        assertTrue(report.contains("```\n````\n"))
    }

    @Test
    fun recognisesTailscaleAddresses() {
        assertTrue(DiagnosticsReport.isTailscaleTarget("100.64.0.1"))
        assertTrue(DiagnosticsReport.isTailscaleTarget("100.127.255.254"))
        assertTrue(DiagnosticsReport.isTailscaleTarget("sun.tail1234.ts.net"))
        assertFalse(DiagnosticsReport.isTailscaleTarget("100.128.0.1"))
        assertFalse(DiagnosticsReport.isTailscaleTarget("192.0.2.180"))
        assertFalse(DiagnosticsReport.isTailscaleTarget("example.com"))
    }
}
