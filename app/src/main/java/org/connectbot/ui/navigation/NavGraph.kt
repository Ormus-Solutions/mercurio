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

package org.connectbot.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import org.connectbot.data.entity.Host
import org.connectbot.ui.needs.NeedsScreen
import org.connectbot.ui.screens.colors.ColorsScreen
import org.connectbot.ui.screens.colors.PaletteEditorScreen
import org.connectbot.ui.screens.console.ConsoleScreen
import org.connectbot.ui.screens.contact.ContactScreen
import org.connectbot.ui.screens.eula.EulaScreen
import org.connectbot.ui.screens.generatepubkey.GeneratePubkeyScreen
import org.connectbot.ui.screens.help.HelpScreen
import org.connectbot.ui.screens.hints.HintsScreen
import org.connectbot.ui.screens.hosteditor.HostEditorScreen
import org.connectbot.ui.screens.hostlist.HostListScreen
import org.connectbot.ui.screens.inbox.InboxScreen
import org.connectbot.ui.screens.panel.SettingsPanelScreen
import org.connectbot.ui.screens.portforwardlist.PortForwardListScreen
import org.connectbot.ui.screens.profiles.ProfileEditorScreen
import org.connectbot.ui.screens.profiles.ProfileListScreen
import org.connectbot.ui.screens.pubkeyeditor.PubkeyEditorScreen
import org.connectbot.ui.screens.pubkeylist.PubkeyListScreen
import org.connectbot.ui.screens.settings.SettingsScreen
import org.connectbot.ui.screens.spaces.SpacesScreen
import org.connectbot.util.IconStyle
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.CoreWatch
import solutions.ormus.logos.herd.HerdrConsole
import timber.log.Timber

