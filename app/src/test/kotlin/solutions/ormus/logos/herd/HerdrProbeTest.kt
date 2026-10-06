/*
 * Copied from Termish, https://github.com/farlume/termish (commit ee9e460),
 * composeApp/src/commonTest/kotlin/dev/termish/herdr/HerdrProbeTest.kt.
 * Mercurio changes: comments translated.
 *
 * MIT License
 *
 * Copyright (c) 2026 Termish Project Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package solutions.ormus.logos.herd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HerdrProbe: a $HOME-prefixed candidate that hits must come back as an absolute path.
 *
 * Root cause (field bug 1): the probe runs through the remote shell, which
 * expands $HOME, so the literal candidate hits; but downstream nothing expands
 * it (single quotes, a direct execvp), and a literal `$HOME/...` fails with
 * `execvp: $HOME/...: No such file or directory`.
 *
 * Root cause (field bug 2): the probe uses `--version`, not `api snapshot`;
 * snapshot needs the daemon running (measured on Linux: server_not_running ->
 * exit 1 + error JSON), so a freshly installed host would read as "not installed".
 */
class HerdrProbeTest {
    private val versionOutput = "herdr 0.8.0"

    @Test
    fun snapPackagedHerdrIsSkipped() {
        // Ubuntu snap build (PATH hits /snap/bin/herdr): sandboxed, skip it and
        // keep probing for the official script install in $HOME/.local/bin
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                when (cmd) {
                    "command -v herdr" -> "/snap/bin/herdr"
                    "\$HOME/.local/bin/herdr --version" -> versionOutput
                    "echo \$HOME" -> "/home/user"
                    else -> null
                }
            }!!
        assertEquals("/home/user/.local/bin/herdr", result.bin)
        assertEquals(
            listOf("command -v herdr", "\$HOME/.local/bin/herdr --version", "echo \$HOME"),
            commands,
        )
    }

    @Test
    fun homeCandidateResolvedToAbsolutePath() {
        // exec PATH lacks ~/.local/bin (bare herdr fails) -> the $HOME candidate hits -> resolved to an absolute path
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                when (cmd) {
                    "command -v herdr" -> null
                    "herdr --version" -> null
                    "\$HOME/.local/bin/herdr --version" -> versionOutput
                    "echo \$HOME" -> "/root"
                    else -> null
                }
            }!!
        assertEquals("/root/.local/bin/herdr", result.bin)
        // Resolution happens after the hit with one extra echo (candidate order unchanged); the PATH
        // candidate first goes through command -v for the snap check (null = no PATH hit or not snap)
        assertEquals(
            listOf(
                "command -v herdr",
                "herdr --version",
                "\$HOME/.local/bin/herdr --version",
                "echo \$HOME",
            ),
            commands,
        )
    }

    @Test
    fun homeCandidateResolvedAfterBareCandidateMissesOtherAbsolutes() {
        // Full candidate order: herdr -> $HOME -> /usr/local/bin (all miss) -> /opt/homebrew hits;
        // an absolute candidate needs no resolution (no echo)
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                if (cmd == "/opt/homebrew/bin/herdr --version") versionOutput else null
            }!!
        assertEquals("/opt/homebrew/bin/herdr", result.bin)
        // one extra command -v before the PATH candidate (snap check)
        assertEquals(
            listOf("command -v herdr") + HerdrApi.BIN_CANDIDATES.map { "$it --version" },
            commands,
        )
    }

    @Test
    fun bareCandidateHitNeedsNoResolution() {
        // The usual case, PATH works: returned as is, no extra probing
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                when (cmd) {
                    "command -v herdr" -> "/usr/local/bin/herdr"
                    "herdr --version" -> versionOutput
                    else -> null
                }
            }!!
        assertEquals("herdr", result.bin)
        assertEquals(listOf("command -v herdr", "herdr --version"), commands)
    }

    @Test
    fun daemonNotRunningIsStillInstalled() {
        // Regression (measured on Linux): api snapshot returns error JSON + exit 1 with no daemon.
        // The probe uses --version: "installed but not started" must hit, never read as not installed
        val snapshotServerError = """{"id":"x","error":{"code":"server_not_running","message":"no herdr server is running"}}"""
        val result =
            HerdrProbe.probe { cmd ->
                when (cmd) {
                    "command -v herdr" -> "/usr/local/bin/herdr"
                    "herdr --version" -> versionOutput
                    else -> snapshotServerError
                }
            }!!
        assertEquals("herdr", result.bin)
    }

    @Test
    fun homeEchoFailureFallsBackToRawCandidate() {
        // echo $HOME fails (odd environment): fall back to the raw candidate, best effort
        val result =
            HerdrProbe.probe { cmd ->
                when (cmd) {
                    "\$HOME/.local/bin/herdr --version" -> versionOutput
                    else -> null
                }
            }!!
        assertEquals("\$HOME/.local/bin/herdr", result.bin)
    }

    @Test
    fun allCandidatesFailReturnsNull() {
        assertNull(HerdrProbe.probe { null })
    }

    @Test
    fun versionOutputValidation() {
        assertTrue(HerdrProbe.isVersionOutput("herdr 0.8.0"))
        assertTrue(HerdrProbe.isVersionOutput("\nherdr 0.8.0\n"))
        assertTrue(HerdrProbe.isVersionOutput("herdr 1.2.3-beta+abc"))
        // a same-named binary or usage output is not a hit
        assertFalse(HerdrProbe.isVersionOutput("usage: herdr [options]"))
        assertFalse(HerdrProbe.isVersionOutput("herdr"))
        assertFalse(HerdrProbe.isVersionOutput(""))
        // snapshot error JSON is not version output
        assertFalse(HerdrProbe.isVersionOutput("""{"id":"x","error":{"code":"server_not_running"}}"""))
    }
}
