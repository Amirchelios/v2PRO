package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.ServerAffiliationInfo
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object AutoSelectorManager {

    private const val TAG = "AutoSelectorManager"
    const val AUTO_SELECTOR_REMARKS = "Auto Selector"
    private const val TCP_PING_TIMEOUT_MS = 3000 // 3 seconds for TCP ping
    private const val CONNECTION_TEST_TIMEOUT_MS = 5000 // 5 seconds for connection quality test
    private const val PROBE_THROUGHPUT_SIZE_KB = 256 // Size of data to download for throughput test

    // Circuit Breaker settings
    private const val FAILURE_THRESHOLD = 3 // Number of consecutive failures to open the circuit
    private const val OPEN_STATE_DURATION_MS = 60000L // 60 seconds in open state
    private const val HALF_OPEN_PROBE_INTERVAL_MS = 10000L // 10 seconds for half-open probe

    // Scoring weights (can be made configurable in settings later)
    private const val WEIGHT_RTT = 0.35
    private const val WEIGHT_JITTER = 0.15
    private const val WEIGHT_LOSS = 0.25
    private const val WEIGHT_THROUGHPUT = 0.25
    private const val PENALTY_FAILURE = 10000L // Penalty for a failed test in ms

    // EWMA alpha for smoothing metrics (0.0 to 1.0, higher means less smoothing)
    private const val EWMA_ALPHA = 0.3

    private val circuitBreakers = mutableMapOf<String, CircuitBreakerState>()

    private data class CircuitBreakerState(
        var state: State = State.CLOSED,
        var lastFailureTime: Long = 0L,
        var consecutiveFailures: Int = 0
    ) {
        enum class State {
            CLOSED, OPEN, HALF_OPEN
        }
    }

    /**
     * Represents the detailed test results for a proxy.
     */
    private data class ProxyTestResult(
        val guid: String,
        val profile: ProfileItem,
        var rtt: Long = -1L, // Round Trip Time in ms
        var jitter: Long = -1L, // Jitter in ms (variance of RTT)
        var packetLoss: Double = -1.0, // Packet loss percentage
        var throughputKbps: Long = -1L, // Throughput in Kbps
        var connectionSuccessful: Boolean = false, // Overall connection success
        var lastTestTime: Long = 0L,
        var historicalMetrics: HistoricalMetrics? = null
    )

    /**
     * Represents historical performance metrics for a proxy.
     */
    data class HistoricalMetrics(
        var averageRtt: Long = -1L,
        var averageJitter: Long = -1L,
        var averageThroughputKbps: Long = -1L,
        var failureCount: Int = 0,
        var successCount: Int = 0,
        var lastUpdateTime: Long = 0L
    )

    /**
     * Automatically selects the best proxy from a list of server GUIDs based on comprehensive metrics.
     * The selected proxy will be named "Auto Selector" and set as the active server.
     *
     * @param context The application context.
     * @param guidList The list of server GUIDs to choose from.
     * @return The GUID of the selected best proxy, or null if no suitable proxy is found.
     */
    /**
     * Automatically selects the best proxy from a list of server GUIDs based on comprehensive metrics.
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

        val allResults = mutableListOf<ProxyTestResult>()

        for (guid in shuffledGuids) {
            val profile = MmkvManager.decodeServerConfig(guid)
            if (profile == null) {
                Log.w(TAG, "Profile for GUID $guid not found, skipping.")
                continue
            }

            val cbState = circuitBreakers.getOrPut(guid) { CircuitBreakerState() }
            val currentTime = System.currentTimeMillis()

            when (cbState.state) {
                CircuitBreakerState.State.OPEN -> {
                    if (currentTime - cbState.lastFailureTime < OPEN_STATE_DURATION_MS) {
                        Log.d(TAG, "Proxy ${profile.remarks} is in OPEN state, skipping.")
                        continue
                    } else {
                        cbState.state = CircuitBreakerState.State.HALF_OPEN
                        Log.d(TAG, "Proxy ${profile.remarks} moved to HALF_OPEN state.")
                    }
                }
                CircuitBreakerState.State.HALF_OPEN -> {
                    if (currentTime - cbState.lastFailureTime < HALF_OPEN_PROBE_INTERVAL_MS) {
                        Log.d(TAG, "Proxy ${profile.remarks} in HALF_OPEN state, waiting for probe interval.")
                        continue
                    }
                }
                else -> {} // CLOSED state, proceed with testing
            }

            Log.d(TAG, "Probing proxy: ${profile.remarks} (${profile.server}:${profile.serverPort})")

            // Load historical metrics
            val historicalMetrics = MmkvManager.decodeHistoricalMetrics(guid) ?: HistoricalMetrics()
            val result = ProxyTestResult(guid, profile, lastTestTime = currentTime, historicalMetrics = historicalMetrics)

            // 1. Perform TCP ping (for RTT and Jitter)
            val tcpPingTimes = mutableListOf<Long>()
            for (i in 0 until 3) { // Perform multiple pings for better RTT/Jitter estimation
                val ping = performTcpPing(profile)
                if (ping != -1L) {
                    tcpPingTimes.add(ping)
                }
            }
            if (tcpPingTimes.isNotEmpty()) {
                result.rtt = tcpPingTimes.average().toLong()
                result.jitter = if (tcpPingTimes.size > 1) Utils.calculateJitter(tcpPingTimes) else 0L
            }
            Log.d(TAG, "TCP ping for ${profile.remarks}: RTT=${result.rtt}ms, Jitter=${result.jitter}ms")

            // 2. Perform connection quality test (for overall success and throughput)
            val connectionQuality = performConnectionQualityTest(context, guid)
            result.connectionSuccessful = connectionQuality
            Log.d(TAG, "Connection quality test for ${profile.remarks}: ${if (connectionQuality) "Successful" else "Failed"}")

            // 3. Perform a small throughput test (if connection is successful)
            if (result.connectionSuccessful) {
                val throughput = performThroughputTest(context, guid, PROBE_THROUGHPUT_SIZE_KB)
                result.throughputKbps = throughput
                Log.d(TAG, "Throughput for ${profile.remarks}: ${result.throughputKbps} Kbps")
            }

            // Update historical metrics and circuit breaker state
            updateHistoricalMetrics(result)
            MmkvManager.encodeHistoricalMetrics(guid, result.historicalMetrics!!) // Save updated historical metrics

            if (!result.connectionSuccessful || result.rtt == -1L) {
                cbState.consecutiveFailures++
                cbState.lastFailureTime = currentTime
                if (cbState.consecutiveFailures >= FAILURE_THRESHOLD) {
                    cbState.state = CircuitBreakerState.State.OPEN
                    Log.w(TAG, "Circuit for ${profile.remarks} OPENED due to ${cbState.consecutiveFailures} consecutive failures.")
                }
            } else {
                cbState.consecutiveFailures = 0
                cbState.state = CircuitBreakerState.State.CLOSED
            }

            allResults.add(result)
        }

        // Filter out proxies that are in OPEN state or failed all tests
        val availableProxies = allResults.filter {
            circuitBreakers[it.guid]?.state != CircuitBreakerState.State.OPEN && it.connectionSuccessful && it.rtt != -1L
        }

        if (availableProxies.isEmpty()) {
            Log.w(TAG, "No suitable proxy found after testing all candidates and applying circuit breaker.")
            return@withContext null
        }

        // Score and select the best proxy
        val scoredProxies = availableProxies.map {
            it to calculateScore(it)
        }.sortedBy { it.second } // Sort by lowest score (lower is better)

        val bestProxyResult = scoredProxies.firstOrNull()?.first

        if (bestProxyResult != null) {
            Log.i(TAG, "Selected best proxy: ${bestProxyResult.profile.remarks} (${bestProxyResult.profile.server}:${bestProxyResult.profile.serverPort}) with score ${scoredProxies.first().second}")

            // Update the selected server's remarks to "Auto Selector"
            bestProxyResult.profile.remarks = AUTO_SELECTOR_REMARKS
            val newGuid = MmkvManager.encodeServerConfig(bestProxyResult.guid, bestProxyResult.profile) // Update existing or create new
            MmkvManager.setSelectServer(newGuid)
            return@withContext newGuid
        }

        Log.w(TAG, "No suitable proxy found after scoring all candidates.")
        return@withContext null
    }

    /**
     * Returns the best available proxy based on historical metrics without performing new tests.
     * This is used for quick initial selection.
     *
     * @param guidList The list of server GUIDs to choose from.
     * @return The GUID of the best available proxy, or null if none found.
     */
    fun getBestAvailableProxy(guidList: List<String>): String? {
        if (guidList.isEmpty()) {
            Log.d(TAG, "GUID list is empty, no proxies to select from historical data.")
            return null
        }

        val availableProxies = guidList.mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            val historicalMetrics = MmkvManager.decodeHistoricalMetrics(guid)

            if (profile != null && historicalMetrics != null && historicalMetrics.successCount > 0) {
                // Create a dummy ProxyTestResult to use the existing scoring mechanism
                ProxyTestResult(
                    guid = guid,
                    profile = profile,
                    rtt = historicalMetrics.averageRtt,
                    jitter = historicalMetrics.averageJitter,
                    throughputKbps = historicalMetrics.averageThroughputKbps,
                    connectionSuccessful = true, // Assume successful if historical data exists
                    lastTestTime = historicalMetrics.lastUpdateTime,
                    historicalMetrics = historicalMetrics
                )
            } else {
                null
            }
        }.filter {
            // Filter out proxies that are in OPEN state based on circuit breaker
            circuitBreakers[it.guid]?.state != CircuitBreakerState.State.OPEN
        }

        if (availableProxies.isEmpty()) {
            Log.w(TAG, "No suitable proxy found from historical data.")
            return null
        }

        val scoredProxies = availableProxies.map {
            it to calculateScore(it)
        }.sortedBy { it.second }

        val bestProxyGuid = scoredProxies.firstOrNull()?.first?.guid
        Log.i(TAG, "Selected best proxy from historical data: ${MmkvManager.decodeServerConfig(bestProxyGuid!!)?.remarks} with score ${scoredProxies.first().second}")
        return bestProxyGuid
    }

    /**
     * Updates the historical metrics for a proxy using EWMA.
     */
    private fun updateHistoricalMetrics(result: ProxyTestResult) {
        val metrics = result.historicalMetrics ?: return
        val alpha = EWMA_ALPHA

        if (result.connectionSuccessful) {
            metrics.successCount++
            if (metrics.averageRtt == -1L) {
                metrics.averageRtt = result.rtt
            } else {
                metrics.averageRtt = (alpha * result.rtt + (1 - alpha) * metrics.averageRtt).toLong()
            }
            if (metrics.averageJitter == -1L) {
                metrics.averageJitter = result.jitter
            } else {
                metrics.averageJitter = (alpha * result.jitter + (1 - alpha) * metrics.averageJitter).toLong()
            }
            if (metrics.averageThroughputKbps == -1L) {
                metrics.averageThroughputKbps = result.throughputKbps
            } else {
                metrics.averageThroughputKbps = (alpha * result.throughputKbps + (1 - alpha) * metrics.averageThroughputKbps).toLong()
            }
        } else {
            metrics.failureCount++
        }
        metrics.lastUpdateTime = System.currentTimeMillis()
    }

    /**
     * Calculates a score for a proxy based on its test results and historical data. Lower score is better.
     */
    private fun calculateScore(result: ProxyTestResult): Double {
        val metrics = result.historicalMetrics ?: HistoricalMetrics()

        // Use current test results if available and valid, otherwise fall back to historical averages
        val currentRtt = if (result.rtt != -1L) result.rtt.toDouble() else metrics.averageRtt.toDouble()
        val currentJitter = if (result.jitter != -1L) result.jitter.toDouble() else metrics.averageJitter.toDouble()
        val currentThroughput = if (result.throughputKbps != -1L) result.throughputKbps.toDouble() else metrics.averageThroughputKbps.toDouble()

        // Normalize metrics to a 0-1 range (or similar)
        val normalizedRtt = normalize(currentRtt, 0.0, 3000.0) // Assuming max RTT of 3000ms
        val normalizedJitter = normalize(currentJitter, 0.0, 500.0) // Assuming max Jitter of 500ms
        val normalizedThroughput = normalize(currentThroughput, 0.0, 10000.0, inverse = true) // Higher throughput is better, so inverse

        var score = (WEIGHT_RTT * normalizedRtt) +
                (WEIGHT_JITTER * normalizedJitter) +
                (WEIGHT_THROUGHPUT * normalizedThroughput)

        // Apply penalty for any non-successful connection, even if it passed initial filters
        if (!result.connectionSuccessful || result.rtt == -1L) {
            score += PENALTY_FAILURE
        }

        // Incorporate historical failure rate
        if (metrics.successCount + metrics.failureCount > 0) {
            val failureRate = metrics.failureCount.toDouble() / (metrics.successCount + metrics.failureCount)
            score += failureRate * PENALTY_FAILURE // Scale penalty by failure rate
        }

        // Add a small penalty for older data to favor more recent tests
        val agePenalty = (System.currentTimeMillis() - metrics.lastUpdateTime).toDouble() / (OPEN_STATE_DURATION_MS * 2) // Max penalty after 2 minutes of old data
        score += min(agePenalty, PENALTY_FAILURE.toDouble()) // Cap the age penalty

        return score
    }

    /**
     * Normalizes a value to a 0-1 range.
     */
    private fun normalize(value: Double, minVal: Double, maxVal: Double, inverse: Boolean = false): Double {
        if (maxVal == minVal) return 0.0
        val normalized = (value - minVal) / (maxVal - minVal)
        return if (inverse) 1.0 - normalized else normalized
    }

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
            pingResult > 0 && pingResult < CONNECTION_TEST_TIMEOUT_MS
        } catch (e: Exception) {
            Log.e(TAG, "Connection quality test error for GUID $guid: ${e.message}")
            false
        }
    }

    /**
     * Performs a small throughput test by attempting to download a small amount of data.
     * This is a simplified simulation and might not reflect actual download speeds accurately.
     *
     * @param context The application context.
     * @param guid The GUID of the profile to test.
     * @param sizeKb The target size of data to "download" in KB.
     * @return Estimated throughput in Kbps, or -1L if failed.
     */
    private fun performThroughputTest(context: Context, guid: String, sizeKb: Int): Long {
        return try {
            val conf = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!conf.status) {
                return -1L
            }
            // Simulate a download by measuring the time it takes to "process" a certain amount of data
            // This is a placeholder and would ideally involve actual data transfer.
            val startTime = System.currentTimeMillis()
            // Simulate work proportional to sizeKb
            Thread.sleep(min(500, sizeKb).toLong()) // Simulate 1ms per KB, max 500ms
            val endTime = System.currentTimeMillis()
            val durationMs = endTime - startTime

            if (durationMs > 0) {
                // Calculate Kbps: (sizeKb * 8 bits/byte * 1000 ms/s) / durationMs
                (sizeKb * 8 * 1000L) / durationMs
            } else {
                -1L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Throughput test error for GUID $guid: ${e.message}")
            -1L
        }
    }
}