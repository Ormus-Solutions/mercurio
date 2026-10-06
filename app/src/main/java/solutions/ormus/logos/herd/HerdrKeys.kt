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
 * A Herdr client action Mercurio sends as keystrokes: [config] is its name under
 * `[keys]` in Herdr's config, [default] Herdr's own binding when the config sets none.
 *
 * The defaults are Herdr 0.9.0's: `herdr --default-config` lists each one, except
 * copy_mode, which it leaves out; its "prefix+[" is from the defaults compiled into
 * the herdr 0.9.0 binary, in its key order between edit_scrollback and focus_pane_left.
 */
enum class HerdrKey(val config: String, val default: String) {
    TOGGLE_SIDEBAR("toggle_sidebar", "prefix+b"),
    COPY_MODE("copy_mode", "prefix+["),
    GOTO("goto", "prefix+g"),
    OPEN_NOTIFICATION("open_notification_target", "prefix+o"),
    DETACH("detach", "prefix+q"),
    RELOAD_CONFIG("reload_config", "prefix+shift+r"),
    HELP("help", "prefix+?"),
    WORKSPACE_PICKER("workspace_picker", "prefix+w"),
    PREVIOUS_TAB("previous_tab", "prefix+p"),
    NEXT_TAB("next_tab", "prefix+n"),
}

/**
 * One [HerdrKey] on a host: [spec], the binding Mercurio uses as Herdr spells it, and
 * [bytes], what it writes for it (the prefix included). Null [bytes] means the key is off,
 * and [problem] says why.
 */
data class HerdrBinding(val spec: String?, val bytes: String?, val problem: String? = null)

/** Herdr's keys on one host: its [prefix], and the binding of every [HerdrKey]. */
data class HerdrKeys(val prefix: HerdrPrefix, val bindings: Map<HerdrKey, HerdrBinding>) {
    /** What to write for [key], or null when the host binds it to nothing Mercurio can send. */
    fun bytes(key: HerdrKey): String? = bindings[key]?.bytes

    /** Why [key] is off on this host; null when it can be sent. */
    fun problem(key: HerdrKey): String? = bindings[key]?.problem

    companion object {
        /** Herdr's built-in keys: no config, or none that could be read. */
        val DEFAULT = resolve(HerdrPrefix.DEFAULT, emptyMap())

        /** The keys from what [HerdrPrefix.readCommand] printed; null [out] means it never ran. */
        fun fromRead(out: CommandOutput?): HerdrKeys = if (out != null && (out.exitCode == null || out.exitCode == 0)) {
            fromConfig(out.stdout)
        } else {
            resolve(HerdrPrefix.fromRead(out), emptyMap())
        }

        /** The keys a Herdr config.toml sets; Herdr's defaults for the ones it leaves out. */
        fun fromConfig(toml: String): HerdrKeys = resolve(HerdrPrefix.fromConfig(toml), HerdrPrefix.keyBindings(toml))

        private const val PREFIX_PLUS = "prefix+"

        /**
         * Each key's binding: the first prefix binding Mercurio can send (it arms the prefix,
         * then sends the key), else the first direct chord it can send, else off.
         */
        private fun resolve(prefix: HerdrPrefix, config: Map<String, List<String>>): HerdrKeys = HerdrKeys(
            prefix,
            HerdrKey.entries.associateWith { key ->
                val specs = (config[key.config] ?: listOf(key.default)).map { it.trim() }.filter { it.isNotEmpty() }
                val (prefixed, direct) = specs.partition { it.startsWith(PREFIX_PLUS, ignoreCase = true) }
                prefixed.firstNotNullOfOrNull { spec ->
                    HerdrPrefix.keyBytes(spec.substring(PREFIX_PLUS.length))?.let { HerdrBinding(spec, prefix.bytes + it) }
                } ?: direct.firstNotNullOfOrNull { spec ->
                    HerdrPrefix.keyBytes(spec)?.let { HerdrBinding(spec, it) }
                } ?: HerdrBinding(
                    specs.firstOrNull(),
                    null,
                    if (specs.isEmpty()) {
                        "Herdr's config leaves ${key.config} unbound."
                    } else {
                        "Herdr's config binds ${key.config} to ${specs.joinToString { "\"$it\"" }}, " +
                            "which Mercurio cannot send from a terminal."
                    },
                )
            },
        )
    }
}
