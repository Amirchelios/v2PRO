package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.ServerAffiliationInfo
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.junit.MockitoJUnitRunner

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class AutoSelectorManagerTest {

    @Mock
    lateinit var mockContext: Context

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        mockkObject(MmkvManager)
        mockkObject(SpeedtestManager)
        mockkObject(V2rayConfigManager)
        mockkObject(HttpUtil)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `autoSelectBestProxy returns null if guidList is empty`() = runTest {
        val result = AutoSelectorManager.autoSelectBestProxy(mockContext, emptyList())
        assert(result == null)
    }

    @Test
    fun `getBestAvailableProxy returns null if guidList is empty`() = runTest {
        val result = AutoSelectorManager.getBestAvailableProxy(emptyList())
        assert(result == null)
    }

    @Test
    fun `autoSelectBestProxy successfully selects a best proxy`() = runTest {
        val guid1 = "guid1"
        val guid2 = "guid2"
        val profile1 = ProfileItem(remarks = "Server 1", server = "1.1.1.1", serverPort = "80", configType = EConfigType.VMESS)
        val profile2 = ProfileItem(remarks = "Server 2", server = "2.2.2.2", serverPort = "443", configType = EConfigType.VLESS)

        every { MmkvManager.decodeServerConfig(guid1) } returns profile1
        every { MmkvManager.decodeServerConfig(guid2) } returns profile2
        every { MmkvManager.decodeServerAffiliationInfo(any()) } returns ServerAffiliationInfo()
        every { MmkvManager.decodeHistoricalMetrics(any()) } returns AutoSelectorManager.HistoricalMetrics()
        every { MmkvManager.encodeHistoricalMetrics(any(), any()) } just Runs
        every { MmkvManager.encodeServerAffiliationInfo(any(), any()) } just Runs
        every { MmkvManager.encodeServerConfig(any(), any()) } returnsArgument 0
        every { MmkvManager.setSelectServer(any()) } just Runs

        // Mock successful TCP ping for both
        every { SpeedtestManager.tcping(profile1.server!!, profile1.serverPort!!.toInt()) } returns 100L
        every { SpeedtestManager.tcping(profile2.server!!, profile2.serverPort!!.toInt()) } returns 50L

        // Mock successful connection quality test for both
        every { V2rayConfigManager.getV2rayConfig4Speedtest(mockContext, guid1) } returns ConfigResult(true, "config1", 10808)
        every { V2rayConfigManager.getV2rayConfig4Speedtest(mockContext, guid2) } returns ConfigResult(true, "config2", 10809)
        every { SpeedtestManager.realPing(any()) } returns 200L

        // Mock successful throughput test for both
        every { HttpUtil.getUrlContentWithUserAgent(any(), any(), 10808) } returns "dummy_data".repeat(256)
        every { HttpUtil.getUrlContentWithUserAgent(any(), any(), 10809) } returns "dummy_data".repeat(512)

        val result = AutoSelectorManager.autoSelectBestProxy(mockContext, listOf(guid1, guid2))

        assert(result != null)
        verify(atLeast = 1) { MmkvManager.encodeServerConfig(any(), any()) }
        verify(atLeast = 1) { MmkvManager.setSelectServer(any()) }
    }

    @Test
    fun `getBestAvailableProxy returns best proxy from historical data`() = runTest {
        val guid1 = "guid1"
        val guid2 = "guid2"
        val profile1 = ProfileItem(remarks = "Server 1", server = "1.1.1.1", serverPort = "80", configType = EConfigType.VMESS)
        val profile2 = ProfileItem(remarks = "Server 2", server = "2.2.2.2", serverPort = "443", configType = EConfigType.VLESS)

        val historicalMetrics1 = AutoSelectorManager.HistoricalMetrics(averageRtt = 100, averageJitter = 10, averageThroughputKbps = 5000, successCount = 5, lastUpdateTime = System.currentTimeMillis())
        val historicalMetrics2 = AutoSelectorManager.HistoricalMetrics(averageRtt = 50, averageJitter = 5, averageThroughputKbps = 10000, successCount = 10, lastUpdateTime = System.currentTimeMillis())

        val affiliationInfo1 = ServerAffiliationInfo(isProblematic = false, isStable = true)
        val affiliationInfo2 = ServerAffiliationInfo(isProblematic = false, isStable = true)

        every { MmkvManager.decodeServerConfig(guid1) } returns profile1
        every { MmkvManager.decodeServerConfig(guid2) } returns profile2
        every { MmkvManager.decodeHistoricalMetrics(guid1) } returns historicalMetrics1
        every { MmkvManager.decodeHistoricalMetrics(guid2) } returns historicalMetrics2
        every { MmkvManager.decodeServerAffiliationInfo(guid1) } returns affiliationInfo1
        every { MmkvManager.decodeServerAffiliationInfo(guid2) } returns affiliationInfo2

        val result = AutoSelectorManager.getBestAvailableProxy(listOf(guid1, guid2))

        assert(result == guid2) // guid2 has better historical metrics
    }

    @Test
    fun `updateHistoricalMetrics correctly updates metrics`() {
        val guid = "test_guid"
        val profile = ProfileItem(remarks = "Test Server", server = "1.2.3.4", serverPort = "80", configType = EConfigType.VMESS)
        val initialMetrics = AutoSelectorManager.HistoricalMetrics(averageRtt = 100, averageJitter = 10, averageThroughputKbps = 5000, failureCount = 0, successCount = 1, lastUpdateTime = 0L)

        val testResult = AutoSelectorManager.ProxyTestResult(
            guid = guid,
            profile = profile,
            rtt = 50L,
            jitter = 5L,
            throughputKbps = 10000L,
            connectionSuccessful = true,
            lastTestTime = System.currentTimeMillis(),
            historicalMetrics = initialMetrics
        )

        // Simulate a successful test
        val oldRtt = initialMetrics.averageRtt
        val oldJitter = initialMetrics.averageJitter
        val oldThroughput = initialMetrics.averageThroughputKbps

        AutoSelectorManager.updateHistoricalMetrics(testResult)

        assert(initialMetrics.successCount == 2)
        assert(initialMetrics.failureCount == 0)
        assert(initialMetrics.averageRtt != oldRtt)
        assert(initialMetrics.averageJitter != oldJitter)
        assert(initialMetrics.averageThroughputKbps != oldThroughput)
        assert(initialMetrics.lastUpdateTime > 0L)

        // Simulate a failed test
        val failedTestResult = AutoSelectorManager.ProxyTestResult(
            guid = guid,
            profile = profile,
            rtt = -1L,
            jitter = -1L,
            throughputKbps = -1L,
            connectionSuccessful = false,
            lastTestTime = System.currentTimeMillis(),
            historicalMetrics = initialMetrics
        )

        val oldSuccessCount = initialMetrics.successCount
        val oldFailureCount = initialMetrics.failureCount

        AutoSelectorManager.updateHistoricalMetrics(failedTestResult)

        assert(initialMetrics.successCount == oldSuccessCount)
        assert(initialMetrics.failureCount == oldFailureCount + 1)
    }
}