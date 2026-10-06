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

package org.connectbot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.connectbot.data.entity.Host
import org.json.JSONException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric runner so the platform org.json classes are real, not stubs. */
@RunWith(AndroidJUnit4::class)
class TailscaleStatusTest {

    @Test
    fun parse_keepsOwnedAndTagged_hidesOtherUsers_sortsOnlineFirst() {
        val devices = TailscaleStatus.parse(FIXTURE)

        assertThat(devices.map { it.shortName }).containsExactly("server", "sun", "nas", "pi")
    }

    @Test
    fun parse_readsFields_stripsTrailingDot_picksIpv4() {
        val sun = TailscaleStatus.parse(FIXTURE).single { it.hostName == "Sun" }

        assertThat(sun.dnsName).isEqualTo("sun.tail1234.ts.net")
        assertThat(sun.ipv4).isEqualTo("100.100.1.1")
        assertThat(sun.online).isTrue()
        assertThat(sun.os).isEqualTo("linux")
    }

    @Test
    fun parse_excludesSelf() {
        val names = TailscaleStatus.parse(FIXTURE).map { it.hostName }

        assertThat(names).doesNotContain("Phone")
    }

    @Test
    fun parse_organizationTailnet_keepsDevicesOfSelfOwner() {
        // CurrentTailnet.Name is a domain here, so no LoginName equals it.
        val json = FIXTURE.replace("\"Name\": \"me@example.com\"", "\"Name\": \"example.com\"")

        val names = TailscaleStatus.parse(json).map { it.shortName }

        assertThat(names).containsExactly("server", "sun", "nas", "pi")
    }

    @Test
    fun parse_selfIsTagged_keepsDevicesOwnedByTailnetLogin() {
        // Self owned by tagged-devices, so only CurrentTailnet.Name marks "mine".
        val json = FIXTURE.replace("\"UserID\": 1, \"Online\": true }", "\"UserID\": 3, \"Online\": true }")

        val names = TailscaleStatus.parse(json).map { it.shortName }

        assertThat(names).containsExactly("server", "sun", "nas", "pi")
    }

    @Test
    fun parse_portedSottoRules_dropMullvad_phonesLast_offlineByLastSeen() {
        val devices = TailscaleStatus.parse(SOTTO_RULES_FIXTURE)

        // Online computers by name, then offline by most recently seen, then phones.
        assertThat(devices.map { it.shortName }).containsExactly("alpha", "zulu", "recent", "stale", "pixel")
        assertThat(devices.map { it.dnsName }).noneMatch { it.endsWith(".mullvad.ts.net") }
        assertThat(devices.single { it.shortName == "zulu" }.tailscaleSsh).isTrue()
        assertThat(devices.single { it.shortName == "alpha" }.tailscaleSsh).isFalse()
        assertThat(devices.single { it.shortName == "pixel" }.osLabel).isEqualTo("Android")
        // Tailscale's zero time means it never recorded a last-seen time.
        assertThat(devices.single { it.shortName == "alpha" }.lastSeen).isNull()
    }

    @Test
    fun parse_stoppedBackend_throwsSoTheCacheIsKept() {
        val json = SOTTO_RULES_FIXTURE.replace("\"BackendState\": \"Running\"", "\"BackendState\": \"Stopped\"")

        assertThrows(IllegalStateException::class.java) { TailscaleStatus.parse(json) }
    }

    @Test
    fun machineUsers_parsesNameUserLinesByShortNameOrHostname() {
        val text = " Box = alice \nlaptop=bob\n\nnot a line\n=nobody\nempty=\n"
        val box = TailscaleDevice("box", "box.tail1234.ts.net", "100.64.0.9", true, "linux")
        val laptop = TailscaleDevice("Laptop", "", "100.64.0.10", true, "macOS")
        val other = TailscaleDevice("other", "other.tail1234.ts.net", "100.64.0.11", true, "linux")

        assertThat(MachineUsers.parse(text)).containsOnly(entry("box", "alice"), entry("laptop", "bob"))
        assertThat(MachineUsers.forDevice(text, box)).isEqualTo("alice")
        assertThat(MachineUsers.forDevice(text, laptop)).isEqualTo("bob")
        assertThat(MachineUsers.forDevice(text, other)).isNull()
        // The default setting is empty: no machine has a known user.
        assertThat(MachineUsers.forDevice("", box)).isNull()
    }

    @Test
    fun parse_withoutPeers_isEmpty() {
        assertThat(TailscaleStatus.parse("""{"Self": {"HostName": "Phone"}}""")).isEmpty()
    }

    @Test
    fun parse_notJson_throws() {
        assertThrows(JSONException::class.java) { TailscaleStatus.parse("tailscale: command not found") }
    }

