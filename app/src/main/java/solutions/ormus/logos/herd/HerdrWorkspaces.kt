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
 * One workboard key on the host list: [console] of [host], named by the workspace it lands
 * on when its line in the host's list has one, else by its Herdr session.
 */
data class HerdrWorkspace(val host: Host, val console: HerdrConsole) {
    val name: String
        get() = console.workspaceLabel?.takeIf { it.isNotBlank() } ?: console.session
}

/**
 * The workboards the host list offers: what each saved Herdr host put in its console list
 * ([HerdrConsoles.LIST_PATH]), kept on the phone after a connect ([HerdrConsoleCache]).
 */
object HerdrWorkspaces {
    /**
     * One row per kept console of each host in [hosts], in the hosts' order then the list's,
     * for the hosts whose post-login attaches Herdr's default session: only those can open a
     * console on another session ([HerdrWorkspacePicker.offers]). A list kept for a host that
     * is no longer saved shows nothing.
     */
    fun rows(hosts: List<Host>, kept: Map<Long, List<HerdrConsole>>): List<HerdrWorkspace> = hosts
        .filter { HerdrWorkspacePicker.offers(it.postLogin) }
        .flatMap { host -> kept[host.id].orEmpty().map { HerdrWorkspace(host, it) } }
}
