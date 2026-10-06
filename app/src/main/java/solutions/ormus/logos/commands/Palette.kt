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

import org.connectbot.data.TailscaleDevice
import solutions.ormus.logos.herd.HerdrAction
import kotlin.math.ln
import kotlin.math.min

/** The kinds of things the command palette finds, in the order it lists them. */
enum class PaletteGroup { KEYS, AGENT, HERDR, SESSIONS, HOSTS, MACHINES, SCREENS, SETTINGS }

/** A console place or tool the palette can open or run. */
enum class PaletteScreen {
    HERD,
    HERDR_SHEET,
    SLASH_MENU,
    READER,
    SESSIONS,
    MACHINES,
    WISH,
    COPY_DETAILS,
    SEND_ERROR,
    TEXT_INPUT,
    PASTE,
    URL_SCAN,
    RESIZE,
    DISCONNECT,
    RECONNECT,
    PORT_FORWARDS,
    FULLSCREEN,
    TITLE_BAR,
}

/** What a palette result does when tapped. */
sealed interface PaletteAction {
    /** Write a compose bar key's bytes to the PTY. */
    data class Key(val sequence: String) : PaletteAction

    /** Run a Herdr command sheet action. */
    data class Herdr(val action: HerdrAction) : PaletteAction

    /** Send or insert one of the current agent's slash commands. */
    data class Slash(val command: SlashCommand) : PaletteAction

    /** Open a saved host's console (connecting when needed). */
    data class ConnectHost(val hostId: Long) : PaletteAction

    /** Show an open session's console. */
    data class SwitchSession(val hostId: Long) : PaletteAction

    /** Connect to a tailnet machine, the way the Machines list does. */
    data class ConnectMachine(val device: TailscaleDevice) : PaletteAction

    data class Open(val screen: PaletteScreen) : PaletteAction

    /** Leave the console for another app screen (a navigation route). */
    data class Navigate(val route: String) : PaletteAction
}

/**
 * One palette result. [key] is a stable id for the recent list; [usageId] is the
 * usage-log id whose count says how often the control behind it is used.
 */
data class PaletteEntry(
    val key: String,
    val title: String,
    val group: PaletteGroup,
    val action: PaletteAction,
    val subtitle: String? = null,
    val usageId: String? = null,
    /** Extra words the search matches, such as "escape" for Esc. */
    val keywords: String = "",
)

/**
 * Fuzzy search and ranking for the command palette.
 *
 * A match lands in a tier by how it matched the title: exact, prefix, word
 * prefix, substring, then letters in order (fuzzy); a match on keywords or the
 * subtitle ranks below any title match. Inside a tier, recently run results
 * come first, then the most used (usage-log counts), so the boost never lifts a
 * result over a better match. With no query, recent and most used lead.
 */
object PaletteSearch {
    private const val EXACT = 1000
    private const val PREFIX = 800
    private const val WORD_PREFIX = 600
    private const val SUBSTRING = 450
    private const val FUZZY = 300
    private const val ALL_WORDS = 280
    private const val KEYWORD_PREFIX = 250
    private const val KEYWORD = 150
    private const val MAX_GAP_PENALTY = 90

    private const val RECENT_TOP = 60
    private const val RECENT_STEP = 3
    private const val USAGE_SCALE = 12.0
    private const val USAGE_MAX = 36

    private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

    /** How well [query] matches [entry], or null when it does not. An empty query matches everything with 0. */
    fun matchScore(query: String, entry: PaletteEntry): Int? {
        val q = normalize(query)
        if (q.isEmpty()) return 0
        val title = normalize(entry.title)
        val extra = normalize(listOfNotNull(entry.keywords, entry.subtitle).joinToString(" "))
        return titleScore(q, title)
            ?: allWordsScore(q, "$title $extra")
            ?: when {
                words(extra).any { it.startsWith(q) } -> KEYWORD_PREFIX
                extra.contains(q) -> KEYWORD
                else -> null
            }
    }

    private fun titleScore(q: String, title: String): Int? = when {
        title == q -> EXACT
        title.startsWith(q) -> PREFIX
        words(title).any { it.startsWith(q) } -> WORD_PREFIX
        title.contains(q) -> SUBSTRING
        else -> subsequenceGap(q, title)?.let { FUZZY - min(it, MAX_GAP_PENALTY) }
    }

    // "split right" finds "Split right" and also "right split": every word must appear.
    private fun allWordsScore(q: String, haystack: String): Int? {
        val parts = q.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return if (parts.all { haystack.contains(it) }) ALL_WORDS else null
    }

    /** The recency and usage boost, always below the gap between two tiers. */
    fun boost(entry: PaletteEntry, recents: List<String>, counts: Map<String, Long>): Int {
        val recent = recents.indexOf(entry.key).takeIf { it >= 0 }?.let { (RECENT_TOP - RECENT_STEP * it).coerceAtLeast(0) } ?: 0
        val count = entry.usageId?.let { counts[it] } ?: 0L
        val usage = if (count > 0) min((USAGE_SCALE * ln(1.0 + count)).toInt(), USAGE_MAX) else 0
        return recent + usage
    }

    /**
     * The entries that match [query], best first. Ties keep the order of
     * [entries] (grouped as the palette lists them).
     */
    fun rank(
        entries: List<PaletteEntry>,
        query: String,
        recents: List<String> = emptyList(),
        counts: Map<String, Long> = emptyMap(),
    ): List<PaletteEntry> = entries
        .mapNotNull { entry -> matchScore(query, entry)?.let { entry to it + boost(entry, recents, counts) } }
        .sortedByDescending { it.second }
        .map { it.first }

    private fun normalize(text: String): String = text.trim().lowercase().removePrefix("/").replace(Regex("\\s+"), " ")

    private fun words(text: String): List<String> = text.split(WORD_BREAK).filter { it.isNotEmpty() }

    /** Letters of [q] appear in order in [text]: how many letters lie between them, or null. */
    private fun subsequenceGap(q: String, text: String): Int? {
        var at = -1
        var first = -1
        for (ch in q) {
            if (ch == ' ') continue
            at = text.indexOf(ch, at + 1)
            if (at < 0) return null
            if (first < 0) first = at
        }
        val letters = q.count { it != ' ' }
        return (at - first + 1) - letters
    }
}

/**
 * The compose bar keys, by name, as the palette sends them. Same bytes as the bar. The
 * Herdr keys are the host's own (PaletteContext.herdrKeys in the palette).
 */
object TerminalKeys {
    private val ESC: String = 27.toChar().toString()

    val UP = ESC + "[A"
    val DOWN = ESC + "[B"
    val RIGHT = ESC + "[C"
    val LEFT = ESC + "[D"
    val ENTER: String = 13.toChar().toString()
    val ESCAPE = ESC
    val MODE = ESC + "[Z" // Shift+Tab: Claude Code's plan / auto-accept cycle
    val STOP: String = 3.toChar().toString() // Ctrl+C
    val TAB: String = 9.toChar().toString()
}
