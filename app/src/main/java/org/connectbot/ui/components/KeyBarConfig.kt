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

package org.connectbot.ui.components

import android.content.SharedPreferences
import androidx.core.content.edit
import org.connectbot.util.PreferenceConstants
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.abs

// Control bytes built from code points so no raw control chars live in the source.
private val ESC_SEQ: String = 27.toChar().toString()

private fun ctrl(letter: Char): String = (letter.code and 0x1f).toChar().toString()

/** One key the bar can hold: [id] is what is saved, [sequence] is what is written to the PTY. */
data class KeyBarKey(
    val id: String,
    val label: String,
    val sequence: String,
    val repeats: Boolean = false,
    val kind: KeyKind = KeyKind.SEQUENCE,
    /** A text key also presses Enter after its text. */
    val enter: Boolean = false,
    /** What a screen reader says: the label, or the whole text of a text key. */
    val description: String = label,
)

/** What a tap on the key does: send [KeyBarKey.sequence], work the Ctrl or Alt state, or run an action. */
enum class KeyKind { SEQUENCE, CTRL, ALT, ARROWS, PASTE, COMPOSE, TEXT }

/** The arrows key: one key that is four, the tap position choosing the arrow. */
object ArrowPad {
    fun sequenceAt(x: Float, y: Float, width: Float, height: Float): String {
        val dx = x - width / 2
        val dy = y - height / 2
        return when {
            abs(dx) > abs(dy) -> if (dx < 0) ESC_SEQ + "[D" else ESC_SEQ + "[C"
            else -> if (dy < 0) ESC_SEQ + "[A" else ESC_SEQ + "[B"
        }
    }
}

/**
 * Every key he can put on the bar. Ids are saved, so never rename one. No Ctrl+B: it is
 * Herdr's default prefix, and the bar's own prefix tracking must see that key.
 */
object KeyBarCatalog {
    // F1 to F4 are SS3 keys, F5 to F12 tilde keys with the gaps xterm keeps.
    private val FUNCTION_TAILS = listOf("OP", "OQ", "OR", "OS", "[15~", "[17~", "[18~", "[19~", "[20~", "[21~", "[23~", "[24~")

    val all: List<KeyBarKey> = listOf(
        KeyBarKey("esc", "Esc", ESC_SEQ),
        KeyBarKey("tab", "Tab", "\t"),
        KeyBarKey("shift-tab", "Shift+Tab", ESC_SEQ + "[Z"),
        KeyBarKey("enter", "Enter", "\r"),
        KeyBarKey("backspace", "Bksp", 127.toChar().toString(), repeats = true),
        KeyBarKey("delete", "Del", ESC_SEQ + "[3~", repeats = true),
        KeyBarKey("left", "\u2190", ESC_SEQ + "[D", repeats = true),
        KeyBarKey("up", "\u2191", ESC_SEQ + "[A", repeats = true),
        KeyBarKey("down", "\u2193", ESC_SEQ + "[B", repeats = true),
        KeyBarKey("right", "\u2192", ESC_SEQ + "[C", repeats = true),
        KeyBarKey("home", "Home", ESC_SEQ + "[H"),
        KeyBarKey("end", "End", ESC_SEQ + "[F"),
        KeyBarKey("pgup", "PgUp", ESC_SEQ + "[5~", repeats = true),
        KeyBarKey("pgdn", "PgDn", ESC_SEQ + "[6~", repeats = true),
        KeyBarKey("ctrl-a", "Ctrl+A", ctrl('a')),
        KeyBarKey("ctrl-c", "Ctrl+C", ctrl('c')),
        KeyBarKey("ctrl-d", "Ctrl+D", ctrl('d')),
        KeyBarKey("ctrl-e", "Ctrl+E", ctrl('e')),
        KeyBarKey("ctrl-l", "Ctrl+L", ctrl('l')),
        KeyBarKey("ctrl-r", "Ctrl+R", ctrl('r')),
        KeyBarKey("ctrl-u", "Ctrl+U", ctrl('u')),
        KeyBarKey("ctrl-w", "Ctrl+W", ctrl('w')),
        KeyBarKey("ctrl-z", "Ctrl+Z", ctrl('z')),
        KeyBarKey("slash", "/", "/"),
        KeyBarKey("dash", "-", "-"),
        KeyBarKey("pipe", "|", "|"),
        KeyBarKey("tilde", "~", "~"),
        KeyBarKey("ctrl", "Ctrl", "", kind = KeyKind.CTRL),
        KeyBarKey("alt", "Alt", "", kind = KeyKind.ALT),
        KeyBarKey("arrows", "Arrows", "", kind = KeyKind.ARROWS),
        KeyBarKey("paste", "Paste", "", kind = KeyKind.PASTE),
        KeyBarKey("compose", "Compose", "", kind = KeyKind.COMPOSE),
    ) + FUNCTION_TAILS.mapIndexed { i, tail -> KeyBarKey("f${i + 1}", "F${i + 1}", ESC_SEQ + tail) }

    private val byId: Map<String, KeyBarKey> = all.associateBy { it.id }

    /** A fixed key by its id, or a text key built from its id; null for anything else. */
    fun byId(id: String): KeyBarKey? = byId[id] ?: textKey(id)

