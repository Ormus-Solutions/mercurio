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

package org.connectbot.ui.screens.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The agent's last reply: from Claude Code's transcript, else from the screen. */
class LastReplyTest {

    @get:Rule
    val temp = TemporaryFolder()

    // A Claude Code screen, shaped like a real one on Sun (made-up words).
    private val screen = """
        |⏺ Earlier reply that should not be copied.
        |
        |❯ ship the reader fix
        |
        |⏺ Shipped the reader fix.
        |  - PR #12 merged.
        |  - The APK is on the server.
        |
        |✻ Cooked for 2m 10s · done 9:41 PM
        |
        |───────────────────────────────────────────
        |❯
        |───────────────────────────────────────────
        |  Opus 5.5  ~/projects/app  ⎇ main
    """.trimMargin()

    @Test
    fun fromScreen_takesTheLastReplyBlock_withoutMarkersOrStatus() {
        assertEquals("Shipped the reader fix.\n- PR #12 merged.\n- The APK is on the server.", LastReply.fromScreen(screen))
    }

    @Test
    fun fromScreen_withoutAReplyMarker_keepsTheLinesAboveTheBox() {
        assertEquals("plain output\nmore output", LastReply.fromScreen("plain output\nmore output\n────────────────\n$ \n────────────────"))
        assertNull(LastReply.fromScreen("   \n"))
    }

    @Test
    fun claudeTranscriptCommand_refusesAnythingButAnId() {
        assertNull(LastReply.claudeTranscriptCommand("x'; rm -rf ~; '"))
        assertNull(LastReply.claudeTranscriptCommand("short"))
    }

    @Test
    fun claudeTranscriptCommand_printsTheLastAnswer_notTheNarrationOrAnOlderReply() {
        assumeTrue("python3 on the build machine", File("/usr/bin/python3").exists())
        val id = "11111111-2222-3333-4444-555555555555"
        val project = File(temp.root, ".claude/projects/-home-me").apply { mkdirs() }
        File(project, "$id.jsonl").writeText(
            listOf(
                """{"type":"user","message":{"content":"first question"}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"old answer"}]}}""",
                """{"type":"user","message":{"content":"second question"}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"Looking."},{"type":"tool_use","name":"Bash"}]}}""",
                // A tool result is a user entry too, but not a user message: it must not reset the reply.
                """{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"Done: all green."}]}}""",
            ).joinToString("\n"),
        )
        val command = checkNotNull(LastReply.claudeTranscriptCommand(id))
        val process = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = temp.root.path }.start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()

        assertEquals("Done: all green.", out.trim())
    }
}
