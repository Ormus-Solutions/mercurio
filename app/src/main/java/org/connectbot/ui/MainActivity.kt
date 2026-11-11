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

package org.connectbot.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import androidx.preference.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.connectbot.R
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.ui.components.DisconnectAllDialog
import org.connectbot.ui.navigation.NavDestinations
import org.connectbot.ui.navigation.openConsoleRoute
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageTracker
import org.connectbot.util.IconStyle
import org.connectbot.util.PreferenceConstants
import org.connectbot.util.ShortcutIconGenerator
import org.connectbot.util.isNotificationPermissionGranted
import solutions.ormus.logos.herd.HerdView
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.HerdrOpen
import timber.log.Timber
import javax.inject.Inject

// TODO: Move back to ComponentActivity when https://issuetracker.google.com/issues/178855209 is fixed.
//       FragmentActivity subclass is required for BiometricPrompt to find the FragmentManager
//       from Compose context.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    companion object {
        private const val STATE_SELECTED_URI = "selectedUri"
        const val DISCONNECT_ACTION = "org.connectbot.action.DISCONNECT"

        /** Extra on a host URI intent: the Herdr pane to focus once its console opens. */
        const val EXTRA_HERD_PANE = "solutions.ormus.logos.extra.HERD_PANE"

        /** Extra beside [EXTRA_HERD_PANE]: the Herdr session the pane is in (the default when missing). */
        const val EXTRA_HERD_SESSION = "solutions.ormus.logos.extra.HERD_SESSION"

        /** Extra beside [EXTRA_HERD_PANE]: the usage id of the notification that was tapped. */
        const val EXTRA_HERD_NOTICE = "solutions.ormus.logos.extra.HERD_NOTICE"

        /**
         * Extra on a host URI intent: open the agent chat ([HerdView.CHAT], on [EXTRA_HERD_PANE]
         * or the session's focused pane) or the Agents board ([HerdView.BOARD]) over the console.
         * Missing or unknown, the console shows the terminal.
         */
        const val EXTRA_HERD_VIEW = "solutions.ormus.logos.extra.HERD_VIEW"

        /**
         * Extra on a host URI intent beside [EXTRA_HERD_SESSION]: the agent's machine as the
         * tailnet names it (`name,dns,ip`), so the saved host that attaches that session is
         * opened whatever the phone calls it ([HerdrOpen]).
         */
        const val EXTRA_HERD_HOSTS = HerdrOpen.EXTRA_HERD_HOSTS
    }

    internal lateinit var appViewModel: AppViewModel

    @Inject
    internal lateinit var usageTracker: UsageTracker

    @Inject
    internal lateinit var hostRepository: HostRepository
    private var bound = false
    private var requestedUri: Uri? by mutableStateOf(null)

    /**
     * What a Herdr notification or a launcher's tap asked for beside the host URI: the [pane]
     * to focus, its [session], the [view] to open over the console (the terminal when null),
     * and the machine's names ([hosts], a launcher's tap only: see [HerdrOpen]).
     */
    private data class HerdRequest(val pane: String?, val session: String, val view: HerdView?, val hosts: String?)

    // The request beside requestedUri, taken whole when the URI is handled, so a second tap
    // while the first is in flight cannot mix the two.
    private var requestedHerd: HerdRequest? = null

    // A launcher's taps resolve and save one at a time (handleConnectionUri): two quick taps on a machine with
    // no saved host must save one host, not two.
    private val openTapLock = Mutex()

    /** Remember a tapped Herdr notification's pane and log the tap (an id only). */
    private fun takeHerdNotification(intent: Intent?) {
        val pane = intent?.getStringExtra(EXTRA_HERD_PANE)
        val session = intent?.getStringExtra(EXTRA_HERD_SESSION) ?: HerdrCommands.DEFAULT_SESSION
        val view = HerdView.parse(intent?.getStringExtra(EXTRA_HERD_VIEW), pane, session)
        requestedHerd = HerdRequest(pane, session, view, intent?.getStringExtra(EXTRA_HERD_HOSTS))
        intent?.getStringExtra(EXTRA_HERD_NOTICE)?.let { usageTracker.log(it) }
        when (view) {
            is HerdView.Chat -> usageTracker.log(UsageActions.LAUNCH_CHAT)
            HerdView.Board -> usageTracker.log(UsageActions.LAUNCH_BOARD)
            null -> Unit
        }
    }
    private var pendingHostConnection: ConsoleRequest? by mutableStateOf(null)

    // Holds the host waiting for permission result; not compose state so it doesn't trigger navigation.
    private var hostAwaitingPermission: ConsoleRequest? = null

    /** A console to show: [host], and the Herdr [console] to open on it (a Workspaces key), null for the host's own. */
    private data class ConsoleRequest(val host: Host, val console: HerdrConsole?)
    internal var makingShortcut by mutableStateOf(false)
    private var showDisconnectAllDialog by mutableStateOf(false)

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Check the actual permission status instead of relying on the launcher result.
        // If user went to settings and granted permission, the result will be false but
        // the actual permission may be granted.
        val actuallyGranted = isNotificationPermissionGranted(this)

        appViewModel.onNotificationPermissionResult(actuallyGranted)?.let { uri ->
            requestedUri = uri
        }
        // Prefs are now written; move the waiting host into pendingHostConnection so
        // the LaunchedEffect that watches it triggers navigation after the warning state is set.
        hostAwaitingPermission?.let { request ->
            Timber.d("Permission result received; proceeding to console for ${request.host.nickname}")
            pendingHostConnection = request
            hostAwaitingPermission = null
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? TerminalManager.TerminalBinder
            val manager = binder?.getService()
            Timber.d("onServiceConnected: manager=$manager")
            appViewModel.setTerminalManager(manager)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.d("onServiceDisconnected")
            appViewModel.setTerminalManager(null)
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)

        appViewModel = ViewModelProvider(this)[AppViewModel::class.java]

        if (savedInstanceState == null) {
            requestedUri = intent?.data
            takeHerdNotification(intent)
            makingShortcut = Intent.ACTION_CREATE_SHORTCUT == intent?.action ||
                Intent.ACTION_PICK == intent?.action
            Timber.d("onCreate: requestedUri=$requestedUri, makingShortcut=$makingShortcut")
            handleIntent(intent)
        } else {
            savedInstanceState.getString(STATE_SELECTED_URI)?.let {
                requestedUri = it.toUri()
            }
        }

        val serviceIntent = Intent(this, TerminalManager::class.java)
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)

        setContent {
            val appUiState by appViewModel.uiState.collectAsState()
            val pendingDisconnectAll by appViewModel.pendingDisconnectAll.collectAsState()
            val isAuthenticated by appViewModel.isAuthenticated.collectAsState()
            val authOnLaunchEnabled = appViewModel.authOnLaunchEnabled
            val navController = rememberNavController()
            val context = LocalContext.current
            // Usage log: stamp each event with the screen showing (its route's first segment).
            DisposableEffect(navController) {
                val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                    usageTracker.setScreen(destination.route)
                }
                navController.addOnDestinationChangedListener(listener)
                onDispose { navController.removeOnDestinationChangedListener(listener) }
            }
            var showPermissionRationale by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                appViewModel.showPermissionRationale.collect {
                    showPermissionRationale = true
                }
            }

            LaunchedEffect(Unit) {
                appViewModel.requestPermission.collect {
                    Timber.d("Received requestPermission event, SDK_INT=${Build.VERSION.SDK_INT}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Timber.d("Launching permission request")
                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        Timber.d("Skipping permission request, SDK < TIRAMISU")
                    }
                }
            }

            LaunchedEffect(requestedUri, navController, appUiState) {
                Timber.d("LaunchedEffect: requestedUri=$requestedUri, appUiState=$appUiState")
                if (appUiState is AppUiState.Ready) {
                    requestedUri?.let { uri ->
                        Timber.d("Processing URI: $uri")
                        navController.let { controller ->
                            val shouldShowRationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                this@MainActivity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                false
                            }

                            Timber.d("shouldShowRationale=$shouldShowRationale")
                            if (appViewModel.checkAndRequestNotificationPermission(context, uri, shouldShowRationale)) {
                                Timber.d("Permission check passed, handling connection")
                                handleConnectionUri(uri, controller)
                                requestedUri = null
                            } else {
                                Timber.d("Permission check blocked, waiting for permission")
                            }
                        }
                    }
                }
            }

            LaunchedEffect(pendingDisconnectAll, appUiState) {
                appViewModel.executePendingDisconnectAllIfReady()
            }

            LaunchedEffect(Unit) {
                appViewModel.finishActivity.collect {
                    if (context is Activity) {
                        context.finish()
                    }
                }
            }

            // Re-check permission status when activity resumes (e.g., user grants/revokes in Settings)
            ObservePermissionOnResume { isGranted ->
                if (hostAwaitingPermission != null) {
                    // Permission state changed while waiting for a connection
                    appViewModel.onNotificationPermissionResult(isGranted)
                    pendingHostConnection = hostAwaitingPermission
                    hostAwaitingPermission = null
                }
            }

            if (showDisconnectAllDialog) {
                ConnectBotTheme {
                    DisconnectAllDialog(
                        onDismiss = {
                            Timber.d("User cancelled disconnectAll")
                            showDisconnectAllDialog = false
                        },
                        onConfirm = {
                            Timber.d("User confirmed disconnectAll")
                            showDisconnectAllDialog = false
                            appViewModel.setPendingDisconnectAll(true)
                        },
                    )
                }
            }

            // Navigate to console when pending host connection is set
            LaunchedEffect(pendingHostConnection, appUiState) {
                if (appUiState is AppUiState.Ready) {
                    pendingHostConnection?.let { request ->
                        Timber.d("Navigating to console for pending host: ${request.host.nickname}")
                        pendingHostConnection = null
                        navController.navigate(NavDestinations.console(request.host.id, request.console))
                    }
                }
            }

            if (showPermissionRationale) {
                ConnectBotTheme {
                    NotificationPermissionRationaleDialog(
                        onDismiss = {
                            Timber.d("User dismissed permission rationale, proceeding anyway")
                            showPermissionRationale = false
                            // User chose "Continue anyway" - proceed without permission
                            appViewModel.pendingConnectionUri.value?.let { uri ->
                                requestedUri = uri
                                appViewModel.clearPendingConnectionUri()
                            }
                            // Handle host waiting for permission result
                            hostAwaitingPermission?.let { request ->
                                appViewModel.onNotificationPermissionResult(isGranted = false)
                                navController.navigate(NavDestinations.console(request.host.id, request.console))
                                hostAwaitingPermission = null
                            }
                        },
                        onAllow = {
                            Timber.d("User chose to allow permission from rationale")
                            showPermissionRationale = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }

            // Callback to check permission before navigating to console. [console] is a Herdr
            // console to open on the host (a Workspaces key); null shows the host's own console.
            val openConsole: (Host, HerdrConsole?) -> Unit = { host, console ->
                Timber.d("openConsole called for host: ${host.nickname}")

                // Check if connection persistence is enabled
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val persistConnections = prefs.getBoolean(PreferenceConstants.CONNECTION_PERSIST, true)

                if (!persistConnections || isNotificationPermissionGranted(context)) {
                    // Either persistence is disabled (no permission needed) or permission granted, navigate immediately
                    navController.navigate(NavDestinations.console(host.id, console))
                } else {
                    // Persistence is enabled but no permission - need to request permission.
                    // Store in hostAwaitingPermission (not compose state) so we don't navigate
                    // until after the permission result is written to prefs.
                    Timber.d("Requesting notification permission before connection")
                    hostAwaitingPermission = ConsoleRequest(host, console)
                    val shouldShowRationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        this@MainActivity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        false
                    }
                    appViewModel.requestNotificationPermission(shouldShowRationale)
                }
            }

            val onNavigateToConsole: (Host) -> Unit = { host -> openConsole(host, null) }

            ConnectBotApp(
                appUiState = appUiState,
                navController = navController,
                makingShortcut = makingShortcut,
                authRequired = authOnLaunchEnabled,
                isAuthenticated = isAuthenticated,
                onAuthenticationSuccess = { appViewModel.onAuthenticationSuccess() },
                onRetryMigration = { appViewModel.retryMigration() },
                onSelectShortcut = { host, color, iconStyle ->
                    createShortcutAndFinish(host, color, iconStyle)
                },
                onNavigateToConsole = onNavigateToConsole,
                onOpenWorkspace = openConsole,
                shouldShowNotificationWarning = {
                    !appViewModel.hostListSnackbarShownThisLaunch && appViewModel.shouldShowNotificationWarning()
                },
                onNotificationSnackbarFinish = { appViewModel.markHostListSnackbarShown() },
                usageLog = usageTracker,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        handleIntent(intent)

        intent.data?.let { uri ->
            takeHerdNotification(intent)
            requestedUri = uri
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == DISCONNECT_ACTION) {
            Timber.d("handleIntent: DISCONNECT_ACTION, showing disconnect dialog")
            showDisconnectAllDialog = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        requestedUri?.let {
            outState.putString(STATE_SELECTED_URI, it.toString())
        }
    }

    override fun onDestroy() {
        Timber.d("onDestroy")
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun handleConnectionUri(uri: Uri, controller: NavController) {
        Timber.d("handleConnectionUri: uri=$uri, fragment=${uri.fragment}")
        val state = appViewModel.uiState.value
        if (state !is AppUiState.Ready) {
            Timber.d("handleConnectionUri: state not ready, current state=$state")
            return
        }

        val manager = state.terminalManager
        // Taken whole, before anything suspends: a second tap meanwhile is its own request.
        val herd = requestedHerd
        requestedHerd = null

        lifecycleScope.launch {
            try {
                // A launcher's tap on an agent (it names the machine): the saved host that shows the
                // agent's Herdr session there, whatever this phone calls it, before the URI's own name. When
                // no saved host there shows it or launches a plain `herdr` for the tap's account, one is saved
                // from the tap and a console for the session opens on it; every later tap resolves to it. The
                // URI path below would make a temporary host: a new connection each tap, on no session. One
                // tap at a time, so a second tap finds the first tap's host instead of saving its own.
                var bridge = herd?.hosts?.let { hosts ->
                    openTapLock.withLock {
                        val names = HerdrOpen.names(hosts, uri.host)
                        val saved = hostRepository.getHosts()
                        val target = HerdrOpen.resolve(saved, herd.session, names, uri.userInfo, uri.port, manager.connectedHostIds())
                            ?: HerdrOpen.newHost(saved, uri.scheme, herd.session, uri.userInfo, uri.host, uri.port, uri.fragment, System.currentTimeMillis())?.let { fresh ->
                                runCatching { hostRepository.saveHost(fresh) }
                                    .onFailure { Timber.w(it, "handleConnectionUri: could not save a host for the tap") }
                                    .getOrNull()
                                    ?.takeIf { it.id > 0L }
                                    ?.let { HerdrOpen.Target(it, HerdrConsole(herd.session)) }
                            }
                        target?.let { runCatching { openTarget(manager, it, herd.session) }.onFailure { Timber.w(it, "handleConnectionUri: the resolved host did not open") }.getOrNull() }
                    }
                }

                if (bridge == null) {
                    val nickname = uri.fragment ?: uri.authority
                    Timber.d("handleConnectionUri: nickname=$nickname")
                    bridge = manager.getConnectedBridge(nickname)
                }

                if (bridge == null) {
                    Timber.d("Creating new connection for URI: $uri")
                    bridge = manager.openConnection(uri)
                }

                // From a Herdr notification or a tap: focus that agent in Herdr as the console opens.
                herd?.pane?.let { pane -> bridge.herd?.perform(HerdrAction.FocusAgent(pane), session = herd.session) }

                // The console screen shows this console: its own screen opens over the host list (a console
                // screen that is up, another host's or this one's, read its route when it opened, so it is
                // left), and the request names the console, with the chat or the Agents board to open over it
                // once Herdr answers, for the screen that shows it.
                manager.requestShow(bridge, herd?.view)
                controller.openConsoleRoute(NavDestinations.console(bridge.host.id, bridge.console, bridge.watch))
            } catch (e: Exception) {
                Timber.e(e, "Error handling connection URI: $uri")
            }
        }
    }

    /**
     * The console [target] names for [session], open already or opened now: the host's own
     * console when its post-login attaches the session, else a console of the host for it beside
     * its own. Null when the host is up but no console of it shows the session and its own
     * console is the one that would (it was taken elsewhere): the URI path then finds the host
     * by its name, as before.
     */
    private suspend fun openTarget(manager: TerminalManager, target: HerdrOpen.Target, session: String): TerminalBridge? {
        val (host, console) = target
        Timber.d("handleConnectionUri: session resolved to host ${host.id}, console=$console")
        manager.consoleFor(host.id, session)?.let { return it }
        return when {
            console != null -> manager.openHerdrConsole(host, console)
            manager.hasBridge(host.id) -> null
            else -> manager.openConnectionForHostId(host.id)
        }
    }

    private fun createShortcutAndFinish(host: Host, color: String?, iconStyle: IconStyle) {
        val uri = host.getUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val icon = ShortcutIconGenerator.generateShortcutIcon(this, color, iconStyle)

        val shortcut = ShortcutInfoCompat.Builder(this, "host-${host.id}")
            .setShortLabel(host.nickname)
            .setLongLabel(host.nickname)
            .setIcon(icon)
            .setIntent(intent)
            .build()

        val result = ShortcutManagerCompat.createShortcutResultIntent(this, shortcut)
        setResult(RESULT_OK, result)
        finish()
    }
}

@Composable
private fun NotificationPermissionRationaleDialog(
    onDismiss: () -> Unit,
    onAllow: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notification_permission_title)) },
        text = { Text(stringResource(R.string.notification_permission_message)) },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.grant_permission))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.connect_anyway))
            }
        },
    )
}
