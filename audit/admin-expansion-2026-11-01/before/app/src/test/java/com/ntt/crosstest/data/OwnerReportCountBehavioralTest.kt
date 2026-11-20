package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OwnerReportCountBehavioralTest {

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

    private suspend fun drainViewModel(vm: OwnerAppDetailViewModel) {
        withTimeout(5000) {
            while (true) {
                val active = vm.viewModelScope.coroutineContext[Job]!!.children.filter { it.isActive }.toList()
                if (active.isEmpty()) break
                active.joinAll()
            }
        }
    }

    // =========================================================================
    // 1. DTO JSON Deserialization Tests (Compatibility & Authoritativeness)
    // =========================================================================

    @Test
    fun legacyJsonWithoutCountKeysDerivesCountsFromReportsArray() {
        val legacyJson = """
            {
                "campaign": {"id": "c1", "owner_id": "owner1", "app_name": "Test App", "package_name": "pkg.test"},
                "reports_count": 0,
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "pending"},
                    {"id": "r2", "campaign_id": "c1", "user_id": "u2", "status": "pending"},
                    {"id": "r3", "campaign_id": "c1", "user_id": "u3", "status": "approved"}
                ],
                "feedbacks": []
            }
        """.trimIndent()

        val result = json.decodeFromString<CampaignDetailResult>(legacyJson)
        assertEquals("Fallback pendingReportsCount derives 2 pending reports", 2, result.pendingReportsCount)
        assertEquals("Fallback testerReportsCount derives reports.size (3)", 3, result.testerReportsCount)
        assertEquals("reportsCount (feedbacks) is preserved at 0", 0, result.reportsCount)
    }

    @Test
    fun legacyJsonWithEmptyReportsDefaultsToZero() {
        val legacyJson = """
            {
                "campaign": {"id": "c1", "owner_id": "owner1", "app_name": "Test App", "package_name": "pkg.test"},
                "reports": [],
                "reports_count": 5
            }
        """.trimIndent()

        val result = json.decodeFromString<CampaignDetailResult>(legacyJson)
        assertEquals(0, result.pendingReportsCount)
        assertEquals(0, result.testerReportsCount)
        assertEquals(5, result.reportsCount)
    }

    @Test
    fun newJsonWithExplicitZeroCountsStaysAuthoritativeZero() {
        // Even if reports array happens to contain PENDING reports, explicit server counts must stay authoritative
        val jsonPayload = """
            {
                "campaign": {"id": "c1", "owner_id": "owner1", "app_name": "Test App", "package_name": "pkg.test"},
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "pending"},
                    {"id": "r2", "campaign_id": "c1", "user_id": "u2", "status": "pending"}
                ],
                "pending_reports_count": 0,
                "tester_reports_count": 0,
                "reports_count": 0
            }
        """.trimIndent()

        val result = json.decodeFromString<CampaignDetailResult>(jsonPayload)
        assertEquals("Authoritative pending_reports_count=0 must not be overridden", 0, result.pendingReportsCount)
        assertEquals("Authoritative tester_reports_count=0 must not be overridden", 0, result.testerReportsCount)
    }

    @Test
    fun newJsonAuthoritativeBeyond200ArrayLimit() {
        // Server returns reports capped at 2, but aggregate counts are 350 total, 50 pending
        val jsonPayload = """
            {
                "campaign": {"id": "c1", "owner_id": "owner1", "app_name": "Test App", "package_name": "pkg.test"},
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "pending"},
                    {"id": "r2", "campaign_id": "c1", "user_id": "u2", "status": "pending"}
                ],
                "pending_reports_count": 50,
                "tester_reports_count": 350,
                "reports_count": 12,
                "avg_rating": 4.5
            }
        """.trimIndent()

        val result = json.decodeFromString<CampaignDetailResult>(jsonPayload)
        assertEquals(50, result.pendingReportsCount)
        assertEquals(350, result.testerReportsCount)
        assertEquals(12, result.reportsCount)
        assertEquals(4.5f, result.avgRating ?: 0f, 0.01f)
    }

    // =========================================================================
    // 2. OwnerAppDetailViewModel Fast Path Behavioral Tests
    // =========================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun viewModelFastPathUsesNewTesterReportsCountAndPendingReportsCount() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val detailJson = """
            {
                "campaign": {"id": "c1", "owner_id": "userOwner", "app_name": "Owner App", "package_name": "pkg.owner", "slot_number": 1},
                "is_actual_owner": true,
                "can_manage": true,
                "testers": [{"user_id": "u1", "joined_at": "2026-11-01T00:00:00Z", "days": 5, "checkin_count": 5}],
                "feedbacks": [{"id": "f1", "campaign_id": "c1", "user_id": "u1", "rating": 5}],
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "pending"}
                ],
                "today_tested_count": 3,
                "reports_count": 1,
                "pending_reports_count": 7,
                "tester_reports_count": 25,
                "avg_rating": 4.8
            }
        """.trimIndent()

        val client = createTestClient { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(detailJson)
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("""{"mission":null}""")
                req.url.toString().contains("user_slots") -> jsonResponse("[]")
                else -> jsonResponse("[]")
            }
        }
        importUser(client, "userOwner")
        val repo = CampaignRepository(client, Localizer(context))
        val vm = OwnerAppDetailViewModel("c1", repo, Localizer(context))

        try {
            vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
            val stats = vm.uiState.value.stats
            assertNotNull(stats)
            assertEquals("totalReportsCount must take tester_reports_count (25), not feedback reports_count (1)", 25, stats.totalReportsCount)
            assertEquals("pendingReportsCount must take pending_reports_count (7)", 7, stats.pendingReportsCount)
            assertEquals("averageRating preserved from avg_rating", 4.8f, stats.averageRating ?: 0f, 0.01f)
            assertEquals("installedTestersCount from testers.size", 1, stats.installedTestersCount)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun viewModelFastPathLegacyFallbackDerivesFromReportsList() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Legacy server missing pending_reports_count and tester_reports_count
        val legacyDetailJson = """
            {
                "campaign": {"id": "c1", "owner_id": "userOwner", "app_name": "Owner App", "package_name": "pkg.owner", "slot_number": 1},
                "is_actual_owner": true,
                "can_manage": true,
                "testers": [],
                "feedbacks": [],
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "pending"},
                    {"id": "r2", "campaign_id": "c1", "user_id": "u2", "status": "pending"},
                    {"id": "r3", "campaign_id": "c1", "user_id": "u3", "status": "approved"},
                    {"id": "r4", "campaign_id": "c1", "user_id": "u4", "status": "rejected"}
                ],
                "reports_count": 0
            }
        """.trimIndent()

        val client = createTestClient { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(legacyDetailJson)
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("""{"mission":null}""")
                req.url.toString().contains("user_slots") -> jsonResponse("[]")
                else -> jsonResponse("[]")
            }
        }
        importUser(client, "userOwner")
        val repo = CampaignRepository(client, Localizer(context))
        val vm = OwnerAppDetailViewModel("c1", repo, Localizer(context))

        try {
            vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
            val stats = vm.uiState.value.stats
            assertEquals("Legacy totalReportsCount derives reports.size (4)", 4, stats.totalReportsCount)
            assertEquals("Legacy pendingReportsCount derives pending reports (2)", 2, stats.pendingReportsCount)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun viewModelAuthoritativeCountsBeyond200ArrayLimit() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Server sends 1 report in array, but authoritative tester_reports_count=260, pending_reports_count=130
        val detailJson = """
            {
                "campaign": {"id": "c1", "owner_id": "userOwner", "app_name": "Owner App", "package_name": "pkg.owner", "slot_number": 1},
                "is_actual_owner": true,
                "can_manage": true,
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "pending"}
                ],
                "pending_reports_count": 130,
                "tester_reports_count": 260,
                "reports_count": 0
            }
        """.trimIndent()

        val client = createTestClient { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(detailJson)
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("""{"mission":null}""")
                req.url.toString().contains("user_slots") -> jsonResponse("[]")
                else -> jsonResponse("[]")
            }
        }
        importUser(client, "userOwner")
        val repo = CampaignRepository(client, Localizer(context))
        val vm = OwnerAppDetailViewModel("c1", repo, Localizer(context))

        try {
            vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
            val stats = vm.uiState.value.stats
            assertEquals(260, stats.totalReportsCount)
            assertEquals(130, stats.pendingReportsCount)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reportReviewTriggersRefreshAndOptimisticUpdate() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val detailCallCount = AtomicInteger(0)

        val detail1 = """
            {
                "campaign": {"id": "c1", "owner_id": "userOwner", "app_name": "Owner App", "package_name": "pkg.owner", "slot_number": 1},
                "is_actual_owner": true,
                "can_manage": true,
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "pending"}
                ],
                "pending_reports_count": 1,
                "tester_reports_count": 1,
                "reports_count": 0,
                "avg_rating": 3.0
            }
        """.trimIndent()

        // Detail 2 returns server-only updates: total reports increased to 2, avg_rating changed to 4.9
        val detail2 = """
            {
                "campaign": {"id": "c1", "owner_id": "userOwner", "app_name": "Owner App", "package_name": "pkg.owner", "slot_number": 1},
                "is_actual_owner": true,
                "can_manage": true,
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "approved"},
                    {"id": "r2", "campaign_id": "c1", "user_id": "u2", "status": "approved"}
                ],
                "pending_reports_count": 0,
                "tester_reports_count": 2,
                "reports_count": 0,
                "avg_rating": 4.9
            }
        """.trimIndent()

        val client = createTestClient { req ->
            val url = req.url.toString()
            when {
                url.contains("get_campaign_detail") -> {
                    val count = detailCallCount.incrementAndGet()
                    jsonResponse(if (count == 1) detail1 else detail2)
                }
                url.contains("approve_tester_report") -> {
                    jsonResponse("""{"success":true,"credits_awarded":10,"owner_credits_awarded":2,"tip_awarded":0}""")
                }
                url.contains("get_campaign_mission") -> jsonResponse("""{"mission":null}""")
                url.contains("user_slots") -> jsonResponse("[]")
                else -> jsonResponse("[]")
            }
        }
        importUser(client, "userOwner")
        val repo = CampaignRepository(client, Localizer(context))
        val vm = OwnerAppDetailViewModel("c1", repo, Localizer(context))

        try {
            drainViewModel(vm)
            assertEquals("Initial pending reports is 1", 1, vm.uiState.value.stats.pendingReportsCount)
            assertEquals("Initial total reports is 1", 1, vm.uiState.value.stats.totalReportsCount)
            assertEquals("Initial detail call count is 1", 1, detailCallCount.get())

            val reportToApprove = vm.uiState.value.reports.first()
            vm.approveReport(reportToApprove, bonusCredits = 0)
            drainViewModel(vm)

            // Verify that server refresh actually executed (call count increased to 2)
            assertEquals("Detail call count must increase after approve refresh", 2, detailCallCount.get())
            // Server-only updates prove refreshed aggregate reached state rather than just optimistic update
            assertEquals("After approve, pending reports is 0", 0, vm.uiState.value.stats.pendingReportsCount)
            assertEquals("After approve refresh, total reports reaches server aggregate (2)", 2, vm.uiState.value.stats.totalReportsCount)
            assertEquals("After approve refresh, average rating reaches server aggregate (4.9)", 4.9f, vm.uiState.value.stats.averageRating ?: 0f, 0.01f)
            assertEquals("Report count in list reaches server list size (2)", 2, vm.uiState.value.reports.size)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reportRejectTriggersRefreshAndPreservesZeroPendingAndTesterTotal() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val detailCallCount = AtomicInteger(0)

        val detail1 = """
            {
                "campaign": {"id": "c1", "owner_id": "userOwner", "app_name": "Owner App", "package_name": "pkg.owner", "slot_number": 1},
                "is_actual_owner": true,
                "can_manage": true,
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "pending"}
                ],
                "pending_reports_count": 1,
                "tester_reports_count": 1,
                "reports_count": 8,
                "avg_rating": 3.5
            }
        """.trimIndent()

        val detail2 = """
            {
                "campaign": {"id": "c1", "owner_id": "userOwner", "app_name": "Owner App", "package_name": "pkg.owner", "slot_number": 1},
                "is_actual_owner": true,
                "can_manage": true,
                "reports": [
                    {"id": "r1", "campaign_id": "c1", "user_id": "u1", "status": "rejected"}
                ],
                "pending_reports_count": 0,
                "tester_reports_count": 1,
                "reports_count": 8,
                "avg_rating": 3.5
            }
        """.trimIndent()

        val client = createTestClient { req ->
            val url = req.url.toString()
            when {
                url.contains("get_campaign_detail") -> {
                    val count = detailCallCount.incrementAndGet()
                    jsonResponse(if (count == 1) detail1 else detail2)
                }
                url.contains("reject_tester_report") -> {
                    jsonResponse("""{"success":true,"credits_deducted":0,"owner_warned":false}""")
                }
                url.contains("get_campaign_mission") -> jsonResponse("""{"mission":null}""")
                url.contains("user_slots") -> jsonResponse("[]")
                else -> jsonResponse("[]")
            }
        }
        importUser(client, "userOwner")
        val repo = CampaignRepository(client, Localizer(context))
        val vm = OwnerAppDetailViewModel("c1", repo, Localizer(context))

        try {
            drainViewModel(vm)
            assertEquals("Initial pending reports is 1", 1, vm.uiState.value.stats.pendingReportsCount)
            assertEquals("Initial total reports is 1", 1, vm.uiState.value.stats.totalReportsCount)
            assertEquals(1, detailCallCount.get())

            val reportToReject = vm.uiState.value.reports.first()
            vm.rejectReport(reportToReject, reason = "spam")
            drainViewModel(vm)

            assertEquals("Detail call count must increase after reject refresh", 2, detailCallCount.get())
            assertEquals("After reject, pending reports is 0", 0, vm.uiState.value.stats.pendingReportsCount)
            assertEquals("After reject, total reports remains tester_reports_count (1), not feedback reports_count (8)", 1, vm.uiState.value.stats.totalReportsCount)
            assertEquals("rejected", vm.uiState.value.reports.first().status)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
