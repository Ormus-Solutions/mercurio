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

package solutions.ormus.logos.home

import org.connectbot.data.TailscaleDevice
import org.connectbot.data.entity.Host
import solutions.ormus.logos.herd.HerdrOpen

/**
 * Where a saved host is: [machine] as the feed or the tailnet names it, the Herdr [session] its
 * post-login attaches (null when it does not launch Herdr), and its [port]. [line] is what the
 * host row reads under the nickname: `lab · work`, or the machine alone; a port other than 22
 * stays on the machine (`lab:2222 · work`), so two hosts of one machine on two ports read apart.
 */
data class HostPlace(val machine: String, val session: String?, val port: Int = 22) {
    val line: String
        get() = (if (port == 22) machine else "$machine:$port") + (session?.let { " · $it" } ?: "")
}

/** The saved hosts' places and their order on the home. Pure. */
object HomeHosts {
    /** A machine's name and the names its host's hostname may carry, each canon ([HerdrOpen.names]). */
    private data class Named(val machine: String, val names: Set<String>)

    /**
     * The place of [host]: the feed's alias whose names ([HomeBoards.hosts]) carry the host's
     * hostname, else the tailnet device whose address, short name or full name is the host's
     * hostname, by its short name; null for a host neither names, or one that is not SSH. The
     * hostname alone decides: a nickname that equals a device's OS hostname says nothing about
     * where the host points. The session is what [HerdrOpen.attached] reads from the post-login.
     */
    fun place(host: Host, devices: List<TailscaleDevice>, feedHosts: Map<String, List<String>>): HostPlace? = place(host, aliases(feedHosts), tailnet(devices))

    /** [place] for every host, by host id, without the hosts that have none; the names are built once. */
    fun places(hosts: List<Host>, devices: List<TailscaleDevice>, feedHosts: Map<String, List<String>>): Map<Long, HostPlace> {
        val aliases = aliases(feedHosts)
        val tailnet = tailnet(devices)
        return hosts.mapNotNull { host -> place(host, aliases, tailnet)?.let { host.id to it } }.toMap()
    }

    /**
     * [hosts] with the placed ones first, by machine, then session, then nickname (case aside);
     * the hosts with no place follow in the order they came (the list's own sort).
     */
    fun order(hosts: List<Host>, places: Map<Long, HostPlace>): List<Host> {
        val (placed, unplaced) = hosts.partition { it.id in places }
        val byPlace = compareBy<Host>({ places.getValue(it.id).machine }, { places.getValue(it.id).session.orEmpty() })
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.nickname }
        return placed.sortedWith(byPlace) + unplaced
    }

    private fun place(host: Host, aliases: List<Named>, tailnet: List<Named>): HostPlace? {
        if (host.protocol != "ssh") return null
        val own = HerdrOpen.canon(host.hostname)
        if (own.isEmpty()) return null
        val machine = aliases.firstOrNull { own in it.names }?.machine
            ?: tailnet.firstOrNull { own in it.names }?.machine
            ?: return null
        return HostPlace(machine, HerdrOpen.attached(host.postLogin), host.port)
    }

    // A blank alias names nothing: the tailnet then names the host, as for one the feed lacks.
    private fun aliases(feedHosts: Map<String, List<String>>): List<Named> = feedHosts.mapNotNull { (alias, names) ->
        alias.takeIf { it.isNotBlank() }?.let { Named(it, HerdrOpen.names(names.joinToString(","), null)) }
    }

    private fun tailnet(devices: List<TailscaleDevice>): List<Named> = devices.mapNotNull { device ->
        device.shortName.takeIf { it.isNotBlank() }?.let { Named(it, HerdrOpen.names(listOfNotNull(device.ipv4, device.shortName, device.dnsName).joinToString(","), null)) }
    }
}
