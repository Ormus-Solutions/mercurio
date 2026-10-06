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

package solutions.ormus.logos.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reading Ormus's own commands: the parser against what the fetch printed on
 * Sun (a few real files, frontmatter names and descriptions only), and the
 * fetch script itself against a throwaway home folder.
 */
class OwnCommandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val parsed = OwnCommands.parse(commandsFixture("commands/own-commands-sun.txt"))
    private fun find(agent: AgentKind, name: String) = parsed.single { it.agent == agent && it.command.name == name }.command

    @Test
    fun sunFixture_everyEntryLandsWithItsAgentAndSource() {
        assertEquals(
            listOf(
                Triple(AgentKind.CLAUDE, SlashSource.COMMAND, "00-gnosis"),
                Triple(AgentKind.CLAUDE, SlashSource.COMMAND, "learn"),
                Triple(AgentKind.CLAUDE, SlashSource.COMMAND, "mplan"),
                Triple(AgentKind.CLAUDE, SlashSource.COMMAND, "dev:commit-smart"),
                Triple(AgentKind.CLAUDE, SlashSource.SKILL, "brain"),
                Triple(AgentKind.CLAUDE, SlashSource.SKILL, "claude-md-overhaul"),
                Triple(AgentKind.CLAUDE, SlashSource.SKILL, "codeql"),
                Triple(AgentKind.GROK, SlashSource.SKILL, "brain"),
                Triple(AgentKind.GROK, SlashSource.SKILL, "user:calcinate"),
                Triple(AgentKind.CLAUDE, SlashSource.PROJECT, "speckit.plan"),
            ),
            parsed.map { Triple(it.agent, it.command.source, it.command.name) },
        )
    }

    @Test
    fun commandWithoutFrontmatter_takesItsFirstLine() {
        assertEquals("Gnosis - Knowledge Of God", find(AgentKind.CLAUDE, "00-gnosis").description)
    }

    @Test
    fun quotedDescription_losesItsQuotes() {
        assertEquals("Trigger a roguelike learning encounter and send via WhatsApp", find(AgentKind.CLAUDE, "learn").description)
    }

    @Test
    fun argumentHint_isKept() {
        assertEquals("[feature-or-task-description]", find(AgentKind.CLAUDE, "mplan").argumentHint)
        assertTrue(find(AgentKind.CLAUDE, "mplan").description.startsWith("The standard opening move for any non-trivial task"))
    }

    @Test
    fun subfolderCommand_isNamespaced() {
        assertEquals("Create a well-crafted git commit with AI-generated message", find(AgentKind.CLAUDE, "dev:commit-smart").description)
    }

    @Test
    fun blockScalars_joinIntoOneLine() {
        assertTrue(find(AgentKind.CLAUDE, "claude-md-overhaul").description.startsWith("Audit and improve a Claude Code memory layer end-to-end. Measures CLAUDE.md and MEMORY.md against"))
        assertTrue(find(AgentKind.CLAUDE, "codeql").description.startsWith("Scans a codebase for security vulnerabilities using CodeQL's interprocedural data flow and taint tracking"))
        parsed.forEach { assertFalse(it.command.description, it.command.description.contains('\n')) }
    }

    @Test
    fun grokSkillWithATakenName_usesTheNameGrokGivesIt() {
        assertEquals("user:calcinate", find(AgentKind.GROK, "user:calcinate").name)
        // The alias only renames Grok's skill; Claude's of the same name stays bare.
        assertEquals("brain", find(AgentKind.GROK, "brain").name)
    }

    @Test
    fun descriptionsAreCappedAndCutCharactersDropped() {
        val long = "x".repeat(500)
        val parsed = OwnCommands.parse("@@ claude-command a.md\ndescription: $long\n@@ claude-command b.md\ndescription: café�\n")
        assertEquals(300, parsed[0].command.description.length)
        assertEquals("café", parsed[1].command.description)
    }

    @Test
    fun unknownKindsAndNamesWithSpaces_areSkipped() {
        val parsed = OwnCommands.parse("@@ other-kind x.md\ndescription: y\n@@ claude-command has space.md\ndescription: z\n")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun frontmatter_shapes() {
        val fields = Frontmatter.parse(
            listOf(
                "name: 'it''s'",
                "description: >-",
                "  first line",
                "  second line",
                "argument-hint: [a] [b]",
                "plain: one",
                "  continued",
                "cut: \"never closed",
            ),
        )
        assertEquals("it's", fields["name"])
        assertEquals("first line second line", fields["description"])
        assertEquals("[a] [b]", fields["argument-hint"])
        assertEquals("one continued", fields["plain"])
        assertEquals("never closed", fields["cut"])
    }

    @Test
    fun fetchCommand_quotesTheProjectFolderAndRefusesOddOnes() {
        assertTrue(OwnCommands.fetchCommand("/home/alice/it's here").endsWith(" sh '/home/alice/it'\\''s here'"))
        assertTrue(OwnCommands.fetchCommand("relative/path").endsWith(" sh ''"))
        assertTrue(OwnCommands.fetchCommand("/a\nb").endsWith(" sh ''"))
        assertTrue(OwnCommands.fetchCommand(null).endsWith(" sh ''"))
    }

    @Test
    fun fetchScript_printsFrontmatterOnly_fromEverySource() {
        assumeTrue(File("/bin/sh").canExecute())
        val home = tmp.newFolder("home")
        fun write(path: String, text: String) = File(home, path).apply { parentFile!!.mkdirs() }.writeText(text)
        write(".claude/commands/plan.md", "---\ndescription: Plan it\nargument-hint: [task]\nallowed-tools: Read\n---\nSECRET BODY\n")
        write(".claude/commands/dev/test.md", "# Test things\n\nSECRET BODY\n")
        write(".claude/skills/brain/SKILL.md", "---\nname: brain\ndescription: |\n  Query the\n  vault\n---\nSECRET BODY\n")
        write(".grok/skills/scout/SKILL.md", "---\r\nname: scout\r\ndescription: Look around\r\n---\r\nSECRET BODY\r\n")
        write("work/app/.claude/commands/ship.md", "---\ndescription: Ship the app\n---\nSECRET BODY\n")

        val process = ProcessBuilder("sh", "-c", OwnCommands.fetchCommand(File(home, "work/app").path))
            .apply {
                environment()["HOME"] = home.path
                environment()["PATH"] = "/usr/bin:/bin"
            }
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.readBytes().decodeToString()

        assertEquals(0, process.exitValue())
        assertFalse(output, output.contains("SECRET BODY"))
        assertFalse(output, output.contains("allowed-tools"))
        val own = OwnCommands.parse(output).map { "${it.agent} ${it.command.source} /${it.command.name} [${it.command.argumentHint}] ${it.command.description}" }
        assertEquals(
            listOf(
                "CLAUDE COMMAND /plan [[task]] Plan it",
                "CLAUDE COMMAND /dev:test [null] Test things",
                "CLAUDE SKILL /brain [null] Query the vault",
                "GROK SKILL /scout [null] Look around",
                "CLAUDE PROJECT /ship [null] Ship the app",
            ),
            own,
        )
    }
}
