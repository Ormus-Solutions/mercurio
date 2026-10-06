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
import solutions.ormus.logos.commands.commandsFixture

/** Herdr's client keys on a host: each action's binding from its config.toml, or Herdr's default. */
class HerdrKeysTest {
    private val nul = 0.toChar().toString()
    private val ctrlB = 2.toChar().toString()
    private val esc = 27.toChar().toString()

    /** Each key's binding and bytes, as one readable table. */
    private fun table(keys: HerdrKeys): Map<HerdrKey, Pair<String?, String?>> = keys.bindings.mapValues { (_, b) -> b.spec to b.bytes }

    @Test
    fun sunsRealConfig_resolvesEveryClientKey() {
        // Sun's ~/.config/herdr/config.toml, copied verbatim (herdr 0.9.0).
        val keys = HerdrKeys.fromConfig(commandsFixture("herdr/config-sun.toml"))

        assertEquals(HerdrPrefix("ctrl+space", nul), keys.prefix)
        assertEquals(
            mapOf(
                HerdrKey.TOGGLE_SIDEBAR to ("prefix+b" to nul + "b"),
                HerdrKey.COPY_MODE to ("prefix+[" to nul + "["),
                HerdrKey.GOTO to ("prefix+g" to nul + "g"),
                HerdrKey.OPEN_NOTIFICATION to ("prefix+o" to nul + "o"),
                HerdrKey.DETACH to ("prefix+d" to nul + "d"),
                HerdrKey.RELOAD_CONFIG to ("prefix+q" to nul + "q"),
                HerdrKey.HELP to ("prefix+?" to nul + "?"),
                HerdrKey.WORKSPACE_PICKER to ("prefix+w" to nul + "w"),
                // previous_tab = ["prefix+p", "alt+left"]: the prefix binding.
                HerdrKey.PREVIOUS_TAB to ("prefix+p" to nul + "p"),
                HerdrKey.NEXT_TAB to ("prefix+n" to nul + "n"),
            ),
            table(keys),
        )
        assertTrue(keys.bindings.values.all { it.problem == null })
    }

    @Test
    fun anEmptyConfig_isHerdrsDefaults() {
        val keys = HerdrKeys.fromConfig("")

        assertEquals(HerdrKeys.DEFAULT, keys)
        // No file: the read command prints nothing.
        assertEquals(HerdrKeys.DEFAULT, HerdrKeys.fromRead(CommandOutput("", "", 0)))
        assertEquals(HerdrPrefix.DEFAULT, keys.prefix)
        assertEquals(
            mapOf(
                HerdrKey.TOGGLE_SIDEBAR to ("prefix+b" to ctrlB + "b"),
                HerdrKey.COPY_MODE to ("prefix+[" to ctrlB + "["),
                HerdrKey.GOTO to ("prefix+g" to ctrlB + "g"),
                HerdrKey.OPEN_NOTIFICATION to ("prefix+o" to ctrlB + "o"),
                // Stock Herdr detaches on q and reloads on shift+r, not Sun's d and q.
                HerdrKey.DETACH to ("prefix+q" to ctrlB + "q"),
                HerdrKey.RELOAD_CONFIG to ("prefix+shift+r" to ctrlB + "R"),
                HerdrKey.HELP to ("prefix+?" to ctrlB + "?"),
                HerdrKey.WORKSPACE_PICKER to ("prefix+w" to ctrlB + "w"),
                HerdrKey.PREVIOUS_TAB to ("prefix+p" to ctrlB + "p"),
                HerdrKey.NEXT_TAB to ("prefix+n" to ctrlB + "n"),
            ),
            table(keys),
        )
    }

    @Test
    fun anArray_usesItsFirstPrefixBinding_evenOverSeveralLines() {
        val keys = HerdrKeys.fromConfig(
            """
            [keys]
            detach = ["alt+q", "prefix+x"]
            goto = [
              "prefix+cmd+g", # no terminal sends cmd
              "prefix+G",
            ]
            help = "prefix+h"
            """.trimIndent(),
        )
        assertEquals(HerdrBinding("prefix+x", ctrlB + "x"), keys.bindings[HerdrKey.DETACH])
        assertEquals(HerdrBinding("prefix+G", ctrlB + "G"), keys.bindings[HerdrKey.GOTO])
        // The line after the array is read as its own key.
        assertEquals(HerdrBinding("prefix+h", ctrlB + "h"), keys.bindings[HerdrKey.HELP])
    }

