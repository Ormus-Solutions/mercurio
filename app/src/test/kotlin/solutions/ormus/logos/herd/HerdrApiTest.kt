/*
 * Copied from Termish, https://github.com/farlume/termish (commit ee9e460),
 * composeApp/src/commonTest/kotlin/dev/termish/herdr/HerdrApiTest.kt.
 * Mercurio changes: cases for Mercurio's parseCommandError and pane read;
 * comments translated.
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

import org.connectbot.transport.CommandOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HerdrApi command building and error parsing.
 *
 * Error shapes measured on herdr 0.8.0: a failure exits non-zero (exit 1) with
 * the error JSON on stderr (such as `herdr agent prompt wW:pxxx hi` -> exit 1 +
 * `{"error":{"code":"agent_not_found",...},"id":"cli:agent:prompt"}`).
 * Reading only stdout would mistake failure for success, so
 * [HerdrApi.parseCommandError] must look at stderr + exitCode.
 */
class HerdrApiTest {
    // ---- parseCommandError golden cases (real CLI output samples) ----

    @Test
    fun successOutputIsNull() {
        val out =
            CommandOutput(
                stdout = """{"id":"cli:agent:prompt","result":{"agent":{"agent":"pi","agent_status":"done","pane_id":"wW:p2"}}}""",
                stderr = "",
                exitCode = 0,
            )
        assertNull(HerdrApi.parseCommandError(out))
    }

    @Test
    fun agentNotFoundErrorOnStderr() {
        // Measured: herdr agent prompt on a bad pane -> exit 1 + error JSON on stderr
        val out =
            CommandOutput(
                stdout = "",
                stderr = """{"error":{"code":"agent_not_found","message":"agent target wW:pxxx not found"},"id":"cli:agent:prompt"}""",
                exitCode = 1,
            )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("agent_not_found", err?.code)
        assertEquals("agent target wW:pxxx not found", err?.message)
    }

    @Test
    fun paneNotFoundErrorOnStderr() {
        // Measured: herdr pane read on a bad pane -> exit 1 + error JSON on stderr
        val out =
            CommandOutput(
                stdout = "",
                stderr = """{"error":{"code":"pane_not_found","message":"pane wW:pxxx not found"},"id":"cli:pane:read"}""",
                exitCode = 1,
            )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("pane_not_found", err?.code)
        assertEquals("pane wW:pxxx not found", err?.message)
    }

    @Test
    fun errorJsonOnStdoutIsAlsoRecognized() {
        // Compatibility: error JSON on stdout is recognized too (some CLI versions / commands)
        val out =
            CommandOutput(
                stdout = """{"error":{"code":"not_found","message":"pane not found"},"id":"req_1"}""",
                stderr = "",
                exitCode = 1,
            )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("not_found", err?.code)
    }

    @Test
    fun nonZeroExitWithoutJsonYieldsExitCodeError() {
        // herdr missing / command not found: stderr is the shell's command not found, no JSON
        val out = CommandOutput(stdout = "", stderr = "herdr: command not found", exitCode = 127)
        val err = HerdrApi.parseCommandError(out)
        assertEquals("exit_127", err?.code)
        // Mercurio: the first stderr line is kept so the phone can say what went wrong.
        assertEquals("herdr: command not found", err?.message)
    }

    @Test
    fun idFirstErrorJsonIsRecognized() {
        // Mercurio: measured on herdr 0.9.0, `herdr api snapshot` with no server prints the id first.
        val out =
            CommandOutput(
                stdout = """{"id":"cli:api:snapshot","error":{"code":"server_not_running","message":"no herdr server is running"}}""",
                stderr = "",
                exitCode = 1,
            )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("server_not_running", err?.code)
        assertEquals("no herdr server is running", err?.message)
    }

    @Test
    fun textThatOnlyMentionsErrorIsNotAnError() {
        // Mercurio: `agent explain` prints prose; a word like "error" in it is not a failure.
        val out = CommandOutput(stdout = "detector: no \"error\" pattern matched", stderr = "", exitCode = 0)
        assertNull(HerdrApi.parseCommandError(out))
    }

    @Test
    fun emptyOutputZeroExitIsSuccess() {
        assertNull(HerdrApi.parseCommandError(CommandOutput("", "", 0)))
    }

    @Test
    fun nullOutputIsFailureNotSuccess() {
        // Connection failure / timeout (execDetailed never ran): never treat it as "submitted"
        val err = HerdrApi.parseCommandError(null)
        assertEquals("command_failed", err?.code)
    }

    // ---- command building ----

    @Test
    fun promptCommandQuotesText() {
        assertEquals("herdr agent prompt wW:p2 'hello world'", HerdrApi.agentPromptCmd("wW:p2", "hello world"))
    }

    @Test
    fun promptCommandEscapesSingleQuote() {
        // POSIX single-quote escaping: ' -> '\'', prevents text injection (exec runs through /bin/sh)
        assertEquals(
            "herdr agent prompt wW:p2 'it'\\''s ok'",
            HerdrApi.agentPromptCmd("wW:p2", "it's ok"),
        )
    }

    @Test
    fun shQuoteRoundTrip() {
        val samples = listOf("", "plain", "with space", "it's", "a'b'c", "中文", "a\nb")
        for (s in samples) {
            assertEquals(s, shUnquote(HerdrApi.shQuote(s)), "round-trip failed for: $s")
        }
    }

    @Test
    fun paneReadCommandShape() {
        // Mercurio: the reader joins soft wraps, asks for 1000 lines, and quotes the pane id.
        assertEquals(
            "herdr pane read 'w1:p1' --source recent-unwrapped --lines 1000",
            HerdrApi.paneReadCmd("w1:p1"),
        )
        assertEquals(
            "herdr pane read 'w1:p1' --source recent-unwrapped --lines 30",
            HerdrApi.paneReadCmd("w1:p1", 30),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun paneReadRefusesShellMetacharacters() {
        HerdrApi.paneReadCmd("w1:p1; rm -rf ~")
    }

    /** Minimal sh single-quote undo (tests only; no nesting): proves the escaping loses nothing. */
    private fun shUnquote(q: String): String {
        require(q.startsWith("'") && q.endsWith("'"))
        val inner = q.removeSurrounding("'")
        return inner.replace("'\\''", "'")
    }
}
