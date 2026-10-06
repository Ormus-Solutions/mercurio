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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A fake Sun for upload tests: [exec] runs the uploader's real shell commands with
 * `sh` and HOME set to [home]; [upload] stands in for SFTP by writing the bytes
 * to the absolute path. [failCommitOnce] makes the next commit run on "Sun" but
 * lose its reply, the case where the phone dies between Sun's write and its own
 * bookkeeping.
 */
class FakeSun(
    val home: File,
    var fingerprint: String? = HOST_KEY,
) : SunLink {
    companion object {
        /** The fake host's key fingerprint, pinned by the upload tests. */
        const val HOST_KEY = "SHA256:dGVzdC1ob3N0LWtleS1ub3QtYS1yZWFsLW1hY2hpbmU"
    }

    val uploads = mutableListOf<Pair<String, ByteArray>>()
    var failCommitOnce = false

    val dir: File get() = File(home, ".local/share/mercurio-usage")

    override fun hostKeyFingerprint(): String? = fingerprint

    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .apply { environment()["HOME"] = home.absolutePath }
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS))
        if (process.exitValue() != 0) throw IOException("exit ${process.exitValue()}: $err")
        if (failCommitOnce && command.contains("mv -f")) {
            failCommitOnce = false
            throw IOException("reply lost")
        }
        return out
    }

    override fun upload(remotePath: String, bytes: ByteArray) {
        require(remotePath.startsWith(home.absolutePath)) { "upload outside home: $remotePath" }
        uploads += remotePath to bytes
        File(remotePath).writeBytes(bytes)
    }

    fun file(name: String): File = File(dir, name)

    fun lines(name: String): List<String> = file(name).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()

    /** Everything stored on "Sun", as one string, for leak checks. */
    fun allText(): String = dir.listFiles().orEmpty().filter { it.isFile }.joinToString("\n") { it.readText() }
}