    @Test
    fun matches_savedHostByAddressOrName() {
        val sun = TailscaleStatus.parse(FIXTURE).single { it.hostName == "Sun" }

        assertThat(sun.matches(sshHost(hostname = "100.100.1.1"))).isTrue()
        assertThat(sun.matches(sshHost(hostname = "SUN"))).isTrue()
        assertThat(sun.matches(sshHost(hostname = "sun.tail1234.ts.net."))).isTrue()
        assertThat(sun.matches(sshHost(nickname = "Sun", hostname = "192.0.2.180"))).isTrue()
        assertThat(sun.matches(sshHost(nickname = "moon", hostname = "100.100.1.2"))).isFalse()
        assertThat(sun.matches(sshHost(hostname = "100.100.1.1").copy(protocol = "telnet"))).isFalse()
    }

    private fun sshHost(nickname: String = "", hostname: String) = Host(nickname = nickname, protocol = "ssh", username = "alice", hostname = hostname)

    companion object {
        /**
         * Synthetic status in the shape measured on a real host: owned online,
         * owned offline, tagged with Tags, tagged-devices owner without Tags,
         * another user's shared device, and Self.
         */
        val FIXTURE = """
            {
              "CurrentTailnet": { "Name": "me@example.com" },
              "Self": { "HostName": "Phone", "DNSName": "phone.tail1234.ts.net.", "UserID": 1, "Online": true },
              "Peer": {
                "nodekey:a": {
                  "HostName": "Sun", "DNSName": "sun.tail1234.ts.net.",
                  "TailscaleIPs": ["fd7a:115c:a1e0::1", "100.100.1.1"],
                  "Online": true, "OS": "linux", "UserID": 1
                },
                "nodekey:b": {
                  "HostName": "NAS", "DNSName": "nas.tail1234.ts.net.",
                  "TailscaleIPs": ["100.100.1.3"], "Online": false, "OS": "linux", "UserID": 1
                },
                "nodekey:c": {
                  "HostName": "server", "DNSName": "server.tail1234.ts.net.",
                  "TailscaleIPs": ["100.100.1.4"], "Online": true, "OS": "linux", "UserID": 3,
                  "Tags": ["tag:server"]
                },
                "nodekey:d": {
                  "HostName": "raspberrypi", "DNSName": "pi.tail1234.ts.net.",
                  "TailscaleIPs": ["100.64.0.9"], "Online": false, "OS": "linux", "UserID": 3
                },
                "nodekey:e": {
                  "HostName": "friends-laptop", "DNSName": "friends-laptop.tail1234.ts.net.",
                  "TailscaleIPs": ["100.64.0.10"], "Online": true, "OS": "macOS", "UserID": 2
                }
              },
              "User": {
                "1": { "LoginName": "me@example.com", "DisplayName": "Me" },
                "2": { "LoginName": "friend@example.org", "DisplayName": "Friend" },
                "3": { "LoginName": "tagged-devices", "DisplayName": "Tagged Devices" }
              }
            }
        """.trimIndent()
    }
}

private val SOTTO_RULES_FIXTURE = """
{
  "BackendState": "Running",
  "CurrentTailnet": { "Name": "me@example.com" },
  "Self": { "HostName": "Phone", "UserID": 1 },
  "User": {
    "1": { "LoginName": "me@example.com" },
    "3": { "LoginName": "tagged-devices" }
  },
  "Peer": {
    "a": { "HostName": "zulu", "DNSName": "zulu.tail1234.ts.net.", "TailscaleIPs": ["100.64.0.1"], "Online": true, "OS": "linux", "UserID": 3, "Tags": ["tag:server"], "sshHostKeys": ["ssh-ed25519 AAAA"] },
    "b": { "HostName": "alpha", "DNSName": "alpha.tail1234.ts.net.", "TailscaleIPs": ["100.64.0.2"], "Online": true, "OS": "linux", "UserID": 1, "LastSeen": "0001-01-01T00:00:00Z" },
    "c": { "HostName": "stale", "DNSName": "stale.tail1234.ts.net.", "TailscaleIPs": ["100.64.0.3"], "Online": false, "OS": "linux", "UserID": 1, "LastSeen": "2026-08-01T10:00:00Z" },
    "d": { "HostName": "recent", "DNSName": "recent.tail1234.ts.net.", "TailscaleIPs": ["100.64.0.4"], "Online": false, "OS": "linux", "UserID": 1, "LastSeen": "2026-10-01T10:00:00Z" },
    "e": { "HostName": "localhost", "DNSName": "pixel.tail1234.ts.net.", "TailscaleIPs": ["100.64.0.5"], "Online": true, "OS": "android", "UserID": 1 },
    "f": { "HostName": "us-nyc-wg-301", "DNSName": "us-nyc-wg-301.mullvad.ts.net.", "TailscaleIPs": ["100.64.0.6"], "Online": true, "OS": "linux", "UserID": 3, "Tags": ["tag:mullvad-exit-node"] }
  }
}
"""
