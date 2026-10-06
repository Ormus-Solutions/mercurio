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

package org.connectbot.ui.commands

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import org.connectbot.ui.theme.ConnectBotTheme
import java.io.File

/**
 * Save a Robolectric render of [activity]'s window to build/outputs/renders/[name],
 * for review beside the emulator screenshots. Needs native graphics
 * (@GraphicsMode(NATIVE)); a failed render writes nothing and fails no test.
 */
internal fun saveRender(activity: Activity, name: String) {
    runCatching {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/outputs/renders").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }.onFailure { System.err.println("render $name not saved: $it") }
}

/** The app theme on the sheet's midnight surface, as the menus sit in the app. */
@Composable
internal fun MenuTheme(content: @Composable () -> Unit) {
    ConnectBotTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) { content() }
    }
}
