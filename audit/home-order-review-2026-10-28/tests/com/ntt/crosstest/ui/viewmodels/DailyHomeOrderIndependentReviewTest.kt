package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.*
import com.ntt.crosstest.util.NetworkMonitor
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DailyHomeOrderIndependentReviewTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionCacheManager.clear(context)
    }
    @After fun cleanup() {
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
    private suspend fun login(c: SupabaseClient) {
        c.auth.importSession(json.decodeFromString<UserSession>("""{"access_token":"fixture-token","refresh_token":"fixture-ref","token_type":"bearer","expires_in":3600,"user":{"id":"userA","aud":"authenticated"}}"""))
    }
    private fun campaign(i: Int, status: String = "open") = Campaign(
        id = "review-campaign-$i", ownerId = "other", appName = "Review $i", packageName = "pkg.$i", status = status
    )
    private fun campaignJson(i: Int, status: String = "open") =
        """{"id":"review-campaign-$i","owner_id":"other","app_name":"Review $i","package_name":"pkg.$i","status":"$status"}"""
    private fun feed(day: LocalDate, open: Boolean = true): String {
        val apps = (1..10).joinToString(",") { campaignJson(it, if (open) "open" else "paused") }
        val completed = (11..20).joinToString(",") { campaignJson(it, "completed") }
        val stats = (1..20).joinToString(",") { i ->
            """"review-campaign-$i":{"today_tested_count":4,"has_tested_today":true,"home_tested_count":9,"stats_date":"$day"}"""
        }
        return """{"open":${if (open) "[$apps]" else "[]"},"paused":${if (open) "[]" else "[$apps]"},"completed":[$completed],"stats":{$stats}}"""
    }
    private fun dashboard(joined: Boolean = false) =
        """{"profile":{"id":"userA","credits":100},"today_checkin_count":4,"joined_campaigns":${if (joined) "[${campaignJson(1)}]" else "[]"},"quest_summary":{}}"""
    private fun offline() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        Shadows.shadowOf(cm).setActiveNetworkInfo(null)
        assertFalse("Fixture must be offline", NetworkMonitor.isOnline(context))
    }
    private suspend fun ready(vm: HomeViewModel) {
        withTimeout(5000) { vm.uiState.first { !it.isLoading && !it.isBackgroundSyncing && (it.displayItems.isNotEmpty() || it.pausedDisplayItems.isNotEmpty()) } }
    }

    @Test fun realViewModelSortsNetworkDataAndPreservesMultiDayStats() = runBlocking {
        val reverse = AtomicBoolean(false)
        val c = client { req -> when {
            req.url.toString().contains("get_home_feed") -> {
                val order = if (reverse.get()) (10 downTo 1).toList() else (1..10).toList()
                val apps = order.joinToString(",") { campaignJson(it) }
                val stats = order.joinToString(",") { """"review-campaign-$it":{"today_tested_count":2,"home_tested_count":18,"stats_date":"$today"}""" }
                reply("""{"open":[$apps],"stats":{$stats}}""")
            }
            req.url.toString().contains("get_my_dashboard") -> reply(dashboard())
            req.url.toString().contains("/rpc/") -> reply("{}")
            else -> reply("[]")
        } }
        login(c)
        val vm = HomeViewModel(CampaignRepository(c, Localizer(context)), localizer = Localizer(context))
        try {
            ready(vm)
            val first = vm.uiState.value.displayItems
            val expected = DailyHomeOrder.sortOpenCampaigns((1..10).map { CampaignDisplayItem(campaign(it), todayTestedCount = 2, homeTestedCount = 18) }, today.toString())
            assertEquals(expected.map { it.campaign.id }, first.map { it.campaign.id })
            assertTrue(first.all { it.homeTestedCount == 18 && it.todayTestedCount == 2 && !it.isTargetReached })
            reverse.set(true)
            vm.refresh(forceRefresh = true)
            ready(vm)
            assertEquals(first, vm.uiState.value.displayItems)
        } finally { vm.viewModelScope.cancel(); c.close() }
    }

    @Test fun pausedAndCompletedOnlyMustReorderBeforeAwaitingNetworkAtNewDay() = runBlocking {
        val day = AtomicReference(today)
        val hold = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val c = client { req -> when {
            req.url.toString().contains("get_home_feed") -> {
                if (hold.get()) { entered.complete(Unit); release.await() }
                reply(feed(day.get(), open = false))
            }
            req.url.toString().contains("get_my_dashboard") -> reply(dashboard())
            req.url.toString().contains("/rpc/") -> reply("{}")
            else -> reply("[]")
        } }
        login(c)
        val vm = HomeViewModel(CampaignRepository(c, Localizer(context), businessDayClock = { day.get() }), localizer = Localizer(context))
        try {
            ready(vm)
            hold.set(true); day.set(today.plusDays(1))
            vm.refresh(forceRefresh = true)
            withTimeout(5000) { entered.await() }
            val state = vm.uiState.value
            assertEquals("Paused/completed-only Home must publish the new ordering day before the network responds", day.get().toString(), state.homeOrderDate)
            assertEquals(DailyHomeOrder.sortOtherCampaigns(state.pausedDisplayItems, day.get().toString()), state.pausedDisplayItems)
            assertEquals(DailyHomeOrder.sortOtherCampaigns(state.completedDisplayItems, day.get().toString()), state.completedDisplayItems)
            assertTrue(state.pausedDisplayItems.none { it.hasTestedToday })
        } finally { release.complete(Unit); vm.viewModelScope.cancel(); c.close() }
    }

    @Test fun offlineRolloverMustNotLeaveYesterdayJoinedFlagsAndCycleTotal() = runBlocking {
        val day = AtomicReference(today)
        val c = client { req -> when {
            req.url.toString().contains("get_home_feed") -> reply(feed(day.get()))
            req.url.toString().contains("get_my_dashboard") -> reply(dashboard(joined = true))
            req.url.toString().contains("/rpc/") -> reply("{}")
            else -> reply("[]")
        } }
        login(c)
        val vm = HomeViewModel(CampaignRepository(c, Localizer(context), businessDayClock = { day.get() }), localizer = Localizer(context))
        try {
            ready(vm)
            assertTrue(vm.uiState.value.joinedStatsMap.getValue("review-campaign-1").hasTestedToday)
            offline(); day.set(today.plusDays(1))
            vm.refresh(context = context, forceRefresh = true)
            val state = vm.uiState.value
            assertEquals(day.get().toString(), state.homeOrderDate)
            assertTrue(state.displayItems.none { it.hasTestedToday })
            val stat = state.joinedStatsMap.getValue("review-campaign-1")
            assertFalse("Joined cards must not keep yesterday's tested flag after main cards are cleared", stat.hasTestedToday)
            assertNull("Yesterday's cycle total must not remain dated as valid in the joined map", stat.homeTestedCount)
        } finally { vm.viewModelScope.cancel(); c.close() }
    }

    @Test fun pausedOnlyOfflineRolloverMustNotDependOnNetworkSuccess() = runBlocking {
        val day = AtomicReference(today)
        val fail = AtomicBoolean(false)
        val c = client { req -> when {
            fail.get() -> reply("{}", HttpStatusCode.InternalServerError)
            req.url.toString().contains("get_home_feed") -> reply(feed(day.get(), open = false))
            req.url.toString().contains("get_my_dashboard") -> reply(dashboard())
            req.url.toString().contains("/rpc/") -> reply("{}")
            else -> reply("[]")
        } }
        login(c)
        val vm = HomeViewModel(CampaignRepository(c, Localizer(context), businessDayClock = { day.get() }), localizer = Localizer(context))
        try {
            ready(vm)
            offline(); fail.set(true); day.set(today.plusDays(1))
            vm.refresh(context = context, forceRefresh = true)
            // Check synchronously, before any failing network response can repair the order.
            assertEquals("Offline daily order must change even when open tab is empty", day.get().toString(), vm.uiState.value.homeOrderDate)
            assertTrue(vm.uiState.value.pausedDisplayItems.none { it.hasTestedToday })
        } finally { vm.viewModelScope.cancel(); c.close() }
    }

    @Test fun staleCountInOneCampaignMustNotClearFreshCampaignPriority() = runBlocking {
        val c = client { req -> when {
            req.url.toString().contains("get_home_feed") -> reply("""{"open":[${campaignJson(1)},${campaignJson(2)}],"stats":{"review-campaign-1":{"today_tested_count":12,"home_tested_count":24,"stats_date":"$today"},"review-campaign-2":{"today_tested_count":4,"home_tested_count":9,"stats_date":"${today.minusDays(1)}"}}}""")
            req.url.toString().contains("get_my_dashboard") -> reply(dashboard())
            req.url.toString().contains("/rpc/") -> reply("{}")
            else -> reply("[]")
        } }
        login(c)
        val vm = HomeViewModel(CampaignRepository(c, Localizer(context)), localizer = Localizer(context))
        try {
            ready(vm)
            val fresh = vm.uiState.value.displayItems.single { it.campaign.id == "review-campaign-1" }
            assertEquals("A stale entry must not erase another app's current daily count and priority", 12, fresh.todayTestedCount)
            assertEquals(24, fresh.homeTestedCount)
            assertTrue(fresh.isTargetReached)
        } finally { vm.viewModelScope.cancel(); c.close() }
    }
}
