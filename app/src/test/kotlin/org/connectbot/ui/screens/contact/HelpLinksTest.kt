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

package org.connectbot.ui.screens.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bug reports and help go to Mercurio, not to upstream ConnectBot, which
 * cannot act on a Mercurio bug. Read from the sources, every locale included.
 */
class HelpLinksTest {
    private val issues = "https://github.com/Ormus-Solutions/mercurio/issues"
    private val main = File("src/main")
    private val upstream = Regex("""connectbot\.org|github\.com/connectbot""")

    private fun stringsFiles() = main.resolve("res").listFiles { f -> f.name.startsWith("values") }!!
        .map { it.resolve("strings.xml") }.filter { it.exists() }

    private fun string(file: File, name: String): String? = Regex("""<string name="$name"[^>]*>(.*?)</string>""")
        .find(file.readText())?.groupValues?.get(1)

    @Test
    fun contactScreenLinks_pointAtMercurio() {
        val values = main.resolve("res/values/strings.xml")
        assertEquals(issues, string(values, "help_report_bug_url"))
        assertEquals("https://github.com/Ormus-Solutions/mercurio", string(values, "help_github_url"))
        assertEquals("https://github.com/Ormus-Solutions/mercurio#readme", string(values, "help_website_url"))
    }

    @Test
    fun logsBugReportInfo_namesMercurioIssues_inEveryLocale() {
        val files = stringsFiles().filter { string(it, "logs_bug_report_info") != null }
        assertTrue("found ${files.size} locales", files.size > 40)
        files.forEach { assertTrue(it.path, string(it, "logs_bug_report_info")!!.contains(issues)) }
    }

    @Test
    fun noBugOrHelpLinkSendsUsersUpstream() {
        val sources = stringsFiles() + main.resolve("java").walk().filter { it.extension == "kt" }
        val hits = sources.filter { upstream.containsMatchIn(it.readText()) }.map { it.path }
        assertEquals(emptyList<String>(), hits)
    }
}
