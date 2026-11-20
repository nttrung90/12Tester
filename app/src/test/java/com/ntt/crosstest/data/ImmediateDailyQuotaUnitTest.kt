package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.ui.viewmodels.HomeViewModel
import io.github.jan.supabase.SupabaseClient
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ImmediateDailyQuotaUnitTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private val vnToday = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()

    private val createdClients = Collections.synchronizedList(mutableListOf<SupabaseClient>())
    private val createdViewModels = Collections.synchronizedList(mutableListOf<HomeViewModel>())
    private val createdJobs = Collections.synchronizedList(mutableListOf<Job>())

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionCacheManager.clear(context)
    }

    @After
    fun cleanup() = runBlocking {
        // 1. Cancel and join all tracked standalone jobs
        val jobs = synchronized(createdJobs) {
            val copy = createdJobs.toList()
            createdJobs.clear()
            copy
        }
        jobs.forEach { job ->
            runCatching { job.cancelAndJoin() }
        }

        // 2. Stop scheduler and cancel + join all ViewModel scopes
        val vms = synchronized(createdViewModels) {
            val copy = createdViewModels.toList()
            createdViewModels.clear()
            copy
        }
        vms.forEach { vm ->
            runCatching { vm.stopMidnightScheduler() }
            runCatching { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }

        // 3. Close all created Supabase clients
        val clients = synchronized(createdClients) {
            val copy = createdClients.toList()
            createdClients.clear()
            copy
        }
        clients.forEach { client ->
            runCatching { client.close() }
        }

        // 4. Settle SessionCacheManager
        SessionCacheManager.clear(context)

        // 5. Reset Dispatchers.Main only after all scopes and jobs are settled
        Dispatchers.resetMain()
    }

    private fun trackJob(job: Job): Job {
        createdJobs.add(job)
        return job
    }

    private fun trackVm(vm: HomeViewModel): HomeViewModel {
        createdViewModels.add(vm)
        return vm
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient {
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
        createdClients.add(client)
        return client
    }

    private suspend fun SupabaseClient.signInAs(userId: String) {
        auth.awaitInitialization()
        auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"test-token","refresh_token":"test-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$userId","aud":"authenticated"}}"""
            )
        )
    }

    @Test
    fun createCampaignV2_bumpsDailyTaskMutationAndRefreshesQuota() = runBlocking {
        val testUser = "user-imm-create"
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.contains("create_campaign_v2") -> {
                    reply(
                        """{
                            "success": true,
                            "receipt_id": "rec-001",
                            "client_request_id": "req-001",
                            "campaign_id": "camp-002",
                            "slot_number": 2,
                            "target_testers": 12,
                            "slot_1_cost": 0,
                            "target_cost": 100,
                            "charged_credits": 100,
                            "min_credits_required": 10,
                            "balance_after": 900,
                            "replayed": false
                        }"""
                    )
                }
                path.contains("get_my_daily_task_status") -> {
                    reply(
                        """{
                            "status_date": "$vnToday",
                            "mode": "owner",
                            "active_app_count": 2,
                            "required_tests": 24,
                            "available_apps": 20,
                            "effective_required_tests": 24,
                            "completed_tests": 0,
                            "logged_in_today": true,
                            "is_penalty_exempt": false,
                            "missed_streak": 0,
                            "next_penalty_percent": 1,
                            "met": false,
                            "base_required_tests": 24,
                            "carry_in_tests": 0,
                            "carryover_remaining_tests": 0,
                            "carryover_ready": true,
                            "history_ready": true
                        }"""
                    )
                }
                path.contains("get_business_quote") -> {
                    reply("""{"total_cost": 100, "min_credits_to_post_app": 10, "quote_token": "rev-1"}""")
                }
                path.contains("profiles") -> {
                    reply("""[{"id": "$testUser", "role": "user", "credits": 900}]""")
                }
                else -> reply("[]")
            }
        }
        client.signInAs(testUser)
        val repository = CampaignRepository(client, Localizer(context))

        val eventDeferred = CompletableDeferred<DailyTaskChangeEvent>()
        val job = trackJob(launch {
            repository.dailyTaskEvents.collect { event ->
                eventDeferred.complete(event)
            }
        })

        val createParams = CreateCampaignV2Params(
            appName = "App 2",
            packageName = "com.test.app2",
            slotNumber = 2,
            targetTesters = 12,
            clientRequestId = "req-001",
            expectedTotalCost = 100,
            expectedMinCredits = 10,
            expectedRevision = "rev-1"
        )

        val result = repository.createCampaignV2(createParams)
        assertEquals(true, result.success)
        assertEquals("rec-001", result.receiptId)

        val event = withTimeout(5000) { eventDeferred.await() }
        assertTrue("Event should be Updated", event is DailyTaskChangeEvent.Updated)
        val updated = event as DailyTaskChangeEvent.Updated
        assertEquals("create_campaign_v2", updated.triggerSource)
        assertEquals(2, updated.status.activeAppCount)
        assertEquals(24, updated.status.baseRequiredTests)

        job.cancelAndJoin()
    }

    @Test
    fun deleteCampaign_bumpsDailyTaskMutationAndEmitsUpdatedQuota() = runBlocking {
        val testUser = "user-imm-delete"
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.contains("delete_owned_campaign") -> {
                    reply(
                        """{
                            "success": true,
                            "status": "removed",
                            "campaign_id": "camp-001",
                            "app_name": "App 1",
                            "owner_id": "$testUser",
                            "reason": "owner_removal"
                        }"""
                    )
                }
                path.contains("get_my_daily_task_status") -> {
                    reply(
                        """{
                            "status_date": "$vnToday",
                            "mode": "member",
                            "active_app_count": 0,
                            "required_tests": 0,
                            "available_apps": 20,
                            "effective_required_tests": 0,
                            "completed_tests": 0,
                            "logged_in_today": true,
                            "is_penalty_exempt": false,
                            "missed_streak": 0,
                            "next_penalty_percent": 1,
                            "met": true,
                            "base_required_tests": 0,
                            "carry_in_tests": 0,
                            "carryover_remaining_tests": 0,
                            "carryover_ready": true,
                            "history_ready": true
                        }"""
                    )
                }
                path.contains("profiles") -> {
                    reply("""[{"id": "$testUser", "role": "user", "credits": 500}]""")
                }
                else -> reply("[]")
            }
        }
        client.signInAs(testUser)
        val repository = CampaignRepository(client, Localizer(context))

        val eventDeferred = CompletableDeferred<DailyTaskChangeEvent>()
        val job = trackJob(launch {
            repository.dailyTaskEvents.collect { event ->
                eventDeferred.complete(event)
            }
        })

        val deleteResult = repository.deleteCampaign("camp-001")
        assertEquals(true, deleteResult.success)
        assertEquals("removed", deleteResult.status)

        val event = withTimeout(5000) { eventDeferred.await() }
        assertTrue("Event should be Updated", event is DailyTaskChangeEvent.Updated)
        val updated = event as DailyTaskChangeEvent.Updated
        assertEquals("delete_campaign", updated.triggerSource)
        assertEquals(0, updated.status.activeAppCount)
        assertEquals(0, updated.status.baseRequiredTests)

        job.cancelAndJoin()
    }

    @Test
    fun postCommitRefreshError_emitsStalePendingWithoutFailingFinancialAction() = runBlocking {
        val testUser = "user-imm-stale"
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.contains("create_campaign_v2") -> {
                    reply(
                        """{
                            "success": true,
                            "receipt_id": "rec-stale-01",
                            "client_request_id": "req-stale-01",
                            "campaign_id": "camp-stale-01",
                            "slot_number": 2,
                            "target_testers": 12,
                            "slot_1_cost": 0,
                            "target_cost": 100,
                            "charged_credits": 100,
                            "min_credits_required": 10,
                            "balance_after": 800,
                            "replayed": false
                        }"""
                    )
                }
                path.contains("get_my_daily_task_status") -> {
                    // Simulate server network/RPC failure when refreshing quota post-commit
                    reply("""{"message": "Network timeout on daily task refresh"}""", HttpStatusCode.InternalServerError)
                }
                path.contains("profiles") -> {
                    reply("""[{"id": "$testUser", "role": "user", "credits": 800}]""")
                }
                else -> reply("[]")
            }
        }
        client.signInAs(testUser)
        val repository = CampaignRepository(client, Localizer(context))

        val eventDeferred = CompletableDeferred<DailyTaskChangeEvent>()
        val job = trackJob(launch {
            repository.dailyTaskEvents.collect { event ->
                eventDeferred.complete(event)
            }
        })

        val createParams = CreateCampaignV2Params(
            appName = "App Stale",
            packageName = "com.test.stale",
            slotNumber = 2,
            targetTesters = 12,
            clientRequestId = "req-stale-01",
            expectedTotalCost = 100,
            expectedMinCredits = 10,
            expectedRevision = "rev-1"
        )

        // Financial action MUST succeed despite post-commit quota refresh error
        val result = repository.createCampaignV2(createParams)
        assertEquals(true, result.success)
        assertEquals("rec-stale-01", result.receiptId)

        // Event emitted must be StalePending indicating refresh retry needed
        val event = withTimeout(5000) { eventDeferred.await() }
        assertTrue("Event should be StalePending", event is DailyTaskChangeEvent.StalePending)
        val stalePending = event as DailyTaskChangeEvent.StalePending
        assertEquals("create_campaign_v2", stalePending.triggerSource)
        assertNotNull("Failure cause should be recorded", stalePending.cause)

        job.cancelAndJoin()
    }

    @Test
    fun homeViewModel_updatesUiState_uponDailyTaskChangeEvent() = runBlocking {
        val testUser = "user-imm-home"
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.contains("profiles") -> {
                    reply("""[{"id": "$testUser", "role": "user", "credits": 500}]""")
                }
                path.contains("get_my_daily_task_status") -> {
                    reply(
                        """{
                            "status_date": "$vnToday",
                            "mode": "owner",
                            "active_app_count": 1,
                            "required_tests": 12,
                            "available_apps": 20,
                            "effective_required_tests": 12,
                            "completed_tests": 0,
                            "logged_in_today": true,
                            "is_penalty_exempt": false,
                            "missed_streak": 0,
                            "next_penalty_percent": 1,
                            "met": false,
                            "base_required_tests": 12,
                            "carry_in_tests": 0,
                            "carryover_remaining_tests": 0,
                            "carryover_ready": true,
                            "history_ready": true
                        }"""
                    )
                }
                else -> reply("[]")
            }
        }
        client.signInAs(testUser)
        val repository = CampaignRepository(client, Localizer(context))
        val viewModel = trackVm(HomeViewModel(repository, context, Localizer(context)))

        // Initial daily task from HomeViewModel load
        val newTask = DailyTaskStatus(
            statusDate = vnToday,
            mode = "owner",
            activeAppCount = 3,
            requiredTests = 36,
            baseRequiredTests = 36,
            completedTests = 5,
            met = false,
            carryInTests = 0,
            carryoverReady = true,
            historyReady = true
        )

        val currentGen = SessionCacheManager.currentGeneration()
        val currentDay = repository.businessDay().toString()
        val mutVer = repository.getMutationVersion("daily_task")

        // Repository receives update event matching 4-way fence
        repository.publishDailyTaskEvent(
            DailyTaskChangeEvent.Updated(
                ownerId = testUser,
                generation = currentGen,
                businessDate = currentDay,
                mutationVersion = mutVer,
                triggerSource = "acceptance_test",
                status = newTask
            )
        )

        val updatedState = withTimeout(5000) {
            viewModel.uiState.first { it.dailyTask?.activeAppCount == 3 }
        }

        assertEquals(3, updatedState.dailyTask?.activeAppCount)
        assertEquals(36, updatedState.dailyTask?.baseRequiredTests)
        assertEquals(5, updatedState.dailyTask?.completedTests)
    }

    @Test
    fun fourWayFencing_dropsStaleOrMismatchedEvents() = runBlocking {
        val testUser = "user-imm-fence"
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id": "$testUser", "role": "user", "credits": 500}]""")
                path.contains("get_my_daily_task_status") -> reply(
                    """{
                        "status_date": "$vnToday",
                        "mode": "owner",
                        "active_app_count": 1,
                        "required_tests": 12,
                        "base_required_tests": 12,
                        "completed_tests": 0,
                        "logged_in_today": true,
                        "is_penalty_exempt": false,
                        "missed_streak": 0,
                        "met": false
                    }"""
                )
                else -> reply("[]")
            }
        }
        client.signInAs(testUser)
        val repository = CampaignRepository(client, Localizer(context))
        val viewModel = trackVm(HomeViewModel(repository, context, Localizer(context)))

        val currentGen = SessionCacheManager.currentGeneration()
        val currentDay = repository.businessDay().toString()
        val mutVer = repository.getMutationVersion("daily_task")

        // 1. Stale event for another user
        repository.publishDailyTaskEvent(
            DailyTaskChangeEvent.Updated(
                ownerId = "different-user",
                generation = currentGen,
                businessDate = currentDay,
                mutationVersion = mutVer,
                triggerSource = "fence_test",
                status = DailyTaskStatus(statusDate = currentDay, activeAppCount = 99, baseRequiredTests = 999)
            )
        )

        // 2. Stale event for past mutation version
        repository.publishDailyTaskEvent(
            DailyTaskChangeEvent.Updated(
                ownerId = testUser,
                generation = currentGen,
                businessDate = currentDay,
                mutationVersion = mutVer - 1,
                triggerSource = "fence_test",
                status = DailyTaskStatus(statusDate = currentDay, activeAppCount = 99, baseRequiredTests = 999)
            )
        )

        // 3. Stale event for different business date
        repository.publishDailyTaskEvent(
            DailyTaskChangeEvent.Updated(
                ownerId = testUser,
                generation = currentGen,
                businessDate = "2020-01-01",
                mutationVersion = mutVer,
                triggerSource = "fence_test",
                status = DailyTaskStatus(statusDate = "2020-01-01", activeAppCount = 99, baseRequiredTests = 999)
            )
        )

        // State should remain unaffected by stale/mismatched events
        assertEquals(false, viewModel.uiState.value.dailyTask?.activeAppCount == 99)
    }
}
