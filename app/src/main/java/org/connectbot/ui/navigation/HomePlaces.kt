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

package org.connectbot.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.preference.PreferenceManager
import org.connectbot.R
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.home.HomeStart

/**
 * The places of the bottom bar. Inbox and Spaces are the Herdr app's two; [HOSTS] is the home that
 * was here first (hosts, Workboards, Workspaces), kept one tap away as a third place because the
 * Herdr app keeps its machines behind a gear, and here that home is the launcher.
 */
enum class HomePlace(val route: String, @StringRes val label: Int, val icon: ImageVector, val usageId: String) {
    INBOX(NavDestinations.INBOX, R.string.inbox_title, Icons.Filled.Inbox, UsageActions.PLACES_INBOX),
    SPACES(NavDestinations.SPACES, R.string.spaces_title, Icons.Filled.GridView, UsageActions.PLACES_SPACES),
    HOSTS(NavDestinations.HOST_LIST, R.string.host_list_section, Icons.Filled.Dns, UsageActions.PLACES_HOSTS),
    ;

    companion object {
        /** The place [route] is, or null for a screen that is not one. */
        fun of(route: String?): HomePlace? = entries.firstOrNull { it.route == route }

        /**
         * The places the bar shows on the screen at [route]: [available] while the screen is one of
         * them, more than one is on, and no shortcut is being picked; none otherwise. A place whose
         * setting went off while its screen stayed on the stack shows no bar.
         */
        fun shown(route: String?, inbox: Boolean, core: Boolean, makingShortcut: Boolean): List<HomePlace> {
            val here = of(route) ?: return emptyList()
            val places = available(inbox, core)
            return if (makingShortcut || here !in places || places.size < 2) emptyList() else places
        }

        /**
         * The places the bar offers: the Inbox while its setting is on, Spaces while herdr-core is
         * on (it reads nothing without it), and the hosts home always. One place alone is no bar.
         */
        fun available(inbox: Boolean, core: Boolean): List<HomePlace> = entries.filter {
            when (it) {
                INBOX -> inbox
                SPACES -> core
                HOSTS -> true
            }
        }
    }
}

/** Test tag of a place's item: `home_place_` and the place's name. */
fun homePlaceTag(place: HomePlace): String = "home_place_${place.name}"

/** The bar: one item per place, [selected] marked. */
@Composable
fun HomeBar(places: List<HomePlace>, selected: HomePlace?, onSelect: (HomePlace) -> Unit, modifier: Modifier = Modifier) {
    val usage = LocalUsageLog.current
    NavigationBar(modifier = modifier, windowInsets = WindowInsets.navigationBars) {
        places.forEach { place ->
            val label = stringResource(place.label)
            NavigationBarItem(
                selected = place == selected,
                onClick = {
                    usage.log(place.usageId)
                    onSelect(place)
                },
                icon = { Icon(place.icon, contentDescription = null) },
                label = { Text(label) },
                modifier = Modifier.testTag(homePlaceTag(place)),
            )
        }
    }
}

/**
 * Back from a place: one step back, or to the hosts home when the place is the bottom of the stack
 * (the app opened on the Inbox) and a pop would leave an empty one.
 */
fun NavController.backOrHosts(): Boolean {
    if (previousBackStackEntry != null) return popBackStack()
    selectPlace(HomePlace.HOSTS)
    return true
}

/** Open the app's Settings screen: the route the Hosts screen and the Inbox gear both use. */
fun NavController.openSettings() = navigateSafely(NavDestinations.SETTINGS)

/**
 * Open the console at [route] over the start destination, dropping any console above it. The
 * start is the hosts home or the Inbox, whichever the app opened on.
 */
fun NavController.openConsoleRoute(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id)
    launchSingleTop = true
}

/**
 * Go to [place] as one of the bar's top places: back to the start destination, then on to the
 * place, so the places do not pile up and Back from one returns to where the app opened.
 */
fun NavController.selectPlace(place: HomePlace) {
    if (currentDestination?.route == place.route) return
    navigate(place.route) {
        popUpTo(graph.findStartDestination().id) { saveState = false }
        launchSingleTop = true
    }
}

/**
 * [content] with the bar under it while the current screen is one of the places and more than one
 * place is on. [content] gets the modifier that fills the space above the bar, which also takes the
 * bottom inset away from the screens (the bar pads for it), so nothing pads for it twice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomePlacesFrame(
    navController: NavController,
    makingShortcut: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    val entry by navController.currentBackStackEntryAsState()
    val place = HomePlace.of(entry?.destination?.route)
    // Read at each stop: the switches in the settings panel change them between two visits.
    // Hidden while the keyboard is up: the bar would hold the content a bar's height above it.
    val places = if (WindowInsets.isImeVisible) emptyList() else HomePlace.shown(entry?.destination?.route, HomeStart.inboxOn(prefs), HomeStart.coreOn(prefs), makingShortcut)
    val showBar = places.isNotEmpty()
    Column(modifier = modifier) {
        content(Modifier.weight(1f).then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier))
        if (showBar) HomeBar(places = places, selected = place, onSelect = navController::selectPlace)
    }
}

/**
 * Open the console at [route] for a core terminal picked on the Jump sheet. The sheet sits on a
 * console, so the new console takes its place: Back returns to whatever opened
 * it from, and a run of jumps does not pile consoles up. Like the Inbox, which leaves the stack
 * when it opens one. No launchSingleTop: after the pop it would compare with the console below,
 * and replace that one.
 */
fun NavController.openJumpedConsole(route: String) = navigate(route) {
    if (currentDestination?.route == NavDestinations.CONSOLE_ROUTE) {
        popUpTo(NavDestinations.CONSOLE_ROUTE) { inclusive = true }
    }
}
