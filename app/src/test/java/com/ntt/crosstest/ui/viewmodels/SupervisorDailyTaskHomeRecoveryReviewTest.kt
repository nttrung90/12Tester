package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.DailyTaskStatus
import com.ntt.crosstest.data.EgressOptimizationConfig
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue

/** Independent integration probes: real repository, HTTP fixture, ViewModel and coroutine clock. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SupervisorDailyTaskHomeRecoveryReviewTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
    private val uid = "supervisor-recovery-owner"
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = ConcurrentLinkedQueue<String>()
    private var stateForDiagnostics: () -> String = { "not constructed" }

    @Before fun setUp() {
        SessionCacheManager.clear(context)
        // These probes deliberately exercise the legacy dashboard contract;
        // protocol V2 has a separate regression suite.
        EgressOptimizationConfig.setAllEnabled(false)
        EgressOptimizationConfig.resetCapabilityCache()
    }
    @After fun tearDown() {
        SessionCacheManager.clear(context)
        EgressOptimizationConfig.setAllEnabled(false)
        EgressOptimizationConfig.resetCapabilityCache()
        Dispatchers.resetMain()
    }

    private fun task(ready: Boolean = true, met: Boolean = false) =
        """{"status_date":"$day","mode":"owner","active_app_count":1,"base_required_tests":1,"required_tests":1,"effective_required_tests":1,"available_apps":3,"completed_tests":${if (met) 1 else 0},"logged_in_today":true,"met":$met,"history_ready":$ready,"carryover_ready":$ready,"carryover_remaining_tests":0,"missed_streak":0}"""

    private fun dashboard(task: String) =
        """{"profile":{"id":"$uid","credits":100},"notifications":[],"my_campaigns":[],"joined_campaigns":[],"leaderboard":[],"today_checkin_count":0,"quest_summary":{},"daily_task":$task}"""

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.baseline(path: String): HttpResponseData = when {
        path.contains("get_my_dashboard") -> reply(dashboard(task()))
        path.contains("get_home_feed") -> reply("""{"open":[],"paused":[],"completed":[],"owner_names":{},"stats":{}}""")
        path.contains("record_daily_login") -> reply("true")
        path.contains("sync_campaign_lifecycle") -> reply("{}")
        path.contains("get_open_missions") -> reply("""{"server_now":"2026-11-15T00:00:00Z","missions":[]}""")
        path.contains("get_user_quest_summary") -> reply("{}")
        else -> reply("[]")
    }

    private fun client(
        dispatcher: CoroutineDispatcher,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = createSupabaseClient("https://supervisor-home.fixture.invalid", "fixture-key") {
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
                requests.add(request.url.encodedPath)
                handler(request)
            }
        })
    }

    private suspend fun login(client: SupabaseClient) {
        client.auth.awaitInitialization()
        client.auth.importSession(json.decodeFromString<UserSession>(
            """{"access_token":"fixture-token","refresh_token":"fixture-refresh","token_type":"bearer","expires_in":3600,"user":{"id":"$uid","aud":"authenticated"}}"""
        ))
    }

    // Repository single-flight requests use Dispatchers.IO; await their observable
    // completion while driving only the HTTP fixture / ViewModel virtual clock.
    private suspend fun awaitState(clock: TestCoroutineScheduler, condition: () -> Boolean) {
        try {
            withTimeout(10_000L) {
                while (!condition()) {
                    clock.runCurrent()
                    delay(1)
                }
            }
        } catch (e: TimeoutCancellationException) {
            println("Fixture wait timed out; requests=$requests; dashboardV2=${EgressOptimizationConfig.isDashboardSyncV2Enabled}; feedV2=${EgressOptimizationConfig.isHomeFeedSyncV2Enabled}; state=${stateForDiagnostics()}")
            throw e
        }
        clock.runCurrent()
    }

    @Test
    fun failedReloadAfterCommittedReportOffersRetryAndRecovers() = runBlocking {
        val clock = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(clock)
        Dispatchers.setMain(dispatcher)
        var serverHealthy = false
        val client = client(dispatcher) { request ->
            if (request.url.encodedPath.contains("get_my_daily_task_status")) {
                if (serverHealthy) reply(task(met = true))
                else reply("""{"message":"fixture offline"}""", HttpStatusCode.ServiceUnavailable)
            } else baseline(request.url.encodedPath)
        }
        login(client)
        val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
        val viewModel = HomeViewModel(repository, context = null, localizer = Localizer(context))
        stateForDiagnostics = { viewModel.uiState.value.toString() }
        try {
            clock.runCurrent()
            viewModel.stopMidnightScheduler()
            awaitState(clock) { viewModel.uiState.value.dailyTask != null && !viewModel.uiState.value.isBackgroundSyncing }
            repository.bumpMutationVersion("daily_task")
            viewModel.triggerDailyTaskReloadAfterReport()
            clock.advanceTimeBy(120_000L)
            clock.runCurrent()
            assertTrue("A failed report-triggered task reload must offer Retry after a bounded interval", viewModel.uiState.value.isDailyTaskError)
            assertFalse("A completed failed reload must not retain an indefinite loading flag", viewModel.uiState.value.isDailyTaskPendingRefresh)

            serverHealthy = true
            viewModel.retryDailyTask()
            clock.runCurrent()
            assertEquals(true, viewModel.uiState.value.dailyTask?.met)
            assertFalse(viewModel.uiState.value.isDailyTaskPendingRefresh)
            assertFalse(viewModel.uiState.value.isDailyTaskError)
        } finally {
            viewModel.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun unresolvedHistoryAfterRetryBudgetOffersManualRetry() = runBlocking {
        val clock = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(clock)
        Dispatchers.setMain(dispatcher)
        val client = client(dispatcher) { request ->
            if (request.url.encodedPath.contains("get_my_daily_task_status")) reply(task(ready = false))
            else baseline(request.url.encodedPath)
        }
        login(client)
        val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
        val viewModel = HomeViewModel(repository, context = null, localizer = Localizer(context))
        stateForDiagnostics = { viewModel.uiState.value.toString() }
        try {
            clock.runCurrent()
            viewModel.stopMidnightScheduler()
            awaitState(clock) { viewModel.uiState.value.dailyTask != null && !viewModel.uiState.value.isBackgroundSyncing }
            viewModel.checkAndSchedulePendingCarryoverRetry(DailyTaskStatus(statusDate = day.toString(), mode = "owner", carryoverReady = false))
            clock.advanceTimeBy(600_000L)
            clock.runCurrent()
            assertTrue("Still-unresolved history at the end of the polling budget must expose a Retry action", viewModel.uiState.value.isDailyTaskError)
            assertFalse(viewModel.pendingCarryoverJob?.isActive == true)
            assertEquals(false, viewModel.uiState.value.dailyTask?.carryoverReady)
        } finally {
            viewModel.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun olderDashboardCannotUndoNewerTaskCompletionAfterSameMutation() = runBlocking {
        val clock = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(clock)
        Dispatchers.setMain(dispatcher)
        val taskStarted = CompletableDeferred<Unit>()
        val dashboardStarted = CompletableDeferred<Unit>()
        val releaseTask = CompletableDeferred<Unit>()
        val releaseDashboard = CompletableDeferred<Unit>()
        var holdDashboard = false
        val client = client(dispatcher) { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_my_daily_task_status") -> {
                    taskStarted.complete(Unit)
                    releaseTask.await()
                    reply(task(met = true))
                }
                holdDashboard && path.endsWith("/get_my_dashboard") -> {
                    dashboardStarted.complete(Unit)
                    val oldSnapshot = dashboard(task(met = false))
                    releaseDashboard.await()
                    reply(oldSnapshot)
                }
                else -> baseline(path)
            }
        }
        login(client)
        val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
        val viewModel = HomeViewModel(repository, context = null, localizer = Localizer(context))
        stateForDiagnostics = { viewModel.uiState.value.toString() }
        try {
            clock.runCurrent()
            viewModel.stopMidnightScheduler()
            awaitState(clock) { viewModel.uiState.value.dailyTask != null && !viewModel.uiState.value.isBackgroundSyncing }
            holdDashboard = true
            repository.bumpMutationVersion("daily_task")
            viewModel.triggerDailyTaskReloadAfterReport()
            awaitState(clock) { taskStarted.isCompleted }
            assertTrue(taskStarted.isCompleted)
            viewModel.refresh(forceRefresh = true)
            awaitState(clock) { dashboardStarted.isCompleted }
            assertTrue("Fixture must hold an actual full dashboard request", dashboardStarted.isCompleted)

            releaseTask.complete(Unit)
            awaitState(clock) { viewModel.uiState.value.dailyTask?.met == true }
            assertEquals("Fresh task response must first publish completion", true, viewModel.uiState.value.dailyTask?.met)

            releaseDashboard.complete(Unit)
            awaitState(clock) { !viewModel.uiState.value.isBackgroundSyncing }
            assertEquals("Late dashboard captured before task completion must not roll the card back", true, viewModel.uiState.value.dailyTask?.met)
            assertFalse(viewModel.uiState.value.isDailyTaskPendingRefresh)
        } finally {
            releaseTask.complete(Unit)
            releaseDashboard.complete(Unit)
            viewModel.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun dashboardFinishingBeforeRetryJobCancelsRetryAndPreservesCompletion() = runBlocking {
        val clock = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(clock)
        Dispatchers.setMain(dispatcher)
        val taskStarted = CompletableDeferred<Unit>()
        val releaseTask = CompletableDeferred<Unit>()
        var dashboardMet = false
        val client = client(dispatcher) { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_my_daily_task_status") -> {
                    taskStarted.complete(Unit)
                    releaseTask.await()
                    reply("""{"message":"fixture failure after dashboard succeeded"}""", HttpStatusCode.ServiceUnavailable)
                }
                path.endsWith("/get_my_dashboard") -> {
                    val body = if (dashboardMet) {
                        """{"status_date":"$day","mode":"owner","active_app_count":1,"base_required_tests":12,"required_tests":12,"effective_required_tests":12,"available_apps":20,"completed_tests":12,"logged_in_today":true,"met":true,"history_ready":true,"carryover_ready":true,"carryover_remaining_tests":0,"missed_streak":0}"""
                    } else {
                        task(met = false)
                    }
                    reply(dashboard(body))
                }
                else -> baseline(path)
            }
        }
        login(client)
        val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
        val viewModel = HomeViewModel(repository, context = null, localizer = Localizer(context))
        stateForDiagnostics = { viewModel.uiState.value.toString() }
        try {
            clock.runCurrent()
            viewModel.stopMidnightScheduler()
            awaitState(clock) { viewModel.uiState.value.dailyTask != null && !viewModel.uiState.value.isBackgroundSyncing }

            dashboardMet = true
            viewModel.triggerDailyTaskReloadAfterReport()
            awaitState(clock) { taskStarted.isCompleted }
            viewModel.refresh(forceRefresh = true)
            awaitState(clock) { viewModel.uiState.value.dailyTask?.met == true && !viewModel.uiState.value.isBackgroundSyncing }

            assertEquals(12, viewModel.uiState.value.dailyTask?.completedTests)
            assertFalse(viewModel.uiState.value.isDailyTaskPendingRefresh)
            assertFalse(viewModel.uiState.value.isDailyTaskError)
            assertFalse("Dashboard winning the race must stop dailyTaskRetryJob", viewModel.dailyTaskRetryJob?.isActive == true)

            releaseTask.complete(Unit)
            clock.advanceTimeBy(120_000L)
            clock.runCurrent()

            assertEquals("Cancelled retry job must not revert completed 12/12 card to error", true, viewModel.uiState.value.dailyTask?.met)
            assertFalse(viewModel.uiState.value.isDailyTaskError)
            assertFalse(viewModel.uiState.value.isDailyTaskPendingRefresh)
        } finally {
            releaseTask.complete(Unit)
            viewModel.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun pauseAndResumeWhilePendingRefreshRecoversCompletionInsteadOfHanging() = runBlocking {
        val clock = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(clock)
        Dispatchers.setMain(dispatcher)
        var failInitialTaskRpc = true
        var dashboardOmitDailyTask = false
        val client = client(dispatcher) { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_my_daily_task_status") -> {
                    if (failInitialTaskRpc) {
                        reply("""{"message":"paused before complete"}""", HttpStatusCode.ServiceUnavailable)
                    } else {
                        reply("""{"status_date":"$day","mode":"owner","active_app_count":1,"base_required_tests":12,"required_tests":12,"effective_required_tests":12,"available_apps":20,"completed_tests":12,"logged_in_today":true,"met":true,"history_ready":true,"carryover_ready":true,"carryover_remaining_tests":0,"missed_streak":0}""")
                    }
                }
                path.endsWith("/get_my_dashboard") -> {
                    if (dashboardOmitDailyTask) {
                        reply("""{"profile":{"id":"$uid","credits":100},"notifications":[],"my_campaigns":[],"joined_campaigns":[],"leaderboard":[],"today_checkin_count":12,"quest_summary":{},"daily_task":null}""")
                    } else {
                        reply(dashboard(task(met = false)))
                    }
                }
                else -> baseline(path)
            }
        }
        login(client)
        val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
        val viewModel = HomeViewModel(repository, context = null, localizer = Localizer(context))
        stateForDiagnostics = { viewModel.uiState.value.toString() }
        try {
            clock.runCurrent()
            viewModel.stopMidnightScheduler()
            awaitState(clock) { viewModel.uiState.value.dailyTask != null && !viewModel.uiState.value.isBackgroundSyncing }

            // Start pending reload, then simulate HomeScreen onPauseOrDispose cancelling the retry job
            viewModel.triggerDailyTaskReloadAfterReport()
            assertTrue(viewModel.uiState.value.isDailyTaskPendingRefresh)
            viewModel.stopDailyTaskRetry()
            assertFalse(viewModel.dailyTaskRetryJob?.isActive == true)
            assertTrue(viewModel.uiState.value.isDailyTaskPendingRefresh)

            // On resume, dashboard lacks daily_task (or fails) while daily task RPC now has 12/12 met=true
            failInitialTaskRpc = false
            dashboardOmitDailyTask = true
            viewModel.resumePendingDailyTaskWork()
            viewModel.refresh(forceRefresh = false)

            clock.advanceTimeBy(1_000L)
            clock.runCurrent()
            awaitState(clock) { viewModel.uiState.value.dailyTask?.met == true && !viewModel.uiState.value.isDailyTaskPendingRefresh }

            assertEquals(12, viewModel.uiState.value.dailyTask?.completedTests)
            assertEquals(true, viewModel.uiState.value.dailyTask?.met)
            assertFalse("Pending refresh flag must clear once resumed retry completes", viewModel.uiState.value.isDailyTaskPendingRefresh)
            assertFalse(viewModel.uiState.value.isDailyTaskError)
        } finally {
            viewModel.viewModelScope.cancel()
            client.close()
        }
    }

    @Test
    fun pauseAndResumeDuringPendingCarryoverRestartsPollingEvenIfRefreshFails() = runBlocking {
        val clock = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(clock)
        Dispatchers.setMain(dispatcher)
        var carryoverSettledOnServer = false
        var failDashboardOnResume = false
        val client = client(dispatcher) { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_my_daily_task_status") -> {
                    if (carryoverSettledOnServer) {
                        reply(task(ready = true, met = true))
                    } else {
                        reply(task(ready = false, met = false))
                    }
                }
                path.endsWith("/get_my_dashboard") -> {
                    if (failDashboardOnResume) {
                        reply("""{"message":"transient resume error"}""", HttpStatusCode.ServiceUnavailable)
                    } else {
                        reply(dashboard(task(ready = false, met = false)))
                    }
                }
                else -> baseline(path)
            }
        }
        login(client)
        val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
        val viewModel = HomeViewModel(repository, context = null, localizer = Localizer(context))
        stateForDiagnostics = { viewModel.uiState.value.toString() }
        try {
            clock.runCurrent()
            awaitState(clock) { viewModel.uiState.value.dailyTask != null && !viewModel.uiState.value.isBackgroundSyncing }
            viewModel.stopMidnightScheduler()
            assertEquals(false, viewModel.uiState.value.dailyTask?.carryoverReady)
            viewModel.checkAndSchedulePendingCarryoverRetry(viewModel.uiState.value.dailyTask)
            assertTrue(viewModel.pendingCarryoverJob?.isActive == true)

            // Simulate HomeScreen onPauseOrDispose
            viewModel.stopPendingCarryoverRetry()
            assertFalse(viewModel.pendingCarryoverJob?.isActive == true)

            // Simulate HomeScreen resume while dashboard refresh fails
            failDashboardOnResume = true
            carryoverSettledOnServer = true
            viewModel.resumePendingDailyTaskWork()
            viewModel.refresh(forceRefresh = false)
            assertTrue("Carryover polling must resume even when dashboard refresh fails", viewModel.pendingCarryoverJob?.isActive == true)

            clock.advanceTimeBy(10_000L)
            clock.runCurrent()
            awaitState(clock) { viewModel.uiState.value.dailyTask?.carryoverReady == true }

            assertEquals(true, viewModel.uiState.value.dailyTask?.carryoverReady)
            assertEquals(true, viewModel.uiState.value.dailyTask?.met)
            assertFalse(viewModel.pendingCarryoverJob?.isActive == true)
        } finally {
            viewModel.viewModelScope.cancel()
            client.close()
        }
    }
}
