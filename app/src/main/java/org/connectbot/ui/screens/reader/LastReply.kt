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
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.parseHerdrSnapshot
import timber.log.Timber
import java.io.IOException

/**
 * The agent's last reply, for the tray's copy key. For a Claude Code pane it is read
 * from Claude Code's own transcript on the host (the last text block since the last user
 * message: the answer, not the narration before it), since a full-screen agent's history
 * is not in the terminal. Otherwise it is
 * the last reply block on screen. Blocking: call it off the main thread.
 */
object LastReply {
    // Claude Code session ids are UUIDs; anything else never reaches a shell.
    private val SESSION_ID = Regex("[A-Za-z0-9-]{8,64}")
    private val RULE = Regex("^[─━-]{10,}\\s*$")

    // Read only the end of a long transcript; a reply is never this far back.
    private const val TAIL_BYTES = 8 * 1024 * 1024

    /** The reply text, or null when there is none to copy. */
    fun read(bridge: TerminalBridge): String? {
        val ssh = bridge.transport as? SSH
        if (ssh != null && bridge.runsHerdr) {
            try {
                claudeReply(ssh)?.let { return it }
            } catch (e: IOException) {
                Timber.w(e, "Claude Code transcript read failed, using the screen")
            } catch (e: IllegalStateException) {
                Timber.w(e, "Claude Code transcript read failed, using the screen")
            }
        }
        return fromScreen(RecentOutput.read(bridge).text)
    }

    private fun claudeReply(ssh: SSH): String? {
        val snapshot = parseHerdrSnapshot(ssh.exec(Herd.snapshotCommand)) ?: return null
        val pane = snapshot.panes.firstOrNull { it.paneId == snapshot.focusedPaneId } ?: return null
        val session = pane.agentSession ?: return null
        if (session.agent != "claude" || session.kind != "id") return null
        val command = claudeTranscriptCommand(session.value ?: return null) ?: return null
        return ssh.exec(command).trim().ifEmpty { null }
    }

    /**
     * The shell command that prints the last reply of Claude Code session [sessionId]:
     * the last assistant text block after the last user message in its transcript
     * (`~/.claude/projects/<project>/<sessionId>.jsonl`). Null for a malformed id.
     */
    fun claudeTranscriptCommand(sessionId: String): String? {
        if (!SESSION_ID.matches(sessionId)) return null
        return """
            |python3 - '$sessionId' <<'MERCURIO_REPLY'
            |import glob, json, os, sys
            |files = glob.glob(os.path.expanduser('~/.claude/projects/*/' + sys.argv[1] + '.jsonl'))
            |if not files:
            |    sys.exit(3)
            |with open(files[0], 'rb') as f:
            |    f.seek(0, 2)
            |    size = f.tell()
            |    f.seek(max(0, size - $TAIL_BYTES))
            |    lines = f.read().decode('utf-8', 'replace').splitlines()
            |if size > $TAIL_BYTES:
            |    lines = lines[1:]
            |reply = ''
            |for line in lines:
            |    try:
            |        entry = json.loads(line)
            |    except ValueError:
            |        continue
            |    content = (entry.get('message') or {}).get('content')
            |    if entry.get('type') == 'user':
            |        if isinstance(content, str) or any(isinstance(c, dict) and c.get('type') == 'text' for c in content or []):
            |            reply = ''
            |    elif entry.get('type') == 'assistant' and isinstance(content, list):
            |        texts = [c.get('text', '') for c in content if isinstance(c, dict) and c.get('type') == 'text' and c.get('text', '').strip()]
            |        if texts:
            |            reply = texts[-1]
            |print(reply.strip())
            |MERCURIO_REPLY
        """.trimMargin()
    }

    /**
     * The last reply block on a screen: the lines above the input box (a rule line) back
     * to the last line opening a reply (`⏺`), without that marker or the reply indent.
     * Status lines (`✻ ...`) and blanks at the ends are dropped.
     */
    fun fromScreen(screen: String): String? {
        val lines = screen.lines()
        val boxTop = lines.indexOfLast { RULE.matches(it) }.let { last ->
            // The box is two rules around the prompt; its top rule is the earlier one.
            val before = lines.subList(0, maxOf(last, 0)).indexOfLast { RULE.matches(it) }
            if (before >= 0 && last - before <= 3) before else last
        }
        val body = if (boxTop > 0) lines.subList(0, boxTop) else lines
        val start = body.indexOfLast { it.startsWith("⏺") }
        val block = (if (start >= 0) body.subList(start, body.size) else body.takeLast(SCREEN_FALLBACK_LINES))
            .filterNot { it.startsWith("✻") }
            .map { it.removePrefix("⏺ ").removePrefix("  ") }
        val text = block.joinToString("\n").trim()
        return text.ifEmpty { null }
    }

    private const val SCREEN_FALLBACK_LINES = 40
}
