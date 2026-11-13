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

package org.connectbot

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The permissions the oss debug build requests, read from its merged manifest through the package
 * manager (the closest the unit test setup gets to the merged manifest file). Photos come in through the
 * system photo picker, which needs no permission, so no media or storage read permission may return.
 */
@RunWith(AndroidJUnit4::class)
class ManifestPermissionsTest {
    private fun requested(): Set<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions.orEmpty().toSet()
    }

    @Test
    fun theMergedManifestStillRequestsThePermissionsTheAppUses() {
        // Guards the two tests below: an empty list would pass them without proving anything.
        val permissions = requested()
        assertTrue(permissions.toString(), "android.permission.INTERNET" in permissions)
        assertTrue(permissions.toString(), "android.permission.RECORD_AUDIO" in permissions)
    }

    @Test
    fun theMergedManifestRequestsNoMediaImagesPermission() {
        assertFalse(requested().toString(), "android.permission.READ_MEDIA_IMAGES" in requested())
    }

    // The permission is declared with maxSdkVersion 32, so it only shows when the test runs on API 32 or older.
    @Test
    @Config(sdk = [30])
    fun theMergedManifestRequestsNoReadExternalStoragePermission() {
        assertFalse(requested().toString(), "android.permission.READ_EXTERNAL_STORAGE" in requested())
    }
}
