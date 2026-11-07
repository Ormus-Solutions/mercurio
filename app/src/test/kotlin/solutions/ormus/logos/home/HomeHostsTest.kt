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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where each saved host is, as the home says it, and the order that gives the rows. */
class HomeHostsTest {
    private val feedHosts = mapOf("lab" to listOf("lab", "lab.example", "192.0.2.10"), "far" to listOf("far.example"))
    private val device = TailscaleDevice(hostName = "Bench", dnsName = "bench.tail.example", ipv4 = "192.0.2.20", online = true, os = "linux")

    private fun host(id: Long, nickname: String, hostname: String, postLogin: String? = null, protocol: String = "ssh") = Host(id = id, nickname = nickname, protocol = protocol, username = "me", hostname = hostname, postLogin = postLogin)

    @Test
    fun place_namesTheMachineByTheFeedsAlias_andTheSessionThePostLoginAttaches() {
        assertEquals(HostPlace("lab", "work"), HomeHosts.place(host(1L, "Lab work", "192.0.2.10", "herdr session attach work\n"), emptyList(), feedHosts))
        assertEquals(HostPlace("lab", "default"), HomeHosts.place(host(2L, "Lab", "Lab.Example.", "herdr\n"), emptyList(), feedHosts))
        // A post-login that does not launch Herdr: the machine alone.
        assertEquals(HostPlace("far", null), HomeHosts.place(host(3L, "Far", "far.example", "tmux a\n"), emptyList(), feedHosts))
        assertEquals("lab · work", HostPlace("lab", "work").line)
        assertEquals("far", HostPlace("far", null).line)
    }

    @Test
    fun place_fallsBackToTheTailnetDevice_byItsShortName() {
        assertEquals(HostPlace("bench", "default"), HomeHosts.place(host(4L, "Bench", "192.0.2.20", "herdr\n"), listOf(device), feedHosts))
        assertEquals(HostPlace("bench", null), HomeHosts.place(host(5L, "Any", "bench.tail.example", null), listOf(device), emptyMap()))
        assertEquals(HostPlace("bench", null), HomeHosts.place(host(5L, "Any", "BENCH", null), listOf(device), emptyMap()))
        // The feed's alias wins over the tailnet's short name.
        val both = mapOf("lab" to listOf("192.0.2.20"))
        assertEquals(HostPlace("lab", null), HomeHosts.place(host(6L, "Both", "192.0.2.20"), listOf(device), both))
    }

    @Test
    fun place_readsTheHostname_notANicknameThatEqualsADevicesName() {
        // The nickname is the device's OS hostname, the hostname points elsewhere: the row must not name the device.
        assertNull(HomeHosts.place(host(10L, "Bench", "203.0.113.9", "herdr\n"), listOf(device), emptyMap()))
        assertNull(HomeHosts.place(host(11L, "bench", "other.example"), listOf(device), feedHosts))
    }

    @Test
    fun place_matchesAliases_caseAndDotAside_andSkipsABlankAlias() {
        val mixed = mapOf("Lab" to listOf("LAB.Example.", "192.0.2.10"), "" to listOf("192.0.2.20"), " " to listOf("bench.tail.example"))
        assertEquals(HostPlace("Lab", null), HomeHosts.place(host(12L, "A", "lab.example"), emptyList(), mixed))
        assertEquals(HostPlace("Lab", null), HomeHosts.place(host(13L, "B", "192.0.2.10"), emptyList(), mixed))
        // A blank alias names nothing: the tailnet names those hosts, or nobody does.
        assertEquals(HostPlace("bench", null), HomeHosts.place(host(14L, "C", "192.0.2.20"), listOf(device), mixed))
        assertNull(HomeHosts.place(host(15L, "D", "bench.tail.example"), emptyList(), mixed))
    }

    @Test
    fun line_carriesThePort_whenItIsNot22() {
        assertEquals("lab:2222 · work", HostPlace("lab", "work", 2222).line)
        assertEquals("lab:2222", HostPlace("lab", null, 2222).line)
        val places = HomeHosts.places(listOf(host(16L, "Lab", "192.0.2.10", "herdr\n").copy(port = 2222)), emptyList(), feedHosts)
        assertEquals("lab:2222 · default", places.getValue(16L).line)
    }

    @Test
    fun place_isNull_forAHostNobodyNames_orNotSsh() {
        assertNull(HomeHosts.place(host(7L, "Else", "else.example", "herdr\n"), listOf(device), feedHosts))
        assertNull(HomeHosts.place(host(8L, "Lab", "192.0.2.10", "herdr\n", protocol = "telnet"), listOf(device), feedHosts))
        assertNull(HomeHosts.place(host(9L, "Lab", "192.0.2.10", "herdr\n"), emptyList(), mapOf("lab" to emptyList())))
    }

    @Test
    fun order_byMachine_thenSession_thenNickname_withTheUnplacedLast_asTheyCame() {
        val hosts = listOf(
            host(1L, "zeta", "else.example"),
            host(2L, "Lab work", "192.0.2.10", "herdr session attach work\n"),
            host(3L, "Lab", "192.0.2.10", "herdr\n"),
            host(4L, "alpha", "other.example"),
            host(5L, "Far", "far.example"),
            host(6L, "lab b", "lab.example", "herdr session attach work\n"),
            host(7L, "Beta", "another.example"),
        )
        val places = HomeHosts.places(hosts, emptyList(), feedHosts)

        assertEquals(setOf(2L, 3L, 5L, 6L), places.keys)
        // The placed first, by machine, session and nickname (case aside); the unplaced keep the order they came in.
        assertEquals(listOf("Far", "Lab", "lab b", "Lab work", "zeta", "alpha", "Beta"), HomeHosts.order(hosts, places).map { it.nickname })
        // Pure: the same lists give the same order, and no place at all leaves the list as it is.
        assertEquals(HomeHosts.order(hosts, places), HomeHosts.order(hosts, places))
        assertEquals(hosts, HomeHosts.order(hosts, emptyMap()))
    }
}
