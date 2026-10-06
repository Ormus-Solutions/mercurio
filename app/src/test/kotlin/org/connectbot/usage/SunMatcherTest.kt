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

package org.connectbot.usage

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.Host
import org.junit.Test

class SunMatcherTest {
    private val setting = "100.64.0.1, sun.example.ts.net, Sun"

    @Test
    fun sunByTailscaleIpMagicDnsOrNickname() {
        assertThat(SunMatcher.isSun(Host(nickname = "home", hostname = "100.64.0.1"), setting)).isTrue()
        assertThat(SunMatcher.isSun(Host(nickname = "x", hostname = "sun.example.ts.net"), setting)).isTrue()
        assertThat(SunMatcher.isSun(Host(nickname = "sun", hostname = "192.0.2.180"), setting)).isTrue()
        assertThat(SunMatcher.isSun(Host(nickname = "Moon", hostname = "100.64.0.2"), setting)).isFalse()
    }

    @Test
    fun defaultSetting_hasNoUploadHostAndNoPinnedKey() {
        assertThat(UsageSettings.SUN_HOSTS_DEFAULT).isEmpty()
        assertThat(UsageSettings.SUN_HOST_KEY_DEFAULT).isEmpty()
        assertThat(SunMatcher.isSun(Host(nickname = "Sun", hostname = "100.64.0.1"), UsageSettings.SUN_HOSTS_DEFAULT)).isFalse()
        assertThat(SunMatcher.fingerprintsMatch(FakeSun.HOST_KEY, UsageSettings.SUN_HOST_KEY_DEFAULT)).isFalse()
    }

    @Test
    fun settingIsEditable() {
        assertThat(SunMatcher.isSun(Host(nickname = "box", hostname = "10.0.0.5"), "10.0.0.5")).isTrue()
        assertThat(SunMatcher.isSun(Host(nickname = "Sun", hostname = "10.0.0.6"), "10.0.0.5")).isFalse()
    }

    @Test
    fun fingerprintComparison() {
        val pinned = FakeSun.HOST_KEY
        assertThat(SunMatcher.fingerprintsMatch(pinned, pinned)).isTrue()
        assertThat(SunMatcher.fingerprintsMatch(pinned.removePrefix("SHA256:"), pinned)).isTrue()
        assertThat(SunMatcher.fingerprintsMatch("$pinned=", pinned)).isTrue()
        assertThat(SunMatcher.fingerprintsMatch(pinned.lowercase(), pinned)).isFalse()
        assertThat(SunMatcher.fingerprintsMatch(null, pinned)).isFalse()
        assertThat(SunMatcher.fingerprintsMatch("", "")).isFalse()
    }
}
