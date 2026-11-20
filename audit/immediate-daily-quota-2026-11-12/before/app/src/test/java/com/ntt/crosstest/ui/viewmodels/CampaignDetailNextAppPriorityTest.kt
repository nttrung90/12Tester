package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.EgressOptimizationConfig
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.PendingReport
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.SessionCacheManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CampaignDetailNextAppPriorityTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val testDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var currentTestDay = LocalDate.of(2026, 11, 10)
    private val clients = mutableListOf<SupabaseClient>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        PendingReportStore.clear(context)
        SessionCacheManager.clear(context)
        EgressOptimizationConfig.resetCapabilityCache()
        EgressOptimizationConfig.isCandidatesV2Enabled = true
        currentTestDay = LocalDate.of(2026, 11, 10)
    }

    @After
    fun tearDown() = runBlocking {
        store.clear()
        PendingReportStore.clear(context)
        SessionCacheManager.clear(context)
        EgressOptimizationConfig.resetCapabilityCache()
        clients.forEach { it.close() }
        clients.clear()
        Dispatchers.resetMain()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun detailJson(campaignId: String = "curr-camp"): String = """{
        "campaign":{"id":"$campaignId","owner_id":"other-owner","app_name":"Current App","package_name":"curr.pkg","status":"open","target_testers":12},
        "is_member":true,
        "is_actual_owner":false,
        "can_manage":false,
        "my_profile":{"id":"test-tester","role":"member","credits":100},
        "reports":[],
        "testers":[],
        "feedbacks":[],
        "checked_in_confirmed_today":false
    }"""

    private suspend fun login(c: SupabaseClient, uid: String) {
        withTimeout(5000) {
            c.auth.awaitInitialization()
        }
        c.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"fixture-$uid","refresh_token":"fixture-ref-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )
    }

    private fun createMockClient(
        candidateJson: String,
        shouldFailCandidates: Boolean = false,
        onCandidateRequest: (suspend () -> Unit)? = null,
        enteredGate: CompletableDeferred<Unit>? = null,
        releaseGate: CompletableDeferred<Unit>? = null,
    ): SupabaseClient {
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                autoLoadFromStorage = false
                autoSaveToStorage = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/get_next_campaign_candidates_v2") -> {
                        enteredGate?.complete(Unit)
                        onCandidateRequest?.invoke()
                        releaseGate?.await()
                        if (shouldFailCandidates) {
                            respond("""{"message":"Network failure"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                        } else {
                            respond(candidateJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    }
                    path.endsWith("/get_campaign_detail") || path.endsWith("/get_campaign_detail_sync_v2") -> {
                        respond(detailJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    path.endsWith("/checkins") -> {
                        respond(
                            "[]",
                            HttpStatusCode.OK,
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                "Content-Range" to listOf("0-0/0"),
                            )
                        )
                    }
                    path.endsWith("/tester_reports") -> {
                        respond(
                            "[]",
                            HttpStatusCode.OK,
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                "Content-Range" to listOf("0-0/0"),
                            )
                        )
                    }
                    else -> respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        clients += client
        return client
    }

    private suspend fun makeVm(client: SupabaseClient, userId: String = "test-tester"): CampaignDetailViewModel {
        login(client, userId)
        val repo = CampaignRepository(client, Localizer(context), businessDayClock = { currentTestDay })
        val submitManager = ReportSubmitManager(context)
        val vm = CampaignDetailViewModel(
            campaignId = "curr-camp",
            repository = repo,
            reportSubmitManager = submitManager,
            myUserId = userId,
            localizer = Localizer(context),
            appContext = context,
        )
        store.put("vm_" + System.nanoTime(), vm)
        testDispatcher.scheduler.runCurrent()
        return vm
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 5000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            testDispatcher.scheduler.runCurrent()
            if (condition()) break
            if (System.currentTimeMillis() > deadline) {
                fail("Condition timed out after ${timeoutMs}ms")
            }
            delay(10)
        }
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun prefetch_success_distinguishesCandidatesAndSelectsWinner() = runBlocking {
        // App 1: under target (2/12), installed
        // App 2: reached target (12/12), installed
        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 10,
                "today_tested_count": 2,
                "stats_date": "2026-11-10"
            },
            {
                "campaign": {"id": "c2", "owner_id": "o2", "app_name": "App 2", "package_name": "pkg.app2", "status": "open", "target_testers": 12},
                "installed_count": 5,
                "today_tested_count": 12,
                "stats_date": "2026-11-10"
            }
        ]"""

        val client = createMockClient(candidateJson)
        val vm = makeVm(client)

        vm.prefetchNextCampaign()
        waitUntil(5000) { !vm.uiState.value.nextSuggestionLoading && vm.uiState.value.nextCampaignToTest != null }

        val state = vm.uiState.value
        assertFalse("Loading must be false after completion", state.nextSuggestionLoading)
        assertFalse("Failed must be false on success", state.nextSuggestionFailed)
        assertNotNull("Next campaign must be chosen", state.nextCampaignToTest)
        // Group 1 (under target) beats Group 3 (reached target)
        assertEquals("c1", state.nextCampaignToTest?.id)
    }

    @Test
    fun prefetch_networkFailure_distinguishedFromGenuinelyEmptyList() = runBlocking {
        val client = createMockClient("[]", shouldFailCandidates = true)
        val vm = makeVm(client)

        vm.prefetchNextCampaign()
        waitUntil(5000) { !vm.uiState.value.nextSuggestionLoading }

        val state = vm.uiState.value
        assertFalse(state.nextSuggestionLoading)
        assertNull(state.nextCampaignToTest)
        assertTrue("Must record load failure so UI shows retry, not 'all done'", state.nextSuggestionFailed)

        // Dismiss cleans up failure flag
        vm.dismissNextAppPrompt()
        assertFalse(vm.uiState.value.nextSuggestionFailed)
    }

    @Test
    fun prefetch_genuinelyEmptyList_hasNoFailureFlag() = runBlocking {
        val client = createMockClient("[]", shouldFailCandidates = false)
        val vm = makeVm(client)

        vm.prefetchNextCampaign()
        waitUntil(5000) { !vm.uiState.value.nextSuggestionLoading }

        val state = vm.uiState.value
        assertFalse(state.nextSuggestionLoading)
        assertNull(state.nextCampaignToTest)
        assertFalse("Genuinely empty list must NOT be marked as failed", state.nextSuggestionFailed)
    }

    @Test
    fun prefetch_allFullFallback_fullCampaignsRemainEligible() = runBlocking {
        val candidateJson = """[
            {
                "campaign": {"id": "c-full", "owner_id": "o1", "app_name": "Full App", "package_name": "pkg.full", "status": "open", "target_testers": 12},
                "installed_count": 20,
                "today_tested_count": 12,
                "stats_date": "2026-11-10"
            }
        ]"""

        val client = createMockClient(candidateJson)
        val vm = makeVm(client)

        vm.prefetchNextCampaign()
        waitUntil(5000) { !vm.uiState.value.nextSuggestionLoading && vm.uiState.value.nextCampaignToTest != null }

        val state = vm.uiState.value
        assertNotNull("Full app must still be suggested when no under-target apps exist", state.nextCampaignToTest)
        assertEquals("c-full", state.nextCampaignToTest?.id)
        assertFalse(state.nextSuggestionFailed)
    }

    @Test
    fun prefetch_staleResponseAfterSessionBump_isIgnored() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 10,
                "today_tested_count": 0,
                "stats_date": "2026-11-10"
            }
        ]"""

        val client = createMockClient(
            candidateJson = candidateJson,
            onCandidateRequest = {
                entered.complete(Unit)
                release.await()
            },
        )
        val vm = makeVm(client)

        vm.prefetchNextCampaign()
        try {
            waitUntil(5000) { entered.isCompleted }
            // Generation incremented while request in-flight
            SessionCacheManager.generation.incrementAndGet()
        } finally {
            release.complete(Unit)
        }

        waitUntil(5000) { !vm.uiState.value.nextSuggestionLoading }

        // Stale result should NOT overwrite UI state with candidate
        val state = vm.uiState.value
        assertNull("Stale generation response must not populate nextCampaignToTest", state.nextCampaignToTest)
        assertFalse("Stale generation response must reset nextSuggestionLoading to false", state.nextSuggestionLoading)
        assertTrue("Stale generation result must publish retryable failure", state.nextSuggestionFailed)
    }

    @Test
    fun staleSequence_doesNotWriteAnyUiState() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 10,
                "today_tested_count": 0,
                "stats_date": "2026-11-10"
            }
        ]"""

        val client = createMockClient(
            candidateJson = candidateJson,
            onCandidateRequest = {
                entered.complete(Unit)
                release.await()
            },
        )
        val vm = makeVm(client)

        vm.prefetchNextCampaign()
        try {
            waitUntil(5000) { entered.isCompleted }
            // User dismisses dialog: seq is bumped to 2, nextSuggestionLoading reset to false, nextSuggestionFailed reset to false
            vm.dismissNextAppPrompt()
            assertFalse(vm.uiState.value.nextSuggestionLoading)
            assertFalse(vm.uiState.value.showNextAppPrompt)
        } finally {
            release.complete(Unit)
        }

        // Old request from seq 1 completes. It must NOT write ANY state to uiState!
        testDispatcher.scheduler.runCurrent()
        delay(50)
        testDispatcher.scheduler.runCurrent()

        val state = vm.uiState.value
        assertNull("Old sequence must not populate candidate", state.nextCampaignToTest)
        assertFalse("Old sequence must not set prompt visible", state.showNextAppPrompt)
        assertFalse("Old sequence must not change loading", state.nextSuggestionLoading)
    }

    @Test
    fun mutationDuringCandidateFetch_doesNotStampEmptyListAndShowsFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 10,
                "today_tested_count": 0,
                "stats_date": "2026-11-10"
            }
        ]"""

        val client = createMockClient(
            candidateJson = candidateJson,
            onCandidateRequest = {
                entered.complete(Unit)
                release.await()
            },
        )
        login(client, "test-tester")
        val repo = CampaignRepository(client, Localizer(context), businessDayClock = { currentTestDay })
        val submitManager = ReportSubmitManager(context)
        val vm = CampaignDetailViewModel(
            campaignId = "curr-camp",
            repository = repo,
            reportSubmitManager = submitManager,
            myUserId = "test-tester",
            localizer = Localizer(context),
            appContext = context,
        )
        store.put("vm_mut", vm)
        testDispatcher.scheduler.runCurrent()

        vm.prefetchNextCampaign()
        try {
            waitUntil(5000) { entered.isCompleted }
            // Mutation happens while candidate RPC in flight:
            repo.invalidateCampaign("c1")
        } finally {
            release.complete(Unit)
        }

        waitUntil(5000) { !vm.uiState.value.nextSuggestionLoading }

        val state = vm.uiState.value
        assertNull("Discarded candidates must not be stored", state.nextCampaignToTest)
        assertTrue("Changed mutation during fetch must surface retryable failure, not empty all-done", state.nextSuggestionFailed)
    }

    @Test
    fun prefetch_dayRollover_invalidatesOldCandidatesAndReloads() = runBlocking {
        val candidateCalls = AtomicInteger()
        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 10,
                "today_tested_count": 0,
                "stats_date": "2026-11-10"
            }
        ]"""

        val client = createMockClient(candidateJson, onCandidateRequest = {
            candidateCalls.incrementAndGet()
        })
        val vm = makeVm(client)

        // Day 1: 2026-11-10
        vm.prefetchNextCampaign()
        waitUntil(5000) { vm.uiState.value.nextCampaignToTest != null }
        assertEquals(1, candidateCalls.get())

        // Rollover to next day: 2026-11-11
        currentTestDay = LocalDate.of(2026, 11, 11)

        // Consuming or prefetching on new day detects staleness and triggers reload
        vm.prefetchNextCampaign()
        waitUntil(5000) { candidateCalls.get() == 2 }
        assertEquals("Day change must trigger fresh candidate request", 2, candidateCalls.get())
    }

    @Test
    fun prefetch_pendingUploadExclusionRecheckedAtConsumptionTime() = runBlocking {
        // Two candidates available
        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 10,
                "today_tested_count": 0,
                "stats_date": "2026-11-10"
            },
            {
                "campaign": {"id": "c2", "owner_id": "o2", "app_name": "App 2", "package_name": "pkg.app2", "status": "open", "target_testers": 12},
                "installed_count": 10,
                "today_tested_count": 0,
                "stats_date": "2026-11-10"
            }
        ]"""

        val client = createMockClient(candidateJson)
        val vm = makeVm(client)

        // 1. Prefetch while opening form
        vm.prefetchNextCampaign()
        waitUntil(5000) { vm.uiState.value.nextCampaignToTest != null }

        // 2. Before submitting or consuming, a pending report for c1 is added locally
        PendingReportStore.write(
            context,
            PendingReport(
                id = "req-c1",
                campaignId = "c1",
                appName = "App 1",
                userId = "test-tester",
                rating = 5,
                comment = "comment",
                submissionContext = "app_test",
            )
        )

        // 3. Consume prefetched candidates
        vm.consumePrefetchedCandidates()
        waitUntil(5000) { vm.uiState.value.nextCampaignToTest?.id == "c2" }

        // c1 was excluded at consumption time; c2 must be chosen!
        assertEquals("c2", vm.uiState.value.nextCampaignToTest?.id)
    }

    @Test
    fun firstSubmissionSnapshot_preservedAcrossSubmitCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // Two candidates in Group 2 (under target, uninstalled)
        // c1: installed_count = 10
        // c2: installed_count = 2 (lower installed_count)
        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 10,
                "today_tested_count": 2,
                "stats_date": "2026-11-10"
            },
            {
                "campaign": {"id": "c2", "owner_id": "o2", "app_name": "App 2", "package_name": "pkg.app2", "status": "open", "target_testers": 12},
                "installed_count": 2,
                "today_tested_count": 2,
                "stats_date": "2026-11-10"
            }
        ]"""

        val client = createMockClient(
            candidateJson = candidateJson,
            onCandidateRequest = {
                entered.complete(Unit)
                release.await()
            },
        )
        val vm = makeVm(client)
        // Test seam: pick first item from shortlist deterministically (no probabilistic flakiness)
        vm.nextCampaignRandomPicker = { it.firstOrNull() }

        // 1. Prefetch starts while testedInThisViewModel is false.
        // It eagerly captures eagerFirstTimeTesterSnapshot = true.
        vm.prefetchNextCampaign()
        try {
            waitUntil(5000) { entered.isCompleted }
            // 2. Race condition simulation: report submission finishes while prefetch in flight!
            // Tested state becomes true in ViewModel session.
            vm.testedInThisViewModel = true
        } finally {
            release.complete(Unit)
        }

        waitUntil(5000) { vm.uiState.value.nextCampaignToTest != null }

        // 3. First-submission snapshot is preserved! Shortlist only contains c2 (lowest installedCount = 2)
        assertEquals("c2", vm.uiState.value.nextCampaignToTest?.id)

        // 4. Later selection behavior: user requests a subsequent suggestion.
        // Since testedInThisViewModel is now true, eager first-time preference is NOT applied.
        // Shortlist contains both [c1, c2]. With picker { it.firstOrNull() }, c1 is selected!
        vm.prefetchNextCampaign(forceRefresh = true)
        waitUntil(5000) { vm.uiState.value.nextCampaignToTest?.id == "c1" }
        assertEquals("c1", vm.uiState.value.nextCampaignToTest?.id)
    }

    @Test
    fun consumption_invalidatedDuringSuspension_publishesFailedState() = runBlocking {
        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 5,
                "today_tested_count": 0,
                "stats_date": "2026-11-10"
            }
        ]"""
        val client = createMockClient(candidateJson)
        val vm = makeVm(client)

        vm.prefetchNextCampaign()
        waitUntil(5000) { vm.uiState.value.nextCampaignToTest != null }
        assertEquals("c1", vm.uiState.value.nextCampaignToTest?.id)
        assertFalse(vm.uiState.value.nextSuggestionFailed)

        // Day changes while dialog is open / before consume
        currentTestDay = currentTestDay.plusDays(1)

        // User attempts to consume with now-invalid day
        vm.consumePrefetchedCandidates()
        waitUntil(5000) { !vm.uiState.value.nextSuggestionLoading }

        val state = vm.uiState.value
        assertNull("Stale result must be cleared", state.nextCampaignToTest)
        assertTrue("Invalidated state during consumption must publish failed state, not genuine-empty all-done", state.nextSuggestionFailed)
    }

    @Test
    fun validateAndConsumeNextCampaign_whenFresh_returnsCampaignId() = runBlocking {
        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 5,
                "today_tested_count": 0,
                "stats_date": "2026-11-10"
            }
        ]"""
        val client = createMockClient(candidateJson)
        val vm = makeVm(client)

        vm.prefetchNextCampaign()
        waitUntil(5000) { vm.uiState.value.nextCampaignToTest != null }

        val id = vm.validateAndConsumeNextCampaign()
        assertEquals("c1", id)
    }

    @Test
    fun validateAndConsumeNextCampaign_whenDayOrMutationChanged_clearsStaleChoiceAndReturnsNull() = runBlocking {
        val candidateJson = """[
            {
                "campaign": {"id": "c1", "owner_id": "o1", "app_name": "App 1", "package_name": "pkg.app1", "status": "open", "target_testers": 12},
                "installed_count": 5,
                "today_tested_count": 0,
                "stats_date": "2026-11-10"
            }
        ]"""
        val client = createMockClient(candidateJson)
        val vm = makeVm(client)

        vm.prefetchNextCampaign()
        waitUntil(5000) { vm.uiState.value.nextCampaignToTest != null }

        // Day changes while user deliberates with dialog open
        currentTestDay = currentTestDay.plusDays(1)

        val id = vm.validateAndConsumeNextCampaign()
        assertNull("Stale ID must not be returned for navigation", id)
        assertNull("Stale nextCampaignToTest must be cleared", vm.uiState.value.nextCampaignToTest)
        assertTrue("Loading should be triggered for refresh", vm.uiState.value.nextSuggestionLoading)
    }
}
