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

package org.connectbot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestUrlTest {

    @Test
    fun theLastUrlWins() {
        val out = "Opened https://github.com/o/r/pull/1\nthen deployed to https://app.example.com/dashboard."
        assertEquals("https://app.example.com/dashboard", LatestUrl.find(out))
    }

    @Test
    fun markdownLinksQuotesAndPunctuation_giveJustTheAddress() {
        assertEquals("https://x.y/a?b=1", LatestUrl.find("see [the PR](https://x.y/a?b=1)."))
        assertEquals("http://100.100.1.1:8765/mercurio.apk", LatestUrl.find("APK: \"http://100.100.1.1:8765/mercurio.apk\""))
        assertEquals("https://x.y/z", LatestUrl.find("done: https://x.y/z!"))
    }

    @Test
    fun noUrl_isNull() {
        assertNull(LatestUrl.find("nothing to open here, ftp://not.web"))
    }
}
