package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.FinancialIntentStore
import com.ntt.crosstest.data.Localizer
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppUpdateReleaseViewModelBehavioralTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var localizer: Localizer
    private val clientsToClose = mutableListOf<SupabaseClient>()
    private val viewModelsToCancel = mutableListOf<AppUpdateReleaseViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = RuntimeEnvironment.getApplication()
        localizer = Localizer(context)
    }

    @After
    fun tearDown() {
        for (vm in viewModelsToCancel) {
            try { vm.viewModelScope.cancel() } catch (_: Exception) {}
        }
        viewModelsToCancel.clear()

        runBlocking {
            for (c in clientsToClose) {
                try { c.close() } catch (_: Exception) {}
            }
        }
        clientsToClose.clear()
        Dispatchers.resetMain()
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

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun extractBodyText(request: HttpRequestData): String {
        return when (val body = request.body) {
            is io.ktor.http.content.TextContent -> body.text
            is io.ktor.http.content.ByteArrayContent -> String(body.bytes())
            else -> body.toString()
        }
    }

    private suspend fun createTestClient(
        userId: String = "user-owner-1",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): SupabaseClient {
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
        val json = Json { ignoreUnknownKeys = true }
        client.auth.awaitInitialization()
        client.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"token-$userId","refresh_token":"refresh-$userId","token_type":"bearer","expires_in":3600,"user":{"id":"$userId","aud":"authenticated"}}"""
            )
        )
        clientsToClose += client
        return client
    }

    private fun trackVm(vm: AppUpdateReleaseViewModel): AppUpdateReleaseViewModel {
        viewModelsToCancel += vm
        return vm
    }

    private val detailPageJson = """
        {
            "campaign": {
                "id": "camp-1",
                "owner_id": "user-owner-1",
                "app_name": "Test App",
                "package_name": "com.test.app",
                "status": "open",
                "slot_number": 2,
                "created_at": "2026-11-01T00:00:00Z"
            },
            "testers": [],
            "reports": [],
            "profiles": {},
            "my_profile": {
                "id": "user-owner-1",
                "credits": 1000
            },
            "today_tested_count": 0,
            "tester_reports_count": 0,
            "pending_reports_count": 0,
            "avg_rating": 5.0
        }
    """.trimIndent()

    private val detailPageWithActiveReleaseJson = """
        {
            "campaign": {
                "id": "camp-1",
                "owner_id": "user-owner-1",
                "app_name": "Test App",
                "package_name": "com.test.app",
                "status": "open",
                "slot_number": 2,
                "created_at": "2026-11-01T00:00:00Z"
            },
            "testers": [],
            "reports": [],
            "profiles": {},
            "my_profile": {
                "id": "user-owner-1",
                "credits": 1000
            },
            "app_update": {
                "id": "rel-active-old",
                "campaign_id": "camp-1",
                "version_name": "1.0.0",
                "target_version_code": 10,
                "status": "active",
                "remaining_credits": 100,
                "reward_per_claim": 6,
                "max_claims": 20,
                "reserved_credits": 120
            },
            "today_tested_count": 0,
            "tester_reports_count": 0,
            "pending_reports_count": 0,
            "avg_rating": 5.0
        }
    """.trimIndent()

    @Test
    fun lostResponse_persistsIntentToDisk_recreationReusesOriginalOperationIdAndQuote() = runTest {
        val context = RuntimeEnvironment.getApplication()
        FinancialIntentStore.clearAllPublishIntentsForUser(context, "user-owner-1")

        var shouldThrowNetwork = true
        var capturedPublishBody: String? = null
        var publishCallCount = 0

        val client = createTestClient("user-owner-1") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_campaign_detail") -> reply(detailPageJson)
                path.contains("get_campaign_app_update_history") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-owner-1","credits":1000}]""")
                path.contains("get_business_quote") -> {
                    reply("""{"quote_type":"app_update","reward_per_claim":8,"max_claims":25,"reserved_budget":200,"revision":"quote-rev-token-200"}""")
                }
                path.contains("publish_app_update_v2") -> {
                    publishCallCount++
                    capturedPublishBody = extractBodyText(request)
                    if (shouldThrowNetwork) {
                        throw IOException("Simulated network lost response")
                    } else {
                        reply("""{
                            "success": true,
                            "owner_credits": 800,
                            "refund_amount": 0,
                            "idempotent_replay": true,
                            "release": {
                                "id": "rel-created-1",
                                "campaign_id": "camp-1",
                                "version_name": "2.0.0",
                                "target_version_code": 20,
                                "release_notes": "Bug fixes",
                                "reward_per_claim": 8,
                                "max_claims": 25,
                                "reserved_credits": 200,
                                "paid_count": 0,
                                "remaining_credits": 200,
                                "status": "active"
                            }
                        }""")
                    }
                }
                else -> reply("{}")
            }
        }
        val repo = CampaignRepository(client, localizer)

        val vm1 = trackVm(AppUpdateReleaseViewModel("camp-1", repo, localizer))
        waitUntil { !vm1.uiState.value.isLoading && !vm1.uiState.value.isQuoteLoading }

        vm1.onVersionNameChange("2.0.0")
        vm1.onVersionCodeChange("20")
        vm1.onReleaseNotesChange("Bug fixes")

        val state = vm1.uiState.value
        assertEquals("quote-rev-token-200", state.policyRevision)
        assertFalse("isLoading must be false", state.isLoading)
        assertFalse("isQuoteLoading must be false", state.isQuoteLoading)
        assertNull("quoteError must be null", state.quoteError)
        assertTrue("hasValidQuote must be true", state.hasValidQuote)
        assertTrue("hasEnoughCredits must be true", state.hasEnoughCredits)
        assertTrue("canPublish must be true", state.canPublish)

        // 1. First publish attempts and fails with network loss
        vm1.publishUpdate()
        waitUntil { vm1.uiState.value.error != null }

        assertNotNull(vm1.uiState.value.error)
        val diskIntent = FinancialIntentStore.peekPublishIntent(context, "user-owner-1", "camp-1")
        assertNotNull(diskIntent)
        assertEquals("2.0.0", diskIntent!!.versionName)
        assertEquals(20L, diskIntent.targetVersionCode)
        assertEquals(200, diskIntent.expectedBudget)
        assertEquals("quote-rev-token-200", diskIntent.expectedRevision)
        assertNull(diskIntent.expectedCurrentReleaseId)
        val originalOperationId = diskIntent.clientOperationId
        assertNotNull(originalOperationId)

        // Verify wire JSON sent explicit JsonNull for CAS when no current active release
        assertNotNull(capturedPublishBody)
        val firstSentJson = Json.parseToJsonElement(capturedPublishBody!!).jsonObject
        assertTrue(firstSentJson.containsKey("p_expected_current_release_id"))
        assertTrue(firstSentJson["p_expected_current_release_id"] is JsonNull)

        // 2. Simulate ViewModel recreation / process restart
        val vm2 = trackVm(AppUpdateReleaseViewModel("camp-1", repo, localizer))
        waitUntil { !vm2.uiState.value.isLoading && !vm2.uiState.value.isQuoteLoading }

        val restored = vm2.uiState.value.pendingPublishIntent
        assertNotNull(restored)
        assertEquals(originalOperationId, restored!!.clientOperationId)

        // 3. Retry sends the EXACT original operation ID and original quote token
        shouldThrowNetwork = false
        vm2.retryPendingPublish()
        waitUntil { vm2.uiState.value.successMessage != null }

        val secondSentJson = Json.parseToJsonElement(capturedPublishBody!!).jsonObject
        assertEquals(originalOperationId, secondSentJson["p_client_operation_id"]?.jsonPrimitive?.content)
        assertEquals("quote-rev-token-200", secondSentJson["p_expected_revision"]?.jsonPrimitive?.content)
        assertEquals("200", secondSentJson["p_expected_budget"]?.jsonPrimitive?.content)

        // 4. Upon success, intent is cleared from disk
        assertNull(FinancialIntentStore.peekPublishIntent(context, "user-owner-1", "camp-1"))
        assertNull(vm2.uiState.value.pendingPublishIntent)
    }

    @Test
    fun atomicPublishVsCloseGate_preventsConcurrentExecution() = runTest {
        var publishCallCount = 0
        var closeCallCount = 0
        val publishStarted = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()

        val client = createTestClient("user-owner-1") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_campaign_detail") -> reply(detailPageWithActiveReleaseJson)
                path.contains("get_campaign_app_update_history") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-owner-1","credits":1000}]""")
                path.contains("get_business_quote") -> {
                    reply("""{"quote_type":"app_update","reward_per_claim":8,"max_claims":25,"reserved_budget":200,"revision":"quote-rev-token-200"}""")
                }
                path.contains("publish_app_update_v2") -> {
                    publishCallCount++
                    publishStarted.complete(Unit)
                    releasePublish.await()
                    reply("""{"success":true,"owner_credits":800,"release":{"id":"rel-1","campaign_id":"camp-1","version_name":"2.1.0","target_version_code":21,"release_notes":"Notes","reward_per_claim":8,"max_claims":25,"reserved_credits":200,"status":"active"}}""")
                }
                path.contains("close_app_update_release") -> {
                    closeCallCount++
                    reply("""{"success":true,"refund_amount":100}""")
                }
                else -> reply("{}")
            }
        }
        val repo = CampaignRepository(client, localizer)
        val vm = trackVm(AppUpdateReleaseViewModel("camp-1", repo, localizer))
        waitUntil { !vm.uiState.value.isLoading && !vm.uiState.value.isQuoteLoading && vm.uiState.value.currentRelease != null }

        vm.onVersionNameChange("2.1.0")
        vm.onVersionCodeChange("21")
        vm.onReleaseNotesChange("Notes")

        // 1. First action starts publish and enters background RPC
        vm.publishUpdate()
        waitUntil { publishStarted.isCompleted }

        // 2. While publish is held in flight, attempt concurrent close -> must be dropped by atomic gate
        vm.closeCurrentRelease()

        // 3. Release publish to complete
        releasePublish.complete(Unit)
        waitUntil { !vm.uiState.value.isPublishing }

        assertEquals(1, publishCallCount)
        assertEquals(0, closeCallCount)
    }

    @Test
    fun changedQuoteConflict_clearsDiskIntentAndPromptsReconfirm() = runTest {
        val context = RuntimeEnvironment.getApplication()
        FinancialIntentStore.clearAllPublishIntentsForUser(context, "user-owner-1")

        val client = createTestClient("user-owner-1") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_campaign_detail") -> reply(detailPageJson)
                path.contains("get_campaign_app_update_history") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-owner-1","credits":1000}]""")
                path.contains("get_business_quote") -> {
                    reply("""{"quote_type":"app_update","reward_per_claim":8,"max_claims":25,"reserved_budget":200,"revision":"quote-rev-token-200"}""")
                }
                path.contains("publish_app_update_v2") -> {
                    respond(
                        content = """{"code":"BUSINESS_POLICY_CONFLICT","message":"BUSINESS_POLICY_CONFLICT: Revision mismatch"}""",
                        status = HttpStatusCode.Conflict,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> reply("{}")
            }
        }
        val repo = CampaignRepository(client, localizer)
        val vm = trackVm(AppUpdateReleaseViewModel("camp-1", repo, localizer))
        waitUntil { !vm.uiState.value.isLoading && !vm.uiState.value.isQuoteLoading }

        vm.onVersionNameChange("2.2.0")
        vm.onVersionCodeChange("22")
        vm.onReleaseNotesChange("Notes")

        vm.publishUpdate()
        waitUntil { vm.uiState.value.error != null }

        // On conflict: disk intent must be cleared, not retrying with stale quote
        assertNull(FinancialIntentStore.peekPublishIntent(context, "user-owner-1", "camp-1"))
        assertNotNull(vm.uiState.value.error)
    }
}
