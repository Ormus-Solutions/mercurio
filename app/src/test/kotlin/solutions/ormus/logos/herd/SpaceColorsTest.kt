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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SpaceColors: the group of a space name, its tile letter and its accent slot. */
class SpaceColorsTest {
    @Test
    fun `the group is the part before the dot or the whole name`() {
        assertEquals("alpha", SpaceColors.group("alpha · ONE"))
        assertEquals("solo", SpaceColors.group("solo"))
        assertEquals("~", SpaceColors.group("~"))
    }

    @Test
    fun `the letter is the first character of the group in upper case`() {
        assertEquals("A", SpaceColors.letter("alpha · ONE"))
        assertEquals("~", SpaceColors.letter("~"))
        assertEquals("?", SpaceColors.letter(""))
    }

    @Test
    fun `two spaces of one group share an accent whatever the letter case`() {
        assertEquals(SpaceColors.accentIndex(SpaceColors.group("alpha · ONE")), SpaceColors.accentIndex(SpaceColors.group("Alpha · TWO")))
    }

    @Test
    fun `the accent slot is fixed by the name and inside the palette`() {
        assertEquals(Math.floorMod("alpha".hashCode(), SpaceColors.ACCENT_COUNT), SpaceColors.accentIndex("alpha"))
        listOf("", "a", "beta", "gamma", "~").forEach { assertTrue(SpaceColors.accentIndex(it) in 0 until SpaceColors.ACCENT_COUNT) }
    }
}
