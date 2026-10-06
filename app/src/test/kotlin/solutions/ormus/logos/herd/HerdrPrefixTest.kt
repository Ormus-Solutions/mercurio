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

package solutions.ormus.logos.herd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading Herdr's prefix from a host's config.toml, and the bytes a terminal sends for it. */
class HerdrPrefixTest {
    private val nul = 0.toChar().toString()
    private val ctrlB = 2.toChar().toString()
    private val esc = 27.toChar().toString()

    // The head of Sun's real ~/.config/herdr/config.toml (herdr 0.9.0).
    private val sunConfig = """
        # tmux session -> herdr workspace, tmux window -> herdr tab, tmux pane -> herdr pane

        [theme]
        name = "terminal"

        [theme.custom]
        panel_bg = "black"

        [terminal]
        new_cwd = "follow"

        [keys]
        prefix = "ctrl+space"

        # Config and help
        reload_config = "prefix+q"
        help = "prefix+?"
        detach = "prefix+d"
        split_horizontal = ["prefix+h", "alt+enter"]
    """.trimIndent()

    @Test
    fun sunsConfig_isCtrlSpace_sentAsNul() {
        val prefix = HerdrPrefix.fromConfig(sunConfig)
        assertEquals(HerdrPrefix("ctrl+space", nul), prefix)
        assertNull(prefix.problem)
    }

    @Test
    fun noFile_orNoPrefix_isHerdrsDefaultCtrlB_withNoProblem() {
        // The read command prints nothing when the file is missing.
        assertEquals(HerdrPrefix.DEFAULT, HerdrPrefix.fromRead(CommandOutput("", "", 0)))
        assertEquals(HerdrPrefix("ctrl+b", ctrlB), HerdrPrefix.DEFAULT)
        assertEquals(HerdrPrefix.DEFAULT, HerdrPrefix.fromConfig("[keys]\nhelp = \"prefix+?\"\n"))
    }

    @Test
    fun aCommentedOutPrefix_isNotSet() {
        // Herdr's own default config ships the line commented out.
        val toml = "[keys]\n# Prefix key to enter prefix mode (default: \"ctrl+b\")\n# prefix = \"ctrl+a\"\n"
        assertEquals(HerdrPrefix.DEFAULT, HerdrPrefix.fromConfig(toml))
    }

    @Test
    fun aPrefixKeyInAnotherTable_isNotTheHerdrPrefix() {
        val toml = """
            [theme]
            prefix = "ctrl+x"

            [keys.extra]
            prefix = "ctrl+y"

            [[agents]]
            prefix = "ctrl+z"

            [keys]
            help = "prefix+?"
        """.trimIndent()
        assertEquals(HerdrPrefix.DEFAULT, HerdrPrefix.fromConfig(toml))
    }

    @Test
    fun keysTable_afterOtherTables_withATrailingComment_andQuotes() {
        assertEquals("ctrl+a", HerdrPrefix.prefixSpec("[ui]\nprefix = 'x'\n[keys] # bindings\nprefix = 'ctrl+a' # like screen\n"))
        assertEquals("f12", HerdrPrefix.prefixSpec("[\"keys\"]\n\"prefix\" = \"f12\"\n"))
        // A dotted key at the top level is the same setting.
        assertEquals("ctrl+a", HerdrPrefix.prefixSpec("keys.prefix = \"ctrl+a\"\n[theme]\n"))
        // A # inside the string is the key, not a comment.
        assertEquals("#", HerdrPrefix.prefixSpec("[keys]\nprefix = \"#\"\n"))
    }

    @Test
    fun anArray_takesItsFirstString_evenOverSeveralLines() {
        assertEquals("ctrl+a", HerdrPrefix.prefixSpec("[keys]\nprefix = [\"ctrl+a\", \"f12\"]\n"))
        assertEquals("f12", HerdrPrefix.prefixSpec("[keys]\nprefix = [\n  \"f12\",\n  \"ctrl+a\",\n]\n"))
    }

