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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.service.DiagnosticsInput
import org.connectbot.service.DiagnosticsReport
import org.connectbot.service.NetworkSummary
import org.connectbot.service.SessionSummary
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UsageUploaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_791_115_200_000L
    private val logged = mutableListOf<String>()
    private lateinit var phoneDir: File
    private lateinit var store: UsageStore
    private lateinit var inbox: InboxStore
    private lateinit var sun: FakeSun

    @Before
    fun setUp() {
        phoneDir = File(temp.root, "phone/usage")
        store = UsageStore(phoneDir)
        inbox = InboxStore(phoneDir)
        sun = FakeSun(temp.newFolder("sun-home"))
    }

    private fun uploader(s: UsageStore = store, i: InboxStore = inbox) = UsageUploader(s, i, { logged += it }, { now })

    private fun upload(s: UsageStore = store, i: InboxStore = inbox) = uploader(s, i).upload(sun, FakeSun.HOST_KEY)

    private fun eventsOnSun() = sun.lines("${store.deviceId()}.jsonl").map { JSONObject(it) }

    private fun wishesOnSun() = sun.lines("${store.deviceId()}-wishes.jsonl").map { JSONObject(it) }

    private fun log(vararg actions: String) = actions.forEach { store.append(now, it, "console", true) }

    @Test
    fun uploadIsIdempotent() {
        log(UsageActions.KEY_UP, UsageActions.KEY_UP, UsageActions.KEY_ESC)

        assertThat(upload()).isEqualTo(UploadResult.Uploaded(3, 0, 0, 0))
        assertThat(upload()).isEqualTo(UploadResult.Uploaded(0, 0, 0, 0))
        log(UsageActions.SEND)
        assertThat(upload()).isEqualTo(UploadResult.Uploaded(1, 0, 0, 0))

        val seqs = eventsOnSun().map { it.getLong("seq") }
        assertThat(seqs).containsExactly(1L, 2L, 3L, 4L)
        assertThat(sun.file("${store.deviceId()}.cursor").readText().trim()).isEqualTo("4")
        assertThat(store.uploadedThrough()).isEqualTo(4)
    }

    @Test
    fun lostReplyNeverSendsTwice() {
        log(UsageActions.KEY_UP, UsageActions.KEY_DOWN)
        sun.failCommitOnce = true

        assertThat(upload()).isInstanceOf(UploadResult.Failed::class.java)
        // Sun wrote the batch, the phone never heard back, so it marked nothing.
        assertThat(store.uploadedThrough()).isEqualTo(0)
        assertThat(upload()).isEqualTo(UploadResult.Uploaded(0, 0, 0, 0))

        assertThat(eventsOnSun().map { it.getLong("seq") }).containsExactly(1L, 2L)
        assertThat(store.uploadedThrough()).isEqualTo(2)
    }

    @Test
    fun freshPhoneStoreReadsSunCursorAfterRestart() {
        log(UsageActions.KEY_UP)
        upload()
        // Restart: new store instances on the same directory.
        val again = UsageStore(phoneDir)
        again.append(now, UsageActions.KEY_TAB)
        assertThat(upload(again, InboxStore(phoneDir))).isEqualTo(UploadResult.Uploaded(1, 0, 0, 0))
        assertThat(eventsOnSun().map { it.getString("action") }).containsExactly(UsageActions.KEY_UP, UsageActions.KEY_TAB)
    }

    @Test
    fun hostKeyMismatch_refusesKeepsQueueAndLogs() {
        log(UsageActions.KEY_UP)
        inbox.add(InboxKind.WISH, "fewer buttons", now, "console", true)
        sun.fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        assertThat(upload()).isEqualTo(UploadResult.HostKeyRefused)
        assertThat(sun.dir.exists()).isFalse()
        assertThat(sun.uploads).isEmpty()
        assertThat(logged).containsExactly(UsageActions.UPLOAD_REFUSED_HOSTKEY)
        assertThat(store.pending(0).events).hasSize(1)
        assertThat(inbox.hasPending()).isTrue()

        sun.fingerprint = null
        assertThat(upload()).isEqualTo(UploadResult.HostKeyRefused)

        // The pinned key, written with base64 padding, matches.
        sun.fingerprint = FakeSun.HOST_KEY + "="
        assertThat(upload()).isEqualTo(UploadResult.Uploaded(1, 0, 1, 0))
    }

    @Test
    fun sunDirectoryIs700AndFiles600_enforcedEveryUpload() {
        log(UsageActions.KEY_UP)
        inbox.add(InboxKind.WISH, "a wish", now, null, null)
        upload()

        assertThat(Files.getPosixFilePermissions(sun.dir.toPath())).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        // Someone loosens them; the next upload tightens them again.
        Files.setPosixFilePermissions(sun.dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        sun.dir.listFiles()!!.forEach { Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("rw-r--r--")) }
        File(sun.dir, "closed.json").writeText("{}")
        log(UsageActions.KEY_DOWN)
        upload()

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(sun.dir.toPath()))).isEqualTo("rwx------")
        val files = sun.dir.listFiles()!!.filter { it.isFile }
        assertThat(files.map { it.name }).contains("${store.deviceId()}.jsonl", "${store.deviceId()}.cursor", "closed.json")
        files.forEach {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(it.toPath()))).describedAs(it.name).isEqualTo("rw-------")
        }
        // Staging files never linger.
        assertThat(files.none { it.name.startsWith(".incoming") }).isTrue()
    }

    @Test
    fun rolledUpRowsUploadOnceAndCountOnce() {
        val old = now - 40L * 24 * 60 * 60 * 1000
        repeat(3) { store.append(old, UsageActions.KEY_UP, "console", true) }
        store.rollUp(now)
        assertThat(upload()).isEqualTo(UploadResult.Uploaded(0, 1, 0, 0))
        assertThat(upload()).isEqualTo(UploadResult.Uploaded(0, 0, 0, 0))
        val daily = eventsOnSun().single()
        assertThat(daily.getString("type")).isEqualTo("daily")
        assertThat(daily.getLong("count")).isEqualTo(3)
    }

    @Test
    fun wishList_editsAndDeletionsSync() {
        val keep = inbox.add(InboxKind.WISH, "fewer buttons", now, "console", true)!!
        val drop = inbox.add(InboxKind.WISH, "secret plan to drop", now + 1, "console", true)!!
        upload()
        assertThat(wishesOnSun().map { it.getString("id") }).containsExactlyInAnyOrder(keep.id, drop.id)

        inbox.edit(keep.id, "fewer buttons on the compose bar", now + 2)
        inbox.delete(drop.id, now + 3)
        assertThat(upload()).isEqualTo(UploadResult.Uploaded(0, 0, 1, 0))

        val onSun = wishesOnSun().single()
        assertThat(onSun.getString("id")).isEqualTo(keep.id)
        assertThat(onSun.getString("text")).isEqualTo("fewer buttons on the compose bar")
        assertThat(sun.allText()).doesNotContain("secret plan")
        assertThat(inbox.hasPending()).isFalse()
    }

    @Test
    fun offlineQueue_survivesRestartAndUploadsOnce() {
        // Saved while there is no connection at all.
        val wish = InboxStore(phoneDir).add(InboxKind.WISH, "scroll with the arrow keys", now, "console", true)!!
        val error = InboxStore(phoneDir).add(InboxKind.ERROR, "Mercurio connection report\nIO_ERROR", now + 1, "console", true)!!

        // App restarts: a fresh store reads the queue from disk.
        val restarted = InboxStore(phoneDir)
        assertThat(restarted.all().map { it.id }).containsExactly(error.id, wish.id)
        assertThat(restarted.pendingCount()).isEqualTo(2)

        assertThat(upload(UsageStore(phoneDir), restarted)).isEqualTo(UploadResult.Uploaded(0, 0, 2, 0))
        val wishUploads = sun.uploads.count { it.first.endsWith("-wishes.jsonl") }
        assertThat(upload(UsageStore(phoneDir), restarted)).isEqualTo(UploadResult.Uploaded(0, 0, 0, 0))
        assertThat(sun.uploads.count { it.first.endsWith("-wishes.jsonl") }).isEqualTo(wishUploads)
        assertThat(wishesOnSun().map { it.getString("type") }).containsExactlyInAnyOrder("wish", "error")
        assertThat(InboxStore(phoneDir).hasPending()).isFalse()
    }

    @Test
    fun closeOnSun_roundTripsToThePhone() {
        assumeTrue("python3 needed", runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        // inbox.py stamps closes with the real clock.
        now = System.currentTimeMillis()
        val wish = inbox.add(InboxKind.WISH, "scroll with the arrow keys", now, "console", true)!!
        val other = inbox.add(InboxKind.WISH, "bigger send button", now + 1, "console", true)!!
        upload()

        val out = inboxPy("close", wish.id.take(8), "done", "in", "PR", "#60")
        assertThat(out).contains("Closed")
        now += 60_000
        assertThat(upload()).isEqualTo(UploadResult.Uploaded(0, 0, 2, 1))

        val local = inbox.all().associateBy { it.id }
        assertThat(local.getValue(wish.id).done).isTrue()
        assertThat(local.getValue(wish.id).note).isEqualTo("done in PR #60")
        assertThat(local.getValue(other.id).done).isFalse()
        assertThat(wishesOnSun().single { it.getString("id") == wish.id }.getBoolean("done")).isTrue()
        val open = inboxPy("list")
        assertThat(open).contains("bigger send button").doesNotContain("scroll with the arrow keys")

        // Reopened on the phone after the close: it stays open.
        inbox.setDone(wish.id, false, now + 1)
        upload()
        assertThat(inbox.all().single { it.id == wish.id }.done).isFalse()
    }

    @Test
    fun errorEntriesCarryNoSecrets() {
        val savedPassword = "hunter2-Saved!"
        val transcript = listOf(
            "alice@sun:~$ cat old.pem",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAsecretbody\n-----END RSA PRIVATE KEY-----",
            "export DB_PASSWORD=sup3rs3cret",
            savedPassword,
        ).joinToString("\n")
        val report = DiagnosticsReport.build(
            DiagnosticsInput(
                appName = "Mercurio",
                versionName = "1.4.0-oss",
                applicationId = "solutions.ormus.logos.debug",
                deviceModel = "Google Pixel 8",
                androidVersion = "15 (API 35)",
                network = NetworkSummary(type = "Wi-Fi", vpnActive = true, tailscaleLikely = true, vpnDetail = "tun0"),
                session = SessionSummary(
                    nickname = "Sun",
                    protocol = "ssh",
                    username = "alice",
                    hostname = "100.100.1.1",
                    port = 22,
                    state = "disconnected",
                    disconnectReason = "AUTH_FAIL",
                    disconnectedAtMillis = now,
                    failure = java.io.IOException("auth with $savedPassword rejected"),
                    connectionLog = "Trying password authentication",
                    transcript = transcript,
                ),
                nowMillis = now,
                timeZone = TimeZone.getTimeZone("America/Panama"),
                failure = null,
                secrets = listOf(savedPassword),
            ),
        )
        inbox.add(InboxKind.ERROR, report, now, "console", false)
        upload()

        val stored = File(phoneDir, InboxStore.INBOX_FILE).readText() + sun.allText()
        assertThat(sun.allText()).contains("AUTH_FAIL")
        listOf(savedPassword, "MIIEowIBAAKCAQEAsecretbody", "BEGIN RSA PRIVATE KEY", "sup3rs3cret").forEach {
            assertThat(stored).describedAs("leaked $it").doesNotContain(it)
        }
    }

    private fun inboxPy(vararg args: String): String {
        val script = listOf(File("../scripts/inbox.py"), File("scripts/inbox.py")).first { it.exists() }
        val process = ProcessBuilder(listOf("python3", script.path, "--dir", sun.dir.path) + args)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS))
        check(process.exitValue() == 0) { out }
        return out
    }
}
