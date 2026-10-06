/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007-2025 Kenny Root
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

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/**
 * Reads the bytes of the most recent screenshot from the device media store.
 *
 * Matches images whose display name starts with "Screenshot" (Android's standard
 * naming), newest first. Returns null if none is found or it cannot be read. The
 * caller must already hold READ_MEDIA_IMAGES (API 33+) or READ_EXTERNAL_STORAGE.
 * Blocking; call off the main thread.
 */
fun loadLatestScreenshot(context: Context): ByteArray? {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
    val args = arrayOf("Screenshot%")
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

    context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            val uri = ContentUris.withAppendedId(collection, id)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                return stream.readBytes()
            }
        }
    }
    return null
}
