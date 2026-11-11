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

package solutions.ormus.logos.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.connectbot.data.entity.Host
import org.connectbot.util.HostConstants
import solutions.ormus.logos.herd.CoreFacts
import solutions.ormus.logos.herd.CoreNotices
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.HerdAttention
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.NeedsYouKind

/**
 * One push from a Herdr host's watcher (`host/mercurio-push-watch`, schema `v: 1`):
 * a pane's agent changed state. Ids and labels only, never pane text.
 *
 * ```json
 * {"v":1,"event":"pane.agent_status_changed","state":"blocked","host":"Sun","session":"default",
 *  "pane_id":"w1:p3","agent":"claude","workspace_id":"w1","workspace":"mercurio","tab_id":"w1:t2",
 *  "tab":"build","ts":1791214536}
 * ```
 *
 * A herdr-core agent's push (`mercurio-push-watch --core`) has session [CoreNotices.SESSION], a terminal
 * address (`t15`, `box/t7`) as its pane id, and the `kind` (why it blocks) and `turn` that Approve and Deny
 * are guarded on. Still ids and labels only.
 */
@Serializable
data class HerdPushAlert(
    val v: Int = 0,
    val event: String = "",
    val state: HerdrAgentStatus = HerdrAgentStatus.UNKNOWN,
    /** The Herdr host's hostname, as `socket.gethostname()` gives it. */
    val host: String = "",
    val session: String? = null,
    @SerialName("pane_id") val paneId: String = "",
    val agent: String? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
    val workspace: String? = null,
    @SerialName("tab_id") val tabId: String? = null,
    val tab: String? = null,
    val ts: Long = 0,
    /** Core only: why the agent blocks (`permission`, `question`, `auth`). */
    val kind: String? = null,
    /** Core only: the agent's turn when it blocked or finished. */
    val turn: Int? = null,
) {
    /** The notice the live monitor would post for this change; null for states that get none. */
    fun notice(): HerdAttention.Notice? {
        val core = session == CoreNotices.SESSION
        val agent = HerdrAgentInfo(
            paneId = paneId,
            workspaceId = workspaceId.orEmpty(),
            tabId = tabId.orEmpty(),
            agent = agent?.ifBlank { null },
            agentStatus = state,
            core = if (core) CoreFacts(needsOf(kind), turn) else null,
        )
        val herdrSession = session ?: HerdrCommands.DEFAULT_SESSION
        return when (state) {
            HerdrAgentStatus.BLOCKED -> HerdAttention.Notice.NeedsYou(agent, workspace?.ifBlank { null }, tab?.ifBlank { null }, since = ts.takeIf { it > 0 }?.times(1000), session = herdrSession)
            HerdrAgentStatus.DONE -> HerdAttention.Notice.Finished(agent, workspace?.ifBlank { null }, tab?.ifBlank { null }, session = herdrSession)
            else -> null
        }
    }

    companion object {
        const val VERSION = 1
        const val EVENT = "pane.agent_status_changed"

        // Herdr pane ids and session names, as HerdApproval and HerdrSessions check them.
        // Pane ids repeat across sessions, so the alert's session travels with its pane.
        private val ID = Regex("[A-Za-z0-9_.:-]+")

        private fun needsOf(kind: String?): NeedsYouKind? = when (kind) {
            "permission" -> NeedsYouKind.PERMISSION
            "question" -> NeedsYouKind.QUESTION
            "auth" -> NeedsYouKind.AUTH
            else -> null
        }

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /** The alert in [content], or null when it is not a v1 alert this phone can act on. */
        fun parse(content: ByteArray): HerdPushAlert? {
            val alert = runCatching { json.decodeFromString<HerdPushAlert>(content.decodeToString()) }.getOrNull() ?: return null
            // A core terminal's address may carry a machine (`box/t7`); a Herdr pane id may not.
            val core = alert.session == CoreNotices.SESSION
            val usable = alert.v == VERSION &&
                alert.event == EVENT &&
                alert.host.isNotBlank() &&
                (if (core) CoreNotices.isAddress(alert.paneId) else ID.matches(alert.paneId)) &&
                (alert.session == null || core || ID.matches(alert.session))
            return alert.takeIf { usable }
        }

        /**
         * The saved SSH host [name] (the alert's hostname) is: by nickname, then hostname,
         * then short name (`sun` for `sun.example.ts.net`), ignoring case. Among equals a host
         * whose post-login command runs Herdr wins. Null for a host this phone does not know.
         */
        fun matchHost(name: String, hosts: List<Host>): Host? {
            val ssh = hosts.filter { it.protocol == "ssh" }
            val short = shortName(name)
            val tiers = listOf<(Host) -> Boolean>(
                { it.nickname.equals(name, ignoreCase = true) },
                { it.hostname.equals(name, ignoreCase = true) },
                { it.nickname.equals(short, ignoreCase = true) || shortName(it.hostname).equals(short, ignoreCase = true) },
            )
            for (tier in tiers) {
                val found = ssh.filter(tier)
                if (found.isNotEmpty()) return found.firstOrNull { Herd.runsHerdr(it) } ?: found.first()
            }
            return null
        }

        private fun shortName(name: String): String = if (HostConstants.isIpAddress(name)) name else name.substringBefore('.')
    }
}