@Composable
fun ConnectBotNavHost(
    navController: NavHostController,
    onNavigateToConsole: (Host) -> Unit,
    modifier: Modifier = Modifier,
    startDestination: String = NavDestinations.HOST_LIST,
    makingShortcut: Boolean = false,
    onSelectShortcut: (Host, String?, IconStyle) -> Unit = { _, _, _ -> },
    shouldShowNotificationWarning: () -> Boolean = { false },
    onNotificationSnackbarFinish: () -> Unit = {},
    onOpenWorkspace: (Host, HerdrConsole) -> Unit = { _, _ -> },
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(NavDestinations.HOST_LIST) {
            HostListScreen(
                makingShortcut = makingShortcut,
                onNavigateToConsole = onNavigateToConsole,
                onOpenWorkspace = onOpenWorkspace,
                onSelectShortcut = onSelectShortcut,
                onNavigateToEditHost = { host ->
                    if (host != null) {
                        navController.navigateSafely("${NavDestinations.HOST_EDITOR}?${NavArgs.HOST_ID}=${host.id}")
                    } else {
                        navController.navigateSafely(NavDestinations.HOST_EDITOR)
                    }
                },
                onNavigateToSettings = {
                    navController.navigateSafely(NavDestinations.SETTINGS)
                },
                onNavigateToSettingsHighlightConnPersist = {
                    navController.navigateSafely(NavDestinations.SETTINGS_HIGHLIGHT_CONN_PERSIST)
                },
                onNavigateToPubkeys = {
                    navController.navigateSafely(NavDestinations.PUBKEY_LIST)
                },
                onNavigateToPortForwards = { host ->
                    navController.navigateSafely("${NavDestinations.PORT_FORWARD_LIST}/${host.id}")
                },
                onNavigateToProfiles = {
                    navController.navigateSafely(NavDestinations.PROFILES)
                },
                onNavigateToHelp = {
                    navController.navigateSafely(NavDestinations.HELP)
                },
                onNavigateToInbox = { navController.navigateSafely(NavDestinations.INBOX) },
                onNavigateToNeeds = { navController.navigateSafely(NavDestinations.NEEDS) },
                shouldShowNotificationWarning = shouldShowNotificationWarning,
                onNotificationSnackbarFinish = onNotificationSnackbarFinish,
            )
        }

        composable(
            route = NavDestinations.CONSOLE_ROUTE,
            arguments = listOf(
                navArgument(NavArgs.HOST_ID) { type = NavType.LongType },
                navArgument(NavArgs.SESSION) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(NavArgs.LABEL) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(NavArgs.WATCH) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(NavArgs.WATCH_SPACE) {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument(NavArgs.WATCH_TAB) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
        ) {
            ConsoleScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToPortForwards = { hostIdForPortForwards ->
                    navController.navigateSafely("${NavDestinations.PORT_FORWARD_LIST}/$hostIdForPortForwards")
                },
                onNavigateToSettings = {
                    navController.navigateSafely(NavDestinations.SETTINGS_HIGHLIGHT_CONN_PERSIST)
                },
                // Switching to another open session re-targets the same console
                // route, so navigateSafely's same-destination guard would block
                // it. Navigate directly and pop the current console so the back
                // stack doesn't grow with every switch.
                onNavigateToConsole = { hostIdToShow ->
                    navController.navigate(NavDestinations.console(hostIdToShow)) {
                        popUpTo(NavDestinations.CONSOLE_ROUTE) {
                            inclusive = true
                        }
                    }
                },
                onNavigateToHostList = {
                    // The hosts home is not on the stack when the app opened on the Inbox.
                    if (!navController.popBackStack(NavDestinations.HOST_LIST, inclusive = false)) navController.selectPlace(HomePlace.HOSTS)
                },
                onNavigateToRoute = { route -> navController.navigateSafely(route) },
                onOpenJumpedConsole = navController::openJumpedConsole,
            )
        }

        composable(
            route = "${NavDestinations.HOST_EDITOR}?${NavArgs.HOST_ID}={${NavArgs.HOST_ID}}",
            arguments = listOf(
                navArgument(NavArgs.HOST_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) {
            HostEditorScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(NavDestinations.INBOX) {
            InboxScreen(
                // A TUI agent opens its Herdr console, a core agent a console that watches its terminal.
                onOpen = { row ->
                    // The Inbox leaves the back stack: Back from the console returns to where the Inbox was opened.
                    navController.navigate(NavDestinations.console(row.hostId, row.console, CoreWatch.target(row.agent)?.takeIf { row.agent.engine == AgentEngine.CORE })) {
                        // Opened first, the Inbox is the bottom of the stack: it stays, so Back returns to it.
                        if (navController.previousBackStackEntry != null) popUpTo(NavDestinations.INBOX) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onNavigateBack = { if (navController.currentBackStackEntry.lifecycleIsResumed()) navController.backOrHosts() },
                onNavigateToNeeds = { navController.navigateSafely(NavDestinations.NEEDS) },
                onNavigateToSettings = { navController.openSettings() },
            )
        }

        composable(NavDestinations.NEEDS) {
            NeedsScreen(onNavigateBack = { if (navController.currentBackStackEntry.lifecycleIsResumed()) navController.backOrHosts() })
        }

        composable(NavDestinations.SPACES) {
            SpacesScreen(
                // A tab opens its first terminal through the watch view; Spaces stays below it.
                onOpenTab = { hostId, target -> navController.navigate(NavDestinations.console(hostId, watch = target)) { launchSingleTop = true } },
                onNavigateBack = { if (navController.currentBackStackEntry.lifecycleIsResumed()) navController.backOrHosts() },
            )
        }

        composable(NavDestinations.PUBKEY_LIST) {
            PubkeyListScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToGenerate = { navController.navigateSafely(NavDestinations.GENERATE_PUBKEY) },
                onNavigateToEdit = { pubkey ->
                    navController.navigateSafely("${NavDestinations.PUBKEY_EDITOR}/${pubkey.id}")
                },
            )
        }

        composable(NavDestinations.GENERATE_PUBKEY) {
            GeneratePubkeyScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = "${NavDestinations.PUBKEY_EDITOR}/{${NavArgs.PUBKEY_ID}}",
            arguments = listOf(
                navArgument(NavArgs.PUBKEY_ID) { type = NavType.LongType },
            ),
        ) {
            PubkeyEditorScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(
            route = "${NavDestinations.PORT_FORWARD_LIST}/{${NavArgs.HOST_ID}}",
            arguments = listOf(
                navArgument(NavArgs.HOST_ID) { type = NavType.LongType },
            ),
        ) {
            PortForwardListScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(NavDestinations.SETTINGS_PANEL) {
            SettingsPanelScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToAllSettings = { navController.navigateSafely(NavDestinations.SETTINGS) },
            )
        }

        composable(
            route = "${NavDestinations.SETTINGS}?highlight={highlight}",
            arguments = listOf(
                navArgument("highlight") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            val highlight = it.arguments?.getString("highlight")
            SettingsScreen(
                onNavigateBack = { navController.safePopBackStack() },
                highlightItem = highlight,
            )
        }

        composable(NavDestinations.COLORS) {
            ColorsScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToPaletteEditor = { schemeId ->
                    navController.navigateSafely("${NavDestinations.PALETTE_EDITOR}/$schemeId")
                },
            )
        }

        composable(
            route = "${NavDestinations.PALETTE_EDITOR}/{${NavArgs.SCHEME_ID}}",
            arguments = listOf(
                navArgument(NavArgs.SCHEME_ID) { type = NavType.LongType },
            ),
        ) {
            PaletteEditorScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToDuplicate = { newSchemeId ->
                    navController.navigate(
                        route = "${NavDestinations.PALETTE_EDITOR}/$newSchemeId",
                        navOptions = NavOptions.Builder()
                            .setPopUpTo(
                                route = "${NavDestinations.PALETTE_EDITOR}/{${NavArgs.SCHEME_ID}}",
                                inclusive = true,
                            )
                            .build(),
                    )
                },
            )
        }

        composable(NavDestinations.PROFILES) {
            ProfileListScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToEdit = { profile ->
                    navController.navigateSafely("${NavDestinations.PROFILE_EDITOR}/${profile.id}")
                },
                onNavigateToColors = {
                    navController.navigateSafely(NavDestinations.COLORS)
                },
            )
        }

        composable(
            route = "${NavDestinations.PROFILE_EDITOR}/{${NavArgs.PROFILE_ID}}",
            arguments = listOf(
                navArgument(NavArgs.PROFILE_ID) { type = NavType.LongType },
            ),
        ) {
            ProfileEditorScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToColors = {
                    navController.navigateSafely(NavDestinations.COLORS)
                },
            )
        }

        composable(NavDestinations.HELP) {
            HelpScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onNavigateToHints = { navController.navigateSafely(NavDestinations.HINTS) },
                onNavigateToEula = { navController.navigateSafely(NavDestinations.EULA) },
                onNavigateToContact = { navController.navigateSafely(NavDestinations.CONTACT) },
            )
        }

        composable(NavDestinations.CONTACT) {
            ContactScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(NavDestinations.EULA) {
            EulaScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }

        composable(NavDestinations.HINTS) {
            HintsScreen(
                onNavigateBack = { navController.safePopBackStack() },
            )
        }
    }
}

/**
 * If the lifecycle is not resumed it means this NavBackStackEntry already processed a nav event.
 *
 * This is used to de-duplicate navigation events.
 */
private fun NavBackStackEntry?.lifecycleIsResumed() = this?.lifecycle?.currentState == Lifecycle.State.RESUMED

/**
 * Safely pops the back stack, preventing double navigation when the user rapidly taps
 * the back button. This checks if the current destination's lifecycle state is RESUMED
 * before allowing the navigation to proceed.
 */
private fun NavHostController.safePopBackStack() = if (currentBackStackEntry.lifecycleIsResumed()) popBackStack() else false

/**
 * A more robust navigate function that avoids navigating to the
 * same destination currently on screen.
 *
 * This checks the destination of the navigation action against the current
 * destination.
 *
 * @param route The destination route to navigate to.
 * @param navOptions Optional NavOptions to apply to this navigation.
 */
fun NavController.navigateSafely(
    route: String,
    navOptions: NavOptions? = null,
) {
    // Find the destination for the given route.
    val destination = graph.findNode(route)

    if (destination != null) {
        // Check if the target destination is the same as the current one.
        if (destination.id != currentDestination?.id) {
            navigate(route, navOptions)
        }
    } else {
        Timber.w("Navigating to unknown NavGraph destination $route")
        navigate(route, navOptions)
    }
}