    @Test
    fun keyBytes_mapsHerdrsSpellingsToWhatATerminalSends() {
        val cases = mapOf(
            "ctrl+b" to ctrlB,
            "ctrl+a" to 1.toChar().toString(),
            "Ctrl+B" to ctrlB,
            "ctrl+space" to nul,
            "ctrl+@" to nul,
            "ctrl+[" to esc,
            "ctrl+]" to 29.toChar().toString(),
            "esc" to esc,
            "escape" to esc,
            "f1" to "${esc}OP",
            "f4" to "${esc}OS",
            "f5" to "$esc[15~",
            "f12" to "$esc[24~",
            "alt+b" to esc + "b",
            "alt+ctrl+b" to esc + ctrlB,
            "-" to "-",
            "minus" to "-",
            "backtick" to "`",
            "+" to "+",
            "ctrl++" to null,
            "a" to "a",
            "shift+a" to "A",
        )
        for ((spec, bytes) in cases) assertEquals(spec, bytes, HerdrPrefix.keyBytes(spec))
    }

    @Test
    fun keyBytes_aKeyNoTerminalCanSend_isNull() {
        listOf("cmd+b", "super+b", "hyper+b", "ctrl+shift+b", "ctrl+1", "shift+f1", "alt+f1", "f13", "", "ctrl+").forEach {
            assertNull(it, HerdrPrefix.keyBytes(it))
        }
    }

    @Test
    fun anUnsendablePrefix_fallsBackToCtrlB_andSaysWhy() {
        val prefix = HerdrPrefix.fromConfig("[keys]\nprefix = \"cmd+b\"\n")
        assertEquals(ctrlB, prefix.bytes)
        assertEquals("ctrl+b", prefix.spec)
        val problem = checkNotNull(prefix.problem)
        assertTrue(problem, problem.contains("prefix = \"cmd+b\""))
    }

    @Test
    fun aFailedRead_fallsBackToCtrlB_andSaysWhy() {
        val failed = HerdrPrefix.fromRead(CommandOutput("", "cat: config.toml: Permission denied\n", 1))
        assertEquals(ctrlB, failed.bytes)
        assertTrue(failed.problem!!, failed.problem!!.contains("exited 1: cat: config.toml: Permission denied"))
        assertNotNull(HerdrPrefix.fromRead(null).problem)
    }

    @Test
    fun readCommand_readsHerdrsConfigPathThroughALoginShell() {
        val command = HerdrPrefix.readCommand
        assertTrue(command, command.startsWith("bash -lc '"))
        assertTrue(command, command.contains("\${HERDR_CONFIG_PATH:-\${XDG_CONFIG_HOME:-\$HOME/.config}/herdr/config.toml}"))
    }

    @Test
    fun session_readsThePrefixOnce_andSendsItAheadOfClientKeys() = runBlocking {
        val keys = mutableListOf<String>()
        val session = HerdSession(
            hostName = "Sun",
            runner = { command -> if (command == HerdrPrefix.readCommand) CommandOutput(sunConfig, "", 0) else null },
            sendKeys = { keys += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        // Before the read: Herdr's default.
        session.armPrefix()
        session.cancelPrefix()
        assertEquals(listOf(ctrlB, esc), keys)
        keys.clear()

        session.readKeys()

        assertEquals(HerdrPrefix("ctrl+space", nul), session.keys.value.prefix)
        session.perform(HerdrAction.GOTO)
        session.armPrefix()
        assertEquals("b", session.keystroke(nul + "b"))
        assertEquals(listOf(nul + "g", nul), keys)
    }

    @Test
    fun session_aRunnerThatThrows_keepsCtrlB_withAProblem() = runBlocking {
        val session = HerdSession(
            hostName = "lab",
            runner = { error("channel closed") },
            sendKeys = {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        session.readKeys()
        assertEquals(ctrlB, session.keys.value.prefix.bytes)
        assertNotNull(session.keys.value.prefix.problem)
        // Not a Herdr failure: the offline card stays about Herdr itself.
        assertNull(session.failure.value)
    }
}
