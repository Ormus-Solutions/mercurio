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

package solutions.ormus.logos.voice

import android.content.SharedPreferences
import androidx.core.content.edit
import org.connectbot.util.PreferenceConstants
import kotlin.math.sqrt

/** The voice key's three settings. It ships off, with no server of its own and the server's own style. */
class VoiceSettings(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean(PreferenceConstants.VOICE_KEY, false)
        set(value) = prefs.edit { putBoolean(PreferenceConstants.VOICE_KEY, value) }

    /** The voice server as he typed it; blank means the host of the session he is in. */
    var server: String
        get() = prefs.getString(PreferenceConstants.VOICE_SERVER, "").orEmpty()
        set(value) = prefs.edit { putString(PreferenceConstants.VOICE_SERVER, value.trim()) }

    var style: VoiceStyle
        get() = VoiceStyle.fromKey(prefs.getString(PreferenceConstants.VOICE_STYLE, null))
        set(value) = prefs.edit { putString(PreferenceConstants.VOICE_STYLE, value.key.orEmpty()) }

    /** The server's base URL for a session on [sessionHost]; null when neither names one. */
    fun baseUrl(sessionHost: String?): String? = VoiceServer.baseUrl(server, sessionHost)
}

/** The bar height of one read of microphone samples. */
object VoiceLevel {
    /** 0 for silence to 1 for full scale; the square root of the RMS, so speech moves the bars visibly. */
    fun of(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / count) / Short.MAX_VALUE
        return sqrt(rms).toFloat().coerceIn(0f, 1f)
    }
}
