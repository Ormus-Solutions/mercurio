/*
 * Copied from Termish, https://github.com/farlume/termish (commit ee9e460),
 * composeApp/src/commonMain/kotlin/dev/termish/herdr/HerdrProbe.kt.
 * Mercurio changes: Timber logging; comments translated.
 *
 * MIT License
 *
 * Copyright (c) 2026 Termish Project Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package solutions.ormus.logos.herd

import timber.log.Timber

/**
 * Herdr probe (a connection-level control-plane operation): try
 * `<bin> --version` for each candidate path on the authenticated connection;
 * the first one that prints a valid version wins.
 *
 * The probe uses `--version`, not `api snapshot`: snapshot needs the Herdr
 * daemon to be running (server_not_running exits 1 with error JSON), and the
 * probe runs before Herdr is injected, so a freshly installed host would be
 * misread as "not installed". `--version` does not depend on the daemon; it
 * means "the binary exists and runs". (Whether the daemon is running is
 * HerdrMonitor's job, through snapshot polling and failure backoff.)
 *
 * The result is connection-level knowledge: the path that hit must be used
 * downstream too, or a non-default PATH (~/.local/bin, /opt/homebrew) probes
 * fine but fails to launch.
 */
object HerdrProbe {
    /** Probe result: the binary path that hit. */
    data class Result(
        val bin: String,
    )

    /**
     * Probe whether Herdr is installed.
     *
     * @param runCommand runs one command on the authenticated connection (an
     *   adapter over SSH.execDetailed with a fixed timeout; null means this
     *   candidate failed)
     * @return the hit, or null when every candidate fails (not installed / bad output)
     */
    fun probe(runCommand: (String) -> String?): Result? {
        for (bin in HerdrApi.BIN_CANDIDATES) {
            // Ubuntu's snap herdr (/snap/bin) is sandboxed (home, permissions) and
            // does not work as a workspace host (Herdr itself warns "you are using a
            // snap version"), so skip it and prefer the official script install
            // (~/.local/bin).
            if (isSnapHerdr(bin, runCommand)) {
                Timber.w("skip snap-packaged herdr (bin=%s)", bin)
                continue
            }
            val raw = runCommand("$bin --version") ?: continue
            if (!isVersionOutput(raw)) continue
            Timber.d("herdr probe hit bin=%s", bin)
            return Result(resolveHome(bin, runCommand))
        }
        return null
    }

    /**
     * Whether the PATH candidate resolves to the snap build (/snap/ prefix).
     * Snap commands live in /snap/bin (on Ubuntu's default PATH); the sandbox
     * keeps them out of the user's home and limits the daemon, so a probe hit
     * on it fails later at launch.
     */
    private fun isSnapHerdr(
        bin: String,
        runCommand: (String) -> String?,
    ): Boolean {
        if (bin != "herdr") return false
        val resolved = runCommand("command -v herdr") ?: return false
        return resolved.startsWith("/snap/")
    }

    /**
     * Version output check: `herdr 0.8.0` (the first line starts with herdr and has a version token).
     * Guards against a same-named binary on PATH or a shim that only prints usage.
     */
    internal fun isVersionOutput(raw: String): Boolean = raw
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.matches(Regex("herdr\\s+\\S+.*")) == true

    /**
     * `$HOME` candidate -> absolute path.
     *
     * The probe runs through the remote shell, which expands $HOME, so the
     * literal candidate hits; but a literal path fails downstream where no shell
     * expands it (single quotes, a direct execvp). Resolve the hit to an absolute
     * path first.
     *
     * If echo fails, fall back to the raw candidate (best effort: the probe at
     * least proved the path works).
     */
    private fun resolveHome(
        bin: String,
        runCommand: (String) -> String?,
    ): String {
        if (!bin.startsWith("\$HOME")) return bin
        val home = runCommand("echo \$HOME")?.trim()?.takeIf { it.startsWith("/") }
        return home?.let { bin.replaceFirst("\$HOME", it) } ?: bin
    }
}
