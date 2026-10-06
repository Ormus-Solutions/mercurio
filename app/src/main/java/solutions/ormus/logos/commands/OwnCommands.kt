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

import solutions.ormus.logos.herd.HerdrApi

/** One of Ormus's own commands or skills, and which agent reads it. */
data class OwnCommand(
    val agent: AgentKind,
    val command: SlashCommand,
)

/**
 * Ormus's own slash commands, read from the host over an SSH exec channel:
 * Claude Code commands (~/.claude/commands, one folder deep), Claude Code skills
 * (~/.claude/skills/NAME/SKILL.md), Grok skills (~/.grok/skills/NAME/SKILL.md),
 * and the project commands of a working directory (.claude/commands).
 *
 * The remote side prints only each file's name and the `name`, `description` and
 * `argument-hint` lines of its YAML frontmatter, never a body. A file without
 * frontmatter gives its first line instead (Claude Code shows that line as the
 * description too).
 */
object OwnCommands {
    /** Kinds the fetch prints, one per source folder. */
    const val CLAUDE_COMMAND = "claude-command"
    const val CLAUDE_SKILL = "claude-skill"
    const val GROK_SKILL = "grok-skill"
    const val PROJECT_COMMAND = "project-command"

    private const val ENTRY = "@@ "
    private const val TITLE = "@title "
    private const val ALIAS = "@alias "
    private const val MAX_DESCRIPTION = 300

    // POSIX sh and awk, so it runs from any login shell (bash, zsh, a Mac's sh).
    // '¤' stands for '$' to keep Kotlin string templates out of the shell code.
    // The script's $1 is the project directory, or empty. Grok's own install
    // comes first: on Sun an exec channel's PATH finds a broken mise shim first.
    private val SCRIPT = """
        proj=¤1
        fm() {
          awk 'NR == 1 { sub(/\r¤/, ""); if (¤0 == "---") { inside = 1; next } }
          { sub(/\r¤/, "") }
          inside && ¤0 == "---" { exit }
          inside && NR > 80 { exit }
          inside && /^[A-Za-z_-]+:/ { keep = (¤1 == "name:" || ¤1 == "description:" || ¤1 == "argument-hint:") }
          inside && keep { print substr(¤0, 1, 400); next }
          !inside && NF { sub(/^#+ */, ""); print "@title " substr(¤0, 1, 200); exit }' "¤1"
        }
        emit() { printf '@@ %s %s\n' "¤1" "¤2"; fm "¤3"; }
        cmds() { for f in "¤2"/*.md "¤2"/*/*.md; do [ -f "¤f" ] && emit "¤1" "¤{f#"¤2"/}" "¤f"; done; }
        skills() { for f in "¤2"/*/SKILL.md; do [ -f "¤f" ] && d=¤{f%/SKILL.md} && emit "¤1" "¤{d##*/}" "¤f"; done; }
        cmds $CLAUDE_COMMAND "¤HOME/.claude/commands"
        skills $CLAUDE_SKILL "¤HOME/.claude/skills"
        skills $GROK_SKILL "¤HOME/.grok/skills"
        if [ -n "¤proj" ] && [ -d "¤proj/.claude/commands" ] && [ "¤proj" != "¤HOME" ]; then
          cmds $PROJECT_COMMAND "¤proj/.claude/commands"
        fi
        g="¤HOME/.grok/bin/grok"
        [ -x "¤g" ] || g=¤(command -v grok 2>/dev/null)
        if [ -n "¤g" ] && [ -x "¤g" ]; then
          "¤g" inspect --json 2>/dev/null | awk -F'"' '/^      "name": / { n = ¤4 } /"invocableAs": "user:/ { print "@alias " n " " ¤4 }'
        fi
        exit 0
    """.trimIndent().replace('¤', '$')

    // A working directory goes to the remote shell as one quoted word; refuse
    // anything that is not a plain absolute path before it gets there.
    private val CWD = Regex("/[^\\u0000-\\u001f]*")

    /** The exec-channel command that prints every own command, with [cwd]'s project commands when given. */
    fun fetchCommand(cwd: String? = null): String {
        val project = cwd?.takeIf { CWD.matches(it) }.orEmpty()
        return "sh -c ${HerdrApi.shQuote(SCRIPT)} sh ${HerdrApi.shQuote(project)}"
    }

