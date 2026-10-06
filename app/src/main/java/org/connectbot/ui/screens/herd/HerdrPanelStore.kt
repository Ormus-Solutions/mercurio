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

package org.connectbot.ui.screens.herd

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrPrefix
import timber.log.Timber

/** A keystroke key of the user's: [label] on the key, [spec] the Herdr key sent after the prefix ("z", "shift+r"). */
@Serializable
data class HerdrPanelKey(val label: String, val spec: String) {
    /** The action the key runs: the prefix, then [spec]'s bytes. Null when a terminal cannot send [spec]. */
    fun action(): HerdrAction.ClientKey? = HerdrPrefix.keyBytes(spec)?.let { HerdrAction.ClientKey(it) }

    /** Worth saving: a label, and a key a terminal can send. */
    val isValid: Boolean get() = label.isNotBlank() && action() != null
}

/**
 * How the user changed the Herdr panel. Default keys are named by their usage id
 * ([herdrSheetUsageId]), a listed name that never changes: [hidden] ones are gone from the
 * panel, [edited] keystroke keys send another key under another label. [custom] keys
 * follow the Session group's own keys.
 */
@Serializable
data class HerdrPanelLayout(
    val hidden: Set<String> = emptySet(),
    val edited: Map<String, HerdrPanelKey> = emptyMap(),
    val custom: List<HerdrPanelKey> = emptyList(),
) {
    val isDefault: Boolean get() = hidden.isEmpty() && edited.isEmpty() && custom.isEmpty()
}

/**
 * The Herdr panel's layout, one for the app (every host's panel is the same), kept in the
 * app's preferences beside its other settings. Nothing is stored until the user changes
 * something, and Restore defaults removes it again.
 */
class HerdrPanelStore(private val prefs: SharedPreferences) {
    private val _layout = MutableStateFlow(read())
    val layout: StateFlow<HerdrPanelLayout> = _layout.asStateFlow()

    fun hide(id: String) = save(_layout.value.let { it.copy(hidden = it.hidden + id) })

    /** Make default keystroke key [id] send [key] instead; ignored when [key] is not valid. */
    fun edit(id: String, key: HerdrPanelKey) {
        if (key.isValid) save(_layout.value.let { it.copy(edited = it.edited + (id to key.trimmed())) })
    }

    fun add(key: HerdrPanelKey) {
        if (key.isValid) save(_layout.value.let { it.copy(custom = it.custom + key.trimmed()) })
    }

    /** Replace custom key [index]; ignored when [key] is not valid or [index] is gone. */
    fun replace(index: Int, key: HerdrPanelKey) {
        val custom = _layout.value.custom
        if (key.isValid && index in custom.indices) {
            save(_layout.value.copy(custom = custom.toMutableList().also { it[index] = key.trimmed() }))
        }
    }

    fun remove(index: Int) {
        val custom = _layout.value.custom
        if (index in custom.indices) save(_layout.value.copy(custom = custom.filterIndexed { i, _ -> i != index }))
    }

    /** Hidden keys come back; edits and custom keys go. */
    fun restoreDefaults() = save(HerdrPanelLayout())

    private fun save(layout: HerdrPanelLayout) {
        _layout.value = layout
        prefs.edit { if (layout.isDefault) remove(PREF_KEY) else putString(PREF_KEY, json.encodeToString(layout)) }
    }

    private fun read(): HerdrPanelLayout {
        val raw = prefs.getString(PREF_KEY, null) ?: return HerdrPanelLayout()
        return try {
            json.decodeFromString<HerdrPanelLayout>(raw)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one: not JSON, or not this shape.
            Timber.w(e, "Herdr panel layout unreadable; showing the defaults")
            HerdrPanelLayout()
        }
    }

    private fun HerdrPanelKey.trimmed() = HerdrPanelKey(label.trim(), spec.trim())

    companion object {
        const val PREF_KEY = "herdrPanelLayout"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
