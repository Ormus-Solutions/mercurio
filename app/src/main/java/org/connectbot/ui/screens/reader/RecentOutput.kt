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

import org.connectbot.service.TerminalBridge
import org.connectbot.transport.SSH
import org.connectbot.util.TerminalText
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.HerdFocus
import solutions.ormus.logos.herd.focus
import solutions.ormus.logos.herd.parseHerdrSnapshot
import timber.log.Timber
import java.io.IOException

/**
 * A session's recent output as plain text. In a Herdr session the terminal only holds the
 * visible screen, so the focused pane's history is read from Herdr over an SSH exec
 * channel; otherwise, or if that fails, it is the session's own rolling transcript.
 * Blocking: call it off the main thread.
 */
object RecentOutput {
    /** The text, the Herdr pane it came from (null for the transcript), and why Herdr failed. */
    data class Result(
        val text: String,
        val herdr: HerdFocus? = null,
        val herdrError: String? = null,
    )

    fun read(bridge: TerminalBridge): Result {
        val ssh = bridge.transport as? SSH
        var herdrError: String? = null
        if (ssh != null && bridge.runsHerdr) {
            try {
                return readHerdr(ssh)
            } catch (e: IOException) {
                herdrError = e.message
            } catch (e: IllegalStateException) {
                herdrError = e.message
            } catch (e: IllegalArgumentException) {
                herdrError = e.message
            }
            Timber.w("Herdr read failed, showing transcript: %s", herdrError)
        }
        return Result(text = bridge.transcriptText(), herdrError = herdrError)
    }

    private fun readHerdr(ssh: SSH): Result {
        val focus = parseHerdrSnapshot(ssh.exec(Herd.snapshotCommand))?.focus()
            ?: throw IOException("no focused pane in the Herdr snapshot")
        val text = TerminalText.toPlainText(ssh.exec(Herd.readCommand(focus.paneId))).trimEnd()
        return Result(text = text, herdr = focus)
    }
}
