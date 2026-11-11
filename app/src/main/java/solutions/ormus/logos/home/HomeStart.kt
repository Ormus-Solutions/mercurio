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

package solutions.ormus.logos.home

import android.content.SharedPreferences
import androidx.core.content.edit
import org.connectbot.ui.navigation.NavDestinations
import org.connectbot.util.PreferenceConstants
import solutions.ormus.logos.herd.CoreSource

/**
 * Where the app opens, and the two settings that decide it. The Inbox and herdr-core settings are
 * on unless he saved them off: [PreferenceConstants.INBOX_DEFAULT] and
 * [PreferenceConstants.HERDR_CORE_DEFAULT] are read where nothing is saved, so a fresh install and
 * an install that never touched them agree. The app opens on the Inbox once a host has answered
 * `herdr-core --version`; the answer is remembered, because a launch has no connection to ask.
 */
object HomeStart {
    fun inboxOn(prefs: SharedPreferences): Boolean = prefs.getBoolean(PreferenceConstants.INBOX, PreferenceConstants.INBOX_DEFAULT)

    fun coreOn(prefs: SharedPreferences): Boolean = prefs.getBoolean(PreferenceConstants.HERDR_CORE, PreferenceConstants.HERDR_CORE_DEFAULT)

    /** The route the app opens on: the Inbox when both settings are on and a host answered, else the hosts home. */
    fun destination(prefs: SharedPreferences, makingShortcut: Boolean = false): String {
        val inbox = !makingShortcut && inboxOn(prefs) && coreOn(prefs) && prefs.getBoolean(PreferenceConstants.HERDR_CORE_SEEN, false)
        return if (inbox) NavDestinations.INBOX else NavDestinations.HOST_LIST
    }

    /**
     * Remember what the hosts' latest herdr-core reads say of the probe: one answer opens the Inbox
     * first, whatever the other hosts say; every read saying herdr-core is missing stops it; a host
     * that could not be reached, or an off setting, changes nothing.
     */
    fun noteProbes(prefs: SharedPreferences, results: Collection<CoreSource.Result>) {
        val seen = when {
            results.any { it is CoreSource.Result.Agents } -> true
            results.any { it is CoreSource.Result.Unavailable && it.reason == CoreSource.NOT_INSTALLED } -> false
            else -> return
        }
        if (prefs.getBoolean(PreferenceConstants.HERDR_CORE_SEEN, false) != seen) prefs.edit { putBoolean(PreferenceConstants.HERDR_CORE_SEEN, seen) }
    }
}
