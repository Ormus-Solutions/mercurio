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

package org.connectbot.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Modifier

/** The usage schema has no field that could carry free text. */
@RunWith(AndroidJUnit4::class)
class UsageSchemaTest {

    private fun fields(type: Class<*>) = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }

    private fun stringFields(type: Class<*>): Set<String> = fields(type).filter { it.type == String::class.java }.map { it.name }.toSet()

    @Test
    fun eventHasOnlyIdentifierStrings() {
        // action: a listed id. screen: a route token. reason: an enum or class name.
        assertThat(stringFields(UsageEvent::class.java)).containsExactlyInAnyOrder("action", "screen", "reason")
        assertThat(fields(UsageEvent::class.java).map { it.name })
            .containsExactlyInAnyOrder("seq", "ts", "action", "screen", "herdr", "durationMs", "reason")
    }

    @Test
    fun dailyCountHasOnlyIdentifierStrings() {
        // id: a generated UUID. day: yyyy-MM-dd.
        assertThat(stringFields(DailyCount::class.java)).containsExactlyInAnyOrder("id", "day", "action", "screen")
    }

    @Test
    fun eventJsonKeys() {
        val json = UsageEvent(1, 2, UsageActions.KEY_UP, "console", true).toJson("d")
        assertThat(json.keys().asSequence().toSet())
            .containsExactlyInAnyOrder("v", "type", "device", "seq", "ts", "action", "screen", "herdr", "duration_ms", "reason")
    }

    @Test
    fun actionIdsAreShortIdentifiers() {
        UsageActions.ALL.forEach { assertThat(it).matches("[a-z][a-z-]*(:[a-z0-9+-]+)?") }
        assertThat(UsageActions.ALL).doesNotHaveDuplicates()
    }

    @Test
    fun docsActionListMatchesTheApp() {
        // Unit tests run from the app module; the list lives at the repo root.
        val file = listOf(File("../docs/usage-actions.txt"), File("docs/usage-actions.txt")).first { it.exists() }
        val documented = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertThat(documented).containsExactlyElementsOf(UsageActions.ALL)
    }
}
