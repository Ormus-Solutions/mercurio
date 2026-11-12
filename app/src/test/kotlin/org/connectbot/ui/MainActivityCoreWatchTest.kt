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

package org.connectbot.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.navigation.NavDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.CoreWatchTarget

/** A tap on a core notice: the intent's extras become the watch view an Inbox row opens. */
@RunWith(AndroidJUnit4::class)
class MainActivityCoreWatchTest {
    private fun tap(address: String?, host: Long? = 7L) = Intent().apply {
        address?.let { putExtra(MainActivity.EXTRA_CORE_ADDRESS, it) }
        host?.let { putExtra(MainActivity.EXTRA_CORE_HOST, it) }
        putExtra(MainActivity.EXTRA_CORE_SPACE, "alpha")
        putExtra(MainActivity.EXTRA_CORE_TAB, "ONE")
    }

    @Test
    fun theExtras_becomeTheSameWatchRouteAnInboxRowOpens() {
        val request = MainActivity.coreWatchFrom(tap("box/t7"))!!

        assertEquals(7L, request.hostId)
        assertEquals(CoreWatchTarget("box/t7", "alpha", "ONE"), request.target)
        assertEquals(
            "console/7?watch=box%2Ft7&watchSpace=alpha&watchTab=ONE",
            NavDestinations.console(request.hostId, watch = request.target),
        )
    }

    @Test
    fun anInvalidAddress_isNoWatch_soTheHostConsoleOpens() {
        assertNull(MainActivity.coreWatchFrom(tap("t1; rm -rf ~")))
        assertNull(MainActivity.coreWatchFrom(tap("-t1")))
        assertNull(MainActivity.coreWatchFrom(tap("")))
    }

    @Test
    fun longLabels_areCapped() {
        val intent = tap("t1").putExtra(MainActivity.EXTRA_CORE_SPACE, "s".repeat(500)).putExtra(MainActivity.EXTRA_CORE_TAB, "t".repeat(500))

        val target = MainActivity.coreWatchFrom(intent)!!.target
        assertEquals(80, target.space.length)
        assertEquals(80, target.tab.length)
    }

    @Test
    fun aNoticeWithoutTheAddressOrHost_keepsToday() {
        assertNull(MainActivity.coreWatchFrom(tap(null)))
        assertNull(MainActivity.coreWatchFrom(tap("t1", host = null)))
        assertNull(MainActivity.coreWatchFrom(null))
    }
}
