package com.ntt.crosstest.util

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.CachedSessionData
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.EgressOptimizationConfig
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem
import com.ntt.crosstest.ui.viewmodels.HomeViewModel
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.IOException
import java.net.InetAddress
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NetworkRecoveryTest {

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        EgressOptimizationConfig.setAllEnabled(false)
        EgressOptimizationConfig.resetCapabilityCache()
    }

    @After fun tearDown() {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        EgressOptimizationConfig.setAllEnabled(false)
        EgressOptimizationConfig.resetCapabilityCache()
        Dispatchers.resetMain()
    }
    @Test fun connectivityWaitsForCapabilitiesAndUnregistersWhenStopped() = runTest {
        val context: Application = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val statuses = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            NetworkMonitor.observeNetwork(context).collect { statuses.add(it) }
        }
        testScheduler.runCurrent()
        val callback = shadow.networkCallbacks.single()
        val network = org.robolectric.shadows.ShadowNetwork.newInstance(101)
        assertEquals(listOf(false), statuses)
        callback.onAvailable(network)
        testScheduler.runCurrent()
        assertEquals(listOf(false), statuses)
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        callback.onCapabilitiesChanged(network, capabilities)
        callback.onCapabilitiesChanged(network, capabilities)
        testScheduler.runCurrent()
        assertEquals(listOf(false, true), statuses)
        callback.onLost(network)
        testScheduler.runCurrent()
        callback.onCapabilitiesChanged(org.robolectric.shadows.ShadowNetwork.newInstance(102), capabilities)
        testScheduler.runCurrent()
        assertEquals(listOf(false, true, false, true), statuses)
        job.cancelAndJoin()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun stalledIconUploadTimesOutAndCancelsSdkTask() = runTest {
        var cancelled = 0
        try {
            awaitIconUpload(timeoutMs = 1_000, onCancel = { cancelled++ }) { awaitCancellation() }
            fail("Expected timeout")
        } catch (e: IOException) {
            assertEquals("icon_upload_timeout", e.message)
        }
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(1, cancelled)
    }

    @Test fun leavingIconUploadCancelsSdkTaskAndPreservesCancellation() = runTest {
        var cancelled = 0
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            awaitIconUpload(onCancel = { cancelled++ }) { awaitCancellation() }
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, cancelled)
    }

    @Test fun completedIconUploadDoesNotCancelOrWaitForTimeout() = runTest {
        var cancelled = 0
        awaitIconUpload(onCancel = { cancelled++ }) { }
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(0, cancelled)
    }

    @Test fun sortAddressesIpv4FirstPrioritizesIpv4AndPreservesIpv6Only() {
        val ipv6First = InetAddress.getByAddress(
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        )
        val ipv4First = InetAddress.getByAddress(byteArrayOf(104, 18, 0, 1))
        val ipv6Second = InetAddress.getByAddress(
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
        )
        val ipv4Second = InetAddress.getByAddress(byteArrayOf(104, 18, 0, 2))

        val mixedSorted = NetworkHttpFactory.sortAddressesIpv4First(
            listOf(ipv6First, ipv4First, ipv6Second, ipv4Second)
        )
        assertEquals(listOf(ipv4First, ipv4Second, ipv6First, ipv6Second), mixedSorted)

        val ipv6Only = NetworkHttpFactory.sortAddressesIpv4First(listOf(ipv6First, ipv6Second))
        assertEquals(listOf(ipv6First, ipv6Second), ipv6Only)
    }

    @Suppress("DEPRECATION")
    @Test fun lateOnLostOfOldNetworkDoesNotEmitFalseWhenNew5GActiveNetworkHasInternet() = runTest {
        val context: Application = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()

        val oldWifi = ShadowNetwork.newInstance(201)
        val wifiInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            NetworkInfo.State.CONNECTED,
        )
        val wifiCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(wifiCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.addNetwork(oldWifi, wifiInfo)
        shadow.setNetworkCapabilities(oldWifi, wifiCaps)
        shadow.setActiveNetworkInfo(wifiInfo)

        val statuses = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            NetworkMonitor.observeNetwork(context).collect { statuses.add(it) }
        }
        testScheduler.runCurrent()
        assertEquals(listOf(true), statuses)

        val callback = shadow.networkCallbacks.single()

        // New 5G network connects and becomes activeNetwork with NET_CAPABILITY_INTERNET
        val new5G = ShadowNetwork.newInstance(202)
        val cellInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_MOBILE,
            0,
            true,
            NetworkInfo.State.CONNECTED,
        )
        val fiveGCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(fiveGCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.addNetwork(new5G, cellInfo)
        shadow.setNetworkCapabilities(new5G, fiveGCaps)
        shadow.setActiveNetworkInfo(cellInfo)

        callback.onAvailable(new5G)
        callback.onCapabilitiesChanged(new5G, fiveGCaps)
        testScheduler.runCurrent()
        assertEquals(listOf(true), statuses)

        // Late onLost from the old Wi-Fi network must not flip status to false
        callback.onLost(oldWifi)
        testScheduler.runCurrent()
        assertEquals(listOf(true), statuses)
        assertTrue(NetworkMonitor.isOnline(context))

        job.cancelAndJoin()
    }

    @Test fun homeViewModelForceRefreshCallsNetworkAndClearsFalseOfflineWhenCachedAndOfflineReported() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()

        val clock = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(clock)
        Dispatchers.setMain(dispatcher)

        val uid = "net-recovery-user"
        val day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        val json = Json { ignoreUnknownKeys = true }
        val requests = ConcurrentLinkedQueue<String>()

        val client = createSupabaseClient("https://net-recovery.fixture.invalid", "fixture-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine(MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    val path = request.url.encodedPath
                    requests.add(path)
                    val body = when {
                        path.contains("get_home_feed") ->
                            """{"open":[{"id":"fresh-app","owner_id":"owner-2","app_name":"Fresh App","package_name":"com.ntt.fresh","status":"open"}],"paused":[],"completed":[],"owner_names":{"owner-2":"Owner Two"},"stats":{}}"""
                        path.contains("get_my_dashboard") ->
                            """{"profile":{"id":"$uid","credits":150},"notifications":[],"my_campaigns":[],"joined_campaigns":[],"leaderboard":[],"today_checkin_count":0,"quest_summary":{},"daily_task":null}"""
                        path.contains("record_daily_login") -> "true"
                        path.contains("sync_campaign_lifecycle") -> "{}"
                        path.contains("get_open_missions") -> """{"server_now":"2026-11-17T00:00:00Z","missions":[]}"""
                        path.contains("get_user_quest_summary") -> "{}"
                        else -> "[]"
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            })
        }

        client.auth.awaitInitialization()
        client.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"fixture-token","refresh_token":"fixture-refresh","token_type":"bearer","expires_in":3600,"user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )

        val cachedItem = CampaignDisplayItem(
            campaign = Campaign(
                id = "cached-app",
                ownerId = "owner-1",
                appName = "Cached App",
                packageName = "com.ntt.cached",
                status = "open",
            ),
            ownerName = "Owner One",
        )
        SessionCacheManager.save(
            context,
            CachedSessionData(
                userId = uid,
                displayItems = listOf(cachedItem),
                timestamp = System.currentTimeMillis(),
                dateVn = day.toString(),
                sessionGeneration = SessionCacheManager.currentGeneration(),
            ),
            targetGeneration = SessionCacheManager.currentGeneration(),
        )

        val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
        val vm = HomeViewModel(repository, context = context, localizer = Localizer(context))
        try {
            vm.stopMidnightScheduler()
            clock.runCurrent()

            // Initial non-forced refresh while ConnectivityManager reports offline keeps cached items
            assertTrue("Non-forced refresh with cached content must not hit network when offline", requests.isEmpty())
            assertTrue(vm.uiState.value.isOffline)
            assertEquals(listOf("cached-app"), vm.uiState.value.displayItems.map { it.campaign.id })

            // Explicit non-forced refresh while still offline also preserves cache without network calls
            vm.refresh(context, forceRefresh = false)
            clock.runCurrent()
            assertTrue(requests.isEmpty())
            assertTrue(vm.uiState.value.isOffline)
            assertEquals(listOf("cached-app"), vm.uiState.value.displayItems.map { it.campaign.id })

            // Force refresh must bypass the false-offline guard, hit the repository, and clear isOffline
            vm.refresh(context, forceRefresh = true)
            withTimeout(10_000L) {
                while (vm.uiState.value.isBackgroundSyncing ||
                    vm.uiState.value.displayItems.none { it.campaign.id == "fresh-app" }
                ) {
                    clock.runCurrent()
                    delay(1)
                }
            }
            clock.runCurrent()

            assertTrue(requests.any { it.contains("get_home_feed") })
            assertFalse("Successful network load must clear false isOffline flag", vm.uiState.value.isOffline)
            assertEquals(listOf("fresh-app"), vm.uiState.value.displayItems.map { it.campaign.id })
        } finally {
            vm.viewModelScope.cancel()
            client.close()
        }
    }
}
