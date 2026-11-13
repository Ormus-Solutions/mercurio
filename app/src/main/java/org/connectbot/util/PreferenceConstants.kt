/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007-2026 Kenny Root
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

package org.connectbot.util

/**
 * @author Kenny Root
 */
object PreferenceConstants {
    const val MEMKEYS: String = "memkeys"

    const val SCROLLBACK: String = "scrollback"

    const val ROTATION: String = "rotation"

    const val BACKUP_KEYS: String = "backupkeys"
    const val BACKUP_KEYS_DEFAULT: Boolean = false

    const val ROTATION_DEFAULT: String = "Default"
    const val ROTATION_LANDSCAPE: String = "Force landscape"
    const val ROTATION_PORTRAIT: String = "Force portrait"
    const val ROTATION_AUTO: String = "Automatic"

    const val FULLSCREEN: String = "fullscreen"
    const val TITLEBARHIDE: String = "titlebarhide"
    const val PG_UPDN_GESTURE: String = "pgupdngesture"

    const val KEYMODE: String = "keymode"
    const val KEY_ALWAYS_VISIBLE: String = "alwaysvisible"

    const val KEYMODE_RIGHT: String = "Use right-side keys"
    const val KEYMODE_LEFT: String = "Use left-side keys"
    const val KEYMODE_NONE: String = "none"

    const val CAMERA: String = "camera"

    const val CAMERA_CTRLA_SPACE: String = "Ctrl+A then Space"
    const val CAMERA_CTRLA: String = "Ctrl+A"
    const val CAMERA_ESC: String = "Esc"
    const val CAMERA_ESC_A: String = "Esc+A"

    const val KEEP_ALIVE: String = "keepalive"

    const val WIFI_LOCK: String = "wifilock"

    const val BUMPY_ARROWS: String = "bumpyarrows"

    const val SORT_BY_COLOR: String = "sortByColor"

    const val BELL: String = "bell"
    const val BELL_VOLUME: String = "bellVolume"
    const val BELL_VIBRATE: String = "bellVibrate"
    const val BELL_NOTIFICATION: String = "bellNotification"
    const val DEFAULT_BELL_VOLUME: Float = 0.25f

    const val CONNECTION_PERSIST: String = "connPersist"
    const val NOTIFICATION_PERMISSION_DENIED: String = "notificationPermissionDenied"

    const val SHIFT_FKEYS: String = "shiftfkeys"
    const val CTRL_FKEYS: String = "ctrlfkeys"
    const val VOLUME_FONT: String = "volumefont"
    const val STICKY_MODIFIERS: String = "stickymodifiers"
    const val YES: String = "yes"
    const val NO: String = "no"
    const val ALT: String = "alt"

    /* Backup identifiers */
    const val BACKUP_PREF_KEY: String = "prefs"

    /* Security */
    const val AUTH_ON_LAUNCH: String = "authOnLaunch"

    /* Font settings */
    const val FONT_FAMILY: String = "fontFamily"
    const val FONT_FAMILY_DEFAULT: String = "SYSTEM_DEFAULT"
    const val CUSTOM_FONTS: String = "customFonts"

    /* Compose bar: the keyboard's Enter starts a new line, and only the bar's ⏎ key sends. */
    const val ENTER_NEW_LINE: String = "enterNewLine"

    /* Key bar: off keeps the default bar; on shows his paged keys (KeyBarLayout.encode) as the pages after the bar's own bottom row. */
    const val KEY_BAR_CUSTOM: String = "keyBarCustom"
    const val KEY_BAR_LAYOUT: String = "keyBarLayout"

    /* Voice key: off keeps the compose bar as it is; on shows a mic key that sends a take to his voice server. */
    const val VOICE_KEY: String = "voiceKey"
    const val VOICE_SERVER: String = "voiceServer"
    const val VOICE_STYLE: String = "voiceStyle"

    /* Theme: the planet picked in the settings panel (Planet.key); none means Sun. */
    const val PLANET: String = "planet"

    /* Theme: retired, the app is dark only. AppViewModel removes a saved value at launch. */
    const val THEME_MODE: String = "themeMode"

    /** The URL of the sessions feed the home reads its workboards from; absent when none is set. */
    const val HOME_FEED_URL: String = "homeFeedUrl"

    /** Read herdr-core agents on a host (boolean, on unless saved off); the Inbox adds them to its rows, and the notices read it (Needs you, Finished, Approve and Deny). */
    const val HERDR_CORE: String = "herdrCore"

    /** The Inbox: one list of every agent, from the home and the Needs you key's long press (boolean, on unless saved off). */
    const val INBOX: String = "inbox"

    /** What [INBOX] and [HERDR_CORE] read when nothing is saved: a fresh install and an install that never touched them (a saved value, off included, wins). */
    const val INBOX_DEFAULT: Boolean = true
    const val HERDR_CORE_DEFAULT: Boolean = true

    /** The panes he jumped to last, newest first, one key per line (text, at most eight); the Jump sheet's Recent group. */
    const val HERDR_JUMP_RECENT: String = "herdrJumpRecent"

    /** A host answered `herdr-core --version` on an earlier visit (boolean, absent until one did): the next launch opens the Inbox first. */
    const val HERDR_CORE_SEEN: String = "herdrCoreSeen"

    /** The version token a host last answered to `herdr-core --version` (string, absent until one did); the feedback text names it. */
    const val HERDR_CORE_VERSION: String = "herdrCoreVersion"
}