    /**
     * Parse what [fetchCommand] printed. Unknown kinds and nameless entries are
     * skipped. A Grok skill whose bare name a built-in or plugin already owns
     * takes the name Grok gives it (`user:calcinate`), from `grok inspect`.
     */
    fun parse(output: String): List<OwnCommand> {
        val result = ArrayList<OwnCommand>()
        val grokNames = HashMap<String, String>()
        var kind: String? = null
        var path = ""
        val lines = ArrayList<String>()

        fun flush() {
            entryOf(kind ?: return, path, lines)?.let(result::add)
            kind = null
        }

        for (raw in output.lineSequence()) {
            val line = raw.removeSuffix("\r")
            when {
                line.startsWith(ENTRY) -> {
                    flush()
                    val rest = line.removePrefix(ENTRY)
                    kind = rest.substringBefore(' ')
                    path = rest.substringAfter(' ', "")
                    lines.clear()
                }

                line.startsWith(ALIAS) -> {
                    flush()
                    val parts = line.removePrefix(ALIAS).split(' ')
                    if (parts.size == 2) grokNames[parts[0]] = parts[1]
                }

                kind != null -> lines += line
            }
        }
        flush()
        return result.map { own ->
            val name = grokNames[own.command.name]
            if (own.agent == AgentKind.GROK && name != null) own.copy(command = own.command.copy(name = name)) else own
        }
    }

    private fun entryOf(kind: String, path: String, lines: List<String>): OwnCommand? {
        val fields = Frontmatter.parse(lines.filterNot { it.startsWith(TITLE) })
        val title = lines.firstOrNull { it.startsWith(TITLE) }?.removePrefix(TITLE)?.trim()
        val description = clean(fields["description"] ?: title.orEmpty())
        val hint = fields["argument-hint"]?.let(::clean)?.ifEmpty { null }
        val (agent, source, name) = when (kind) {
            CLAUDE_COMMAND -> Triple(AgentKind.CLAUDE, SlashSource.COMMAND, commandName(path))
            PROJECT_COMMAND -> Triple(AgentKind.CLAUDE, SlashSource.PROJECT, commandName(path))
            CLAUDE_SKILL -> Triple(AgentKind.CLAUDE, SlashSource.SKILL, fields["name"]?.trim()?.ifEmpty { null } ?: path)
            GROK_SKILL -> Triple(AgentKind.GROK, SlashSource.SKILL, fields["name"]?.trim()?.ifEmpty { null } ?: path)
            else -> return null
        }
        if (name.isBlank() || name.any { it.isWhitespace() }) return null
        return OwnCommand(agent, SlashCommand(name, description, hint, source))
    }

    /** `dev/commit-smart.md` is `/dev:commit-smart`, the way Claude Code names commands in subfolders. */
    private fun commandName(path: String): String = path.removeSuffix(".md").replace('/', ':')

    // The remote awk cuts long lines by bytes, which can split a character; drop the
    // replacement characters that leaves.
    private fun clean(text: String): String = text.replace("\uFFFD", "").replace(Regex("\\s+"), " ").trim().take(MAX_DESCRIPTION)
}

/**
 * The few YAML frontmatter shapes skill and command files use: `key: value`,
 * quoted values, block scalars (`|`, `>`, `|-`, `>-`) and plain values that
 * continue on indented lines. Values come back with their lines joined by spaces.
 */
object Frontmatter {
    private val KEY = Regex("^([A-Za-z_][A-Za-z0-9_-]*):(.*)$")

    fun parse(lines: List<String>): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        var key: String? = null
        val value = StringBuilder()

        fun flush() {
            val k = key ?: return
            fields[k] = unquote(value.toString().trim())
        }

        for (line in lines) {
            val match = KEY.matchEntire(line)
            if (match != null) {
                flush()
                key = match.groupValues[1]
                value.clear()
                val first = match.groupValues[2].trim()
                if (!isBlockIndicator(first)) value.append(first)
            } else if (key != null && (line.isBlank() || line.first().isWhitespace())) {
                if (value.isNotEmpty() && line.isNotBlank()) value.append(' ')
                value.append(line.trim())
            }
        }
        flush()
        return fields
    }

    private fun isBlockIndicator(value: String): Boolean = value.isNotEmpty() && value[0] in "|>" && value.drop(1).all { it in "+-0123456789" }

    private fun unquote(value: String): String = when {
        value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> value.substring(1, value.length - 1).replace("\\\"", "\"")

        value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.length - 1).replace("''", "'")

        // A value cut short by the fetch keeps its opening quote only.
        value.startsWith('"') || value.startsWith('\'') -> value.substring(1)

        else -> value
    }
}
