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

package org.connectbot.ui.screens.panel

/**
 * What the Send feedback row prefills: the app version and, when a host answered, the herdr-core
 * version. Nothing else leaves: no logs, no host names.
 */
object FeedbackText {
    private val VERSION = Regex("""^herdr-core\s+v?(\d+(?:\.\d+)+(?:[-+][0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?)""")

    /** The version token of a `herdr-core --version` answer, or null when it holds none. */
    fun coreVersionOf(stdout: String): String? = VERSION.find(stdout.lineSequence().firstOrNull().orEmpty().trim())?.groupValues?.get(1)

    fun body(appVersion: String, coreVersion: String?): String = buildString {
        append("\n\n--\nApp ").append(appVersion).append(", Android")
        if (coreVersion != null) append("\nherdr-core ").append(coreVersion)
    }
}
