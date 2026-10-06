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

import org.connectbot.transport.CommandOutput

/**
 * Herdr's prefix key on one host: [spec] as Herdr's config spells it ("ctrl+b"), and
 * [bytes], what a terminal sends for that key. [problem] says why Mercurio fell back
 * to Herdr's default instead of the host's setting; null when nothing went wrong.
 */
data class HerdrPrefix(val spec: String, val bytes: String, val problem: String? = null) {
    companion object {
        /** Herdr's built-in prefix (its default config: `prefix = "ctrl+b"`). */
        val DEFAULT = HerdrPrefix("ctrl+b", 2.toChar().toString())

        /**
         * Prints the host's Herdr config, or nothing when there is none. Herdr reads
         * `$HERDR_CONFIG_PATH`, else `${XDG_CONFIG_HOME:-$HOME/.config}/herdr/config.toml`;
         * a login shell, like every other Herdr command, so the user's environment applies.
         */
        val readCommand: String = HerdrApi.loginShell(
            "f=\"\${HERDR_CONFIG_PATH:-\${XDG_CONFIG_HOME:-\$HOME/.config}/herdr/config.toml}\"; " +
                "if [ -f \"\$f\" ]; then cat \"\$f\"; fi",
        )

        private val ESC: String = 27.toChar().toString()

        // Function keys as xterm sends them.
        private val FUNCTION_KEYS = mapOf(
            "f1" to "${ESC}OP", "f2" to "${ESC}OQ", "f3" to "${ESC}OR", "f4" to "${ESC}OS",
            "f5" to "$ESC[15~", "f6" to "$ESC[17~", "f7" to "$ESC[18~", "f8" to "$ESC[19~",
            "f9" to "$ESC[20~", "f10" to "$ESC[21~", "f11" to "$ESC[23~", "f12" to "$ESC[24~",
        )

        // Herdr's names for keys that are awkward to write in a key spec.
        private val NAMED_KEYS = mapOf(
            "space" to " ", "esc" to ESC, "escape" to ESC, "enter" to "\r", "return" to "\r",
            "tab" to "\t", "backspace" to 127.toChar().toString(),
            "minus" to "-", "comma" to ",", "ampersand" to "&", "plus" to "+", "backtick" to "`",
        )

        /** The prefix from what [readCommand] printed; null [out] means it never ran. */
        fun fromRead(out: CommandOutput?): HerdrPrefix = when {
            out == null -> DEFAULT.copy(problem = "Mercurio could not read Herdr's config on the host, so it sends Herdr's default prefix, ctrl+b.")

            out.exitCode != null && out.exitCode != 0 -> DEFAULT.copy(
                problem = "Reading Herdr's config exited ${out.exitCode}" +
                    (out.stderr.lineSequence().firstOrNull { it.isNotBlank() }?.let { ": ${it.trim()}" } ?: "") +
                    ", so Mercurio sends Herdr's default prefix, ctrl+b.",
            )

            else -> fromConfig(out.stdout)
        }

        /** The prefix a Herdr config.toml sets; Herdr's default when it sets none. */
        fun fromConfig(toml: String): HerdrPrefix {
            val spec = prefixSpec(toml)?.trim() ?: return DEFAULT
            val bytes = keyBytes(spec) ?: return DEFAULT.copy(
                problem = "Herdr's config sets prefix = \"$spec\", a key Mercurio cannot send from a terminal, " +
                    "so it sends Herdr's default prefix, ctrl+b.",
            )
            return HerdrPrefix(spec, bytes)
        }

        /**
         * `prefix` in the `[keys]` table (or `keys.prefix` at the top): the string, the
         * first string of an array, or the raw value when it is neither. Null when unset.
         */
        internal fun prefixSpec(toml: String): String? = keyBindings(toml)["prefix"]?.firstOrNull()

        /**
         * Every binding in the `[keys]` table (or `keys.<name>` at the top), by name: its
         * string, every string of an array, or the raw value when it holds no string.
         */
        internal fun keyBindings(toml: String): Map<String, List<String>> {
            val bindings = mutableMapOf<String, List<String>>()
            var table = ""
            val lines = toml.lineSequence().map { stripComment(it).trim() }.iterator()
            while (lines.hasNext()) {
                val line = lines.next()
                if (line.startsWith("[")) {
                    // [[array.of.tables]] is never [keys]; quoted parts compare by their name.
                    table = if (line.startsWith("[[")) line else line.removePrefix("[").substringBefore(']').toml()
                    continue
                }
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val key = line.substring(0, eq).toml()
                val full = if (table.isEmpty()) key else "$table.$key"
                var value = line.substring(eq + 1).trim()
                // An array may run over several lines, to its closing bracket.
                if (value.startsWith("[")) {
                    while (!closes(value) && lines.hasNext()) value += " " + lines.next()
                }
                val name = full.removePrefix("keys.")
                if (name == full || '.' in name || name in bindings) continue
                bindings[name] = strings(value).ifEmpty { listOf(value) }
            }
            return bindings
        }

        /**
         * The bytes a terminal sends for Herdr key [spec] (ctrl/alt/shift, then one key),
         * or null when a terminal cannot send it (cmd, super, unknown names).
         */
        fun keyBytes(spec: String): String? {
            val s = spec.trim()
            if (s.isEmpty()) return null
            // The key is the last part; "+" is a key itself ("+", "ctrl++").
            val keyStart = if (s.endsWith('+')) s.length - 1 else s.lastIndexOf('+') + 1
            val key = s.substring(keyStart)
            val mods = s.substring(0, keyStart).removeSuffix("+").lowercase().split('+').filter { it.isNotEmpty() }.toSet()
            if (!MODIFIERS.containsAll(mods)) return null
            val ctrl = "ctrl" in mods || "control" in mods
            val alt = "alt" in mods || "meta" in mods || "option" in mods
            var bytes = baseKey(key) ?: return null
            if ("shift" in mods) {
                // Only Shift+letter reaches a host as plain bytes (the capital letter).
                if (ctrl || bytes.length != 1 || !bytes[0].isLetter()) return null
                bytes = bytes.uppercase()
            }
            if (ctrl) bytes = ctrlOf(bytes) ?: return null
            if (alt) {
                if (bytes.length != 1) return null
                bytes = ESC + bytes
            }
            return bytes
        }

        private val MODIFIERS = setOf("ctrl", "control", "alt", "meta", "option", "shift")

        private fun baseKey(key: String): String? {
            if (key.length == 1) return key
            val name = key.lowercase()
            return NAMED_KEYS[name] ?: FUNCTION_KEYS[name]
        }

        // Ctrl+letter is the letter & 0x1f; Ctrl+Space and Ctrl+@ are NUL; Ctrl+[ \ ] ^ _ are 0x1b..0x1f.
        private fun ctrlOf(bytes: String): String? {
            if (bytes.length != 1) return null
            val c = bytes[0].uppercaseChar()
            return when (c) {
                ' ' -> 0.toChar().toString()
                in '@'..'_' -> (c.code and 0x1f).toChar().toString()
                else -> null
            }
        }

        /** [line] without its `#` comment; a `#` inside a string stays. */
        private fun stripComment(line: String): String {
            var quote: Char? = null
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quote == null && c == '#' -> return line.substring(0, i)
                    quote == null && (c == '"' || c == '\'') -> quote = c
                    quote == '"' && c == '\\' -> i++
                    c == quote -> quote = null
                }
                i++
            }
            return line
        }

        /** Every string in [text] ("basic" or 'literal'), in order. */
        private fun strings(text: String): List<String> {
            val found = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                val quote = text[i]
                if (quote != '"' && quote != '\'') {
                    i++
                    continue
                }
                val out = StringBuilder()
                i++
                while (i < text.length && text[i] != quote) {
                    if (quote == '"' && text[i] == '\\' && i + 1 < text.length) {
                        i++
                        out.append(
                            when (val e = text[i]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> e
                            },
                        )
                    } else {
                        out.append(text[i])
                    }
                    i++
                }
                // An unclosed string is not a value.
                if (i < text.length) found += out.toString()
                i++
            }
            return found
        }

        /** Whether the array [text] opens is closed: its brackets balance outside strings. */
        private fun closes(text: String): Boolean {
            var depth = 0
            var quote: Char? = null
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    quote == '"' && c == '\\' -> i++
                    quote != null -> if (c == quote) quote = null
                    c == '"' || c == '\'' -> quote = c
                    c == '[' -> depth++
                    c == ']' -> if (--depth == 0) return true
                }
                i++
            }
            return false
        }

        // A TOML key or table name compared by its parts: spaces and quotes around each part dropped.
        private fun String.toml(): String = split('.').joinToString(".") { it.trim().trim('"', '\'') }
    }
}
