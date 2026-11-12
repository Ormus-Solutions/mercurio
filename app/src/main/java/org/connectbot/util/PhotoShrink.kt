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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import solutions.ormus.logos.herd.PasteUpload
import java.io.ByteArrayOutputStream

/**
 * Re-encode an image as JPEG (quality 85) at the largest of [PasteUpload.EDGE_STEPS] that brings it
 * within the limit for the target, or null when it cannot be decoded or no step fits. The result has no
 * transparency and no EXIF orientation. Blocking; call off the main thread.
 */
fun shrinkPhoto(bytes: ByteArray, coreTarget: Boolean): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    for (edge in PasteUpload.EDGE_STEPS) {
        val options = BitmapFactory.Options().apply {
            inSampleSize = PasteUpload.sampleSize(bounds.outWidth, bounds.outHeight, edge)
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        val encoded = out.toByteArray()
        if (PasteUpload.fits(encoded.size.toLong(), coreTarget)) return encoded
    }
    return null
}
