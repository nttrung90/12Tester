package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.CreditLog
import com.ntt.crosstest.data.FinancialIntentStore
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.PendingCreateCampaignIntent
import com.ntt.crosstest.util.CreditLogRenderer
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CampaignPostingPolicyViewModelTest {

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
        userId: String = "user-publisher-1",
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

    private fun buildAllQuoteJson(
        revision: String = "quote-rev-v1",
        minCreditsToPostApp: Int = 10,
        slot1Fee: Int = 0,
        target15Fee: Int = 10,
        target20Fee: Int = 20,
    ): String = """
        {
            "quote_type": "all",
            "revision": "$revision",
            "push_fee": 100,
            "min_credits_to_post_app": $minCreditsToPostApp,
            "slot_fees": {
                "slot_1": $slot1Fee,
                "slot_2": 50,
                "slot_3": 100,
                "slot_4_plus": 200,
                "renewal_days": 30
            },
            "target_fees": {
                "target_12": 0,
                "target_15": $target15Fee,
                "target_20": $target20Fee
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
    fun dynamicPostingQuote_computesSlot1AndTargetCostsAndStandardizedGteThreshold() = runTest {
        val client = createTestClient("user-publisher-1") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_business_quote") -> reply(
                    buildAllQuoteJson(
                        revision = "quote-dynamic-1",
                        minCreditsToPostApp = 25,
                        slot1Fee = 15,
                        target15Fee = 20,
                        target20Fee = 35,
                    )
                )
                path.contains("user_slots") -> reply("""[{"user_id":"user-publisher-1","slot_number":2,"expires_at":"2026-12-31T00:00:00Z"}]""")
                path.contains("campaigns") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-publisher-1","credits":60}]""")
                else -> reply("[]")
            }
        }
        val repo = CampaignRepository(client, localizer)
        val vm = trackVm(CreateCampaignViewModel(repo, localizer))

        waitUntil { !vm.uiState.value.isLoadingCredits && !vm.uiState.value.isLoadingSlots && !vm.uiState.value.isLoadingPolicyQuote }

        // Default selection: Slot 1, Target 12 -> slotPostCost = 15, targetCost = 0, total = 15, required = 40
        val s1 = vm.uiState.value
        assertEquals(25, s1.minCreditsToPostApp)
        assertEquals(15, s1.slot1PostFee)
        assertEquals(15, s1.slotPostCost)
        assertEquals(0, s1.targetCost)
        assertEquals(15, s1.totalPostCost)
        assertEquals(40, s1.requiredCredits)
        assertTrue(s1.hasEnoughCredits) // 60 >= 40

        // Select Target 15 on Slot 1 -> slotPostCost = 15, targetCost = 20, total = 35, required = 60
        vm.selectTarget(15)
        val s2 = vm.uiState.value
        assertEquals(35, s2.totalPostCost)
        assertEquals(60, s2.requiredCredits)
        assertTrue(s2.hasEnoughCredits) // Exact boundary: 60 >= 60 is ALLOWED!

        // Select Target 20 on Slot 1 -> total = 50, required = 75 -> 60 < 75 is BLOCKED!
        vm.selectTarget(20)
        val s3 = vm.uiState.value
        assertEquals(50, s3.totalPostCost)
        assertEquals(75, s3.requiredCredits)
        assertFalse(s3.hasEnoughCredits)

        // Switch to unlocked Slot 2 -> slotPostCost = 0, targetCost = 35, total = 35, required = 60 -> ALLOWED!
        vm.selectSlot(2)
        val s4 = vm.uiState.value
        assertEquals(0, s4.slotPostCost)
        assertEquals(35, s4.totalPostCost)
        assertEquals(60, s4.requiredCredits)
        assertTrue(s4.hasEnoughCredits)
    }

    @Test
    fun baselineExact10Credits_isAllowedWhile9CreditsIsBlocked() = runTest {
        var currentCredits = 9
        var createRpcCount = 0

        val client = createTestClient("user-publisher-gte") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_business_quote") -> reply(buildAllQuoteJson())
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-publisher-gte","credits":$currentCredits}]""")
                path.contains("create_campaign_v2") -> {
                    createRpcCount++
                    reply("""{
                        "success": true,
                        "receipt_id": "rec-exact-10",
                        "client_request_id": "dummy-req",
                        "campaign_id": "camp-exact-10",
                        "slot_number": 1,
                        "target_testers": 12,
                        "slot_1_cost": 0,
                        "target_cost": 0,
                        "charged_credits": 0,
                        "min_credits_required": 10,
                        "balance_after": 10,
                        "replayed": false
                    }""")
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = trackVm(CreateCampaignViewModel(repo, localizer))
        waitUntil { !vm.uiState.value.isLoadingCredits && !vm.uiState.value.isLoadingSlots && !vm.uiState.value.isLoadingPolicyQuote }

        vm.update(packageName = "com.example.exactten", appName = "Exact 10 App")

        // 9 credits < 10 required -> blocked before RPC
        assertFalse(vm.uiState.value.hasEnoughCredits)
        vm.create()
        assertNotNull(vm.uiState.value.error)
        assertEquals(0, createRpcCount)

        // Increase balance to exact 10 credits -> allowed!
        currentCredits = 10
        val repo2 = CampaignRepository(client, localizer)
        val vm2 = trackVm(CreateCampaignViewModel(repo2, localizer))
        waitUntil { !vm2.uiState.value.isLoadingCredits && !vm2.uiState.value.isLoadingSlots && !vm2.uiState.value.isLoadingPolicyQuote }
        vm2.update(packageName = "com.example.exactten", appName = "Exact 10 App")
        assertTrue(vm2.uiState.value.hasEnoughCredits)

        vm2.create()
        waitUntil { vm2.uiState.value.created }
        assertEquals(1, createRpcCount)
    }

    @Test
    fun create_networkLossRetry_reusesExactClientRequestIdAndClearsOnSuccess() = runTest {
        val context = RuntimeEnvironment.getApplication()
        FinancialIntentStore.clearAllCreateCampaignIntentsForUser(context, "user-publisher-retry")

        var shouldThrowNetwork = true
        var createCallCount = 0
        val capturedRequestIds = mutableListOf<String>()
        var capturedExpectedTotalCost: Int? = null
        var capturedExpectedMinCredits: Int? = null

        val client = createTestClient("user-publisher-retry") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_business_quote") -> reply(
                    buildAllQuoteJson(
                        revision = "rev-retry-1",
                        minCreditsToPostApp = 20,
                        slot1Fee = 15,
                        target15Fee = 10,
                    )
                )
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-publisher-retry","credits":100}]""")
                path.contains("create_campaign_v2") -> {
                    createCallCount++
                    val body = Json.parseToJsonElement(extractBodyText(request)).jsonObject
                    capturedRequestIds += body["p_client_request_id"]!!.jsonPrimitive.content
                    capturedExpectedTotalCost = body["p_expected_total_cost"]!!.jsonPrimitive.int
                    capturedExpectedMinCredits = body["p_expected_min_credits"]!!.jsonPrimitive.int
                    if (shouldThrowNetwork) {
                        throw IOException("Simulated network drop during create_campaign_v2")
                    } else {
                        reply("""{
                            "success": true,
                            "receipt_id": "rec-replayed-1",
                            "client_request_id": "${capturedRequestIds.lastOrNull().orEmpty()}",
                            "campaign_id": "camp-replayed-1",
                            "slot_number": 1,
                            "target_testers": 15,
                            "slot_1_cost": 15,
                            "target_cost": 10,
                            "charged_credits": 25,
                            "min_credits_required": 20,
                            "balance_after": 75,
                            "replayed": true
                        }""")
                    }
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = trackVm(CreateCampaignViewModel(repo, localizer))
        waitUntil { !vm.uiState.value.isLoadingCredits && !vm.uiState.value.isLoadingSlots && !vm.uiState.value.isLoadingPolicyQuote }

        vm.selectTarget(15)
        vm.update(packageName = "com.example.retryapp", appName = "Retry App")

        // 1. First attempt drops connection
        vm.create()
        waitUntil { vm.uiState.value.error != null && !vm.uiState.value.isSaving }

        val stored = FinancialIntentStore.peekCreateCampaignIntent(
            context = context,
            userId = "user-publisher-retry",
            packageName = "com.example.retryapp",
            slotNumber = 1,
        )
        assertNotNull(stored)
        assertEquals(25, stored!!.expectedTotalCost)
        assertEquals(20, stored.expectedMinCredits)
        assertEquals("rev-retry-1", stored.expectedRevision)
        assertEquals(1, capturedRequestIds.size)
        assertEquals(stored.clientRequestId, capturedRequestIds[0])

        // 2. Retry reuses the EXACT stored clientRequestId
        shouldThrowNetwork = false
        vm.create()
        waitUntil { vm.uiState.value.created }

        assertEquals(2, createCallCount)
        assertEquals(stored.clientRequestId, capturedRequestIds[1])
        assertEquals(25, capturedExpectedTotalCost)
        assertEquals(20, capturedExpectedMinCredits)
        assertNull(
            FinancialIntentStore.peekCreateCampaignIntent(
                context = context,
                userId = "user-publisher-retry",
                packageName = "com.example.retryapp",
                slotNumber = 1,
            )
        )
    }

    @Test
    fun create_priceMismatch_clearsIntentAndReloadsFreshQuote() = runTest {
        val context = RuntimeEnvironment.getApplication()
        FinancialIntentStore.clearAllCreateCampaignIntentsForUser(context, "user-publisher-mismatch")

        var quoteVersion = 1

        val client = createTestClient("user-publisher-mismatch") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_business_quote") -> {
                    if (quoteVersion == 1) {
                        reply(buildAllQuoteJson(revision = "rev-old", minCreditsToPostApp = 10, slot1Fee = 0))
                    } else {
                        reply(buildAllQuoteJson(revision = "rev-new", minCreditsToPostApp = 30, slot1Fee = 20))
                    }
                }
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-publisher-mismatch","credits":100}]""")
                path.contains("create_campaign_v2") -> {
                    quoteVersion = 2
                    reply(
                        """{"code":"P0001","message":"BUSINESS_POLICY_PRICE_MISMATCH: Policy changed"}""",
                        HttpStatusCode.BadRequest,
                    )
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = trackVm(CreateCampaignViewModel(repo, localizer))
        waitUntil { !vm.uiState.value.isLoadingCredits && !vm.uiState.value.isLoadingSlots && !vm.uiState.value.isLoadingPolicyQuote }

        assertEquals("rev-old", vm.uiState.value.policyRevision)
        assertEquals(0, vm.uiState.value.slot1PostFee)

        vm.update(packageName = "com.example.mismatch", appName = "Mismatch App")
        vm.create()
        waitUntil { vm.uiState.value.policyRevision == "rev-new" && !vm.uiState.value.isLoadingPolicyQuote }

        // Definitive mismatch clears pending intent and refreshes UI quote
        assertNull(
            FinancialIntentStore.peekCreateCampaignIntent(
                context = context,
                userId = "user-publisher-mismatch",
                packageName = "com.example.mismatch",
                slotNumber = 1,
            )
        )
        assertEquals(30, vm.uiState.value.minCreditsToPostApp)
        assertEquals(20, vm.uiState.value.slot1PostFee)
    }

    @Test
    fun create_failClosedWhenQuoteUnavailable() = runTest {
        var createRpcCount = 0

        val client = createTestClient("user-publisher-noquote") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_business_quote") -> throw IOException("Quote endpoint unreachable")
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-publisher-noquote","credits":200}]""")
                path.contains("create_campaign_v2") -> {
                    createRpcCount++
                    reply("{}")
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = trackVm(CreateCampaignViewModel(repo, localizer))
        waitUntil { !vm.uiState.value.isLoadingCredits && !vm.uiState.value.isLoadingSlots && !vm.uiState.value.isLoadingPolicyQuote }

        assertNull(vm.uiState.value.policyRevision)
        vm.update(packageName = "com.example.noquote", appName = "No Quote App")
        vm.create()

        assertEquals(0, createRpcCount)
        assertEquals(localizer.get(R.string.admin_policy_quote_unavailable), vm.uiState.value.error)
    }

    @Test
    fun accountSwitch_createCampaignIntentIsolation() {
        val context = RuntimeEnvironment.getApplication()
        FinancialIntentStore.clearAllCreateCampaignIntentsForUser(context, "user-A")
        FinancialIntentStore.clearAllCreateCampaignIntentsForUser(context, "user-B")

        val intentA = PendingCreateCampaignIntent(
            userId = "user-A",
            packageName = "com.example.sharedpkg",
            slotNumber = 1,
            targetTesters = 15,
            expectedTotalCost = 25,
            expectedMinCredits = 10,
            expectedRevision = "rev-A",
            clientRequestId = "req-camp-A",
        )
        FinancialIntentStore.saveCreateCampaignIntent(context, intentA)

        assertNotNull(FinancialIntentStore.peekCreateCampaignIntent(context, "user-A", "com.example.sharedpkg", 1))
        assertNull(FinancialIntentStore.peekCreateCampaignIntent(context, "user-B", "com.example.sharedpkg", 1))
    }

    @Test
    fun creditLogRenderer_rendersTargetUpgradeActionAndLegacyPostAppUpgradeNote() {
        val newActionLog = CreditLog(
            id = "log-1",
            userId = "u1",
            amount = -15,
            action = "target_upgrade",
            note = "Target upgrade (15/day): Demo App",
        )
        val legacyNoteLog = CreditLog(
            id = "log-2",
            userId = "u1",
            amount = -10,
            action = "post_app",
            note = "Target upgrade (15/day): Legacy App",
        )
        val slot1PostLog = CreditLog(
            id = "log-3",
            userId = "u1",
            amount = -20,
            action = "post_app",
            note = "Slot 1 posting fee: Demo App",
        )

        val expectedUpgradeTitle = localizer.get(R.string.credit_action_target_upgrade)
        val expectedPostTitle = localizer.get(R.string.credit_action_post_app)

        assertEquals(expectedUpgradeTitle, CreditLogRenderer.render(newActionLog, localizer))
        assertEquals(expectedUpgradeTitle, CreditLogRenderer.render(legacyNoteLog, localizer))
        assertEquals(expectedPostTitle, CreditLogRenderer.render(slot1PostLog, localizer))
    }

    @Test
    fun create_metadataChangeAfterNetworkError_generatesNewClientRequestId() = runTest {
        val context = RuntimeEnvironment.getApplication()
        FinancialIntentStore.clearAllCreateCampaignIntentsForUser(context, "user-publisher-meta-change")

        var shouldThrowNetwork = true
        val capturedRequestIds = mutableListOf<String>()

        val client = createTestClient("user-publisher-meta-change") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_business_quote") -> reply(buildAllQuoteJson())
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> reply("[]")
                path.contains("profiles") -> reply("""[{"id":"user-publisher-meta-change","credits":100}]""")
                path.contains("create_campaign_v2") -> {
                    val body = Json.parseToJsonElement(extractBodyText(request)).jsonObject
                    capturedRequestIds += body["p_client_request_id"]!!.jsonPrimitive.content
                    if (shouldThrowNetwork) {
                        throw IOException("Network error")
                    } else {
                        reply("""{
                            "success": true,
                            "receipt_id": "rec-2",
                            "client_request_id": "${capturedRequestIds.last()}",
                            "campaign_id": "camp-2",
                            "slot_number": 1,
                            "target_testers": 12,
                            "slot_1_cost": 0,
                            "target_cost": 0,
                            "charged_credits": 0,
                            "min_credits_required": 10,
                            "balance_after": 100,
                            "replayed": false
                        }""")
                    }
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = trackVm(CreateCampaignViewModel(repo, localizer))
        waitUntil { !vm.uiState.value.isLoadingCredits && !vm.uiState.value.isLoadingSlots && !vm.uiState.value.isLoadingPolicyQuote }

        vm.update(packageName = "com.example.metachange", appName = "Initial App Name")
        vm.create()
        waitUntil { vm.uiState.value.error != null && !vm.uiState.value.isSaving }
        assertEquals(1, capturedRequestIds.size)

        // User modifies appName -> metadataHash changes!
        vm.update(appName = "Changed App Name")
        shouldThrowNetwork = false
        vm.create()
        waitUntil { vm.uiState.value.created }

        assertEquals(2, capturedRequestIds.size)
        // Request IDs must be different because metadata changed
        assertTrue("Request ID should rotate when metadata changes", capturedRequestIds[0] != capturedRequestIds[1])
    }

    @Test
    fun getMySlotsInfo_doesNotCacheWhenQuoteFails() = runTest {
        var quoteShouldFail = true
        var quoteFetchCount = 0

        val client = createTestClient("user-publisher-slot-cache") { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_business_quote") -> {
                    quoteFetchCount++
                    if (quoteShouldFail) {
                        throw IOException("Network failure for quote")
                    } else {
                        reply(buildAllQuoteJson())
                    }
                }
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> reply("[]")
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)

        // 1st call fails quote
        val slots1 = repo.getMySlotsInfo()
        assertEquals(1, quoteFetchCount)
        assertNull(slots1.firstOrNull()?.policyRevision)

        // 2nd call: quote now succeeds, cache must NOT prevent fetching fresh quote
        quoteShouldFail = false
        val slots2 = repo.getMySlotsInfo()
        assertEquals(2, quoteFetchCount)
        assertNotNull(slots2.firstOrNull()?.policyRevision)
    }
}
