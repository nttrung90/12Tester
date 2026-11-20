package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.createSupabaseClient
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
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IndependentCachePublicationAuditTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() {
        SessionCacheManager.clear(context)
    }

    @After
    fun tearDown() {
        SessionCacheManager.clear(context)
    }

    private fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )

    private suspend fun importUser(client: io.github.jan.supabase.SupabaseClient, userId: String) {
        val sessionJson = """
            {
                "access_token": "token-$userId",
                "refresh_token": "ref-$userId",
                "token_type": "bearer",
                "expires_in": 3600,
                "user": {
                    "id": "$userId",
                    "aud": "authenticated"
                }
            }
        """.trimIndent()
        client.auth.importSession(json.decodeFromString<UserSession>(sessionJson))
    }

    private fun createTestClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): io.github.jan.supabase.SupabaseClient {
        return createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ownerDetailMustRecheckAccountAfterLoadingSlots() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val enteredSlots=CompletableDeferred<Unit>()
        val releaseSlots=CompletableDeferred<Unit>()
        val client=createTestClient { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse("""{"campaign":{"id":"c1","owner_id":"userA","app_name":"A private app","package_name":"pkg.c1","slot_number":1},"can_manage":true,"is_actual_owner":true}""")
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("""{"mission":null}""")
                req.url.toString().contains("user_slots") -> {
                    enteredSlots.complete(Unit)
                    releaseSlots.await()
                    jsonResponse("[]")
                }
                else -> jsonResponse("[]")
            }
        }
        importUser(client,"userA")
        val repo=CampaignRepository(client,Localizer(context))
        val vm=com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel("c1",repo,Localizer(context))

        try {
            withTimeout(5000) { enteredSlots.await() }
            importUser(client,"userB")
            releaseSlots.complete(Unit)
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertFalse("old A detail must not publish after slots await changed account to B",vm.uiState.value.campaign?.ownerId=="userA")
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun switchingAccountWithRefreshFailureMustRemoveAlreadyDisplayedPrivateState() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val failB=java.util.concurrent.atomic.AtomicBoolean(false)
        val startedB=CompletableDeferred<Unit>()
        val client=createTestClient { req ->
            if(failB.get()) {
                startedB.complete(Unit)
                jsonResponse("{}",HttpStatusCode.InternalServerError)
            } else when {
                req.url.toString().contains("get_home_feed") -> jsonResponse("{}")
                req.url.toString().contains("get_my_dashboard") -> jsonResponse("""{"profile":{"id":"userA","credits":100},"notifications":[{"id":"a1","user_id":"userA"}],"quest_summary":{}}""")
                req.url.toString().contains("/rpc/") -> jsonResponse("{}")
                else -> jsonResponse("[]")
            }
        }
        importUser(client,"userA")
        val repo=CampaignRepository(client,Localizer(context))
        val vm=com.ntt.crosstest.ui.viewmodels.HomeViewModel(repo,localizer=Localizer(context))
        try {
            withTimeout(5000) { vm.uiState.first { it.userProfile?.id=="userA" } }
            assertEquals("userA",vm.uiState.value.notifications.first().userId)
            failB.set(true)
            importUser(client,"userB")
            withTimeout(5000) { startedB.await() }
            withTimeout(5000) { vm.uiState.first { !it.isBackgroundSyncing } }
            val state=vm.uiState.value
            assertTrue("B must not keep A private state while B refresh fails",state.userProfile?.id!="userA" && state.notifications.none { it.userId=="userA" })
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ownerDetailMustNotPublishYesterdayCountAfterSlotsAwaitCrossesMidnight() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val day=java.util.concurrent.atomic.AtomicReference(java.time.LocalDate.of(2026,10,27))
        val enteredSlots=CompletableDeferred<Unit>()
        val releaseSlots=CompletableDeferred<Unit>()
        val client=createTestClient { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse("""{"campaign":{"id":"c1","owner_id":"userA","app_name":"App","package_name":"pkg.c1","slot_number":1},"can_manage":true,"is_actual_owner":true,"today_tested_count":9}""")
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("""{"mission":null}""")
                req.url.toString().contains("user_slots") -> {
                    enteredSlots.complete(Unit)
                    releaseSlots.await()
                    jsonResponse("[]")
                }
                else -> jsonResponse("[]")
            }
        }
        importUser(client,"userA")
        val repo=CampaignRepository(client,Localizer(context),businessDayClock={day.get()})
        val vm=com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel("c1",repo,Localizer(context))
        try {
            withTimeout(5000) { enteredSlots.await() }
            day.set(day.get().plusDays(1))
            releaseSlots.complete(Unit)
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertTrue("yesterday detail must not become current-day UI after helper await",vm.uiState.value.stats?.testedTodayCount!=9)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun optionalProfileMustNotDisableOwnerPurgeForNotificationsAndFeedFlags() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val failB=java.util.concurrent.atomic.AtomicBoolean(false)
        val startedB=CompletableDeferred<Unit>()
        val client=createTestClient { req ->
            if(failB.get()) {
                startedB.complete(Unit)
                jsonResponse("{}",HttpStatusCode.InternalServerError)
            } else when {
                req.url.toString().contains("get_home_feed") -> jsonResponse("""{"open":[{"id":"c1","owner_id":"other","app_name":"App","package_name":"pkg"}],"stats":{"c1":{"has_tested_today":true}}}""")
                req.url.toString().contains("get_my_dashboard") -> jsonResponse("""{"profile":null,"notifications":[{"id":"a1","user_id":"userA"}],"quest_summary":{}}""")
                req.url.toString().contains("/rpc/") -> jsonResponse("{}")
                else -> jsonResponse("[]")
            }
        }
        importUser(client,"userA")
        val repo=CampaignRepository(client,Localizer(context))
        val vm=com.ntt.crosstest.ui.viewmodels.HomeViewModel(repo,localizer=Localizer(context))
        try {
            withTimeout(5000) { vm.uiState.first { it.notifications.any { n -> n.userId=="userA" } && it.displayItems.any { item -> item.hasTestedToday } } }
            assertNull(vm.uiState.value.userProfile)
            failB.set(true)
            importUser(client,"userB")
            withTimeout(5000) { startedB.await() }
            withTimeout(5000) { vm.uiState.first { !it.isBackgroundSyncing } }
            val state=vm.uiState.value
            assertTrue("owner scope must not depend on optional profile presence",state.notifications.none { it.userId=="userA" })
            assertTrue("A tested-today flags must not survive B refresh failure",state.displayItems.none { it.hasTestedToday })
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun homeMustNotPublishPreviousDayCountersFromInFlightRefresh() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val firstDay=java.time.LocalDate.of(2026,10,27)
        val day=java.util.concurrent.atomic.AtomicReference(firstDay)
        val enteredFeed=CompletableDeferred<Unit>()
        val enteredDashboard=CompletableDeferred<Unit>()
        val releaseFeed=CompletableDeferred<Unit>()
        val feedReads=AtomicInteger()
        val client=createTestClient { req ->
            when {
                req.url.toString().contains("get_home_feed") -> {
                    val old=feedReads.incrementAndGet()==1
                    if(old) { enteredFeed.complete(Unit); releaseFeed.await() }
                    jsonResponse("""{"open":[{"id":"c1","owner_id":"other","app_name":"App","package_name":"pkg"}],"stats":{"c1":{"has_tested_today":$old}}}""")
                }
                req.url.toString().contains("get_my_dashboard") -> {
                    val count=if(day.get()==firstDay) 9 else 0
                    enteredDashboard.complete(Unit)
                    jsonResponse("""{"profile":{"id":"userA","credits":100},"today_checkin_count":$count,"quest_summary":{}}""")
                }
                req.url.toString().contains("/rpc/") -> jsonResponse("{}")
                else -> jsonResponse("[]")
            }
        }
        importUser(client,"userA")
        val repo=CampaignRepository(client,Localizer(context),businessDayClock={day.get()})
        val vm=com.ntt.crosstest.ui.viewmodels.HomeViewModel(repo,localizer=Localizer(context))
        try {
            withTimeout(5000) { enteredFeed.await(); enteredDashboard.await() }
            day.set(firstDay.plusDays(1))
            releaseFeed.complete(Unit)
            withTimeout(5000) { vm.uiState.first { !it.isLoading && !it.isBackgroundSyncing } }
            val state=vm.uiState.value
            assertEquals("old dashboard count must not be labeled today after refresh crosses midnight",0,state.todayCheckinCount)
            assertTrue("old feed daily flags must not be current after midnight",state.displayItems.none { it.hasTestedToday })
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
