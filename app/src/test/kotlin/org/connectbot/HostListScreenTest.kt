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

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.connectbot.data.entity.Host
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.home.WorkboardsSection
import org.connectbot.ui.screens.hostlist.ConnectionState
import org.connectbot.ui.screens.hostlist.HostListScreen
import org.connectbot.ui.screens.hostlist.HostListScreenContent
import org.connectbot.ui.screens.hostlist.HostListTestTags
import org.connectbot.ui.screens.hostlist.HostListUiState
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.ui.workspaces.WorkspacesSection
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.HerdrWorkspace
import solutions.ormus.logos.home.HomeBoard
import solutions.ormus.logos.home.HomeWorkspace
import solutions.ormus.logos.home.HostPlace
import solutions.ormus.logos.home.WorkboardBlock
import solutions.ormus.logos.home.WorkboardKey
import solutions.ormus.logos.home.WorkboardTarget

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HostListScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun hostListScreen_displaysTitle() {
        val title = composeTestRule.activity.getString(R.string.app_name)

        composeTestRule.setContent {
            ConnectBotTheme {
                HostListScreen(
                    onNavigateToConsole = {},
                    onNavigateToEditHost = {},
                    onNavigateToSettings = {},
                    onNavigateToPubkeys = {},
                    onNavigateToPortForwards = {},
                    onNavigateToProfiles = {},
                    onNavigateToHelp = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(title)
            .assertIsDisplayed()
    }

    @Test
    fun hostListScreen_hasAddButton() {
        val addHost = composeTestRule.activity.getString(R.string.hostpref_add_host)

        composeTestRule.setContent {
            ConnectBotTheme {
                HostListScreen(
                    onNavigateToConsole = {},
                    onNavigateToEditHost = {},
                    onNavigateToSettings = {},
                    onNavigateToPubkeys = {},
                    onNavigateToPortForwards = {},
                    onNavigateToProfiles = {},
                    onNavigateToHelp = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(addHost, ignoreCase = true)
            .assertIsDisplayed()
    }

    @Test
    fun hostListScreen_menuOpensOnClick() {
        val moreOptions = composeTestRule.activity.getString(R.string.button_more_options)
        val managePubkeys = composeTestRule.activity.getString(R.string.list_menu_pubkeys)

        composeTestRule.setContent {
            ConnectBotTheme {
                HostListScreen(
                    onNavigateToConsole = {},
                    onNavigateToEditHost = {},
                    onNavigateToSettings = {},
                    onNavigateToPubkeys = {},
                    onNavigateToPortForwards = {},
                    onNavigateToProfiles = {},
                    onNavigateToHelp = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(moreOptions)
            .performClick()

        composeTestRule
            .onNodeWithText(managePubkeys)
            .assertIsDisplayed()
    }

    @Test
    fun hostListScreen_addButtonCallsCallback() {
        var addHostCalled = false
        val addHost = composeTestRule.activity.getString(R.string.hostpref_add_host)

        composeTestRule.setContent {
            ConnectBotTheme {
                HostListScreen(
                    onNavigateToConsole = {},
                    onNavigateToEditHost = { addHostCalled = true },
                    onNavigateToSettings = {},
                    onNavigateToPubkeys = {},
                    onNavigateToPortForwards = {},
                    onNavigateToProfiles = {},
                    onNavigateToHelp = {},
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(addHost, ignoreCase = true)
            .performClick()

        assert(addHostCalled)
    }

    @Test
    fun hostListScreen_showsSnackbar_whenShouldShowWarning() {
        val warning = composeTestRule.activity.getString(R.string.notification_permission_denied_snackbar)

        composeTestRule.setContent {
            ConnectBotTheme {
                HostListScreen(
                    onNavigateToConsole = {},
                    onNavigateToEditHost = {},
                    onNavigateToSettings = {},
                    onNavigateToPubkeys = {},
                    onNavigateToPortForwards = {},
                    onNavigateToProfiles = {},
                    onNavigateToHelp = {},
                    shouldShowNotificationWarning = { true },
                    onNotificationSnackbarFinish = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(warning)
            .assertIsDisplayed()
    }

    @Test
    fun hostListScreen_snackbarSettingsAction_navigatesToSettingsHighlight() {
        var navigatedToSettingsHighlight = false
        val settings = composeTestRule.activity.getString(R.string.list_menu_settings)

        composeTestRule.setContent {
            ConnectBotTheme {
                HostListScreen(
                    onNavigateToConsole = {},
                    onNavigateToEditHost = {},
                    onNavigateToSettings = {},
                    onNavigateToSettingsHighlightConnPersist = { navigatedToSettingsHighlight = true },
                    onNavigateToPubkeys = {},
                    onNavigateToPortForwards = {},
                    onNavigateToProfiles = {},
                    onNavigateToHelp = {},
                    shouldShowNotificationWarning = { true },
                    onNotificationSnackbarFinish = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(settings)
            .performClick()

        assertTrue(navigatedToSettingsHighlight)
    }

    @Test
    fun hostListScreen_noSnackbar_whenShouldNotShowWarning() {
        val warning = composeTestRule.activity.getString(R.string.notification_permission_denied_snackbar)

        composeTestRule.setContent {
            ConnectBotTheme {
                HostListScreen(
                    onNavigateToConsole = {},
                    onNavigateToEditHost = {},
                    onNavigateToSettings = {},
                    onNavigateToPubkeys = {},
                    onNavigateToPortForwards = {},
                    onNavigateToProfiles = {},
                    onNavigateToHelp = {},
                    shouldShowNotificationWarning = { false },
                    onNotificationSnackbarFinish = {},
                )
            }
        }

        composeTestRule
            .onAllNodes(androidx.compose.ui.test.hasText(warning))
            .fetchSemanticsNodes()
            .let { nodes -> assertTrue("Snackbar should not be shown", nodes.isEmpty()) }
    }

    @Test
    fun hostListScreenContent_emptyStateAddButtonNavigatesToEditor() {
        var editedHost: Host? = Host(nickname = "sentinel", hostname = "sentinel")

        setHostListContent(
            uiState = HostListUiState(hosts = emptyList()),
            onNavigateToEditHost = { editedHost = it },
        )

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.empty_hosts_message))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.hostpref_add_host))
            .performClick()

        assertTrue(editedHost == null)
    }

    @Test
    fun hostListScreenContent_topMenuInvokesActionsAndDisconnectAll() {
        var sortCalled = false
        var settingsCalled = false
        var profilesCalled = false
        var pubkeysCalled = false
        var exportCalled = false
        var importCalled = false
        var helpCalled = false
        var disconnectAllCalled = false

        setHostListContent(
            uiState = HostListUiState(sortedByColor = true),
            onToggleSortOrder = { sortCalled = true },
            onNavigateToSettings = { settingsCalled = true },
            onNavigateToProfiles = { profilesCalled = true },
            onNavigateToPubkeys = { pubkeysCalled = true },
            onExportHosts = { exportCalled = true },
            onImportHosts = { importCalled = true },
            onNavigateToHelp = { helpCalled = true },
            onDisconnectAll = { disconnectAllCalled = true },
        )

        openTopMenu()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_menu_sortname)).performClick()
        assertTrue(sortCalled)

        openTopMenu()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_menu_settings)).performClick()
        assertTrue(settingsCalled)

        openTopMenu()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.profile_list_title)).performClick()
        assertTrue(profilesCalled)

        openTopMenu()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_menu_pubkeys)).performClick()
        assertTrue(pubkeysCalled)

        openTopMenu()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_menu_export_hosts)).performClick()
        assertTrue(exportCalled)

        openTopMenu()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_menu_import_hosts)).performClick()
        assertTrue(importCalled)

        openTopMenu()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.title_help)).performClick()
        assertTrue(helpCalled)

        openTopMenu()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_menu_disconnect)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.disconnect_all_pos)).performClick()
        assertTrue(disconnectAllCalled)
    }

    @Test
    fun hostListScreenContent_hostRowsShowStateAndNavigate() {
        var navigatedHost: Host? = null
        val connected = testHost(id = 1L, nickname = "prod", protocol = "ssh", color = "#4CAF50")
        val disconnected = testHost(id = 2L, nickname = "legacy", protocol = "telnet", color = "#F44336")

        setHostListContent(
            uiState = HostListUiState(
                hosts = listOf(connected, disconnected),
                connectionStates = mapOf(
                    connected.id to ConnectionState.CONNECTED,
                    disconnected.id to ConnectionState.DISCONNECTED,
                ),
            ),
            onNavigateToConsole = { navigatedHost = it },
        )

        composeTestRule
            .onNodeWithContentDescription(composeTestRule.activity.getString(R.string.image_description_connected))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(composeTestRule.activity.getString(R.string.image_description_disconnected))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(HostListTestTags.itemRow(connected.id))
            .performClick()
        assertTrue(navigatedHost == connected)
    }

    @Test
    fun hostListScreenContent_makingShortcutSelectsHost() {
        var selectedShortcutHost: Host? = null
        val connected = testHost(id = 1L, nickname = "prod", protocol = "ssh", color = "#4CAF50")

        setHostListContent(
            uiState = HostListUiState(hosts = listOf(connected)),
            makingShortcut = true,
            onSelectShortcut = { selectedShortcutHost = it },
        )

        composeTestRule
            .onNodeWithTag(HostListTestTags.itemRow(connected.id))
            .performClick()
        assertTrue(selectedShortcutHost == connected)
    }

    @Test
    fun hostListScreenContent_hostMenuInvokesActionsAndDialogs() {
        var editedHost: Host? = null
        var portForwardHost: Host? = null
        var duplicatedHost: Host? = null
        var forgottenHost: Host? = null
        var disconnectedHost: Host? = null
        var deletedHost: Host? = null
        val host = testHost(id = 3L, nickname = "staging", protocol = "ssh")

        setHostListContent(
            uiState = HostListUiState(
                hosts = listOf(host),
                connectionStates = mapOf(host.id to ConnectionState.CONNECTED),
            ),
            onNavigateToEditHost = { editedHost = it },
            onNavigateToPortForwards = { portForwardHost = it },
            onDuplicateHost = { duplicatedHost = it },
            onForgetHostKeys = { forgottenHost = it },
            onDisconnectHost = { disconnectedHost = it },
            onDeleteHost = { deletedHost = it },
        )

        openHostMenu(host)
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_host_edit)).performClick()
        assertTrue(editedHost == host)

        openHostMenu(host)
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_host_portforwards)).performClick()
        assertTrue(portForwardHost == host)

        openHostMenu(host)
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_host_duplicate)).performClick()
        assertTrue(duplicatedHost == host)

        openHostMenu(host)
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_host_forget_keys)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.button_yes)).performClick()
        assertTrue(forgottenHost == host)

        openHostMenu(host)
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_host_disconnect)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.button_yes)).performClick()
        assertTrue(disconnectedHost == host)

        openHostMenu(host)
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_host_delete)).performClick()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.button_yes)).performClick()
        assertTrue(deletedHost == host)
    }

    @Test
    fun hostListScreenContent_disconnectDisabledWhenHostNotConnected() {
        val host = testHost(id = 4L, nickname = "offline", protocol = "ssh", color = null)

        setHostListContent(
            uiState = HostListUiState(
                hosts = listOf(host),
                connectionStates = mapOf(host.id to ConnectionState.DISCONNECTED),
            ),
        )

        openHostMenu(host)
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.list_host_disconnect))
            .assertIsNotEnabled()
    }

    @Test
    fun hostListScreenContent_hostsSectionComesBeforeMachines() {
        val host = testHost(id = 5L, nickname = "alpha", protocol = "ssh")
        val machinesTitle = composeTestRule.activity.getString(R.string.machines_title)

        setHostListContent(
            uiState = HostListUiState(hosts = listOf(host)),
            machines = {
                Column {
                    MonoLabel(text = machinesTitle)
                    (1..4).forEach { Text("machine-$it") }
                }
            },
        )

        val hostsTop = composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.host_list_section))
            .assertIsDisplayed()
            .getBoundsInRoot()
            .top
        val machinesTop = composeTestRule
            .onNodeWithText(machinesTitle)
            .assertIsDisplayed()
            .getBoundsInRoot()
            .top
        val hostRowTop = composeTestRule
            .onNodeWithTag(HostListTestTags.itemRow(host.id))
            .getBoundsInRoot()
            .top
        assertTrue("Hosts header above Machines header", hostsTop < machinesTop)
        assertTrue("Saved host row above Machines header", hostRowTop < machinesTop)
    }

    private fun openTopMenu() {
        composeTestRule
            .onNodeWithContentDescription(composeTestRule.activity.getString(R.string.button_more_options))
            .performClick()
    }

    private fun openHostMenu(host: Host) {
        composeTestRule
            .onNodeWithTag(HostListTestTags.itemMenuButton(host.id))
            .performClick()
    }

    private fun setHostListContent(
        uiState: HostListUiState = HostListUiState(),
        makingShortcut: Boolean = false,
        onNavigateToConsole: (Host) -> Unit = {},
        onSelectShortcut: (Host) -> Unit = {},
        onNavigateToEditHost: (Host?) -> Unit = {},
        onNavigateToSettings: () -> Unit = {},
        onNavigateToPubkeys: () -> Unit = {},
        onNavigateToPortForwards: (Host) -> Unit = {},
        onNavigateToProfiles: () -> Unit = {},
        onNavigateToHelp: () -> Unit = {},
        onToggleSortOrder: () -> Unit = {},
        onDeleteHost: (Host) -> Unit = {},
        onDuplicateHost: (Host) -> Unit = {},
        onForgetHostKeys: (Host) -> Unit = {},
        onDisconnectHost: (Host) -> Unit = {},
        onDisconnectAll: () -> Unit = {},
        onExportHosts: () -> Unit = {},
        onImportHosts: () -> Unit = {},
        machines: @Composable () -> Unit = {},
        workspaces: (@Composable () -> Unit)? = null,
        workboards: (@Composable () -> Unit)? = null,
        places: Map<Long, HostPlace> = emptyMap(),
        sortedByPlace: Boolean = false,
    ) {
        composeTestRule.setContent {
            ConnectBotTheme {
                HostListScreenContent(
                    uiState = uiState,
                    makingShortcut = makingShortcut,
                    onNavigateToConsole = onNavigateToConsole,
                    onSelectShortcut = onSelectShortcut,
                    onNavigateToEditHost = onNavigateToEditHost,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToPubkeys = onNavigateToPubkeys,
                    onNavigateToPortForwards = onNavigateToPortForwards,
                    onNavigateToProfiles = onNavigateToProfiles,
                    onNavigateToHelp = onNavigateToHelp,
                    onToggleSortOrder = onToggleSortOrder,
                    onDeleteHost = onDeleteHost,
                    onDuplicateHost = onDuplicateHost,
                    onForgetHostKeys = onForgetHostKeys,
                    onDisconnectHost = onDisconnectHost,
                    onDisconnectAll = onDisconnectAll,
                    onExportHosts = onExportHosts,
                    onImportHosts = onImportHosts,
                    machines = machines,
                    workspaces = workspaces,
                    workboards = workboards,
                    places = places,
                    sortedByPlace = sortedByPlace,
                )
            }
        }
    }

    @Test
    fun workspacesSection_onlyWhenTheScreenHasOne() {
        setHostListContent(uiState = HostListUiState(hosts = listOf(testHost(1L, "box")), isLoading = false))
        composeTestRule.onNodeWithText("Workspaces").assertDoesNotExist()
        composeTestRule.onNodeWithText("Hosts").assertIsDisplayed()
    }

    @Test
    fun aHostRow_readsItsPlaceUnderTheNickname_elseItsAddress() {
        val lab = testHost(1L, "Lab")
        val far = testHost(2L, "Far")
        setHostListContent(
            uiState = HostListUiState(hosts = listOf(lab, far), isLoading = false),
            places = mapOf(lab.id to HostPlace("lab", "work")),
        )
        composeTestRule.onNodeWithText("lab · work").assertIsDisplayed()
        composeTestRule.onNodeWithText("ssh://Lab.example.com:22").assertDoesNotExist()
        composeTestRule.onNodeWithText("ssh://Far.example.com:22").assertIsDisplayed()
    }

    @Test
    fun theSortMenu_offersPlace_whileAFeedOrdersTheRows() {
        setHostListContent(uiState = HostListUiState(sortedByColor = true), sortedByPlace = true)
        openTopMenu()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_menu_sortplace)).assertIsDisplayed()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.list_menu_sortname)).assertDoesNotExist()
    }

    @Test
    fun workboardsSection_firstAboveTheHosts() {
        val box = testHost(1L, "box").copy(postLogin = "herdr\n")
        val board = HomeBoard("work", machine = "box", session = "work", workspaces = listOf(HomeWorkspace("review")))
        val key = WorkboardKey(board, board.workspaces.single(), WorkboardTarget(box, HerdrConsole("work", workspaceLabel = "review")))
        setHostListContent(
            uiState = HostListUiState(hosts = listOf(box), isLoading = false),
            workboards = { WorkboardsSection(blocks = listOf(WorkboardBlock(board, listOf(key))), fetchedAt = 0L, error = null, onOpen = { _, _ -> }) },
        )
        composeTestRule.onNodeWithText("Workboards").assertIsDisplayed()
        composeTestRule.onNodeWithText("review").assertIsDisplayed()
        val boards = composeTestRule.onNodeWithText("Workboards").fetchSemanticsNode().positionInRoot.y
        val hosts = composeTestRule.onNodeWithText("Hosts").fetchSemanticsNode().positionInRoot.y
        assertTrue("the Workboards heading sits above the Hosts heading", boards < hosts)
    }

    @Test
    fun workspacesSection_firstAboveTheHosts() {
        val box = testHost(1L, "box").copy(postLogin = "herdr\n")
        setHostListContent(
            uiState = HostListUiState(hosts = listOf(box), isLoading = false),
            workspaces = { WorkspacesSection(workspaces = listOf(HerdrWorkspace(box, HerdrConsole("review"))), onOpen = { _, _ -> }) },
        )
        composeTestRule.onNodeWithText("Workspaces").assertIsDisplayed()
        composeTestRule.onNodeWithText("review").assertIsDisplayed()
        val keys = composeTestRule.onNodeWithText("Workspaces").fetchSemanticsNode().positionInRoot.y
        val hosts = composeTestRule.onNodeWithText("Hosts").fetchSemanticsNode().positionInRoot.y
        assertTrue("the Workspaces heading sits above the Hosts heading", keys < hosts)
    }

    private fun testHost(
        id: Long,
        nickname: String,
        protocol: String = "ssh",
        color: String? = "#2196F3",
    ): Host = Host(
        id = id,
        nickname = nickname,
        protocol = protocol,
        username = "user",
        hostname = "$nickname.example.com",
        port = if (protocol == "telnet") 23 else 22,
        color = color,
    )
}
