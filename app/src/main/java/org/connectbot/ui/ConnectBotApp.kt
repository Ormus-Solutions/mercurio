/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
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

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package org.connectbot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.preference.PreferenceManager
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalManager
import org.connectbot.ui.navigation.ConnectBotNavHost
import org.connectbot.ui.navigation.HomePlacesFrame
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.NoUsageLog
import org.connectbot.usage.UsageLog
import org.connectbot.util.IconStyle
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.home.HomeStart

val LocalTerminalManager = compositionLocalOf<TerminalManager?> {
    null
}

@Composable
fun ConnectBotApp(
    appUiState: AppUiState,
    navController: NavHostController,
    makingShortcut: Boolean,
    authRequired: Boolean,
    isAuthenticated: Boolean,
    onAuthenticationSuccess: () -> Unit,
    onRetryMigration: () -> Unit,
    onSelectShortcut: (Host, String?, IconStyle) -> Unit,
    onNavigateToConsole: (Host) -> Unit,
    modifier: Modifier = Modifier,
    shouldShowNotificationWarning: () -> Boolean = { false },
    onNotificationSnackbarFinish: () -> Unit = {},
    usageLog: UsageLog = NoUsageLog,
    onOpenWorkspace: (Host, HerdrConsole) -> Unit = { _, _ -> },
) {
    ConnectBotTheme {
        when (appUiState) {
            is AppUiState.Loading -> {
                LoadingScreen(modifier = modifier)
            }

            is AppUiState.MigrationInProgress -> {
                MigrationScreen(
                    uiState = MigrationUiState.InProgress(appUiState.state),
                    onRetry = onRetryMigration,
                    modifier = modifier,
                )
            }

            is AppUiState.MigrationFailed -> {
                MigrationScreen(
                    uiState = MigrationUiState.Failed(
                        appUiState.error,
                        appUiState.debugLog,
                    ),
                    onRetry = onRetryMigration,
                    modifier = modifier,
                )
            }

            is AppUiState.Ready -> {
                if (authRequired && !isAuthenticated && !makingShortcut) {
                    AuthenticationScreen(
                        onAuthenticationSuccess = onAuthenticationSuccess,
                        modifier = modifier,
                    )
                } else {
                    // Read once: the Inbox first once a host has answered herdr-core, else the hosts home.
                    val context = LocalContext.current
                    val startDestination = remember { HomeStart.destination(PreferenceManager.getDefaultSharedPreferences(context), makingShortcut) }
                    CompositionLocalProvider(
                        LocalTerminalManager provides appUiState.terminalManager,
                        LocalUsageLog provides usageLog,
                    ) {
                        HomePlacesFrame(navController = navController, makingShortcut = makingShortcut, modifier = modifier) { navModifier ->
                            ConnectBotNavHost(
                                navController = navController,
                                startDestination = startDestination,
                                makingShortcut = makingShortcut,
                                onSelectShortcut = onSelectShortcut,
                                onNavigateToConsole = onNavigateToConsole,
                                shouldShowNotificationWarning = shouldShowNotificationWarning,
                                onNotificationSnackbarFinish = onNotificationSnackbarFinish,
                                onOpenWorkspace = onOpenWorkspace,
                                modifier = navModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
