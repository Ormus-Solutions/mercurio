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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The machines Herdr was seen on, kept in the preferences as `host:port:user` keys ([CoreMachines.keyOf]).
 * The Inbox and Spaces log in in the background only to a Herdr machine: one a saved host marks (its
 * post-login starts Herdr), or one in this list. A machine joins when a console to it found Herdr, and
 * leaves when its herdr-core read says it is not installed, when the saved host that marked it is edited to
 * start something else, or when no saved host is left for it. No database: nothing to migrate.
 */
@Singleton
class HerdrHosts(
    private val load: () -> Set<String>,
    private val store: (Set<String>) -> Unit,
    // The machines a saved host marked at the last [reconcile]: how an edit that removes the Herdr start is seen,
    // also after the app restarted. Held in memory when no storage is given.
    private val loadMarked: (() -> Set<String>)? = null,
    private val storeMarked: ((Set<String>) -> Unit)? = null,
) {
    @Inject
    constructor(prefs: SharedPreferences) : this(
        { prefs.getStringSet(PREF_KEY, null).orEmpty().toSet() },
        { next -> prefs.edit { putStringSet(PREF_KEY, next) } },
        { prefs.getStringSet(PREF_MARKED, null).orEmpty().toSet() },
        { next -> prefs.edit { putStringSet(PREF_MARKED, next) } },
    )

    private val lock = Any()
    private var markedHeld = emptySet<String>()

    private fun marked(): Set<String> = loadMarked?.invoke() ?: markedHeld

    private fun setMarked(next: Set<String>) {
        if (storeMarked != null) storeMarked.invoke(next) else markedHeld = next
    }

    /**
     * Bring the list in line with the machines of the saved hosts and the consoles now, and give the keys to read. [all]
     * are the keys of every machine a saved or live host has; [marked] those a saved host marks; [live] those with a
     * console that found Herdr. A key no host has is dropped (a deleted host). A key that was marked at the last call and
     * is not now was edited to start something else: the edit wins, so the key leaves the list and is not learned
     * again, and an open console to it keeps it in the keys to read only until that console closes. Other [live] keys
     * join the list. Writes only what changed.
     */
    fun reconcile(all: Set<String>, marked: Set<String>, live: Set<String>): Set<String> = synchronized(lock) {
        val before = load()
        val wasMarked = marked()
        val edited = wasMarked.filterTo(HashSet()) { it in all && it !in marked }
        val next = before.filterTo(HashSet()) { it in all && it !in edited }
        next.addAll(live.filter { it in all && it !in edited })
        if (next != before) store(next)
        // An edit stays remembered while a console to the machine is open, so it wins when that console closes.
        val remembered = marked.filterTo(HashSet()) { it in all }.also { it.addAll(edited.filter { key -> key in live }) }
        if (remembered != wasMarked) setMarked(remembered)
        next + edited.filter { it in live }
    }

    /** The machine keys Herdr was seen on. */
    fun learned(): Set<String> = synchronized(lock) { load() }

    /** [key] was seen running Herdr; a key already here is not written again. */
    fun learn(key: String) = synchronized(lock) {
        val now = load()
        if (key !in now) store(now + key)
    }

    /** Herdr is not on [key] any more. */
    fun forget(key: String) = synchronized(lock) {
        val now = load()
        if (key in now) store(now - key)
    }

    companion object {
        /** The preference that holds the list, a string set. */
        const val PREF_KEY = "herdrMachines"

        /** The preference that holds the machines a saved host marked at the last [reconcile], a string set. */
        const val PREF_MARKED = "herdrMarked"

        /** A list that lives only as long as it is held: tests, and a [CoreLinks] made without preferences. */
        fun inMemory(initial: Set<String> = emptySet()): HerdrHosts {
            var held = initial
            return HerdrHosts({ held }, { held = it })
        }
    }
}
