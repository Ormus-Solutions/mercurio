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

package org.connectbot.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.connectbot.usage.InboxStore
import org.connectbot.usage.UsageClock
import org.connectbot.usage.UsageStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UsageModule {
    /** App-private (`filesDir`), outside external storage and the backed-up preferences. */
    private fun usageDir(context: Context): File = File(context.filesDir, "usage")

    @Provides
    @Singleton
    fun provideUsageStore(@ApplicationContext context: Context): UsageStore = UsageStore(usageDir(context))

    @Provides
    @Singleton
    fun provideInboxStore(@ApplicationContext context: Context): InboxStore = InboxStore(usageDir(context))

    @Provides
    fun provideUsageClock(): UsageClock = UsageClock { System.currentTimeMillis() }
}
