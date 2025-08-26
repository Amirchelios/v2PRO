package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

object AutoSelectorManager {

    private const val TAG = "AutoSelectorManager"
    private const val AUTO_SELECTOR_REMARKS = "Auto Selector"
    private const val TCP_PING_TIMEOUT_MS = 3000 // 3 seconds for TCP ping
    private const val CONNECTION_TEST_TIMEOUT_MS = 5000 // 5 seconds for connection quality test

    /**
     * Automatically selects the best proxy from a list of server GUIDs based on TCP ping and connection quality.
     * The selected proxy will be named "Auto Selector" and set as the active server.
     *
     * @param context The application context.
     * @param guidList The list of server GUIDs to choose from.
     * @return The GUID of the selected best proxy, or null if no suitable proxy is found.
     */
    suspend fun autoSelectBestProxy(context: Context, guidList: List<String>): String? = withContext(Dispatchers.IO) {
        if (guidList.isEmpty()) {
            Log.d(TAG, "GUID list is empty, no proxies to auto-select.")
            return@withContext null
        }

        val shuffledGuids = guidList.shuffled(Random(System.currentTimeMillis()))
        Log.d(TAG, "Shuffled GUIDs for auto-selection: $shuffledGuids")

        val results = mutableListOf<ProxyTestResult>()

        for (guid in shuffledGuids) {
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile == null) {
                Log.w(TAG, "Profile for GUID $guid not found, skipping.")
                continue
            }

            Log.d(TAG, "Testing proxy: ${profile.remarks} (${profile.server}:${profile.serverPort})")

            // 1. Perform TCP ping
            val tcpPingResult = performTcpPing(profile)
            Log.d(TAG, "TCP ping for ${profile.remarks}: ${if (tcpPingResult != -1L) "${tcpPingResult}ms" else "Failed"}")

            // 2. Perform connection quality test
            val connectionQualityResult = performConnectionQualityTest(context, guid)
            Log.d(TAG, "Connection quality test for ${profile.remarks}: ${if (connectionQualityResult) "Successful" else "Failed"}")

            results.add(ProxyTestResult(guid, profile, tcpPingResult, connectionQualityResult))
        }

        // Filter for proxies that passed both tests, then by lowest ping
        val bestProxy = results
            .filter { it.connectionQualitySuccessful }
            .filter { it.tcpPingMillis != -1L }
            .minByOrNull { it.tcpPingMillis }

        if (bestProxy != null) {
            Log.i(TAG, "Selected best proxy: ${bestProxy.profile.remarks} (${bestProxy.profile.server}:${bestProxy.profile.serverPort})")

            // Update the selected server's remarks to "Auto Selector"
            bestProxy.profile.remarks = AUTO_SELECTOR_REMARKS
            val newGuid = MmkvManager.encodeServerConfig(bestProxy.guid, bestProxy.profile) // Update existing or create new
            MmkvManager.setSelectServer(newGuid)
            return@withContext newGuid
        }

        Log.w(TAG, "No suitable proxy found after testing all candidates.")
        return@withContext null
    }

    private data class ProxyTestResult(
        val guid: String,
        val profile: ProfileItem,
        val tcpPingMillis: Long,
        val connectionQualitySuccessful: Boolean
    )

    /**
     * Performs a raw TCP ping to the server address and port of the given profile.
     *
     * @param profile The ProfileItem to test.
     * @return The connection time in milliseconds, or -1 if connection failed.
     */
    private fun performTcpPing(profile: ProfileItem): Long {
        val host = profile.server ?: return -1L
        val port = profile.serverPort?.toIntOrNull() ?: return -1L

        return try {
            val socket = Socket()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(host, port), TCP_PING_TIMEOUT_MS)
            val time = System.currentTimeMillis() - start
            socket.close()
            time
        } catch (e: Exception) {
            Log.e(TAG, "TCP ping error for ${profile.remarks} ($host:$port): ${e.message}")
            -1L
        }
    }

    /**
     * Performs a connection quality test using the V2Ray core's outbound delay measurement.
     * This simulates actual traffic through the proxy.
     *
     * @param context The application context.
     * @param guid The GUID of the profile to test.
     * @return True if the connection quality test is successful, false otherwise.
     */
    private fun performConnectionQualityTest(context: Context, guid: String): Boolean {
        return try {
            val conf = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!conf.status) {
                Log.w(TAG, "Failed to get V2Ray config for speedtest for GUID $guid.")
                return false
            }
            // Use realPing which measures outbound delay through the V2Ray core
            val pingResult = SpeedtestManager.realPing(conf.content)
            // A successful ping (non-negative and within a reasonable range) indicates good quality
            // The user mentioned "download and upload" easily, so a successful ping is a good indicator.
            pingResult > 0 && pingResult < CONNECTION_TEST_TIMEOUT_MS
        } catch (e: Exception) {
            Log.e(TAG, "Connection quality test error for GUID $guid: ${e.message}")
            false
        }
    }
}