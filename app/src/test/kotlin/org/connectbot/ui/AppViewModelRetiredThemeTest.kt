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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.data.migration.DatabaseMigrator
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.NotificationPermissionHelper
import org.connectbot.util.PreferenceConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * The theme setting is gone (the app is dark only). A theme an older version saved,
 * light included, is removed at launch and nothing else in the preferences changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppViewModelRetiredThemeTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(
        default = testDispatcher,
        io = testDispatcher,
        main = testDispatcher,
    )
    private lateinit var migrator: DatabaseMigrator
    private lateinit var notificationPermissionHelper: NotificationPermissionHelper
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        migrator = mock()
        notificationPermissionHelper = mock()
        whenever(migrator.isMigrationNeeded()).thenReturn(false)
        whenever(notificationPermissionHelper.isGranted()).thenReturn(true)
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("retired-theme", Context.MODE_PRIVATE)
            .apply { edit(commit = true) { clear() } }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun savedLightTheme_isRemovedAtLaunch() = runTest {
        prefs.edit(commit = true) {
            putString(PreferenceConstants.THEME_MODE, "LIGHT")
            putString(PreferenceConstants.FONT_FAMILY, "JETBRAINS_MONO")
        }

        AppViewModel(migrator, prefs, dispatchers, notificationPermissionHelper)
        advanceUntilIdle()

        assertFalse(prefs.contains(PreferenceConstants.THEME_MODE))
        assertEquals("JETBRAINS_MONO", prefs.getString(PreferenceConstants.FONT_FAMILY, null))
    }

    @Test
    fun noSavedTheme_launchesCleanly() = runTest {
        AppViewModel(migrator, prefs, dispatchers, notificationPermissionHelper)
        advanceUntilIdle()

        assertFalse(prefs.contains(PreferenceConstants.THEME_MODE))
    }
}