    const val MAX_TEXT_KEY: Int = 64

    private const val TEXT_PREFIX = "text:"
    private const val TEXT_ENTER_PREFIX = "textcr:"

    /**
     * The id of a key that types [text] (then Enter when [enter]); null for a blank or too long
     * text. The text is percent-encoded, so no "," or ";" of it breaks the saved layout.
     */
    fun textKeyId(text: String, enter: Boolean): String? {
        if (!usable(text)) return null
        return (if (enter) TEXT_ENTER_PREFIX else TEXT_PREFIX) + URLEncoder.encode(text, "UTF-8")
    }

    // No control characters: a stray byte 2 would be Herdr's prefix.
    private fun usable(text: String) = text.isNotBlank() && text.length <= MAX_TEXT_KEY && text.none { it.isISOControl() }

    private fun textKey(id: String): KeyBarKey? {
        val enter = id.startsWith(TEXT_ENTER_PREFIX)
        if (!enter && !id.startsWith(TEXT_PREFIX)) return null
        val text = try {
            URLDecoder.decode(id.substringAfter(':'), "UTF-8")
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!usable(text)) return null
        val label = (if (text.length > LABEL_CHARS) text.take(LABEL_CHARS - 1) + "\u2026" else text) + if (enter) "\u23CE" else ""
        return KeyBarKey(id, label, text, kind = KeyKind.TEXT, enter = enter, description = text)
    }

    private const val LABEL_CHARS = 8
}

/**
 * The key bar as pages of key ids, in the order he set. Every edit returns a new layout, so a
 * screen can hold one in state; a no-op edit (unknown key, bad index, a full page) returns it unchanged.
 */
data class KeyBarLayout(val pages: List<List<String>>) {
    init {
        require(pages.isNotEmpty()) { "a key bar has at least one page" }
    }

    fun add(page: Int, id: String): KeyBarLayout {
        val keys = pages.getOrNull(page) ?: return this
        if (KeyBarCatalog.byId(id) == null || keys.size >= MAX_KEYS_PER_PAGE) return this
        return replace(page, keys + id)
    }

    fun remove(page: Int, index: Int): KeyBarLayout {
        val keys = pages.getOrNull(page) ?: return this
        if (index !in keys.indices) return this
        return replace(page, keys.filterIndexed { i, _ -> i != index })
    }

    fun move(page: Int, from: Int, to: Int): KeyBarLayout {
        val keys = pages.getOrNull(page) ?: return this
        if (from !in keys.indices || to !in keys.indices) return this
        val moved = keys.toMutableList()
        moved.add(to, moved.removeAt(from))
        return replace(page, moved)
    }

    fun addPage(): KeyBarLayout = if (pages.size >= MAX_PAGES) this else KeyBarLayout(pages + listOf(emptyList()))

    /** The last page stays: the bar always has one. */
    fun removePage(page: Int): KeyBarLayout {
        if (pages.size <= 1 || page !in pages.indices) return this
        return KeyBarLayout(pages.filterIndexed { i, _ -> i != page })
    }

    /** Pages split by ";", keys by ",". Key ids never hold either. */
    fun encode(): String = pages.joinToString(PAGE_SEP) { it.joinToString(KEY_SEP) }

    private fun replace(page: Int, keys: List<String>) = KeyBarLayout(pages.mapIndexed { i, old -> if (i == page) keys else old })

    companion object {
        const val MAX_PAGES: Int = 8
        const val MAX_KEYS_PER_PAGE: Int = 5
        private const val PAGE_SEP = ";"
        private const val KEY_SEP = ","

        /** A saved page may hold more than the editor now lets him add (a six-key page from before). */
        private const val MAX_KEYS_WHEN_READING = 8

        /** The bar he sees until he edits it: the Herdr app's 18 keys in its order, five to a page. */
        val DEFAULT: KeyBarLayout = KeyBarLayout(
            listOf(
                listOf("esc", "ctrl", "alt", "tab", "shift-tab"),
                listOf("arrows", "ctrl-c", "ctrl-d", "ctrl-z", "ctrl-l"),
                listOf("pipe", "tilde", "slash", "dash", "home"),
                listOf("end", "pgup", "pgdn"),
            ),
        )

        /** Null (never saved) is [DEFAULT]; unknown keys are dropped, so a bar from a newer build still opens. */
        fun decode(text: String?): KeyBarLayout {
            if (text == null) return DEFAULT
            val pages = text.split(PAGE_SEP).map { page ->
                page.split(KEY_SEP).filter { KeyBarCatalog.byId(it) != null }.take(MAX_KEYS_WHEN_READING)
            }
            return KeyBarLayout(pages.take(MAX_PAGES))
        }
    }
}

/** The key bar setting (off) and his layout, in the app's default preferences. */
class KeyBarStore(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean(PreferenceConstants.KEY_BAR_CUSTOM, false)
        set(value) = prefs.edit { putBoolean(PreferenceConstants.KEY_BAR_CUSTOM, value) }

    var layout: KeyBarLayout
        get() = KeyBarLayout.decode(prefs.getString(PreferenceConstants.KEY_BAR_LAYOUT, null))
        set(value) = prefs.edit { putString(PreferenceConstants.KEY_BAR_LAYOUT, value.encode()) }
}
