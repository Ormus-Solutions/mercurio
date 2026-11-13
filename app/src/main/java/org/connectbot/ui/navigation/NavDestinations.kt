/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
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

package org.connectbot.ui.navigation

import android.net.Uri
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdrConsole

object NavDestinations {
    const val HOST_LIST = "host_list"
    const val CONSOLE = "console"

    /** The console route: the host, then the Herdr console to open on it (none: the host's own console). */
    const val CONSOLE_ROUTE = "$CONSOLE/{${NavArgs.HOST_ID}}?${NavArgs.SESSION}={${NavArgs.SESSION}}&${NavArgs.LABEL}={${NavArgs.LABEL}}&${NavArgs.WATCH}={${NavArgs.WATCH}}&${NavArgs.WATCH_SPACE}={${NavArgs.WATCH_SPACE}}&${NavArgs.WATCH_TAB}={${NavArgs.WATCH_TAB}}"
    const val HOST_EDITOR = "host_editor"
    const val PUBKEY_LIST = "pubkey_list"
    const val GENERATE_PUBKEY = "generate_pubkey"
    const val PUBKEY_EDITOR = "pubkey_editor"
    const val PORT_FORWARD_LIST = "port_forward_list"
    const val SETTINGS = "settings"
    const val SETTINGS_HIGHLIGHT_CONN_PERSIST = "settings?highlight=conn_persist"
    const val SETTINGS_PANEL = "settings_panel"
    const val COLORS = "colors"
    const val PALETTE_EDITOR = "palette_editor"
    const val PROFILES = "profiles"
    const val PROFILE_EDITOR = "profile_editor"
    const val HELP = "help"
    const val EULA = "eula"
    const val HINTS = "hints"
    const val CONTACT = "contact"

    /** The Inbox: every agent of every connected host, sorted by what it needs. */
    const val INBOX = "inbox"

    /** The Unblock view: the questions store's open asks, one at a time, opened by a swipe on the home. */
    const val NEEDS = "needs"

    /** The Spaces list: the herdr-core spaces of every connected host, a box per tab. */
    const val SPACES = "spaces"

    /**
     * The route that shows [hostId]'s console, or [console] opened on that host (a Workspaces
     * key): the console connects the host when nothing of it is open.
     */
    fun console(hostId: Long, console: HerdrConsole? = null, watch: CoreWatchTarget? = null): String = buildString {
        append(CONSOLE).append('/').append(hostId)
        if (watch != null) {
            append('?').append(NavArgs.WATCH).append('=').append(Uri.encode(watch.address))
            append('&').append(NavArgs.WATCH_SPACE).append('=').append(Uri.encode(watch.space))
            append('&').append(NavArgs.WATCH_TAB).append('=').append(Uri.encode(watch.tab))
        } else if (console != null) {
            append('?').append(NavArgs.SESSION).append('=').append(Uri.encode(console.session))
            console.workspaceLabel?.let { append('&').append(NavArgs.LABEL).append('=').append(Uri.encode(it)) }
        }
    }
}

object NavArgs {
    const val HOST_ID = "hostId"
    const val SESSION = "session"
    const val LABEL = "label"
    const val WATCH = "watch"
    const val WATCH_SPACE = "watchSpace"
    const val WATCH_TAB = "watchTab"
    const val BRIDGE_NAME = "bridgeName"
    const val PUBKEY_ID = "pubkeyId"
    const val SCHEME_ID = "schemeId"
    const val PROFILE_ID = "profileId"
}
