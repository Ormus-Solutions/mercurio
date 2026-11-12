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

package solutions.ormus.logos.herd

import org.connectbot.transport.CommandOutput
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Where a pasted photo ended up: the [path] to give the agent, or the [reason] it did not get there. */
sealed interface PasteOutcome {
    data class Landed(val path: String) : PasteOutcome
    data class Failed(val reason: String) : PasteOutcome
}

/** What to do with a photo of a given size before any upload. */
enum class SizeDecision { Send, Shrink }

/**
 * The compose bar's photo paste, as pure builders and readers (the console runs them over its exec and
 * SFTP channels). A photo is staged in a private folder of the login user, never a shared `/tmp` one,
 * and the path the agent reads is only ever one a command printed.
 */
object PasteUpload {
    private const val DIR = "\$HOME/.cache/mercurio-pastes"

    /** How long the folder check may take. */
    const val DIR_TIMEOUT_MS = 10_000L

    /**
     * The shell script that makes [DIR] with mode 700 when it is missing and prints its path only when it
     * is a real directory (not a link) owned by the user and writable by nobody else. Anything else
     * exits 1 with a reason on stderr and prints nothing, so a folder another user made first is never used.
     */
    fun dirScript(): String = "[ -n \"\$HOME\" ] || { echo \"HOME is not set on the host\" >&2; exit 1; }; " +
        "d=\"$DIR\"; " +
        "{ mkdir -p \"\$HOME/.cache\" && { [ -e \"\$d\" ] || [ -L \"\$d\" ] || mkdir -m 700 \"\$d\"; } && " +
        "[ -d \"\$d\" ] && [ ! -L \"\$d\" ] && [ -O \"\$d\" ] && " +
        "case \"\$(ls -ld \"\$d\")\" in d????[!w]??[!w]*) true;; *) false;; esac; } && " +
        "printf '%s\\n' \"\$d\" || " +
        "{ echo \"\$d is not a private folder owned by you\" >&2; exit 1; }"

    /** The exec command for [dirScript]. */
    fun dirCommand(): String = HerdrApi.loginShell(dirScript())

    /** The private folder [dirScript] printed, or why there is none. */
    fun dirOf(output: CommandOutput?): PasteOutcome {
        val code = output?.exitCode ?: return PasteOutcome.Failed("the host did not answer; no photo was sent")
        if (code != 0) {
            val why = output.stderr.trim().ifEmpty { "exit $code" }
            return PasteOutcome.Failed("no private folder on the host: $why")
        }
        val path = output.stdout.lines().map { it.trim() }.lastOrNull { it.startsWith("/") }
            ?: return PasteOutcome.Failed("the host named no folder for the photo")
        return PasteOutcome.Landed(path)
    }

    /** A file name for a photo of [mime] taken at [now]. */
    fun fileName(mime: String?, now: Long): String {
        val ext = when (mime?.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/avif" -> "avif"
            else -> "img"
        }
        return "photo-$now.$ext"
    }

    /** The path `hc put` printed for a core terminal; a failure never falls back to the uploaded path. */
    fun putOutcome(output: CommandOutput?): PasteOutcome = CoreSend.landedPath(output)?.let { PasteOutcome.Landed(it) } ?: PasteOutcome.Failed(CoreSend.putFailure(output))

    /** The most bytes read from a picked photo; past it the photo counts as unreadable. */
    const val MAX_READ_BYTES = 64L * 1024 * 1024

    /** The most bytes sent to a plain host (a core terminal is held to [CoreSend.MAX_PUT_BYTES]). */
    private const val MAX_PLAIN_BYTES = 32L * 1024 * 1024

    /** The long edges, in pixels, a too large photo is scaled down to, one after another until it fits. */
    val EDGE_STEPS = listOf(2560, 1600, 1024)

    /** The most bytes a photo may have for the target: `hc put`'s limit for a core terminal. */
    fun limitFor(coreTarget: Boolean): Long = if (coreTarget) CoreSend.MAX_PUT_BYTES else MAX_PLAIN_BYTES

    /** Whether a photo of [size] bytes goes as it is or is scaled down first. */
    fun decide(size: Long, coreTarget: Boolean): SizeDecision = if (fits(size, coreTarget)) SizeDecision.Send else SizeDecision.Shrink

    /** Whether [size] bytes is within the limit for the target. */
    fun fits(size: Long, coreTarget: Boolean): Boolean = size <= limitFor(coreTarget)

    /** The power-of-two decode step that brings the long edge of a [width] x [height] image to [maxEdge] or less. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxEdge) sample *= 2
        return sample
    }

    /** All bytes of [input], or null as soon as more than [max] are read (the rest is not read). */
    fun readCapped(input: InputStream, max: Long = MAX_READ_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
    }
}
