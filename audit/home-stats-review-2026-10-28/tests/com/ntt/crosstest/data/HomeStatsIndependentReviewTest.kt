package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem
import com.ntt.crosstest.ui.viewmodels.HomeViewModel
import com.ntt.crosstest.ui.viewmodels.AdminUsersViewModel
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HomeStatsIndependentReviewTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private val campaign = Campaign(id="c1", ownerId="other", appName="App", packageName="pkg", targetTesters=12)
    private val campaignJson = """{"id":"c1","owner_id":"other","app_name":"App","package_name":"pkg","target_testers":12}"""
    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionCacheManager.clear(context)
    }
    @After fun cleanup() {
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType,"application/json"))
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                sessionManager=MemorySessionManager()
                codeVerifierCache=MemoryCodeVerifierCache()
                autoLoadFromStorage=false
                autoSaveToStorage=false
            }
            install(Postgrest)
            httpEngine=MockEngine(handler)
        }
    private suspend fun login(client: SupabaseClient, uid: String) {
        client.auth.importSession(json.decodeFromString<UserSession>("""{"access_token":"token-$uid","refresh_token":"ref-$uid","token_type":"bearer","expires_in":3600,"user":{"id":"$uid","aud":"authenticated"}}"""))
    }
    private fun feed(date: LocalDate, homeCount: Int=9, tested: Boolean=true) =
        """{"open":[$campaignJson],"stats":{"c1":{"today_tested_count":4,"has_tested_today":$tested,"home_tested_count":$homeCount,"stats_date":"$date"}}}"""
    private fun dashboard() = """{"profile":{"id":"userA","credits":100},"joined_campaigns":[$campaignJson],"quest_summary":{}}"""

    @Test fun expiredCycleCountMustRemainUnknownRatherThanRenderZero() {
        val snapshot=CachedSessionData(dateVn=today.minusDays(1).toString(),displayItems=listOf(
            CampaignDisplayItem(campaign=campaign,todayTestedCount=4,homeTestedCount=9,statsDate=today.minusDays(1).toString())
        ))
        val item=snapshot.sanitizeForDate().displayItems.single()
        assertNull("Expired total is unknown; the exact value passed to CampaignCard must not be the fabricated integer 0", item.displayTestedCount)
    }

    @Test fun nextDayRefreshFailureMustClearPreviousDayTestFlag() = runBlocking {
        val day=AtomicReference(today)
        val fail=AtomicBoolean(false)
        val failedRequest=CompletableDeferred<Unit>()
        val client=client { req ->
            if(fail.get()) {
                failedRequest.complete(Unit)
                reply("{}",HttpStatusCode.InternalServerError)
            } else when {
                req.url.toString().contains("get_home_feed") -> reply(feed(day.get()))
                req.url.toString().contains("get_my_dashboard") -> reply(dashboard())
                req.url.toString().contains("/rpc/") -> reply("{}")
                else -> reply("[]")
            }
        }
        login(client,"userA")
        val repo=CampaignRepository(client,Localizer(context),businessDayClock={day.get()})
        val vm=HomeViewModel(repo,localizer=Localizer(context))
        try {
            withTimeout(5000) { vm.uiState.first { !it.isBackgroundSyncing && it.displayItems.firstOrNull()?.hasTestedToday==true } }
            day.set(today.plusDays(1)); fail.set(true)
            vm.refresh(forceRefresh=true)
            withTimeout(5000) { failedRequest.await(); vm.uiState.first { !it.isBackgroundSyncing } }
            assertFalse("A refresh started after midnight must invalidate yesterday's tested flag even when the request fails",vm.uiState.value.displayItems.single().hasTestedToday)
        } finally { vm.viewModelScope.cancel(); client.close() }
    }

    @Test fun staleFeedMustNotPublishUnsanitizedJoinedStats() = runBlocking {
        val client=client { req -> when {
            req.url.toString().contains("get_home_feed") -> reply(feed(today.minusDays(1)))
            req.url.toString().contains("get_my_dashboard") -> reply(dashboard())
            req.url.toString().contains("/rpc/") -> reply("{}")
            else -> reply("[]")
        } }
        login(client,"userA")
        val vm=HomeViewModel(CampaignRepository(client,Localizer(context)),localizer=Localizer(context))
        try {
            withTimeout(5000) { vm.uiState.first { !it.isLoading && !it.isBackgroundSyncing && it.joinedStatsMap.containsKey("c1") } }
            val stat=vm.uiState.value.joinedStatsMap.getValue("c1")
            assertNull("Joined app cards must not retain home total 9 when the same response was rejected as stale for main cards",stat.homeTestedCount)
        } finally { vm.viewModelScope.cancel(); client.close() }
    }

    @Test fun staleFeedMustNotEraseFreshDailyDashboardCount() = runBlocking {
        val client=client { req -> when {
            req.url.toString().contains("get_home_feed") -> reply(feed(today.minusDays(1)))
            req.url.toString().contains("get_my_dashboard") -> reply("""{"profile":{"id":"userA","credits":100},"today_checkin_count":2,"quest_summary":{}}""")
            req.url.toString().contains("/rpc/") -> reply("{}")
            else -> reply("[]")
        } }
        login(client,"userA")
        val vm=HomeViewModel(CampaignRepository(client,Localizer(context)),localizer=Localizer(context))
        try {
            withTimeout(5000) { vm.uiState.first { !it.isLoading && !it.isBackgroundSyncing && it.displayItems.isNotEmpty() } }
            assertEquals("A stale campaign count must not erase a fresh personal daily count from today's dashboard",2,vm.uiState.value.todayCheckinCount)
        } finally { vm.viewModelScope.cancel(); client.close() }
    }

    @Test fun staleFeedMustScheduleAControlledRefetch() = runBlocking {
        val reads=AtomicInteger()
        val client=client { req -> when {
            req.url.toString().contains("get_home_feed") -> {
                val n=reads.incrementAndGet()
                reply(if(n==1) feed(today.minusDays(1)) else feed(today,homeCount=6,tested=false))
            }
            req.url.toString().contains("get_my_dashboard") -> reply(dashboard())
            req.url.toString().contains("/rpc/") -> reply("{}")
            else -> reply("[]")
        } }
        login(client,"userA")
        val vm=HomeViewModel(CampaignRepository(client,Localizer(context)),localizer=Localizer(context))
        try {
            withTimeout(5000) { vm.uiState.first { !it.isLoading && !it.isBackgroundSyncing && it.displayItems.isNotEmpty() } }
            delay(200)
            assertTrue("Discarding stale stats must initiate a fresh request; observed feed request count="+reads.get(),reads.get()>=2)
        } finally { vm.viewModelScope.cancel(); client.close() }
    }

    @Test fun repositoryMustNotCacheOldServerDateAsFreshToday() = runBlocking {
        val reads=AtomicInteger()
        val client=client { req -> when {
            req.url.toString().contains("get_home_feed") -> { reads.incrementAndGet(); reply(feed(today.minusDays(1))) }
            else -> reply("{}")
        } }
        login(client,"userA")
        val repo=CampaignRepository(client,Localizer(context))
        try {
            repo.getHomeFeed(forceRefresh=true)
            repo.getHomeFeed(forceRefresh=false)
            assertEquals("A payload with yesterday's stats_date must not be served as a fresh cache entry stamped today",2,reads.get())
        } finally { client.close() }
    }

    @Test fun adminConfigResponseMustNotPublishAfterAccountSwitch() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val client=client { req -> when {
            req.url.toString().contains("admin_get_home_stats_config") -> {
                entered.complete(Unit); release.await()
                reply("""{"active_cycle_days":3,"revision":"secret-admin-revision","today":"$today"}""")
            }
            else -> reply("[]")
        } }
        login(client,"userA")
        val vm=AdminUsersViewModel(CampaignRepository(client,Localizer(context)),AuthRepository(client),AppConfigRepository(client),Localizer(context))
        try {
            vm.loadHomeStatsConfig()
            withTimeout(5000) { entered.await() }
            login(client,"userB")
            release.complete(Unit)
            withTimeout(5000) { vm.uiState.first { !it.isHomeStatsLoading } }
            assertNull("An admin response authorized for A must not publish into the current session B",vm.uiState.value.homeStatsConfig)
        } finally { release.complete(Unit); vm.viewModelScope.cancel(); client.close() }
    }
}
