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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class UsageStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000

    // 2026-10-04T12:00:00Z
    private val now = 1_791_115_200_000L

    private fun store(dir: File = File(temp.root, "usage")) = UsageStore(dir, TimeZone.getTimeZone("UTC"))

    @Test
    fun eventsSurviveRestartAndKeepCountingSequence() {
        val dir = File(temp.root, "usage")
        store(dir).apply {
            append(now, UsageActions.KEY_UP, "console", true)
            append(now + 1, UsageActions.KEY_ESC, "console", false)
        }
        val reopened = store(dir)
        assertThat(reopened.snapshot().events.map { it.action }).containsExactly(UsageActions.KEY_UP, UsageActions.KEY_ESC)
        assertThat(reopened.append(now + 2, UsageActions.SEND).seq).isEqualTo(3)
        assertThat(reopened.deviceId()).matches(UsageStore.DEVICE_ID.pattern)
        assertThat(store(dir).deviceId()).isEqualTo(reopened.deviceId())
    }

    @Test
    fun rollUp_foldsOldEventsIntoDailyCountsPerBucket() {
        val s = store()
        val old = now - 40 * day
        repeat(3) { s.append(old + it, UsageActions.KEY_UP, "console", true) }
        s.append(old + 10, UsageActions.KEY_UP, "console", false)
        s.append(old + day, UsageActions.KEY_UP, "console", true)
        s.append(old + 20, UsageActions.SESSION_END, "console", true, durationMs = 60_000)
        s.append(old + 21, UsageActions.SESSION_END, "console", true, durationMs = 30_000)
        s.append(now, UsageActions.KEY_DOWN, "console", true)

        s.rollUp(now)

        val snap = s.snapshot()
        assertThat(snap.events.map { it.action }).containsExactly(UsageActions.KEY_DOWN)
        val rows = snap.dailies.associateBy { Triple(it.day, it.action, it.herdr) }
        assertThat(rows[Triple("2026-08-25", UsageActions.KEY_UP, true)]!!.count).isEqualTo(3)
        assertThat(rows[Triple("2026-08-25", UsageActions.KEY_UP, false)]!!.count).isEqualTo(1)
        assertThat(rows[Triple("2026-08-26", UsageActions.KEY_UP, true)]!!.count).isEqualTo(1)
        val sessions = rows[Triple("2026-08-25", UsageActions.SESSION_END, true)]!!
        assertThat(sessions.count).isEqualTo(2)
        assertThat(sessions.durationMs).isEqualTo(90_000)
        // Total preserved: 8 events in, 7 rolled + 1 raw out.
        assertThat(snap.dailies.sumOf { it.count } + snap.events.size).isEqualTo(8)
    }

    @Test
    fun rollUp_capsRawEventsAndKeepsTheNewest() {
        val s = store()
        repeat(30) { s.append(now + it, UsageActions.KEY_TAB, "console", null) }
        s.rollUp(now + 100, maxRaw = 10)
        val snap = s.snapshot()
        assertThat(snap.events).hasSize(10)
        assertThat(snap.events.first().seq).isEqualTo(21)
        assertThat(snap.dailies.single().count).isEqualTo(20)
    }

    @Test
    fun rollUp_separatesUploadedFromPendingAndPersists() {
        val dir = File(temp.root, "usage")
        val s = store(dir)
        val old = now - 40 * day
        repeat(4) { s.append(old + it, UsageActions.KEY_UP, "console", true) }
        s.markUploaded(cursor = 2, dailyIds = emptySet(), at = now)
        s.rollUp(now)

        val rows = store(dir).snapshot().dailies
        assertThat(rows.single { it.uploaded }.count).isEqualTo(2)
        assertThat(rows.single { !it.uploaded }.count).isEqualTo(2)
        // Only the rows Sun lacks are pending.
        assertThat(store(dir).pending(serverCursor = 2).dailies.map { it.count }).containsExactly(2L)
    }

    @Test
    fun clear_dropsEventsButKeepsDeviceAndSequence() {
        val s = store()
        s.append(now, UsageActions.KEY_UP)
        val device = s.deviceId()
        s.clear()
        assertThat(s.snapshot().events).isEmpty()
        assertThat(s.deviceId()).isEqualTo(device)
        assertThat(s.append(now, UsageActions.KEY_UP).seq).isEqualTo(2)
    }

    @Test
    fun storeFilesAreOwnerOnly() {
        val dir = File(temp.root, "usage")
        val s = store(dir)
        s.append(now, UsageActions.KEY_UP)
        s.rollUp(now + 40 * day)
        InboxStore(dir).add(InboxKind.WISH, "less clutter", now, "console", true)

        val owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        assertThat(owner).containsAll(Files.getPosixFilePermissions(dir.toPath()))
        val files = dir.listFiles()!!.filter { it.isFile }
        assertThat(files.map { it.name }).contains(UsageStore.EVENTS_FILE, UsageStore.STATE_FILE, InboxStore.INBOX_FILE)
        files.forEach { file ->
            assertThat(Files.getPosixFilePermissions(file.toPath()))
                .describedAs(file.name)
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
    }
}
