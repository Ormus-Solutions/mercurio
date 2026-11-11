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

import java.util.Locale

/**
 * How a space name becomes a letter tile: the group is the part before " · " (the whole name when
 * there is none), the letter is the group's first character, and the group picks one of
 * [ACCENT_COUNT] accents. The slot is fixed by the group's name alone (String.hashCode is
 * specified), so a group keeps its colour across runs and phones, like the Herdr app's tiles.
 * The Spaces list and the Inbox read the same mapping.
 */
object SpaceColors {
    const val ACCENT_COUNT = 6

    private const val GROUP_SEP = " · "

    fun group(space: String): String = space.substringBefore(GROUP_SEP)

    fun letter(space: String): String = group(space).firstOrNull { !it.isWhitespace() }?.uppercase(Locale.ROOT) ?: "?"

    fun accentIndex(group: String): Int = Math.floorMod(group.trim().lowercase(Locale.ROOT).hashCode(), ACCENT_COUNT)
}
