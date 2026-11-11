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

package org.connectbot.res

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** A merge that left a conflict marker in a resource file still builds, so a test keeps them out. */
class NoMergeMarkersTest {
    private val marker = Regex("^(<{7}|\\|{7}|={7}|>{7})( |$)")

    @Test
    fun resourceFiles_holdNoMergeMarkerLines() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val hits = res.walkTopDown().filter { it.isFile && it.extension == "xml" }.flatMap { file ->
            file.readLines().withIndex().filter { marker.containsMatchIn(it.value) }.map { "${file.path}:${it.index + 1}" }
        }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
