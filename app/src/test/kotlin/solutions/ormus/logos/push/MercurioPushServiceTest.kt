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

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.unifiedpush.android.connector.data.PushMessage

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MercurioPushServiceTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `a push is posted before the connector unbinds the service`() {
        // The connector calls onMessage, then unbinds, which destroys the service at once.
        var posted = false
        val controller = Robolectric.buildService(MercurioPushService::class.java).create()
        val service = controller.get()
        service.notices = mock {
            onBlocking { post(any(), any()) } doSuspendableAnswer {
                delay(200)
                posted = true
                true
            }
        }

        service.onMessage(PushMessage("{}".toByteArray(), false), "default")
        controller.destroy()

        assertTrue("the notice was cut off when the service was destroyed", posted)
    }
}
