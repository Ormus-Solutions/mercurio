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

import android.content.SharedPreferences
import androidx.core.content.edit
import org.connectbot.util.PreferenceConstants
import java.util.Locale

/** The Jump sheet's Recent list, kept in the preferences, the newest first. */
class JumpRecents(private val prefs: SharedPreferences) {
    fun load(): List<String> = prefs.getString(PreferenceConstants.HERDR_JUMP_RECENT, null)?.lines()?.filter { it.isNotEmpty() }.orEmpty()

    /** Put [key] first; the list keeps [JumpSheet.RECENT_CAP] entries. */
    fun record(key: String) {
        prefs.edit { putString(PreferenceConstants.HERDR_JUMP_RECENT, JumpSheet.record(load(), key).joinToString("\n")) }
    }
}

/** The Jump sheet: the rows of both engines, its Recent list, and its Recent / All / Matches groups. Pure; the sheet draws it. */
object JumpSheet {
    /** How many recent rows are kept. */
    const val RECENT_CAP = 8

    enum class Group { RECENT, ALL, MATCHES }

    data class Item(
        val key: String,
        val space: String,
        val second: String,
        val third: String?,
        val agent: Agent,
        val target: JumpTarget?,
        val core: InboxRow?,
    )

    data class Section(val group: Group, val items: List<Item>)

    /** [recent] with [key] first, once, cut to [RECENT_CAP]. */
    fun record(recent: List<String>, key: String): List<String> = (listOf(key) + recent.filter { it != key }).take(RECENT_CAP)

    /**
     * The rows of one host: its Herdr [targets] (already in the Inbox rank) and its herdr-core
     * [core] rows, all in the Inbox rank ([Agent.rank]). The second line is the tab, then [host].
     */
    fun items(targets: List<JumpTarget>, host: String, core: List<InboxRow>): List<Item> {
        val tui = targets.mapIndexed { i, t ->
            Item(
                key = "${t.session}/${t.tabId}/${t.paneId}",
                space = t.workspaceLabel.ifBlank { t.workspaceNumber.toString() },
                second = "${t.tabLabel.ifBlank { t.tabNumber.toString() }} · $host",
                third = t.titleLine,
                // The place in the given order is the address: a tie keeps the order Herdr gave.
                agent = t.toAgent("tui/%09d".format(i)),
                target = t,
                core = null,
            )
        }
        val cores = core.filter { CoreWatch.target(it.agent) != null }.map { r ->
            Item(
                key = r.key,
                space = r.agent.space,
                second = "${r.agent.tab} · ${r.host}",
                third = r.agent.title?.takeIf { it.isNotBlank() },
                agent = r.agent,
                target = null,
                core = r,
            )
        }
        return (tui + cores).sortedWith(compareBy<Item, Agent>(Agent.RANKING) { it.agent }.thenBy { it.key })
    }

    /**
     * The groups to draw. An empty [query]: Recent (the [recent] keys that still have a row, newest
     * first), then All (the other rows). A query: one Matches group over space, tab, title, agent,
     * session and folder, in the rank. A group with no row is left out.
     */
    fun sections(items: List<Item>, recent: List<String>, query: String): List<Section> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isNotEmpty()) {
            val hit = items.filter { item ->
                val hay = listOfNotNull(item.space, item.second, item.third, item.agent.kind, item.target?.session, item.target?.folder).joinToString(" ").lowercase(Locale.ROOT)
                words.all { it in hay }
            }
            return listOf(Section(Group.MATCHES, hit)).filter { it.items.isNotEmpty() }
        }
        val byKey = items.associateBy { it.key }
        val latest = recent.mapNotNull { byKey[it] }
        val rest = items - latest.toSet()
        return listOf(Section(Group.RECENT, latest), Section(Group.ALL, rest)).filter { it.items.isNotEmpty() }
    }
}
