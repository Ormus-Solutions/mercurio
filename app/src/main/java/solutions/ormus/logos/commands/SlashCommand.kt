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

/** Where a slash command comes from. */
enum class SlashSource {
    /** Ships with the agent (see [SlashCatalogs]). */
    BUILT_IN,

    /** A custom command file, such as ~/.claude/commands/name.md. */
    COMMAND,

    /** A skill folder, such as ~/.claude/skills/name/SKILL.md or ~/.grok/skills/name/SKILL.md. */
    SKILL,

    /** A command of the project in the session's working directory (.claude/commands). */
    PROJECT,
}

/**
 * One slash command of an agent.
 *
 * @param name the command without its leading slash ("compact", "dev:commit-smart")
 * @param argumentHint what the command takes after its name, as the agent shows it
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val argumentHint: String? = null,
    val source: SlashSource = SlashSource.BUILT_IN,
) {
    /** What the agent reads: the name with its slash. */
    val text: String get() = "/$name"

    /** One of Ormus's own commands or skills rather than a built-in. */
    val isOwn: Boolean get() = source != SlashSource.BUILT_IN

    /**
     * Whether a tap sends the command at once ("/name" and Enter). Built-ins that
     * take nothing do; a command with an argument hint, and every custom command,
     * goes to the dictation field instead so the rest can be dictated.
     */
    val sendsAtOnce: Boolean get() = !isOwn && argumentHint.isNullOrBlank()
}
