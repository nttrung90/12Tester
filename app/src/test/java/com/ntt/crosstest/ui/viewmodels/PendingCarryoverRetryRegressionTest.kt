package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.DailyTaskStatus
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PendingCarryoverRetryRegressionTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        SessionCacheManager.clear(context)
    }

    @After
    fun tearDown() {
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }

    private fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )

    private fun MockRequestHandleScope.respondBaseline(url: String, currentUid: String): HttpResponseData {
        return when {
            url.contains("get_home_feed") -> jsonResponse("""{"open":[],"paused":[],"completed":[],"owner_names":{},"stats":{}}""")
            url.contains("get_my_dashboard") -> jsonResponse("""{"profile":{"id":"$currentUid","credits":100},"notifications":[],"my_campaigns":[],"joined_campaigns":[],"leaderboard":[],"quest_summary":null,"daily_task":null}""")
            url.contains("sync_campaign_lifecycle") -> jsonResponse("{}")
            url.contains("record_daily_login") -> jsonResponse("true")
            url.contains("get_open_missions") -> jsonResponse("""{"server_now":"2026-10-30T00:00:00Z","missions":[]}""")
            else -> jsonResponse("[]")
        }
    }

    private fun createTestClient(
        dispatcher: CoroutineDispatcher,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): SupabaseClient {
        val engine = MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler(handler)
        })
        return createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = engine
        }
    }

    private suspend fun importUser(client: SupabaseClient, userId: String) {
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
        client.auth.awaitInitialization()
        client.auth.importSession(json.decodeFromString<UserSession>(sessionJson))
    }

    private fun dailyTaskJson(date: String, ready: Boolean, missedStreak: Int = 0): String {
        return """
            {
                "status_date": "$date",
                "mode": "owner",
                "active_app_count": 2,
                "required_tests": 6,
                "available_apps": 20,
                "effective_required_tests": 6,
                "completed_tests": 4,
                "logged_in_today": true,
                "is_penalty_exempt": false,
                "missed_streak": $missedStreak,
                "next_penalty_percent": 1,
                "met": false,
                "base_required_tests": 6,
                "carry_in_tests": 0,
                "carryover_remaining_tests": 0,
                "carryover_ready": $ready,
                "history_ready": true,
                "carryover_effective_from": null
            }
        """.trimIndent()
    }

    @Test
    fun testPendingThrough120sThenReadyTransitionsAutomaticallyWithoutManualRefresh() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(testDispatcher)

        val testDate = "2026-10-30"
        val dayRef = AtomicReference(LocalDate.parse(testDate))
        val isReadyOnServer = AtomicBoolean(false)
        val pollCount = AtomicInteger(0)

        val client = createTestClient(testDispatcher) { req ->
            val url = req.url.toString()
            if (url.contains("get_my_daily_task_status")) {
                pollCount.incrementAndGet()
                val ready = isReadyOnServer.get()
                jsonResponse(dailyTaskJson(dayRef.get().toString(), ready = ready))
            } else {
                respondBaseline(url, "user_carryover_1")
            }
        }
        importUser(client, "user_carryover_1")

        val repo = CampaignRepository(
            supabase = client,
            localizer = Localizer(context),
            businessDayClock = { dayRef.get() }
        )
        val vm = HomeViewModel(repo, context = null, localizer = Localizer(context))
        scheduler.runCurrent()
        vm.stopMidnightScheduler()

        try {
            // Đặt lại counter sau khi initial setup settle
            pollCount.set(0)

            val initialPendingTask = DailyTaskStatus(
                statusDate = testDate,
                mode = "owner",
                carryoverReady = false,
                missedStreak = 0,
            )

            // Bắt đầu lập lịch retry cho pending carryover
            vm.checkAndSchedulePendingCarryoverRetry(initialPendingTask)
            assertTrue("Retry job must be active", vm.pendingCarryoverJob?.isActive == true)

            // Delays: 5s, 10s (t=15s), 20s (t=35s), 30s (t=65s), 30s (t=95s).
            // Sau 95s là delay 30s tiếp theo -> t=125s mới bắn lần 6.
            // Tiến virtual time tới 120s: 5 lần đã thực hiện, server vẫn pending, state chưa ready.
            scheduler.advanceTimeBy(120_000L)
            scheduler.runCurrent()

            assertEquals("Must have polled exactly 5 times by 120s", 5, pollCount.get())
            assertEquals(false, vm.uiState.value.dailyTask?.carryoverReady)
            assertTrue("Retry job must still be running waiting for next attempt", vm.pendingCarryoverJob?.isActive == true)

            // Cron chốt xong sau 00:07 (>120s): Server chuyển sang ready = true
            isReadyOnServer.set(true)

            // Tiến virtual time thêm 10s (t=130s, vượt mốc 125s) -> Lần thử 6 tự động chạy
            scheduler.advanceTimeBy(10_000L)
            scheduler.runCurrent()

            assertEquals("6th attempt must have fired", 6, pollCount.get())
            assertEquals(true, vm.uiState.value.dailyTask?.carryoverReady)
            // Dừng ngay khi ready: job phải tự kết thúc
            assertFalse("Retry job must terminate automatically when carryover becomes ready", vm.pendingCarryoverJob?.isActive == true)
        } finally {
            vm.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun testBoundedMaxAttemptsDoesNotLoopInfinitely() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(testDispatcher)

        val testDate = "2026-10-30"
        val dayRef = AtomicReference(LocalDate.parse(testDate))
        val pollCount = AtomicInteger(0)

        // Server luôn trả pending (hoặc lỗi/null)
        val client = createTestClient(testDispatcher) { req ->
            val url = req.url.toString()
            if (url.contains("get_my_daily_task_status")) {
                pollCount.incrementAndGet()
                jsonResponse(dailyTaskJson(dayRef.get().toString(), ready = false))
            } else {
                respondBaseline(url, "user_bounded")
            }
        }
        importUser(client, "user_bounded")

        val repo = CampaignRepository(
            supabase = client,
            localizer = Localizer(context),
            businessDayClock = { dayRef.get() }
        )
        val vm = HomeViewModel(repo, context = null, localizer = Localizer(context))
        scheduler.runCurrent()
        vm.stopMidnightScheduler()

        try {
            pollCount.set(0)

            val initialPendingTask = DailyTaskStatus(
                statusDate = testDate,
                mode = "owner",
                carryoverReady = false,
            )

            vm.checkAndSchedulePendingCarryoverRetry(initialPendingTask)

            // Tổng thời gian của PENDING_CARRYOVER_BACKOFF_MS là 545s (~9.1 phút, 13 lần thử).
            // Tiến virtual time qua 600s:
            scheduler.advanceTimeBy(600_000L)
            scheduler.runCurrent()

            assertEquals(
                "Must execute exactly bounded number of attempts",
                HomeViewModel.PENDING_CARRYOVER_BACKOFF_MS.size,
                pollCount.get()
            )
            assertFalse("Retry job must complete and become inactive after all bounded attempts", vm.pendingCarryoverJob?.isActive == true)
        } finally {
            vm.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun testStaleGenerationCancellation_ABAUserMustNotPublishIntoNewSession() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(testDispatcher)

        val testDate = "2026-10-30"
        val dayRef = AtomicReference(LocalDate.parse(testDate))

        val client = createTestClient(testDispatcher) { req ->
            val url = req.url.toString()
            if (url.contains("get_my_daily_task_status")) {
                jsonResponse(dailyTaskJson(dayRef.get().toString(), ready = true, missedStreak = 99))
            } else {
                respondBaseline(url, "userA")
            }
        }
        importUser(client, "userA")

        val repo = CampaignRepository(
            supabase = client,
            localizer = Localizer(context),
            businessDayClock = { dayRef.get() }
        )
        val vm = HomeViewModel(repo, context = null, localizer = Localizer(context))
        scheduler.runCurrent()
        vm.stopMidnightScheduler()

        try {
            val pendingTask = DailyTaskStatus(
                statusDate = testDate,
                mode = "owner",
                carryoverReady = false,
                missedStreak = 0,
            )

            vm.checkAndSchedulePendingCarryoverRetry(pendingTask)
            assertTrue(vm.pendingCarryoverJob?.isActive == true)

            // Trước khi lần thử chạy, phiên người dùng bị thay đổi thế hệ cache:
            // Mô phỏng A -> B -> A: uid vẫn là userA nhưng generation bị tăng do đăng xuất / đăng nhập lại
            SessionCacheManager.generation.incrementAndGet()

            // Tiến virtual time
            scheduler.advanceTimeBy(10_000L)
            scheduler.runCurrent()

            // Retry job phải hủy an toàn và không được publish missedStreak = 99 vào state
            assertTrue("Stale session response must not publish into new generation", vm.uiState.value.missedStreak != 99)
            assertFalse("Job must break and become inactive on stale generation", vm.pendingCarryoverJob?.isActive == true)
        } finally {
            vm.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun testCrossMidnightCancellation_mustNotPublishIntoNewDay() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(testDispatcher)

        val dayRef = AtomicReference(LocalDate.parse("2026-10-30"))

        val client = createTestClient(testDispatcher) { req ->
            val url = req.url.toString()
            if (url.contains("get_my_daily_task_status")) {
                // Server trả về kết quả hôm qua
                jsonResponse(dailyTaskJson("2026-10-30", ready = true, missedStreak = 77))
            } else {
                respondBaseline(url, "user_midnight")
            }
        }
        importUser(client, "user_midnight")

        val repo = CampaignRepository(
            supabase = client,
            localizer = Localizer(context),
            businessDayClock = { dayRef.get() }
        )
        val vm = HomeViewModel(repo, context = null, localizer = Localizer(context))
        scheduler.runCurrent()
        vm.stopMidnightScheduler()

        try {
            val pendingTask = DailyTaskStatus(
                statusDate = "2026-10-30",
                mode = "owner",
                carryoverReady = false,
            )

            vm.checkAndSchedulePendingCarryoverRetry(pendingTask)
            assertTrue(vm.pendingCarryoverJob?.isActive == true)

            // Sang ngày mới trước khi delay hoàn thành
            dayRef.set(LocalDate.parse("2026-10-31"))

            scheduler.advanceTimeBy(10_000L)
            scheduler.runCurrent()

            // Job phải ngắt vì dayRollover, không được publish status hôm qua vào state ngày mới
            assertTrue("Cross-midnight stale response must not publish into new day", vm.uiState.value.missedStreak != 77)
            assertFalse("Job must cancel/break when business day changes", vm.pendingCarryoverJob?.isActive == true)
        } finally {
            vm.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun testActiveRetryOwnedByPriorIdentityDoesNotSuppressStartingRetryForCurrentIdentity() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(testDispatcher)

        val testDate = "2026-10-30"
        val dayRef = AtomicReference(LocalDate.parse(testDate))
        val currentReturningUid = AtomicReference("userA")

        val client = createTestClient(testDispatcher) { req ->
            val url = req.url.toString()
            if (url.contains("get_my_daily_task_status")) {
                val uid = currentReturningUid.get()
                val ready = (uid == "userB")
                jsonResponse(dailyTaskJson(dayRef.get().toString(), ready = ready, missedStreak = if (ready) 3 else 0))
            } else {
                respondBaseline(url, currentReturningUid.get())
            }
        }
        importUser(client, "userA")

        val repo = CampaignRepository(
            supabase = client,
            localizer = Localizer(context),
            businessDayClock = { dayRef.get() }
        )
        val vm = HomeViewModel(repo, context = null, localizer = Localizer(context))
        scheduler.runCurrent()
        vm.stopMidnightScheduler()

        try {
            val taskA = DailyTaskStatus(statusDate = testDate, mode = "owner", carryoverReady = false)
            vm.checkAndSchedulePendingCarryoverRetry(taskA)
            val jobA = vm.pendingCarryoverJob
            assertNotNull(jobA)
            assertTrue(jobA!!.isActive)

            // User A đổi sang user B
            importUser(client, "userB")
            currentReturningUid.set("userB")
            SessionCacheManager.generation.incrementAndGet()

            val taskB = DailyTaskStatus(statusDate = testDate, mode = "owner", carryoverReady = false)
            // Lập lịch retry cho user B: không được để job của user A chặn user B
            vm.checkAndSchedulePendingCarryoverRetry(taskB)
            val jobB = vm.pendingCarryoverJob

            assertTrue("Old job for user A must be cancelled", jobA.isCancelled)
            assertNotNull("New job for user B must be started", jobB)
            assertTrue("New job for user B must be active", jobB!!.isActive)

            // Tiến virtual time để job B hoàn tất (userB trả về ready = true ở attempt 1 sau 5s)
            scheduler.advanceTimeBy(10_000L)
            scheduler.runCurrent()

            assertEquals("UI state must receive user B's updated status", 3, vm.uiState.value.missedStreak)
            assertEquals(true, vm.uiState.value.dailyTask?.carryoverReady)
        } finally {
            vm.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun testStopPendingCarryoverRetrySafelyCancelsJob() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val testDispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(testDispatcher)

        val testDate = "2026-10-30"
        val dayRef = AtomicReference(LocalDate.parse(testDate))

        val client = createTestClient(testDispatcher) { req ->
            val url = req.url.toString()
            if (url.contains("get_my_daily_task_status")) {
                jsonResponse(dailyTaskJson(testDate, ready = false))
            } else {
                respondBaseline(url, "user_cancel")
            }
        }
        importUser(client, "user_cancel")

        val repo = CampaignRepository(
            supabase = client,
            localizer = Localizer(context),
            businessDayClock = { dayRef.get() }
        )
        val vm = HomeViewModel(repo, context = null, localizer = Localizer(context))
        scheduler.runCurrent()
        vm.stopMidnightScheduler()

        try {
            val task = DailyTaskStatus(statusDate = testDate, mode = "owner", carryoverReady = false)
            vm.checkAndSchedulePendingCarryoverRetry(task)
            val job = vm.pendingCarryoverJob
            assertNotNull(job)
            assertTrue(job!!.isActive)

            vm.stopPendingCarryoverRetry()
            assertTrue("Job must be cancelled", job.isCancelled)
            assertNull("Job reference must be null after stop", vm.pendingCarryoverJob)
        } finally {
            vm.viewModelScope.cancel()
            client.close()
        }
    }
}
