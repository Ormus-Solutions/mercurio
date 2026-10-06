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

package org.connectbot.usage

import java.io.File

/**
 * Owner-only file helpers for the usage and wish-list store. The store lives in the
 * app's private files directory (never external storage, never the backed-up
 * preferences), and every file and directory it writes is readable and writable
 * by the app alone.
 */
internal object PrivateFiles {
    fun ensureDir(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        restrict(dir, executable = true)
        return dir
    }

    /** Drop every group and other permission bit, keep the owner's. */
    fun restrict(file: File, executable: Boolean = file.isDirectory) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (executable) file.setExecutable(true, true)
    }

    /** Replace [file] with [text] via a temp file and rename, so a crash never leaves half a file. */
    fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        restrict(tmp)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        restrict(file)
    }

    fun appendLine(file: File, line: String) {
        val existed = file.exists()
        file.appendText(line + "\n")
        if (!existed) restrict(file)
    }
}
