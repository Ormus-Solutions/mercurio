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
import android.graphics.Matrix
import android.media.ExifInterface
import solutions.ormus.logos.herd.PasteUpload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Re-encode an image as JPEG (quality 85) at the largest of [PasteUpload.EDGE_STEPS] that brings it
 * within the limit for the target, or null when it cannot be decoded or no step fits. The photo's EXIF
 * orientation is applied to the pixels (turned or flipped after the scale down), so the result is upright
 * and carries no orientation tag. The result has no transparency. Blocking; call off the main thread.
 */
fun shrinkPhoto(bytes: ByteArray, coreTarget: Boolean): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val upright = orientationMatrix(exifOrientation(ByteArrayInputStream(bytes)))
    for (edge in PasteUpload.EDGE_STEPS) {
        val options = BitmapFactory.Options().apply {
            inSampleSize = PasteUpload.sampleSize(bounds.outWidth, bounds.outHeight, edge)
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        val turned = turnUpright(bitmap, upright) ?: return null
        val out = ByteArrayOutputStream()
        turned.compress(Bitmap.CompressFormat.JPEG, 85, out)
        turned.recycle()
        val encoded = out.toByteArray()
        if (PasteUpload.fits(encoded.size.toLong(), coreTarget)) return encoded
    }
    return null
}

/** The EXIF orientation read from [input] (1 to 8), or 1 when it has none or its metadata cannot be read. */
internal fun exifOrientation(input: InputStream): Int = try {
    ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (_: IOException) {
    ExifInterface.ORIENTATION_NORMAL
} catch (_: RuntimeException) {
    ExifInterface.ORIENTATION_NORMAL
} catch (_: OutOfMemoryError) {
    // Android 8 and older let it through from the platform reader; send the photo unturned, do not refuse it.
    ExifInterface.ORIENTATION_NORMAL
}

/** The matrix that makes a photo with this EXIF [orientation] upright, or null when it needs none. */
private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }

    ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }

    ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }

    ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
        postRotate(90f)
        postScale(-1f, 1f)
    }

    ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }

    ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
        postRotate(-90f)
        postScale(-1f, 1f)
    }

    ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }

    else -> null
}

/** [bitmap] with [matrix] applied (it is recycled), [bitmap] itself for a null matrix, or null when memory runs out. */
private fun turnUpright(bitmap: Bitmap, matrix: Matrix?): Bitmap? {
    if (matrix == null) return bitmap
    val turned = try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (_: OutOfMemoryError) {
        null
    }
    if (turned !== bitmap) bitmap.recycle()
    return turned
}