    @Test
    fun onlyDirectChords_areSentAsTheChordItself() {
        val keys = HerdrKeys.fromConfig("[keys]\ndetach = \"alt+q\"\nhelp = [\"cmd+h\", \"ctrl+alt+h\"]\n")
        assertEquals(HerdrBinding("alt+q", esc + "q"), keys.bindings[HerdrKey.DETACH])
        assertEquals(HerdrBinding("ctrl+alt+h", esc + 8.toChar()), keys.bindings[HerdrKey.HELP])
        // No prefix goes ahead of a direct chord.
        assertEquals(HerdrInvocation.Keys(esc + "q"), HerdrCommands().build(HerdrAction.DETACH, null, keys = keys))
    }

    @Test
    fun anUnsendableBinding_turnsTheKeyOff_andSaysWhy() {
        val keys = HerdrKeys.fromConfig(
            """
            [keys]
            detach = "prefix+cmd+d"
            reload_config = ["super+r", "alt+left"]
            goto = ""
            help = 42
            """.trimIndent(),
        )
        for (key in listOf(HerdrKey.DETACH, HerdrKey.RELOAD_CONFIG, HerdrKey.GOTO, HerdrKey.HELP)) {
            assertNull(key.name, keys.bytes(key))
            assertNotNull(key.name, keys.problem(key))
        }
        assertTrue(keys.problem(HerdrKey.DETACH)!!, keys.problem(HerdrKey.DETACH)!!.contains("detach to \"prefix+cmd+d\""))
        assertTrue(keys.problem(HerdrKey.RELOAD_CONFIG)!!, keys.problem(HerdrKey.RELOAD_CONFIG)!!.contains("\"super+r\", \"alt+left\""))
        assertEquals("Herdr's config leaves goto unbound.", keys.problem(HerdrKey.GOTO))
        assertTrue(keys.problem(HerdrKey.HELP)!!, keys.problem(HerdrKey.HELP)!!.contains("\"42\""))
        // The rest keep Herdr's defaults; an off key builds nothing to send.
        assertEquals(ctrlB + "b", keys.bytes(HerdrKey.TOGGLE_SIDEBAR))
        assertNull(HerdrCommands().build(HerdrAction.DETACH, null, keys = keys))
    }

    @Test
    fun bindingsOutsideTheKeysTable_areNotClientKeys() {
        val keys = HerdrKeys.fromConfig(
            """
            [ui]
            detach = "prefix+u"

            [keys.indexed]
            goto = "prefix+i"

            [[keys.command]]
            help = "prefix+c"
            """.trimIndent(),
        )
        assertEquals(HerdrKeys.DEFAULT, keys)
    }

    @Test
    fun aFailedRead_isHerdrsDefaults_withThePrefixsProblem() {
        val keys = HerdrKeys.fromRead(CommandOutput("[keys]\ndetach = \"prefix+d\"\n", "cat: Permission denied", 1))
        assertEquals(HerdrKeys.DEFAULT.bindings, keys.bindings)
        assertNotNull(keys.prefix.problem)
        assertEquals(HerdrKeys.DEFAULT.bindings, HerdrKeys.fromRead(null).bindings)
    }

    @Test
    fun session_aDirectChordWhilePrefixed_leavesPrefixModeFirst() = runBlocking {
        val sent = mutableListOf<String>()
        val session = HerdSession(
            hostName = "stock",
            runner = { command -> if (command == HerdrPrefix.readCommand) CommandOutput("[keys]\ndetach = \"alt+q\"\n", "", 0) else null },
            sendKeys = { sent += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        session.readKeys()

        session.armPrefix()
        session.perform(HerdrAction.DETACH)
        session.perform(HerdrAction.RELOAD_CONFIG)

        // Prefix, Esc to leave prefix mode, the chord; then reload's default, prefix and R.
        assertEquals(listOf(ctrlB, esc, esc + "q", ctrlB + "R"), sent)
    }
}
