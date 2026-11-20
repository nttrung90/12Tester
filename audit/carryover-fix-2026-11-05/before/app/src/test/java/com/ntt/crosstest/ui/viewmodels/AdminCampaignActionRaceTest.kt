package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.R
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.ReportSubmitManager
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
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminCampaignActionRaceTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val localizer by lazy { Localizer(context) }
    private val json = Json { ignoreUnknownKeys = true }
    private val testDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        SessionCacheManager.clear(context)
    }

    @After
    fun cleanup() {
        store.clear()
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun createClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://fixture.invalid", "dummy-key") {
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

    private suspend fun login(c: SupabaseClient, uid: String) {
        c.auth.awaitInitialization()
        c.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"fixture-$uid","refresh_token":"fixture-ref-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )
    }

    private fun createVm(
        campaignId: String = "camp-1",
        repo: CampaignRepository,
        reportSubmitManager: ReportSubmitManager,
        userId: String? = "admin-A",
    ): CampaignDetailViewModel {
        val vm = CampaignDetailViewModel(campaignId, repo, reportSubmitManager, userId, localizer, context)
        store.put(campaignId + "_" + (userId ?: "null") + "_" + System.nanoTime(), vm)
        return vm
    }

    private fun latestJob(vm: CampaignDetailViewModel): Job? =
        vm.viewModelScope.coroutineContext[Job]?.children?.lastOrNull()

    private suspend fun awaitJob(job: Job?, timeoutMs: Long = 5000) {
        if (job == null) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!job.isCompleted) {
            testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceUntilIdle()
            if (job.isCompleted) break
            if (System.currentTimeMillis() > deadline) {
                fail("Job timed out after ${timeoutMs}ms")
            }
            withContext(Dispatchers.IO) {
                Thread.sleep(5)
            }
        }
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 5000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceUntilIdle()
            if (condition()) break
            if (System.currentTimeMillis() > deadline) {
                fail("Condition timed out after ${timeoutMs}ms")
            }
            withContext(Dispatchers.IO) {
                Thread.sleep(5)
            }
        }
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun detailJson(status: String = "open", ownerId: String = "owner-1"): String = """{
        "campaign":{"id":"camp-1","owner_id":"$ownerId","app_name":"Test App","package_name":"com.test.app","status":"$status"},
        "is_member":true,
        "is_actual_owner":false,
        "can_manage":true,
        "my_profile":{"id":"admin-1","role":"admin","credits":100},
        "reports":[],
        "testers":[],
        "feedbacks":[],
        "checked_in_confirmed_today":false,
        "today_tested_count":0,
        "reports_count":0,
        "avg_rating":5.0,
        "my_checkin_dates":[],
        "unread_warnings":[],
        "weekly_warnings_count":0
    }"""

    private fun deleteSuccessJson(): String =
        """{"success":true,"campaign_id":"camp-1","app_name":"Test App","owner_id":"owner-1","reason":"Admin removal"}"""

    private fun pauseSuccessJson(): String =
        """{"success":true,"campaign_id":"camp-1","previous_status":"open","status":"paused","paused_reason":"Policy violation","app_name":"Test App","owner_id":"owner-1"}"""

    private fun resumeSuccessJson(status: String = "open"): String =
        """{"success":true,"campaign_id":"camp-1","previous_status":"paused","status":"$status","app_name":"Test App","owner_id":"owner-1","admin_resumed":true}"""

    @Test
    fun constructor_withUnconfinedTestDispatcherAndAuthenticatedClient_doesNotCrash() = runBlocking {
        withTimeout(10_000) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val client = createClient { reply(detailJson()) }
            try {
                login(client, "admin-1")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                // Under UnconfinedTestDispatcher, init immediately launches coroutines and collects sessionStatus
                // Backing fields (adminActionLock) must be initialized before init runs.
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-1")
                assertNotNull(vm.uiState.value)
            } finally {
                client.close()
                Dispatchers.setMain(testDispatcher)
            }
        }
    }

    @Test
    fun delayedAdminDeleteResponse_afterAccountChange_neverExecutesOnDeletedOrPublishesState() = runBlocking {
        withTimeout(10_000) {
            val deleteStarted = CompletableDeferred<Unit>()
            val releaseDelete = CompletableDeferred<Unit>()
            var onDeletedCalled = false

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_delete_campaign") -> {
                        deleteStarted.complete(Unit)
                        releaseDelete.await()
                        reply(deleteSuccessJson())
                    }
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                // Admin clicks delete
                vm.adminDeleteCampaign(reason = "violation") {
                    onDeletedCalled = true
                }
                val deleteJob = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                deleteStarted.await()

                // Account changes while delete RPC is in flight!
                login(client, "admin-B")

                // Release the delayed delete response
                releaseDelete.complete(Unit)
                awaitJob(deleteJob)

                // Verification: onDeleted must NEVER execute after session change!
                assertFalse("onDeleted must NEVER execute after session change", onDeletedCalled)
                assertNull("Message must not publish to state after session change", vm.uiState.value.message)
                assertFalse("isActionLoading must be reset", vm.uiState.value.isActionLoading)
            } finally {
                releaseDelete.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun delayedA_switchB_startB_finishA_whileBPending_preservesLoading_noACallbacks_thenBCompletesNormally() = runBlocking {
        withTimeout(10_000) {
            val deleteAStarted = CompletableDeferred<Unit>()
            val releaseDeleteA = CompletableDeferred<Unit>()
            val pauseBStarted = CompletableDeferred<Unit>()
            val releasePauseB = CompletableDeferred<Unit>()
            var aDeletedCalled = false

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_delete_campaign") -> {
                        deleteAStarted.complete(Unit)
                        releaseDeleteA.await()
                        reply(deleteSuccessJson())
                    }
                    path.contains("admin_pause_campaign") -> {
                        pauseBStarted.complete(Unit)
                        releasePauseB.await()
                        reply(pauseSuccessJson())
                    }
                    else -> reply("[]")
                }
            }

            try {
                // 1. User A starts delete
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                vm.adminDeleteCampaign(reason = "A delete") {
                    aDeletedCalled = true
                }
                val jobA = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                deleteAStarted.await()
                assertTrue("A action is loading", vm.uiState.value.isActionLoading)

                // 2. Switch to User B while A is in flight
                login(client, "admin-B")

                // 3. User B starts pause while A is STILL in flight
                vm.adminPauseCampaign(reason = "B pause")
                val jobB = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                pauseBStarted.await()
                assertTrue("B action is loading", vm.uiState.value.isActionLoading)

                // 4. Release A while B is STILL pending
                releaseDeleteA.complete(Unit)
                awaitJob(jobA)

                // Verification while B is pending:
                assertFalse("A's delete callback must NEVER execute after session change", aDeletedCalled)
                assertTrue("B is still pending, so isActionLoading MUST REMAIN TRUE", vm.uiState.value.isActionLoading)
                assertNull("A's message must not be published", vm.uiState.value.message)

                // 5. Now release B
                releasePauseB.complete(Unit)
                awaitJob(jobB)

                // B completes normally
                assertFalse("isActionLoading must be false after B finishes", vm.uiState.value.isActionLoading)
                assertEquals(localizer.get(R.string.campaign_admin_pause_success), vm.uiState.value.message)
                assertFalse("A's callback was never called throughout", aDeletedCalled)
            } finally {
                releaseDeleteA.complete(Unit)
                releasePauseB.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun sameUidGenerationChange_allowsNewAdminActionBeforeOldCompletes_andOldFinallyDoesNotClearNewLoading() = runBlocking {
        withTimeout(10_000) {
            val pause1Started = CompletableDeferred<Unit>()
            val releasePause1 = CompletableDeferred<Unit>()
            val resume2Started = CompletableDeferred<Unit>()
            val releaseResume2 = CompletableDeferred<Unit>()

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign") -> {
                        pause1Started.complete(Unit)
                        releasePause1.await()
                        reply(pauseSuccessJson())
                    }
                    path.contains("admin_resume_campaign") -> {
                        resume2Started.complete(Unit)
                        releaseResume2.await()
                        reply(resumeSuccessJson())
                    }
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                // 1. Start action 1 (pause) under generation 1
                vm.adminPauseCampaign(reason = "pause 1")
                val job1 = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                pause1Started.await()
                assertTrue(vm.uiState.value.isActionLoading)

                // 2. Generation changes for same UID
                SessionCacheManager.generation.incrementAndGet()

                // 3. Start action 2 (resume) under generation 2 while action 1 is still in flight!
                vm.adminResumeCampaign()
                val job2 = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                resume2Started.await()
                assertTrue("Action 2 is admitted and loading", vm.uiState.value.isActionLoading)

                // 4. Release action 1 while action 2 is still pending
                releasePause1.complete(Unit)
                awaitJob(job1)

                // Old action 1 finally must not clear action 2's loading
                assertTrue("Action 2 is still pending, so isActionLoading must stay true", vm.uiState.value.isActionLoading)
                assertNull("Action 1 message must not be published", vm.uiState.value.message)

                // 5. Release action 2
                releaseResume2.complete(Unit)
                awaitJob(job2)

                assertFalse("isActionLoading must be false after action 2 completes", vm.uiState.value.isActionLoading)
                assertEquals(localizer.get(R.string.campaign_admin_resume_success), vm.uiState.value.message)
            } finally {
                releasePause1.complete(Unit)
                releaseResume2.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun pendingBAction_refreshDoesNotReleaseBGate_duplicateBActionRejected() = runBlocking {
        withTimeout(10_000) {
            val pauseStarted = CompletableDeferred<Unit>()
            val releasePause = CompletableDeferred<Unit>()
            val pauseCalls = AtomicInteger(0)

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign") -> {
                        pauseCalls.incrementAndGet()
                        pauseStarted.complete(Unit)
                        releasePause.await()
                        reply(pauseSuccessJson())
                    }
                    else -> reply("[]")
                }
            }

            try {
                // 1. VM constructed originally for User A
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                // 2. Auth changes to User B
                login(client, "admin-B")

                // 3. User B starts an admin action (pause)
                vm.adminPauseCampaign(reason = "violation")
                val pauseJob = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                pauseStarted.await()
                assertTrue("Action B must be loading", vm.uiState.value.isActionLoading)
                assertEquals(1, pauseCalls.get())

                // 4. A refresh occurs while B action is still pending
                vm.refresh(forceRefresh = false)
                testDispatcher.scheduler.runCurrent()

                // 5. User B attempts a duplicate admin action while action is still pending
                vm.adminPauseCampaign(reason = "duplicate B attempt")
                testDispatcher.scheduler.runCurrent()

                // B gate must NOT have been released by refresh(), duplicate must be rejected!
                assertEquals("Duplicate B action must be rejected, exactly 1 RPC called", 1, pauseCalls.get())
                assertTrue("Action B must remain loading", vm.uiState.value.isActionLoading)

                // 6. Complete the pending RPC
                releasePause.complete(Unit)
                awaitJob(pauseJob)

                assertFalse("isActionLoading becomes false after B finishes", vm.uiState.value.isActionLoading)
                assertEquals(localizer.get(R.string.campaign_admin_pause_success), vm.uiState.value.message)
                assertEquals(1, pauseCalls.get())
            } finally {
                releasePause.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun delayedAdminPauseResponse_afterSameUidGenerationChange_doesNotPublishOrRefresh() = runBlocking {
        withTimeout(10_000) {
            val pauseStarted = CompletableDeferred<Unit>()
            val releasePause = CompletableDeferred<Unit>()

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign") -> {
                        pauseStarted.complete(Unit)
                        releasePause.await()
                        reply(pauseSuccessJson())
                    }
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                vm.adminPauseCampaign(reason = "spam")
                val pauseJob = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                pauseStarted.await()

                // Same UID, but SessionCacheManager generation changes while RPC is in flight
                SessionCacheManager.generation.incrementAndGet()

                releasePause.complete(Unit)
                awaitJob(pauseJob)

                assertNull(
                    "Pause success message must not be published when generation changed",
                    vm.uiState.value.message
                )
                assertFalse(vm.uiState.value.isActionLoading)
            } finally {
                releasePause.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun duplicateSubmissions_withQueuedStandardTestDispatcher_admitsOnlyFirstCall() = runBlocking {
        withTimeout(10_000) {
            val deleteRpcCalls = AtomicInteger(0)
            val pauseRpcCalls = AtomicInteger(0)
            var deleteCallbackCount = 0

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_delete_campaign") -> {
                        deleteRpcCalls.incrementAndGet()
                        reply(deleteSuccessJson())
                    }
                    path.contains("admin_pause_campaign") -> {
                        pauseRpcCalls.incrementAndGet()
                        reply(pauseSuccessJson())
                    }
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                // Two delete clicks queued synchronously before dispatcher runs
                vm.adminDeleteCampaign(reason = "duplicate test") { deleteCallbackCount++ }
                vm.adminDeleteCampaign(reason = "duplicate test 2") { deleteCallbackCount++ }

                val deleteJob = latestJob(vm)
                awaitJob(deleteJob)

                assertEquals("Duplicate delete call must be rejected synchronously", 1, deleteRpcCalls.get())
                assertEquals("onDeleted callback must execute exactly once", 1, deleteCallbackCount)

                // Two pause clicks queued synchronously before dispatcher runs
                vm.adminPauseCampaign(reason = "test pause 1")
                vm.adminPauseCampaign(reason = "test pause 2")

                val pauseJob = latestJob(vm)
                awaitJob(pauseJob)

                assertEquals("Duplicate pause call must be rejected synchronously", 1, pauseRpcCalls.get())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun synchronousAdmission_setsIsActionLoadingImmediately() = runBlocking {
        withTimeout(10_000) {
            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign") -> reply(pauseSuccessJson())
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                assertFalse(vm.uiState.value.isActionLoading)

                // Click admin pause: before advancing test scheduler at all, isActionLoading MUST be true
                vm.adminPauseCampaign(reason = "instant check")
                assertTrue(
                    "isActionLoading must be set synchronously on admission, not inside coroutine launch",
                    vm.uiState.value.isActionLoading
                )

                val pauseJob = latestJob(vm)
                awaitJob(pauseJob)
                assertFalse(vm.uiState.value.isActionLoading)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun cancellationHandling_andOwnedFlagCleanup() = runBlocking {
        withTimeout(10_000) {
            val resumeStarted = CompletableDeferred<Unit>()
            val releaseResume = CompletableDeferred<Unit>()
            val resumeRpcCalls = AtomicInteger(0)

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_resume_campaign") -> {
                        resumeRpcCalls.incrementAndGet()
                        resumeStarted.complete(Unit)
                        releaseResume.await()
                        reply(resumeSuccessJson())
                    }
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                vm.adminResumeCampaign()
                val resumeJob = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                resumeStarted.await()

                // Trigger onCleared to cancel in-flight jobs and clean up owned admission flag
                vm.javaClass.getDeclaredMethod("onCleared").apply {
                    isAccessible = true
                    invoke(vm)
                }

                // Release suspended RPC
                releaseResume.complete(Unit)
                awaitJob(resumeJob)

                // Verify owned flag is cleaned up by calling an admin action again
                vm.adminResumeCampaign()
                val secondJob = latestJob(vm)
                awaitJob(secondJob)

                assertEquals("A second call must be admitted after cancellation cleanup", 2, resumeRpcCalls.get())
            } finally {
                releaseResume.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun inFlightRefresh_doesNotOverrideNewMutationState() = runBlocking {
        withTimeout(10_000) {
            val refreshStarted = CompletableDeferred<Unit>()
            val releaseOldRefresh = CompletableDeferred<Unit>()
            var refreshCount = 0

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> {
                        refreshCount++
                        if (refreshCount == 2) {
                            // Stale refresh: delayed with status "open"
                            refreshStarted.complete(Unit)
                            releaseOldRefresh.await()
                            reply(detailJson(status = "open"))
                        } else if (refreshCount >= 3) {
                            // Post-mutation refresh: status "paused"
                            reply(detailJson(status = "paused"))
                        } else {
                            reply(detailJson(status = "open"))
                        }
                    }
                    path.contains("admin_pause_campaign") -> {
                        reply(pauseSuccessJson())
                    }
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign?.status == "open" }

                assertEquals("open", vm.uiState.value.campaign?.status)

                // Start a forced refresh in background that will be delayed
                vm.refresh(forceRefresh = true)
                testDispatcher.scheduler.runCurrent()
                refreshStarted.await()

                // While old refresh is in flight, admin pauses the campaign
                vm.adminPauseCampaign(reason = "pause app")
                val pauseJob = latestJob(vm)
                awaitJob(pauseJob)
                waitUntil { vm.uiState.value.campaign?.status == "paused" }

                assertEquals("paused", vm.uiState.value.campaign?.status)
                assertEquals(localizer.get(R.string.campaign_admin_pause_success), vm.uiState.value.message)

                // Now release the delayed stale refresh (which has status = "open")
                releaseOldRefresh.complete(Unit)
                waitUntil { !vm.uiState.value.isBackgroundSyncing }

                // State MUST NOT be overridden by the older refresh!
                assertEquals(
                    "Stale refresh must not overwrite mutation state with old 'open' status",
                    "paused",
                    vm.uiState.value.campaign?.status
                )
            } finally {
                releaseOldRefresh.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun validAdminActionSuccess_andDeleteCallbackExactlyOnce() = runBlocking {
        withTimeout(10_000) {
            var onDeletedCount = 0

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson(status = "open"))
                    path.contains("admin_delete_campaign") -> reply(deleteSuccessJson())
                    path.contains("admin_pause_campaign") -> reply(pauseSuccessJson())
                    path.contains("admin_resume_campaign") -> reply(resumeSuccessJson())
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-A")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-A")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                // Pause
                vm.adminPauseCampaign(reason = "routine check")
                awaitJob(latestJob(vm))
                waitUntil { vm.uiState.value.message == localizer.get(R.string.campaign_admin_pause_success) }
                assertEquals(localizer.get(R.string.campaign_admin_pause_success), vm.uiState.value.message)
                assertFalse(vm.uiState.value.isActionLoading)

                // Resume
                vm.adminResumeCampaign()
                awaitJob(latestJob(vm))
                waitUntil { vm.uiState.value.message == localizer.get(R.string.campaign_admin_resume_success) }
                assertEquals(localizer.get(R.string.campaign_admin_resume_success), vm.uiState.value.message)
                assertFalse(vm.uiState.value.isActionLoading)

                // Delete
                vm.adminDeleteCampaign(reason = "routine deletion") {
                    onDeletedCount++
                }
                awaitJob(latestJob(vm))
                waitUntil { onDeletedCount == 1 }
                assertEquals(1, onDeletedCount)
                assertEquals(localizer.get(R.string.campaign_remove_success_msg), vm.uiState.value.message)
                assertFalse(vm.uiState.value.isActionLoading)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun preNetworkGuard_unauthenticatedAdmin_rejectsImmediately() = runBlocking {
        withTimeout(10_000) {
            var deleteCalls = 0
            var rpcAttempted = false

            val client = createClient { req ->
                val path = req.url.encodedPath
                if (path.contains("admin_delete_campaign")) {
                    rpcAttempted = true
                }
                reply(detailJson())
            }

            try {
                // Unauthenticated: no session
                client.auth.awaitInitialization()
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = null)
                // Anonymous read-only detail loading is allowed. Settle that work
                // before checking the separate synchronous admin-action rejection.
                testDispatcher.scheduler.runCurrent()
                waitUntil { !vm.uiState.value.isLoading && !vm.uiState.value.isBackgroundSyncing }

                vm.adminDeleteCampaign(reason = "unauth") {
                    deleteCalls++
                }
                testDispatcher.scheduler.advanceUntilIdle()

                assertEquals("Delete callback must not execute when unauthenticated", 0, deleteCalls)
                assertFalse("Network RPC must not be called", rpcAttempted)
                assertFalse(vm.uiState.value.isActionLoading)
                assertEquals(localizer.get(R.string.error_permission_denied), vm.uiState.value.error)
            } finally {
                client.close()
            }
        }
    }
}
