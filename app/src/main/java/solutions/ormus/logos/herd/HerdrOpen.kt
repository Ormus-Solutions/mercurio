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

import org.connectbot.data.entity.Host

/**
 * Where a tap on an agent lands. A launcher's tile knows the agent's machine, its Herdr
 * session and its pane; it does not know how this phone names that machine's hosts, nor which
 * of them attaches that session. So it sends the machine's names on the tailnet beside the
 * session ([EXTRA_HERD_HOSTS]), and the host is picked here from the saved hosts alone: the
 * copy whose post-login attaches the session, else a plain `herdr` host that can open a
 * console for it. Pure, so the rule is tested without a phone.
 */
object HerdrOpen {
    /**
     * Extra beside `EXTRA_HERD_SESSION`: how the agent's machine is named on the tailnet, as
     * `name,dns,ip` (any order, any of them), one of which a saved host's hostname carries.
     * Only an intent that carries it goes through [resolve]: a Herdr notification's does not.
     */
    const val EXTRA_HERD_HOSTS = "solutions.ormus.logos.extra.HERD_HOSTS"

    /**
     * What to open: [host], and the console to open on it for the session, or null when the
     * host's own console attaches it (its post-login does).
     */
    data class Target(val host: Host, val console: HerdrConsole?)

    /**
     * The Herdr session [postLogin] attaches: the default for a plain `herdr` launch, the named
     * one for `herdr session attach <name>` or `herdr [options] --session <name> ...`; null when
     * it does not launch Herdr, attaches another machine's (`--remote`), or names a session Herdr
     * could not have given.
     */
    fun attached(postLogin: String?): String? {
        val command = postLogin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!HerdrWorkspacePicker.HERDR_LAUNCH.containsMatchIn(command) || command.contains("--remote")) return null
        val named = ATTACH.find(command) ?: SESSION_FLAG.find(command)
        val session = named?.let { it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { it.groupValues[3] } }
            ?: return HerdrCommands.DEFAULT_SESSION
        return session.takeIf(HerdrSessions::isName)
    }

    /**
     * The Herdr session a console shows now: what its terminal attached when it could choose (a
     * console opened for a session, or a host whose plain `herdr` post-login lets the picker and
     * the Jump to list attach one), else what its post-login attaches, unless a jump took it to
     * another session since. [attached] is the terminal's own record of its attach; null while
     * the console has no Herdr session yet.
     */
    fun shown(console: HerdrConsole?, postLogin: String?, attached: String?): String? {
        if (console != null || HerdrWorkspacePicker.offers(postLogin)) return attached
        val own = attached(postLogin) ?: return null
        return attached?.takeIf { it != HerdrCommands.DEFAULT_SESSION } ?: own
    }

    /**
     * The names in the extra's [value] (comma-separated) and the URI's [uriHost], each trimmed,
     * lower-cased and without a trailing dot; nothing for nothing.
     */
    fun names(value: String?, uriHost: String?): Set<String> = (value.orEmpty().split(',') + listOfNotNull(uriHost))
        .map(::canon)
        .filter { it.isNotEmpty() }
        .toSet()

    /**
     * The host to open [session] on, among the SSH [hosts] whose hostname is one of [names],
     * with the URI's [user] when it names one and its [port] when it is above 0 (another
     * account or another port of the machine is another Herdr): the one whose post-login
     * attaches [session] (its own console shows it), else a plain `herdr` host, which opens a
     * console for the session beside its own. Among several, one with a connection up
     * ([connected], by host id) first, then the one connected last. Null when no saved host
     * is on that machine, or none runs Herdr there: the caller then opens the URI as it always did.
     */
    fun resolve(hosts: List<Host>, session: String, names: Set<String>, user: String? = null, port: Int = 0, connected: Set<Long> = emptySet()): Target? {
        if (names.isEmpty() || !HerdrSessions.isName(session)) return null
        val there = hosts.filter {
            it.protocol == "ssh" && canon(it.hostname) in names &&
                (user.isNullOrBlank() || it.username == user) && (port <= 0 || it.port == port)
        }
        val order = compareBy<Host>({ it.id in connected }, { it.lastConnect })
        there.filter { attached(it.postLogin) == session }.maxWithOrNull(order)?.let { return Target(it, null) }
        val plain = there.filter { HerdrWorkspacePicker.offers(it.postLogin) && attached(it.postLogin) == HerdrCommands.DEFAULT_SESSION }
            .maxWithOrNull(order) ?: return null
        return Target(plain, HerdrConsole(session))
    }

    /**
     * The host to save for a tap that [resolve] found nothing for: the URI's account ([user]) on its [hostname]
     * and [port], with a plain `herdr` post-login, so a console for [session] opens on it now and every later tap
     * resolves to it. Named by the URI's fragment ([nickname]), else the host's first label (an address whole),
     * and kept apart from the [hosts] saved already (` 2`, ` 3`, ... after it: a nickname is unique). Null for a
     * tap that is not SSH ([scheme]), names no account or host, or asks for a session Herdr could not have given:
     * the caller then opens the URI as it always did.
     */
    fun newHost(hosts: List<Host>, scheme: String?, session: String, user: String?, hostname: String?, port: Int, nickname: String?, now: Long): Host? {
        if (scheme != "ssh" || !HerdrSessions.isName(session)) return null
        val account = user?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = hostname?.trim()?.removeSurrounding("[", "]")?.removeSuffix(".")?.takeIf { it.isNotEmpty() } ?: return null
        val wanted = nickname?.trim()?.takeIf { it.isNotEmpty() } ?: if (ADDRESS.matches(name)) name else name.substringBefore('.')
        val taken = hosts.map { it.nickname.trim() }.toSet()
        val free = generateSequence(1) { it + 1 }.map { if (it == 1) wanted else "$wanted $it" }.first { it !in taken }
        return Host(
            nickname = free,
            protocol = "ssh",
            username = account,
            hostname = name,
            port = if (port > 0) port else 22,
            postLogin = PLAIN_LAUNCH,
            lastConnect = now,
        )
    }

    /** The post-login of a host saved by a tap: Herdr, its default session, so a console for any session opens beside. */
    const val PLAIN_LAUNCH = "herdr\n"

    /** An IPv4 or IPv6 address: a host named by one is named by the whole of it, not by a first label. */
    private val ADDRESS = Regex("""[0-9.]+|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*""")

    /** [name] as the saved hosts are matched: trimmed, lower-cased, without a trailing dot. */
    internal fun canon(name: String): String = name.trim().removeSuffix(".").lowercase()

    // A session name as a shell word: bare up to a space or a shell break, or in single or double quotes.
    private const val NAME = """(?:'([^']+)'|"([^"]+)"|([^\s;&|)'"]+))"""

    // `herdr session attach <name>`.
    private val ATTACH = Regex("""(?<![\w.-])herdr\s+session\s+attach\s+$NAME""")

    // `herdr [options] --session <name>` or `--session=<name>`, before the subcommand.
    private val SESSION_FLAG = Regex("""(?<![\w.-])herdr(?:\s+-\S+)*\s+--session[=\s]+$NAME""")
}
