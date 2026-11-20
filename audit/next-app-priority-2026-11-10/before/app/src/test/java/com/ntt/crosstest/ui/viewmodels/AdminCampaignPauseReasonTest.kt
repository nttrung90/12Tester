package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AdminPauseReason
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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminCampaignPauseReasonTest {

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
        userId: String? = "admin-1",
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

    private fun pauseReasonSuccessJson(reasonCode: String, note: String): String =
        """{"success":true,"campaign_id":"camp-1","previous_status":"open","status":"paused","paused_reason":"admin_manual","admin_pause_reason_code":"$reasonCode","admin_note":"$note","changed":true,"notification_id":"notif-1"}"""

    private fun legacyPauseSuccessJson(note: String?): String =
        """{"success":true,"campaign_id":"camp-1","previous_status":"open","status":"paused","paused_reason":"admin_manual","app_name":"Test App","owner_id":"owner-1","admin_note":${if (note != null) "\"$note\"" else "null"}}"""

    @Test
    fun pauseWithMissingGoogleGroup_routesToNewRpc_withCorrectPayloadAndInvokesOnSuccess() = runBlocking {
        withTimeout(10_000) {
            val rpcCalled = AtomicBoolean(false)
            var capturedPayload: String? = null

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign_with_reason") -> {
                        rpcCalled.set(true)
                        capturedPayload = req.body.toByteArray().decodeToString()
                        reply(pauseReasonSuccessJson("missing_google_group", "Chưa thêm Google Group"))
                    }
                    path.contains("campaigns") -> reply("[]")
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-1")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-1")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                val successInvoked = AtomicBoolean(false)
                vm.adminPauseCampaignWithReason(
                    reasonCode = AdminPauseReason.MISSING_GOOGLE_GROUP.code,
                    customReason = null,
                    onSuccess = { successInvoked.set(true) }
                )

                val job = latestJob(vm)
                awaitJob(job)

                assertTrue("New RPC admin_pause_campaign_with_reason must be called", rpcCalled.get())
                assertNotNull("Payload must be captured", capturedPayload)

                val jsonPayload = json.parseToJsonElement(capturedPayload!!).jsonObject
                assertEquals("camp-1", jsonPayload["p_campaign_id"]?.jsonPrimitive?.content)
                assertEquals("missing_google_group", jsonPayload["p_reason_code"]?.jsonPrimitive?.content)

                assertTrue("onSuccess callback must be executed on success", successInvoked.get())
                assertFalse("isActionLoading must be false after completion", vm.uiState.value.isActionLoading)
                assertEquals(localizer.get(R.string.campaign_admin_pause_success), vm.uiState.value.message)
                assertNull(vm.uiState.value.error)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun pauseWithPolicyViolation_routesToNewRpc_withPolicyViolationPayload() = runBlocking {
        withTimeout(10_000) {
            val rpcCalled = AtomicBoolean(false)
            var capturedPayload: String? = null

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign_with_reason") -> {
                        rpcCalled.set(true)
                        capturedPayload = req.body.toByteArray().decodeToString()
                        reply(pauseReasonSuccessJson("policy_violation", "Vi phạm chính sách"))
                    }
                    path.contains("campaigns") -> reply("[]")
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-1")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-1")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                val successInvoked = AtomicBoolean(false)
                vm.adminPauseCampaignWithReason(
                    reasonCode = AdminPauseReason.POLICY_VIOLATION.code,
                    customReason = null,
                    onSuccess = { successInvoked.set(true) }
                )

                val job = latestJob(vm)
                awaitJob(job)

                assertTrue(rpcCalled.get())
                val jsonPayload = json.parseToJsonElement(capturedPayload!!).jsonObject
                assertEquals("policy_violation", jsonPayload["p_reason_code"]?.jsonPrimitive?.content)
                assertTrue(successInvoked.get())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun pauseWithCustomReason_routesToLegacyRpc_withCustomReasonText() = runBlocking {
        withTimeout(10_000) {
            val legacyRpcCalled = AtomicBoolean(false)
            var capturedPayload: String? = null

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign") && !path.contains("with_reason") -> {
                        legacyRpcCalled.set(true)
                        capturedPayload = req.body.toByteArray().decodeToString()
                        reply(legacyPauseSuccessJson("Custom violation note"))
                    }
                    path.contains("campaigns") -> reply("[]")
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-1")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-1")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                val successInvoked = AtomicBoolean(false)
                vm.adminPauseCampaignWithReason(
                    reasonCode = AdminPauseReason.CUSTOM.code,
                    customReason = "Custom violation note",
                    onSuccess = { successInvoked.set(true) }
                )

                val job = latestJob(vm)
                awaitJob(job)

                assertTrue("Custom option must route to legacy admin_pause_campaign", legacyRpcCalled.get())
                val jsonPayload = json.parseToJsonElement(capturedPayload!!).jsonObject
                assertEquals("Custom violation note", jsonPayload["p_reason"]?.jsonPrimitive?.content)
                assertTrue(successInvoked.get())
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun pauseFailure_retainsDraft_doesNotInvokeOnSuccess_andSetsError() = runBlocking {
        withTimeout(10_000) {
            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign_with_reason") ->
                        reply("""{"message":"Network connection failed"}""", HttpStatusCode.InternalServerError)
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-1")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-1")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                val successInvoked = AtomicBoolean(false)
                vm.adminPauseCampaignWithReason(
                    reasonCode = AdminPauseReason.MISSING_GOOGLE_GROUP.code,
                    customReason = null,
                    onSuccess = { successInvoked.set(true) }
                )

                val job = latestJob(vm)
                awaitJob(job)

                assertFalse("onSuccess must NOT be called on failure", successInvoked.get())
                assertFalse("isActionLoading must be released after failure", vm.uiState.value.isActionLoading)
                assertNotNull("Error message must be present for display", vm.uiState.value.error)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun concurrentDuplicatePause_rejectedByActionGate() = runBlocking {
        withTimeout(10_000) {
            val pauseStarted = CompletableDeferred<Unit>()
            val releasePause = CompletableDeferred<Unit>()
            val pauseCalls = AtomicInteger(0)

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign_with_reason") -> {
                        pauseCalls.incrementAndGet()
                        pauseStarted.complete(Unit)
                        releasePause.await()
                        reply(pauseReasonSuccessJson("missing_google_group", "Chưa thêm Google Group"))
                    }
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-1")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-1")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                // First call starts and pauses in flight
                vm.adminPauseCampaignWithReason(reasonCode = AdminPauseReason.MISSING_GOOGLE_GROUP.code)
                val job1 = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                pauseStarted.await()
                assertTrue(vm.uiState.value.isActionLoading)

                // Second call while first is in flight -> must be rejected by gate
                vm.adminPauseCampaignWithReason(reasonCode = AdminPauseReason.MISSING_GOOGLE_GROUP.code)
                testDispatcher.scheduler.runCurrent()

                // Only 1 call reached network
                assertEquals(1, pauseCalls.get())

                // Release first call
                releasePause.complete(Unit)
                awaitJob(job1)

                assertFalse(vm.uiState.value.isActionLoading)
                assertEquals(localizer.get(R.string.campaign_admin_pause_success), vm.uiState.value.message)
            } finally {
                releasePause.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun sessionGenerationChange_discardsStaleResponse_andDoesNotInvokeOnSuccess() = runBlocking {
        withTimeout(10_000) {
            val pauseStarted = CompletableDeferred<Unit>()
            val releasePause = CompletableDeferred<Unit>()

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("get_campaign_detail") -> reply(detailJson())
                    path.contains("admin_pause_campaign_with_reason") -> {
                        pauseStarted.complete(Unit)
                        releasePause.await()
                        reply(pauseReasonSuccessJson("missing_google_group", "Chưa thêm Google Group"))
                    }
                    else -> reply("[]")
                }
            }

            try {
                login(client, "admin-1")
                val repo = CampaignRepository(client, localizer)
                val reportSubmitManager = ReportSubmitManager(context)
                val vm = createVm(repo = repo, reportSubmitManager = reportSubmitManager, userId = "admin-1")
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

                val successInvoked = AtomicBoolean(false)
                vm.adminPauseCampaignWithReason(
                    reasonCode = AdminPauseReason.MISSING_GOOGLE_GROUP.code,
                    onSuccess = { successInvoked.set(true) }
                )
                val job1 = latestJob(vm)
                testDispatcher.scheduler.runCurrent()
                pauseStarted.await()

                // User session generation changes mid-flight (e.g. account switch or session refresh)
                SessionCacheManager.generation.incrementAndGet()

                // Release pause response
                releasePause.complete(Unit)
                awaitJob(job1)

                // Stale response must be discarded: onSuccess NOT called, success message NOT posted
                assertFalse("Stale response must NOT invoke onSuccess", successInvoked.get())
                assertNull("Stale response must NOT publish message", vm.uiState.value.message)
            } finally {
                releasePause.complete(Unit)
                client.close()
            }
        }
    }
}
