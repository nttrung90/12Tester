package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel
import com.ntt.crosstest.ui.viewmodels.HomeViewModel
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionReportAndExpirationTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionCacheManager.clear(context)
        PendingReportStore.clear(context)
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        store.clear()
        try {
            WorkManager.getInstance(context).cancelAllWork().result.get()
        } catch (_: Exception) {}
        PendingReportStore.clear(context)
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }

    @Test
    fun testTesterReport_isMissionReport_resolution() {
        // 1. submissionContext == "mission"
        val r1 = TesterReport(id = "r1", campaignId = "c1", userId = "u1", submissionContext = "mission")
        assertTrue(r1.isMissionReport)

        // 2. submissionContext null but missionId is set
        val r2 = TesterReport(id = "r2", campaignId = "c1", userId = "u1", missionId = "m-123")
        assertTrue(r2.isMissionReport)

        // 3. Regular app test report
        val r3 = TesterReport(id = "r3", campaignId = "c1", userId = "u1", submissionContext = "app_test", missionId = null)
        assertFalse(r3.isMissionReport)

        // 4. Legacy report with nulls
        val r4 = TesterReport(id = "r4", campaignId = "c1", userId = "u1")
        assertFalse(r4.isMissionReport)
    }

    @Test
    fun testSubmitTesterReportResult_isMissionReport_resolution() {
        val res1 = SubmitTesterReportResult(success = true, submissionContext = "mission")
        assertTrue(res1.isMissionReport)

        val res2 = SubmitTesterReportResult(success = true, missionId = "m-456")
        assertTrue(res2.isMissionReport)

        val res3 = SubmitTesterReportResult(success = true, submissionContext = "app_test")
        assertFalse(res3.isMissionReport)
    }

    @Test
    fun testCampaignRepository_isMissionActiveAndUnexpired() {
        val mockEngine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val supabase = createSupabaseClient("https://mock.supabase.co", "anon-key") {
            httpEngine = mockEngine
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
        }
        val repo = CampaignRepository(supabase, Localizer(context))

        val nowEpochMs = 1761696000000L // 2025-10-29T00:00:00Z
        val futureEndsAt = Instant.ofEpochMilli(nowEpochMs + 3600000L).toString() // +1 hour
        val pastEndsAt = Instant.ofEpochMilli(nowEpochMs - 3600000L).toString() // -1 hour

        // 1. Open mission in future -> unexpired
        val mOpenFuture = Mission(id = "m1", status = "open", endsAt = futureEndsAt)
        assertTrue(repo.isMissionActiveAndUnexpired(mOpenFuture, nowEpochMs))

        // 2. Open mission in past -> expired
        val mOpenPast = Mission(id = "m2", status = "open", endsAt = pastEndsAt)
        assertFalse(repo.isMissionActiveAndUnexpired(mOpenPast, nowEpochMs))

        // 3. Full mission in future -> still active for joined testers
        val mFullFuture = Mission(id = "m3", status = "full", endsAt = futureEndsAt)
        assertTrue(repo.isMissionActiveAndUnexpired(mFullFuture, nowEpochMs))

        // 4. Paused mission in future -> can still complete if joined
        val mPausedFuture = Mission(id = "m4", status = "paused_by_creator", endsAt = futureEndsAt)
        assertTrue(repo.isMissionActiveAndUnexpired(mPausedFuture, nowEpochMs))

        // 5. Deleted / stopped mission -> not active
        val mDeleted = Mission(id = "m5", status = "deleted", endsAt = futureEndsAt)
        assertFalse(repo.isMissionActiveAndUnexpired(mDeleted, nowEpochMs))

        val mStopped = Mission(id = "m6", status = "stopped_no_credits", endsAt = futureEndsAt)
        assertFalse(repo.isMissionActiveAndUnexpired(mStopped, nowEpochMs))
    }

    @Test
    fun testCampaignRepository_getOpenMissions_filtersExpiredAndUpdatesClock() = runBlocking {
        val serverNowStr = "2026-10-29T10:00:00Z"
        val futureDeadline = "2026-10-29T12:00:00Z"
        val pastDeadline = "2026-10-29T08:00:00Z"

        val jsonResponse = """
            {
                "missions": [
                    {
                        "id": "m-active",
                        "title": "Active Mission",
                        "status": "open",
                        "ends_at": "$futureDeadline",
                        "campaign_id": "c1",
                        "max_participants": 10,
                        "participant_count": 2,
                        "reward_credits": 20
                    },
                    {
                        "id": "m-expired",
                        "title": "Expired Mission",
                        "status": "open",
                        "ends_at": "$pastDeadline",
                        "campaign_id": "c2",
                        "max_participants": 10,
                        "participant_count": 5,
                        "reward_credits": 30
                    }
                ],
                "server_now": "$serverNowStr"
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath.contains("get_open_missions")) {
                respond(
                    content = jsonResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

        val supabase = createSupabaseClient("https://mock.supabase.co", "anon-key") {
            httpEngine = mockEngine
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
        }

        val repo = CampaignRepository(supabase, Localizer(context))
        val missions = repo.getOpenMissions(forceRefresh = true)

        assertEquals("Chỉ giữ nhiệm vụ chưa hết hạn theo server_now", 1, missions.size)
        assertEquals("m-active", missions[0].id)
    }

    @Test
    fun testReportSubmitManager_preservesMissionIdentityInPersist() {
        val manager = ReportSubmitManager(context)
        val submitId = "submit-mission-123"

        val req = ReportSubmitManager.SubmitRequest(
            campaignId = "camp-1",
            appName = "App 1",
            userId = "user-1",
            rating = 5,
            comment = "Nhiệm vụ hoàn thành",
            preparedScreenshot = null,
            fallbackScreenshotUrl = null,
            fallbackImageHash = null,
            needsJoin = false,
            clientSubmitId = submitId,
            missionId = "mission-xyz",
            missionTitle = "Nhiệm vụ trải nghiệm 5 sao",
        )

        // Dùng reflection gọi private persist(request) để kiểm tra lưu trữ bền vững
        val persistMethod = ReportSubmitManager::class.java.getDeclaredMethod("persist", ReportSubmitManager.SubmitRequest::class.java)
        persistMethod.isAccessible = true
        val pendingReport = persistMethod.invoke(manager, req) as PendingReport

        assertEquals("mission-xyz", pendingReport.missionId)
        assertEquals("Nhiệm vụ trải nghiệm 5 sao", pendingReport.missionTitle)

        // Đọc lại từ disk PendingReportStore
        val diskReport = PendingReportStore.read(context, submitId)
        assertNotNull(diskReport)
        assertEquals("mission-xyz", diskReport!!.missionId)
        assertEquals("Nhiệm vụ trải nghiệm 5 sao", diskReport.missionTitle)
    }

    @Test
    fun testHomeViewModel_pruneExpiredMissions() = runBlocking {
        val serverNowStr = "2026-10-29T10:00:00Z"
        val futureDeadline = "2026-10-29T12:00:00Z"
        val pastDeadline = "2026-10-29T08:00:00Z"

        val mockEngine = MockEngine { request ->
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val supabase = createSupabaseClient("https://mock.supabase.co", "anon-key") {
            httpEngine = mockEngine
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
        }

        val repo = CampaignRepository(supabase, Localizer(context))
        repo.updateServerClock(serverNowStr)

        val vm = HomeViewModel(
            repository = repo,
            context = context,
            localizer = Localizer(context),
            reportSubmitManager = ReportSubmitManager(context),
        )

        val mActive = Mission(id = "m1", endsAt = futureDeadline, status = "open")
        val mExpired = Mission(id = "m2", endsAt = pastDeadline, status = "open")

        val unexpired = listOf(mActive, mExpired).filter { repo.isMissionActiveAndUnexpired(it) }
        assertEquals(1, unexpired.size)
        assertEquals("m1", unexpired[0].id)
    }
}
