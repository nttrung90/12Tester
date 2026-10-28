package com.ntt.crosstest.data

import android.app.Application
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
class IndependentCacheClockAuditTest {

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



    private fun clockedRepo(client: io.github.jan.supabase.SupabaseClient, day: java.util.concurrent.atomic.AtomicReference<java.time.LocalDate>, elapsed: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(1000L)) = CampaignRepository(client,Localizer(context),monotonicClock={elapsed.get()},businessDayClock={day.get()})

    @Test
    fun homeFeedMustRefreshTodayStatsAfterBusinessDayChanges() = runBlocking {
        val day=java.util.concurrent.atomic.AtomicReference(java.time.LocalDate.of(2026,10,27))
        val reads=AtomicInteger()
        val client=createTestClient { jsonResponse("""{"stats":{"c1":{"has_tested_today":${reads.incrementAndGet()==1}}}}""") }
        val repo=clockedRepo(client,day)
        importUser(client,"userA")
        assertTrue(repo.getHomeFeed()!!.stats["c1"]!!.hasTestedToday)
        day.set(day.get().plusDays(1))
        assertFalse("feed cannot return yesterday as tested today",repo.getHomeFeed()!!.stats["c1"]!!.hasTestedToday)
    }

    @Test
    fun dashboardMustRefreshTodayCountAfterBusinessDayChanges() = runBlocking {
        val day=java.util.concurrent.atomic.AtomicReference(java.time.LocalDate.of(2026,10,27))
        val reads=AtomicInteger()
        val client=createTestClient { jsonResponse("""{"profile":{"id":"userA","credits":100},"today_checkin_count":${if(reads.incrementAndGet()==1) 9 else 0}}""") }
        val repo=clockedRepo(client,day)
        importUser(client,"userA")
        assertEquals(9,repo.getMyDashboard()!!.todayCheckinCount)
        day.set(day.get().plusDays(1))
        assertEquals("dashboard cannot return yesterday count as today",0,repo.getMyDashboard()!!.todayCheckinCount)
    }

    @Test
    fun detailMustRefreshTodayCountAfterBusinessDayChanges() = runBlocking {
        val day=java.util.concurrent.atomic.AtomicReference(java.time.LocalDate.of(2026,10,27))
        val reads=AtomicInteger()
        val client=createTestClient { jsonResponse("""{"campaign":{"id":"c1","owner_id":"userA","app_name":"App","package_name":"pkg"},"today_tested_count":${if(reads.incrementAndGet()==1) 9 else 0}}""") }
        val repo=clockedRepo(client,day)
        importUser(client,"userA")
        assertEquals(9,repo.getCampaignDetailPage("c1")!!.todayTestedCount)
        day.set(day.get().plusDays(1))
        assertEquals("detail cannot return yesterday count as today",0,repo.getCampaignDetailPage("c1")!!.todayTestedCount)
    }

    @Test
    fun injectedMonotonicClockMustControlReadTtlToo() = runBlocking {
        val day=java.util.concurrent.atomic.AtomicReference(java.time.LocalDate.of(2026,10,27))
        val elapsed=java.util.concurrent.atomic.AtomicLong(1000L)
        val reads=AtomicInteger()
        val client=createTestClient { jsonResponse("""[{"id":"${if(reads.incrementAndGet()==1) "old" else "new"}","app_name":"App","package_name":"pkg"}]""") }
        val repo=clockedRepo(client,day,elapsed)
        assertEquals("old",repo.listOpenCampaigns().first().id)
        elapsed.addAndGet(CampaignRepository.CACHE_TTL_MS+1)
        assertEquals("read TTL must use injected monotonic time just like cache creation","new",repo.listOpenCampaigns().first().id)
    }

    @Test
    fun midnightInFlightMustNotBeReusedBySameRepositoryNextDay() = runBlocking {
        val day=java.util.concurrent.atomic.AtomicReference(java.time.LocalDate.of(2026,10,27))
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val checkinReads=AtomicInteger()
        val client=createTestClient { req ->
            if(req.url.toString().contains("checkins")) {
                if(checkinReads.incrementAndGet()==1) {
                    entered.complete(Unit)
                    release.await()
                    jsonResponse("""[{"id":"ch1","campaign_id":"c1","user_id":"userA","checkin_date":"2026-10-27"}]""")
                } else jsonResponse("[]")
            } else jsonResponse("[]")
        }
        val repo=clockedRepo(client,day)
        importUser(client,"userA")
        val old=async(start=CoroutineStart.UNDISPATCHED) { try { repo.getTodayCheckinCount() } catch (_:Exception) { null } }
        withTimeout(5000) { entered.await() }
        day.set(day.get().plusDays(1))
        release.complete(Unit)
        old.await()
        assertEquals("same repository must fetch new day rather than reuse old in-flight result",0,repo.getTodayCheckinCount())
        assertEquals(2,checkinReads.get())
    }
}

