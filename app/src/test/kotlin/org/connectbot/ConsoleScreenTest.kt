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

package org.connectbot

import android.content.Context
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.testing.TestNavHostController
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.data.entity.Host
import org.connectbot.service.DisconnectReason
import org.connectbot.service.ModifierLevel
import org.connectbot.service.ModifierState
import org.connectbot.service.PromptManager
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalKeyListener
import org.connectbot.service.TerminalManager
import org.connectbot.terminal.DelKeyMode
import org.connectbot.terminal.ProgressState
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.ui.navigation.NavDestinations
import org.connectbot.ui.screens.console.CONSOLE_TITLE_TAG
import org.connectbot.ui.screens.console.ConsoleScreen
import org.connectbot.ui.screens.console.ConsoleUiState
import org.connectbot.ui.screens.console.ConsoleViewModel
import org.connectbot.ui.screens.console.TITLE_PULL_MARK_TAG
import org.connectbot.ui.screens.console.TitlePull
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.util.PreferenceConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import solutions.ormus.logos.herd.Agent
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.CoreRead
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdSession

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ConsoleScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun setContent(
        mockTerminalManager: TerminalManager? = null,
        mockConsoleViewModel: ConsoleViewModel? = null,
        fontScale: Float = 1f,
    ) {
        composeTestRule.setContent {
            val context = LocalContext.current
            navController = TestNavHostController(context)
            navController.navigatorProvider.addNavigator(ComposeNavigator())
            ConnectBotTheme {
                CompositionLocalProvider(
                    LocalTerminalManager provides mockTerminalManager,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    NavHost(navController = navController, startDestination = "start") {
                        composable("start") {}
                        composable(
                            route = "console/{hostId}",
                            arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
                        ) {
                            if (mockConsoleViewModel != null) {
                                ConsoleScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToPortForwards = {},
                                    onNavigateToInbox = {},
                                    viewModel = mockConsoleViewModel,
                                )
                            } else {
                                ConsoleScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToPortForwards = {},
                                    onNavigateToInbox = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun navigateToConsoleScreen(hostId: Long = -1L) {
        composeTestRule.runOnUiThread {
            navController.navigate("console/$hostId")
        }
    }

    @Test
    fun consoleScreen_showsNotificationWarningSnackbar_whenConnPersistDisabled() {
        val warning = composeTestRule.activity.getString(R.string.notification_permission_console_warning)
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceConstants.CONNECTION_PERSIST, false)
            .putBoolean(PreferenceConstants.NOTIFICATION_PERMISSION_DENIED, true)
            .commit()

        setContent()
        navigateToConsoleScreen()

        composeTestRule
            .onNodeWithText(warning)
            .assertIsDisplayed()
    }

    @Test
    fun consoleScreen_showsNotificationWarningSnackbar_whenPermissionDeniedOnDevice() {
        // On API 33+, notification permission is denied by default in Robolectric.
        // conn_persist defaults to true, so this exercises the permission-revoked-in-settings path.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return

        val warning = composeTestRule.activity.getString(R.string.notification_permission_console_warning)
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Simulate that the permission flow has run before (key exists) but was then revoked in Settings.
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceConstants.NOTIFICATION_PERMISSION_DENIED, false)
            .commit()

        setContent()
        navigateToConsoleScreen()

        composeTestRule
            .onNodeWithText(warning)
            .assertIsDisplayed()
    }

    @Test
    fun consoleScreen_noNotificationWarningSnackbar_whenConnPersistEnabledAndPermissionGranted() {
        // On API < 33, POST_NOTIFICATIONS doesn't exist so it's considered granted.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) return

        val warning = composeTestRule.activity.getString(R.string.notification_permission_console_warning)
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceConstants.CONNECTION_PERSIST, true)
            .commit()

        setContent()
        navigateToConsoleScreen()

        composeTestRule
            .onAllNodes(androidx.compose.ui.test.hasText(warning))
            .fetchSemanticsNodes()
            .let { nodes -> assertEquals("Snackbar should not be shown when both conditions are clear", 0, nodes.size) }
    }

    @Test
    fun consoleScreen_notificationWarningSettingsAction_navigatesToSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceConstants.CONNECTION_PERSIST, false)
            .putBoolean(PreferenceConstants.NOTIFICATION_PERMISSION_DENIED, true)
            .commit()

        var navigatedToSettings = false

        composeTestRule.setContent {
            val ctx = LocalContext.current
            navController = TestNavHostController(ctx)
            navController.navigatorProvider.addNavigator(ComposeNavigator())
            ConnectBotTheme {
                CompositionLocalProvider(LocalTerminalManager provides null) {
                    NavHost(navController = navController, startDestination = "start") {
                        composable("start") {}
                        composable(
                            route = "console/{hostId}",
                            arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
                        ) {
                            ConsoleScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToPortForwards = {},
                                onNavigateToInbox = {},
                                onNavigateToSettings = { navigatedToSettings = true },
                            )
                        }
                    }
                }
            }
        }
        navigateToConsoleScreen()

        composeTestRule
            .onNodeWithText("Settings")
            .performClick()

        assertTrue(navigatedToSettings)
    }

    @Test
    fun consoleScreen_theGearOpensTheSettingsPanel() {
        var route: String? = null

        composeTestRule.setContent {
            val ctx = LocalContext.current
            navController = TestNavHostController(ctx)
            navController.navigatorProvider.addNavigator(ComposeNavigator())
            ConnectBotTheme {
                CompositionLocalProvider(LocalTerminalManager provides null) {
                    NavHost(navController = navController, startDestination = "start") {
                        composable("start") {}
                        composable(
                            route = "console/{hostId}",
                            arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
                        ) {
                            ConsoleScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToPortForwards = {},
                                onNavigateToInbox = {},
                                onNavigateToRoute = { route = it },
                            )
                        }
                    }
                }
            }
        }
        navigateToConsoleScreen()

        composeTestRule.onNodeWithContentDescription("Settings").performClick()

        assertEquals(NavDestinations.SETTINGS_PANEL, route)
    }

    @Test
    fun consoleScreen_hidesTopAppBar_whenPreferenceSet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceConstants.TITLEBARHIDE, true)
            .commit()

        setContent()
        navigateToConsoleScreen()

        composeTestRule.onNodeWithTag("top_app_bar").assertIsNotDisplayed()
    }

    @Test
    fun consoleScreen_displaysTopAppBarByDefault() {
        setContent()
        navigateToConsoleScreen()

        composeTestRule
            .onNodeWithTag("top_app_bar")
            .assertIsDisplayed()
    }

    @Test
    fun consoleScreen_displaysBackButton() {
        setContent()
        navigateToConsoleScreen()

        composeTestRule
            .onNodeWithContentDescription("Back")
            .assertIsDisplayed()
    }

    @Test
    fun consoleScreen_backButtonNavigatesUp() {
        setContent()
        navigateToConsoleScreen()

        composeTestRule
            .onNodeWithContentDescription("Back")
            .performClick()

        composeTestRule.runOnIdle {
            assertTrue(navController.currentBackStackEntry?.destination?.route == "start")
        }
    }

    @Test
    fun consoleScreen_tappingTheHostNameOpensTheSessionDrawer_andThereIsNoHamburger() {
        setContent()
        navigateToConsoleScreen()

        composeTestRule.onAllNodesWithContentDescription("Sessions").assertCountEquals(0)
        composeTestRule.onNodeWithTag(CONSOLE_TITLE_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Host list").assertIsDisplayed()
    }

    /** Puts a finger on the title, pulls it down [by], checks [during] while it is down, then lifts. */
    private fun pullTitle(by: Dp, during: () -> Unit = {}) {
        val title = composeTestRule.onNodeWithTag(CONSOLE_TITLE_TAG)
        title.performTouchInput {
            down(center)
            // Past touch slop in small steps, as a finger moves.
            val steps = 12
            repeat(steps) { moveBy(Offset(0f, by.toPx() / steps)) }
        }
        composeTestRule.waitForIdle()
        during()
        title.performTouchInput { up() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun consoleScreen_shortTitlePullDoesNothing() {
        setContent()
        navigateToConsoleScreen()

        pullTitle(TitlePull.DEFAULT_THRESHOLD * 0.6f) {
            composeTestRule.onNodeWithTag(TITLE_PULL_MARK_TAG).assertIsDisplayed()
        }

        composeTestRule.onNodeWithTag(TITLE_PULL_MARK_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("Host list").assertIsNotDisplayed()
    }

    @Test
    fun consoleScreen_titlePullPastTheThresholdOpensTheSessionDrawer_withAMarkWhilePulling() {
        setContent()
        navigateToConsoleScreen()

        pullTitle(TitlePull.DEFAULT_THRESHOLD * 1.4f) {
            composeTestRule.onNodeWithTag(TITLE_PULL_MARK_TAG).assertIsDisplayed()
            composeTestRule.onNode(hasText("Sessions") and hasAnyAncestor(hasTestTag(TITLE_PULL_MARK_TAG))).assertIsDisplayed()
            composeTestRule.onNodeWithText("Host list").assertIsNotDisplayed()
        }

        composeTestRule.onNodeWithTag(TITLE_PULL_MARK_TAG).assertDoesNotExist()
        // The state the title tap opens.
        composeTestRule.onNodeWithText("Host list").assertIsDisplayed()
    }

    @Test
    fun consoleScreen_titlePullPastTwiceTheThresholdAsks_andDisconnectsOnlyOnConfirm() {
        val mockBridge = mock(TerminalBridge::class.java)
        `when`(mockBridge.host).thenReturn(Host(nickname = "box", hostname = "box.example"))
        `when`(mockBridge.isDisconnected).thenReturn(false)
        val mockViewModel = mock(ConsoleViewModel::class.java)
        `when`(mockViewModel.uiState).thenReturn(
            MutableStateFlow(ConsoleUiState(bridges = listOf(mockBridge), currentBridgeIndex = 0, isLoading = true)),
        )
        `when`(mockViewModel.networkStatusMessages).thenReturn(MutableSharedFlow())
        `when`(mockViewModel.allBridges).thenReturn(MutableStateFlow<List<TerminalBridge>>(emptyList()))
        `when`(mockViewModel.shouldShowNotificationWarning()).thenReturn(false)
        setContent(mockConsoleViewModel = mockViewModel)
        navigateToConsoleScreen(hostId = 1L)

        pullTitle(TitlePull.DEFAULT_THRESHOLD * 2.4f) {
            composeTestRule.onNode(hasText("Disconnect") and hasAnyAncestor(hasTestTag(TITLE_PULL_MARK_TAG))).assertIsDisplayed()
        }

        composeTestRule.onNodeWithText("Host list").assertIsNotDisplayed()
        composeTestRule.onNodeWithText("Yes").assertIsDisplayed()
        verify(mockBridge, never()).dispatchDisconnect(DisconnectReason.USER_REQUESTED)

        composeTestRule.onNodeWithText("Yes").performClick()
        composeTestRule.waitForIdle()
        verify(mockBridge).dispatchDisconnect(DisconnectReason.USER_REQUESTED)
    }

    @Test
    fun consoleScreen_titlePullPastTwiceTheThreshold_dismissedDoesNotDisconnect() {
        val mockBridge = mock(TerminalBridge::class.java)
        `when`(mockBridge.host).thenReturn(Host(nickname = "box", hostname = "box.example"))
        `when`(mockBridge.isDisconnected).thenReturn(false)
        val mockViewModel = mock(ConsoleViewModel::class.java)
        `when`(mockViewModel.uiState).thenReturn(
            MutableStateFlow(ConsoleUiState(bridges = listOf(mockBridge), currentBridgeIndex = 0, isLoading = true)),
        )
        `when`(mockViewModel.networkStatusMessages).thenReturn(MutableSharedFlow())
        `when`(mockViewModel.allBridges).thenReturn(MutableStateFlow<List<TerminalBridge>>(emptyList()))
        `when`(mockViewModel.shouldShowNotificationWarning()).thenReturn(false)
        setContent(mockConsoleViewModel = mockViewModel)
        navigateToConsoleScreen(hostId = 1L)

        pullTitle(TitlePull.DEFAULT_THRESHOLD * 2.4f)
        composeTestRule.onNodeWithText("No").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Yes").assertDoesNotExist()
        verify(mockBridge, never()).dispatchDisconnect(DisconnectReason.USER_REQUESTED)
    }

    @Test
    fun consoleScreen_displaysTextInputButton() {
        setContent()
        navigateToConsoleScreen()

        composeTestRule
            .onNodeWithContentDescription("Draft a long message")
            .assertIsDisplayed()
    }

    @Test
    fun consoleScreen_topBarHasNoSearchOrPaste_searchLivesInTheMenu() {
        setContent()
        navigateToConsoleScreen()

        composeTestRule.onAllNodesWithContentDescription("Paste").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Search everything").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Search everything").assertIsDisplayed()
    }

    @Test
    @Ignore("rotation is disabled until stability is addressed")
    fun consoleScreen_setsLandscapeOrientation_whenPreferenceSet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(
            PreferenceConstants.ROTATION,
            PreferenceConstants.ROTATION_LANDSCAPE,
        ).commit()

        setContent()
        navigateToConsoleScreen()

        composeTestRule.runOnIdle {
            assertEquals(SCREEN_ORIENTATION_LANDSCAPE, composeTestRule.activity.requestedOrientation)
        }
    }

    @Test
    @Ignore("rotation is disabled until stability is addressed")
    fun consoleScreen_setsPortraitOrientation_whenPreferenceSet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(
            PreferenceConstants.ROTATION,
            PreferenceConstants.ROTATION_PORTRAIT,
        ).commit()

        setContent()
        navigateToConsoleScreen()

        composeTestRule.runOnIdle {
            assertEquals(SCREEN_ORIENTATION_PORTRAIT, composeTestRule.activity.requestedOrientation)
        }
    }

    @Test
    @Ignore("rotation is disabled until stability is addressed")
    fun consoleScreen_setsSensorOrientation_whenAutoPreferenceSet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(
            PreferenceConstants.ROTATION,
            PreferenceConstants.ROTATION_AUTO,
        ).commit()

        setContent()
        navigateToConsoleScreen()

        composeTestRule.runOnIdle {
            assertEquals(SCREEN_ORIENTATION_SENSOR, composeTestRule.activity.requestedOrientation)
        }
    }

    @Test
    @Ignore("rotation is disabled until stability is addressed")
    fun consoleScreen_setsPortraitOrientation_whenDefaultPreferenceAndNoHardwareKeyboard() {
        setContent()
        navigateToConsoleScreen()

        composeTestRule.runOnIdle {
            assertEquals(SCREEN_ORIENTATION_PORTRAIT, composeTestRule.activity.requestedOrientation)
        }
    }

    @Test
    @Ignore("rotation is disabled until stability is addressed")
    fun consoleScreen_restoresOriginalOrientation_onNavigateBack() {
        // Set an initial orientation that's different from what ConsoleScreen might set
        composeTestRule.runOnUiThread {
            composeTestRule.activity.requestedOrientation = SCREEN_ORIENTATION_BEHIND
        }

        setContent()
        navigateToConsoleScreen()

        // Verify orientation changed
        composeTestRule.runOnIdle {
            // Default rotation is portrait (without keyboard in tests)
            assertEquals(SCREEN_ORIENTATION_PORTRAIT, composeTestRule.activity.requestedOrientation)
        }

        // Navigate back
        composeTestRule
            .onNodeWithContentDescription("Back")
            .performClick()

        // Verify orientation restored
        composeTestRule.runOnIdle {
            assertEquals(SCREEN_ORIENTATION_BEHIND, composeTestRule.activity.requestedOrientation)
        }
    }

    @Test
    fun consoleScreen_keepsScreenAwake_whenPreferenceEnabledAndConnectionActive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceConstants.KEEP_ALIVE, true)
            .commit()

        val mockViewModel = mock(ConsoleViewModel::class.java)
        val mockBridge = mock(TerminalBridge::class.java)
        `when`(mockBridge.isDisconnected).thenReturn(false)

        val uiStateFlow = MutableStateFlow(
            ConsoleUiState(
                bridges = listOf(mockBridge),
                currentBridgeIndex = 0,
                isLoading = true,
            ),
        )
        val networkStatusMessages = MutableSharedFlow<String>()

        `when`(mockViewModel.uiState).thenReturn(uiStateFlow)
        `when`(mockViewModel.networkStatusMessages).thenReturn(networkStatusMessages)
        `when`(mockViewModel.allBridges).thenReturn(MutableStateFlow<List<TerminalBridge>>(emptyList()))
        `when`(mockViewModel.shouldShowNotificationWarning()).thenReturn(false)

        setContent(mockConsoleViewModel = mockViewModel)
        navigateToConsoleScreen(hostId = 1L)

        composeTestRule.runOnIdle {
            val rootLayout = composeTestRule.activity.findViewById<View>(android.R.id.content)
            assertTrue("Screen should be kept awake", hasKeepScreenOn(rootLayout))
        }
    }

    @Test
    fun consoleScreen_doesNotKeepScreenAwake_whenPreferenceDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceConstants.KEEP_ALIVE, false)
            .commit()

        val mockViewModel = mock(ConsoleViewModel::class.java)
        val mockBridge = mock(TerminalBridge::class.java)
        `when`(mockBridge.isDisconnected).thenReturn(false)

        val uiStateFlow = MutableStateFlow(
            ConsoleUiState(
                bridges = listOf(mockBridge),
                currentBridgeIndex = 0,
                isLoading = true,
            ),
        )
        val networkStatusMessages = MutableSharedFlow<String>()

        `when`(mockViewModel.uiState).thenReturn(uiStateFlow)
        `when`(mockViewModel.networkStatusMessages).thenReturn(networkStatusMessages)
        `when`(mockViewModel.allBridges).thenReturn(MutableStateFlow<List<TerminalBridge>>(emptyList()))
        `when`(mockViewModel.shouldShowNotificationWarning()).thenReturn(false)

        setContent(mockConsoleViewModel = mockViewModel)
        navigateToConsoleScreen(hostId = 1L)

        composeTestRule.runOnIdle {
            val rootLayout = composeTestRule.activity.findViewById<View>(android.R.id.content)
            assertFalse("Screen should not be kept awake when preference is disabled", hasKeepScreenOn(rootLayout))
        }
    }

    @Test
    fun consoleScreen_doesNotKeepScreenAwake_whenDisconnected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PreferenceConstants.KEEP_ALIVE, true)
            .commit()

        val mockViewModel = mock(ConsoleViewModel::class.java)
        val mockBridge = mock(TerminalBridge::class.java)
        `when`(mockBridge.isDisconnected).thenReturn(true)

        val uiStateFlow = MutableStateFlow(
            ConsoleUiState(
                bridges = listOf(mockBridge),
                currentBridgeIndex = 0,
                isLoading = true,
            ),
        )
        val networkStatusMessages = MutableSharedFlow<String>()

        `when`(mockViewModel.uiState).thenReturn(uiStateFlow)
        `when`(mockViewModel.networkStatusMessages).thenReturn(networkStatusMessages)
        `when`(mockViewModel.allBridges).thenReturn(MutableStateFlow<List<TerminalBridge>>(emptyList()))
        `when`(mockViewModel.shouldShowNotificationWarning()).thenReturn(false)

        setContent(mockConsoleViewModel = mockViewModel)
        navigateToConsoleScreen(hostId = 1L)

        composeTestRule.runOnIdle {
            val rootLayout = composeTestRule.activity.findViewById<View>(android.R.id.content)
            assertFalse("Screen should not be kept awake when disconnected", hasKeepScreenOn(rootLayout))
        }
    }

    // The Pin to Inbox entry in the real console menu (ConsoleScreen's wiring). CorePinEntryTest tests the entry alone and
    // CorePinViewModelTest canPinCore alone: with the entry left out of the screen, neither goes red.
    private fun pinScreen(canPin: Boolean, pinned: Boolean?, writes: Boolean): Pair<ConsoleViewModel, TerminalBridge> {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(nickname = "box", hostname = "box.example"))
        `when`(bridge.isDisconnected).thenReturn(false)
        val vm = mock(ConsoleViewModel::class.java)
        `when`(vm.uiState).thenReturn(MutableStateFlow(ConsoleUiState(bridges = listOf(bridge), currentBridgeIndex = 0, isLoading = true)))
        `when`(vm.networkStatusMessages).thenReturn(MutableSharedFlow())
        `when`(vm.allBridges).thenReturn(MutableStateFlow<List<TerminalBridge>>(emptyList()))
        `when`(vm.shouldShowNotificationWarning()).thenReturn(false)
        `when`(vm.canPinCore(anyOrNull())).thenReturn(canPin)
        wheneverBlocking { vm.corePinned(any()) }.thenReturn(pinned)
        wheneverBlocking { vm.setCorePin(any(), any()) }.thenReturn(writes)
        setContent(mockConsoleViewModel = vm)
        navigateToConsoleScreen(hostId = 1L)
        composeTestRule.waitForIdle()
        return vm to bridge
    }

    private fun string(id: Int) = composeTestRule.activity.getString(id)

    private fun openMoreOptions() {
        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeTestRule.waitUntil(5_000) { composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aCoreConsole_menuShowsPin_aTapWritesOnThatBridge_andTheNextOpenReadsAgain() {
        val (vm, bridge) = pinScreen(canPin = true, pinned = false, writes = true)
        openMoreOptions()
        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).assertIsDisplayed().assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        waitForText(string(R.string.console_pin_done))
        verifyBlocking(vm) { setCorePin(bridge, true) }
        composeTestRule.onAllNodesWithText(string(R.string.console_menu_pin)).assertCountEquals(0)
        openMoreOptions()
        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).assertIsDisplayed()
        verifyBlocking(vm, times(2)) { corePinned(bridge) }
    }

    @Test
    fun aPinnedCoreConsole_menuShowsUnpin_andAFailedUnpinSaysSo() {
        val (vm, bridge) = pinScreen(canPin = true, pinned = true, writes = false)
        openMoreOptions()
        composeTestRule.onAllNodesWithText(string(R.string.console_menu_pin)).assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.console_menu_unpin)).assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        waitForText(string(R.string.console_unpin_failed))
        verifyBlocking(vm) { setCorePin(bridge, false) }
    }

    @Test
    fun aConsoleNotOfferedPin_menuHasNoEntry_andReadsNothing() {
        val (vm, _) = pinScreen(canPin = false, pinned = false, writes = true)
        openMoreOptions()
        composeTestRule.onNodeWithText("Search everything").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.console_menu_pin)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.console_menu_unpin)).assertCountEquals(0)
        verifyBlocking(vm, never()) { corePinned(any()) }
    }

    @Test
    fun aCoreConsoleWhoseReadFails_entryIsOffWithItsReason() {
        val (vm, _) = pinScreen(canPin = true, pinned = null, writes = true)
        openMoreOptions()
        waitForText(string(R.string.console_pin_unknown))
        composeTestRule.onNodeWithText(string(R.string.console_menu_pin)).performClick()
        composeTestRule.waitForIdle()
        verifyBlocking(vm, never()) { setCorePin(any(), any()) }
    }

    // The title row of a watched console, with the place of the terminal among the agents ("3 of 9"): the real ConsoleScreen's top
    // bar (isLoading keeps the terminal out), at 360 dp, with native text measuring: the legacy mode measures 1 px a character, so a width check cannot fail there. The place goes at the start of the second line, so the title keeps its width.
    private fun watchedScreen(fontScale: Float) {
        val agents = (1..9).map { Agent(address = "t$it", engine = AgentEngine.CORE, space = "alpha one", tab = "build & test", state = AgentState.Working) }
        val herd = mock(HerdSession::class.java)
        `when`(herd.coreRead).thenReturn(MutableStateFlow(CoreRead(CoreSource.Result.Agents(agents), 10)))
        `when`(herd.attached).thenReturn(MutableStateFlow("default"))
        // The console waits here for a view to open; none is asked for.
        wheneverBlocking { herd.takeView() }.doSuspendableAnswer { awaitCancellation() }
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(id = 1, nickname = "box", hostname = "box.example"))
        `when`(bridge.watch).thenReturn(CoreWatchTarget("t3", "alpha one", "build & test"))
        `when`(bridge.herd).thenReturn(herd)
        `when`(bridge.isDisconnected).thenReturn(false)
        `when`(bridge.activityPreview).thenReturn(MutableStateFlow(""))
        val vm = mock(ConsoleViewModel::class.java)
        `when`(vm.uiState).thenReturn(MutableStateFlow(ConsoleUiState(bridges = listOf(bridge), currentBridgeIndex = 0, isLoading = true)))
        `when`(vm.networkStatusMessages).thenReturn(MutableSharedFlow())
        `when`(vm.allBridges).thenReturn(MutableStateFlow(listOf(bridge)))
        `when`(vm.shouldShowNotificationWarning()).thenReturn(false)
        setContent(mockConsoleViewModel = vm, fontScale = fontScale)
        navigateToConsoleScreen(hostId = 1L)
        composeTestRule.waitUntil(5_000) { composeTestRule.onAllNodesWithContentDescription("Agent 3 of 9", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun inTitle(text: String) = composeTestRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(CONSOLE_TITLE_TAG)), useUnmergedTree = true)

    private fun assertTitleWholeAndPlaceOnSecondLine() {
        val title = mutableListOf<TextLayoutResult>()
        inTitle("build & test").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(title)
        assertFalse("the title keeps its width: \"build & test\" is not cut", title.first().isLineEllipsized(0))
        val titleBounds = inTitle("build & test").fetchSemanticsNode().boundsInRoot
        val place = composeTestRule.onNode(hasContentDescription("Agent 3 of 9") and hasAnyAncestor(hasTestTag(CONSOLE_TITLE_TAG)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val subtitle = inTitle("alpha one \u00b7 box").fetchSemanticsNode().boundsInRoot
        assertTrue("the place is on the second line, under the title", place.top >= titleBounds.bottom - 1f)
        assertTrue("and at its start, before space and host", place.right <= subtitle.left + 1f)
        assertTrue("on the same line as them", kotlin.math.abs(place.center.y - subtitle.center.y) < place.height)
    }

    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun aWatchedConsoleTitle_keepsItsWidth_withThePlaceOnTheSecondLine_at360dp() {
        watchedScreen(fontScale = 1.0f)
        assertTitleWholeAndPlaceOnSecondLine()
    }

    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun aWatchedConsoleTitle_keepsItsWidth_withThePlaceOnTheSecondLine_atFont13() {
        watchedScreen(fontScale = 1.3f)
        assertTitleWholeAndPlaceOnSecondLine()
    }

    private fun hasKeepScreenOn(view: View): Boolean {
        if (view.keepScreenOn) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasKeepScreenOn(view.getChildAt(i))) return true
            }
        }
        return false
    }
}
