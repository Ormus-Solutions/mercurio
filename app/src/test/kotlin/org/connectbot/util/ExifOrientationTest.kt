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

import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.InputStream

/**
 * [exifOrientation] never lets the metadata reader refuse a photo. The platform reader catches an
 * [OutOfMemoryError] itself from API 28 on; Android 8 (API 26) lets it through, and minSdk is 24, so
 * these tests run on API 26.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class ExifOrientationTest {
    /** A stream that starts with [head] (a file signature) and then runs the reader out of memory. */
    private fun streamThatRunsOutOfMemory(head: ByteArray) = object : InputStream() {
        private var next = 0

        override fun read(): Int = if (next < head.size) head[next++].toInt() and 0xFF else throw OutOfMemoryError("test: the metadata reader ran out of memory")
    }

    @Test
    fun aJpegReadThatRunsOutOfMemoryGivesNormal() {
        val jpegApp1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x00, 0x20)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exifOrientation(streamThatRunsOutOfMemory(jpegApp1)))
    }

    @Test
    fun aReadThatRunsOutOfMemoryAtTheFirstByteGivesNormal() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exifOrientation(streamThatRunsOutOfMemory(ByteArray(0))))
    }
}
