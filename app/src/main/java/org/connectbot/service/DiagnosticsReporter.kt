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

package org.connectbot.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import org.connectbot.BuildConfig
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.SecurePasswordStorage
import timber.log.Timber
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers what [DiagnosticsReport] needs (app version, device, network,
 * session state, connection log, transcript) and returns the finished,
 * secrets-free report text. Reusable by anything that hands a failure to a
 * person or an agent: "Copy details" today, a wish-list inbox later.
 *
 * Runs on the injected IO dispatcher: it reads connectivity over IPC and the
 * saved passwords it scrubs from the keystore.
 */
@Singleton
class DiagnosticsReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
    private val passwordStorage: SecurePasswordStorage,
) {
    /** Report for [bridge]: its host, state, failure, connection log and transcript. */
    suspend fun build(bridge: TerminalBridge): String = withContext(dispatchers.io) {
        val host = bridge.host
        val state = when {
            bridge.isDisconnected -> "disconnected"
            bridge.isSessionOpen -> "open"
            bridge.isConnecting -> "connecting"
            else -> "not connected"
        }
        val session = SessionSummary(
            nickname = host.nickname,
            protocol = host.protocol,
            username = host.username,
            hostname = host.hostname,
            port = host.port,
            state = state,
            disconnectReason = bridge.disconnectReason.takeIf { bridge.isDisconnected }?.name,
            disconnectedAtMillis = bridge.disconnectedAt.takeIf { bridge.isDisconnected },
            failure = bridge.lastFailure,
            connectionLog = bridge.connectionLogText(),
            transcript = bridge.transcriptText(),
        )
        DiagnosticsReport.build(baseInput(session, failure = null, secrets = savedPasswords(host)))
    }

    /** Report for a [failure] that has no live bridge, optionally about [host]. */
    suspend fun build(failure: Throwable, host: Host? = null): String = withContext(dispatchers.io) {
        val session = host?.let {
            SessionSummary(
                nickname = it.nickname,
                protocol = it.protocol,
                username = it.username,
                hostname = it.hostname,
                port = it.port,
                state = "failed",
            )
        }
        DiagnosticsReport.build(baseInput(session, failure, host?.let { savedPasswords(it) } ?: emptyList()))
    }

    private fun baseInput(session: SessionSummary?, failure: Throwable?, secrets: List<String>) = DiagnosticsInput(
        appName = context.getString(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        applicationId = context.packageName,
        deviceModel = deviceModel(),
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        network = networkSummary(),
        session = session,
        nowMillis = System.currentTimeMillis(),
        timeZone = TimeZone.getDefault(),
        failure = failure,
        secrets = secrets,
    )

    private fun savedPasswords(host: Host): List<String> = listOfNotNull(host.id, host.jumpHostId).mapNotNull { id ->
        try {
            passwordStorage.getPassword(id)
        } catch (e: Exception) {
            // Unreadable storage holds nothing we could leak; report anyway.
            Timber.w(e, "Could not read saved password to scrub from report")
            null
        }
    }

    private fun deviceModel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
    }

    private fun networkSummary(): NetworkSummary {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkSummary(type = "unknown", vpnActive = false)
        return try {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val type = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN only"
                else -> "other"
            }

            // The VPN is usually not the default network's own transport, so
            // look at every network for one.
            @Suppress("DEPRECATION")
            val vpnNetworks = cm.allNetworks.filter {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            val vpnLinks = vpnNetworks.mapNotNull { cm.getLinkProperties(it) }
            val vpnAddresses = vpnLinks.flatMap { link -> link.linkAddresses.map { it.address.hostAddress.orEmpty() } }
            val detail = vpnLinks.joinToString("; ") { link ->
                listOfNotNull(link.interfaceName)
                    .plus(link.linkAddresses.map { it.address.hostAddress.orEmpty() })
                    .joinToString(" ")
            }.ifBlank { null }
            NetworkSummary(
                type = type,
                vpnActive = vpnNetworks.isNotEmpty(),
                tailscaleLikely = vpnAddresses.any { DiagnosticsReport.isCgnat(it) },
                vpnDetail = detail,
            )
        } catch (e: SecurityException) {
            Timber.w(e, "Network state unavailable for report")
            NetworkSummary(type = "unknown", vpnActive = false)
        }
    }
}
