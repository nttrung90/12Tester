package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.CampaignPushStore
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.FinancialIntentStore
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.PendingSlotIntent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SlotAndPushDurabilityBehavioralTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var localizer: Localizer
    private val clientsToClose = mutableListOf<SupabaseClient>()
    private val viewModelsToCancel = mutableListOf<ViewModel>()

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

    private fun <T : ViewModel> trackVm(vm: T): T {
        viewModelsToCancel += vm
        return vm
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
        userId: String = "user-slot-owner",
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

    private val detailPageJson = """
        {
            "campaign": {
                "id": "camp-1",
                "owner_id": "user-slot-owner",
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
                "id": "user-slot-owner",
                "credits": 1000
            },
            "today_tested_count": 0,
            "tester_reports_count": 0,
            "pending_reports_count": 0,
            "avg_rating": 5.0
        }
    """.trimIndent()

    private val allQuoteJson = """
        {
            "quote_type": "all",
            "revision": "all-quote-token-999",
            "push_fee": 100,
            "slot_fees": {
                "slot_2": 50,
                "slot_3": 100,
                "slot_4_plus": 200,
                "renewal_days": 30
            },
            "app_update_rewards": {
                "reward_per_claim": 6,
                "max_claims": 20,
                "reserved_budget": 120
            },
            "report_rewards": {
                "provisional_reward": 10,
                "review_tester_bonus": 2,
                "review_owner_bonus": 2
            }
        }
    """.trimIndent()

    @Test
    fun renewCurrentSlot_lostReply_retryReusesSameUUIDAndOriginalQuote() = runTest {
        val context = RuntimeEnvironment.getApplication()
        FinancialIntentStore.clearAllSlotIntentsForUser(context, "user-slot-owner")

        var shouldThrowNetwork = true
        var capturedSlotRequestId: String? = null
        var unlockCallCount = 0

        val client = createTestClient("user-slot-owner") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_campaign_detail") -> reply(detailPageJson)
                path.contains("get_business_quote") -> reply(allQuoteJson)
                path.contains("user_slots") -> reply("""[{"user_id":"user-slot-owner","slot_number":2,"expires_at":"2026-12-01T00:00:00Z"}]""")
                path.contains("campaigns") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-slot-owner","credits":1000}]""")
                path.contains("unlock_or_renew_slot_v2") -> {
                    unlockCallCount++
                    val body = extractBodyText(request)
                    val json = Json.parseToJsonElement(body).jsonObject
                    capturedSlotRequestId = json["p_client_request_id"]?.jsonPrimitive?.content
                    if (shouldThrowNetwork) {
                        throw IOException("Simulated network loss on slot renewal")
                    } else {
                        reply("""{
                            "success": true,
                            "slot_number": 2,
                            "cost": 50,
                            "balance": 950,
                            "expires_at": "2026-12-31T00:00:00Z",
                            "replayed": true,
                            "renewal_days": 30
                        }""")
                    }
                }
                path.contains("get_campaign_mission") -> reply("""{"mission":null}""")
                else -> reply("[]")
            }
        }
        val repo = CampaignRepository(client, localizer)

        val vm = trackVm(OwnerAppDetailViewModel("camp-1", repo, localizer))
        waitUntil { vm.uiState.value.campaign != null && !vm.uiState.value.isBackgroundSyncing }

        // 1. First renew attempt fails with network loss
        vm.renewCurrentSlot()
        waitUntil { vm.uiState.value.error != null }

        assertNotNull(vm.uiState.value.error)
        val savedIntent = FinancialIntentStore.peekSlotIntent(context, "user-slot-owner", 2)
        assertNotNull(savedIntent)
        assertEquals(50, savedIntent!!.expectedCost)
        val originalRequestId = savedIntent.clientRequestId
        assertNotNull(originalRequestId)
        assertEquals(originalRequestId, capturedSlotRequestId)

        // 2. Retry renew reuses the EXACT stored clientRequestId from FinancialIntentStore!
        shouldThrowNetwork = false
        vm.renewCurrentSlot()
        waitUntil { vm.uiState.value.message != null }

        assertEquals(2, unlockCallCount)
        assertEquals(originalRequestId, capturedSlotRequestId)

        // 3. Upon success, intent is cleared from disk
        assertNull(FinancialIntentStore.peekSlotIntent(context, "user-slot-owner", 2))
    }

    @Test
    fun accountSwitch_slotIntentIsolation() {
        val context = RuntimeEnvironment.getApplication()
        FinancialIntentStore.clearAllSlotIntentsForUser(context, "user-A")
        FinancialIntentStore.clearAllSlotIntentsForUser(context, "user-B")

        val intentA = PendingSlotIntent(
            userId = "user-A",
            slotNumber = 2,
            expectedCost = 50,
            expectedRevision = "rev-A",
            renewalDays = 30,
            clientRequestId = "req-A-1",
        )
        FinancialIntentStore.saveSlotIntent(context, intentA)

        // User A sees its own intent
        val retrievedA = FinancialIntentStore.peekSlotIntent(context, "user-A", 2)
        assertNotNull(retrievedA)
        assertEquals("req-A-1", retrievedA!!.clientRequestId)

        // User B sees NOTHING of User A's intent!
        val retrievedB = FinancialIntentStore.peekSlotIntent(context, "user-B", 2)
        assertNull(retrievedB)
    }

    @Test
    fun pushApp_lostReply_preservesCampaignPushStoreRequestId() = runTest {
        val context = RuntimeEnvironment.getApplication()
        CampaignPushStore.clearPendingRequestId(context, "user-slot-owner", "camp-1")

        var shouldThrowNetwork = true
        var capturedPushRequestId: String? = null
        var pushCallCount = 0

        val client = createTestClient("user-slot-owner") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_campaign_detail") -> reply(detailPageJson)
                path.contains("get_business_quote") -> reply("""{"quote_type":"push","cost":100,"revision":"push-token-1"}""")
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-slot-owner","credits":1000}]""")
                path.contains("reconcile_campaign_push_receipt") -> reply("""{"success":false,"found":false}""")
                path.contains("push_campaign_v2") -> {
                    pushCallCount++
                    val body = extractBodyText(request)
                    val json = Json.parseToJsonElement(body).jsonObject
                    capturedPushRequestId = json["p_client_request_id"]?.jsonPrimitive?.content
                    if (shouldThrowNetwork) {
                        throw IOException("Simulated network loss on push")
                    } else {
                        reply("""{
                            "success": true,
                            "receipt_id": "rec-1",
                            "client_request_id": "$capturedPushRequestId",
                            "campaign_id": "camp-1",
                            "charged_credits": 100,
                            "balance_after": 900,
                            "business_date": "2026-11-01",
                            "expires_at": "2026-11-01T23:59:59Z",
                            "push_order": 1,
                            "replayed": true
                        }""")
                    }
                }
                path.contains("get_campaign_mission") -> reply("""{"mission":null}""")
                else -> reply("[]")
            }
        }
        val repo = CampaignRepository(client, localizer)

        val vm = trackVm(OwnerAppDetailViewModel("camp-1", repo, localizer))
        waitUntil { vm.uiState.value.campaign != null && !vm.uiState.value.isBackgroundSyncing }

        // 1. Push fails due to network loss
        vm.pushApp(context)
        waitUntil { vm.uiState.value.pushError != null }

        assertNotNull(vm.uiState.value.pushError)
        val pendingId = CampaignPushStore.peekPendingRequestId(context, "user-slot-owner", "camp-1")
        assertNotNull(pendingId)
        assertEquals(pendingId, capturedPushRequestId)

        // 2. Retry reuses the exact same request ID
        shouldThrowNetwork = false
        vm.retryOriginalPurchase(context)
        waitUntil { vm.uiState.value.message != null }

        assertEquals(2, pushCallCount)
        assertEquals(pendingId, capturedPushRequestId)

        // 3. On success, store is cleared
        assertNull(CampaignPushStore.peekPendingRequestId(context, "user-slot-owner", "camp-1"))
    }

    @Test
    fun slot_quoteUnavailable_blocksPurchase() = runTest {
        val client = createTestClient("user-slot-owner") { reply("{}") }
        val repo = CampaignRepository(client, localizer)

        val vm = trackVm(CreateCampaignViewModel(repo, localizer))
        waitUntil { !vm.uiState.value.isLoadingSlots }

        // Attempting to unlock with null revision must be blocked
        vm.unlockOrRenewSlot(slotNumber = 2, expectedCost = 50, expectedRevision = null)
        waitUntil { !vm.uiState.value.isUnlockingSlot }

        assertNotNull(vm.uiState.value.error)
        assertEquals(localizer.get(com.ntt.crosstest.R.string.admin_policy_quote_unavailable), vm.uiState.value.error)
    }
}
