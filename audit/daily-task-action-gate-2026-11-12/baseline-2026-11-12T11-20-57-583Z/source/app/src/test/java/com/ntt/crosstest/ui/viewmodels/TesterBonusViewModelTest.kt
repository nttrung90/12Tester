package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.CampaignTesterBonusStore
import com.ntt.crosstest.data.CreateTesterBonusResult
import com.ntt.crosstest.data.GetTesterBonusResult
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.ReconcileTesterBonusResult
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.TesterBonusIntent
import com.ntt.crosstest.data.TesterBonusStoreCorruptedStateException
import com.ntt.crosstest.data.TesterBonusStoreState
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
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
class TesterBonusViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clientsToClose = mutableListOf<SupabaseClient>()
    private val vmsToCancel = mutableListOf<TesterBonusViewModel>()

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var localizer: Localizer
    private val userId = "user-owner-101"
    private val campaignId = "camp-bonus-202"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        localizer = Localizer(context)
        val prefs = context.getSharedPreferences("crosstest_campaign_tester_bonus_requests", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        for (vm in vmsToCancel) {
            try { vm.viewModelScope.cancel() } catch (_: Exception) {}
        }
        vmsToCancel.clear()

        for (c in clientsToClose) {
            try { c.close() } catch (_: Exception) {}
        }
        clientsToClose.clear()
        Dispatchers.resetMain()
    }

    private fun track(vm: TesterBonusViewModel): TesterBonusViewModel {
        vmsToCancel.add(vm)
        return vm
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 3000,
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

    private suspend fun createTestClient(
        uid: String = userId,
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
                """{"access_token":"token-$uid","refresh_token":"refresh-$uid","token_type":"bearer","expires_in":3600,"user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )
        clientsToClose += client
        return client
    }

    private fun createStandardRepository(
        balance: Int = 500,
        campaignStatus: String = "open",
        existingRoundJson: String = """{"has_round":false,"status":"none"}""",
        createHandler: (suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData)? = null
    ): Pair<CampaignRepository, SupabaseClient> = runBlocking {
        val client = createTestClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_campaign_tester_bonus") -> {
                    reply(existingRoundJson)
                }
                path.contains("campaigns") -> {
                    reply(
                        """
                        [
                            {
                                "id": "$campaignId",
                                "owner_id": "$userId",
                                "app_name": "Awesome Game",
                                "package_name": "com.game.awesome",
                                "status": "$campaignStatus",
                                "target_testers": 12,
                                "target_days": 14,
                                "created_at": "2026-11-01T00:00:00Z"
                            }
                        ]
                        """
                    )
                }
                path.contains("profiles") -> {
                    reply(
                        """
                        [
                            {
                                "id": "$userId",
                                "credits": $balance,
                                "role": "member"
                            }
                        ]
                        """
                    )
                }
                createHandler != null -> {
                    createHandler(request)
                }
                else -> reply("{}", HttpStatusCode.NotFound)
            }
        }
        val repo = CampaignRepository(client, localizer)
        Pair(repo, client)
    }

    @Test
    fun `initial load sets campaign and wallet balance`() = runBlocking {
        val (repo, _) = createStandardRepository(balance = 350)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))

        waitUntil { !vm.uiState.value.isLoading }

        val state = vm.uiState.value
        assertEquals("Awesome Game", state.campaign?.appName)
        assertEquals(350, state.currentBalance)
        assertEquals(userId, state.currentUserId)
        assertFalse(state.hasActiveOrClosingRound)
        assertNull(state.error)
    }

    @Test
    fun `budget calculation checks boundaries, bounds, and wallet balance`() = runBlocking {
        val (repo, _) = createStandardRepository(balance = 100)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        // 1. Empty inputs
        vm.onRewardCreditsChanged("")
        vm.onMaxRecipientsChanged("")
        assertFalse(vm.uiState.value.isFormValid)
        assertEquals(0L, vm.uiState.value.calculatedBudget)

        // 2. Non-integer / negative inputs
        vm.onRewardCreditsChanged("-5")
        vm.onMaxRecipientsChanged("10")
        assertFalse(vm.uiState.value.isFormValid)
        assertNotNull(vm.uiState.value.inputValidationError)

        vm.onRewardCreditsChanged("5.5")
        assertFalse(vm.uiState.value.isFormValid)

        // 3. Valid input within balance
        vm.onRewardCreditsChanged("5")
        vm.onMaxRecipientsChanged("10")
        assertTrue(vm.uiState.value.isFormValid)
        assertEquals(50L, vm.uiState.value.calculatedBudget)
        assertEquals(50L, vm.uiState.value.expectedBalance)
        assertNull(vm.uiState.value.inputValidationError)

        // 4. Budget exceeds wallet balance
        vm.onMaxRecipientsChanged("30") // 5 * 30 = 150 > 100
        assertFalse(vm.uiState.value.isFormValid)
        assertEquals(150L, vm.uiState.value.calculatedBudget)
        assertEquals(-50L, vm.uiState.value.expectedBalance)
        assertNotNull(vm.uiState.value.inputValidationError)

        // 5. Overflow check (huge number)
        vm.onRewardCreditsChanged("2000000000")
        vm.onMaxRecipientsChanged("10")
        assertFalse(vm.uiState.value.isFormValid)
        assertNotNull(vm.uiState.value.inputValidationError)
    }

    @Test
    fun `existing active round opens in read-only progress mode`() = runBlocking {
        val existingJson = """
            {
                "has_round": true,
                "is_owner": true,
                "round_id": "round-active-1",
                "campaign_id": "$campaignId",
                "reward_credits": 10,
                "max_recipients": 20,
                "paid_count": 5,
                "remaining_slots": 15,
                "reserved_credits": 200,
                "remaining_credits": 150,
                "spent_credits": 50,
                "refunded_credits": 0,
                "status": "active"
            }
        """
        val (repo, _) = createStandardRepository(existingRoundJson = existingJson)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        val state = vm.uiState.value
        assertTrue(state.hasActiveOrClosingRound)
        assertNotNull(state.existingRound)
        assertEquals("active", state.existingRound?.status)
        assertEquals(5, state.existingRound?.paidCount)
        assertEquals(15, state.existingRound?.remainingSlots)
    }

    @Test
    fun `closed or exhausted round allows creating a next round while displaying historical progress`() = runBlocking {
        val closedRoundJson = """
            {
                "has_round": true,
                "is_owner": true,
                "round_id": "round-old-closed",
                "campaign_id": "$campaignId",
                "reward_credits": 5,
                "max_recipients": 10,
                "paid_count": 10,
                "remaining_slots": 0,
                "reserved_credits": 50,
                "remaining_credits": 0,
                "spent_credits": 50,
                "refunded_credits": 0,
                "status": "closed"
            }
        """
        val (repo, _) = createStandardRepository(balance = 300, existingRoundJson = closedRoundJson)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        val state = vm.uiState.value
        // Terminal round does NOT block new creation!
        assertFalse(state.hasActiveOrClosingRound)
        assertTrue(state.hasTerminalRound)
        assertNotNull(state.existingRound)
        assertEquals("closed", state.existingRound?.status)

        // Can fill form and budget is calculated normally for next round
        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        assertTrue(vm.uiState.value.isFormValid)
        assertEquals(100L, vm.uiState.value.calculatedBudget)
    }

    @Test
    fun `submitCreateBonus persists intent before calling RPC and clears on confirmed valid evidence`() = runBlocking {
        var rpcCalled = false
        var capturedRequestId: String? = null

        val (repo, _) = createStandardRepository(balance = 500) { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                rpcCalled = true
                val pending = CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId)
                assertNotNull("Intent must be persisted before network call", pending)
                capturedRequestId = pending?.clientRequestId

                reply(
                    """
                    {
                        "success": true,
                        "replayed": false,
                        "round_id": "new-round-999",
                        "client_request_id": "$capturedRequestId",
                        "campaign_id": "$campaignId",
                        "reward_credits": 10,
                        "max_recipients": 10,
                        "paid_count": 0,
                        "remaining_slots": 10,
                        "reserved_credits": 100,
                        "remaining_credits": 100,
                        "spent_credits": 0,
                        "refunded_credits": 0,
                        "owner_balance": 400,
                        "status": "active"
                    }
                    """
                )
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        assertTrue(vm.uiState.value.isFormValid)

        vm.submitCreateBonus()

        waitUntil { !vm.uiState.value.isActionLoading }

        assertTrue(rpcCalled)
        assertTrue(vm.uiState.value.isSuccess)
        assertEquals(400, vm.uiState.value.currentBalance)
        assertNotNull(vm.uiState.value.confirmedResult)

        // Store must be cleared after confirmed server evidence
        assertNull(CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }

    @Test
    fun `malformed or empty response retains intent and does not clear store`() = runBlocking {
        val (repo, _) = createStandardRepository(balance = 500) { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                // Empty malformed payload with success=false / missing fields
                reply("""{}""")
            } else if (request.url.encodedPath.contains("reconcile_campaign_tester_bonus")) {
                reply("""{"found": false}""")
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        vm.submitCreateBonus()

        waitUntil { !vm.uiState.value.isActionLoading && !vm.uiState.value.isReconciling }

        assertFalse(vm.uiState.value.isSuccess)
        assertTrue(vm.uiState.value.hasUnresolvedIntent)
        assertNotNull("Intent must NOT be cleared on unverified response", CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }

    @Test
    fun `definitive server validation error releases intent`() = runBlocking {
        val (repo, _) = createStandardRepository(balance = 500) { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                reply(
                    """{"code":"P0001","message":"INSUFFICIENT_CREDITS: Required 600 but available 500"}""",
                    HttpStatusCode.BadRequest
                )
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        vm.submitCreateBonus()

        waitUntil { !vm.uiState.value.isActionLoading }

        assertFalse(vm.uiState.value.isSuccess)
        assertNotNull(vm.uiState.value.error)
        // Intent must be cleared on definitive validation failure
        assertNull(CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }

    @Test
    fun `ambiguous network error retains intent and triggers reconcile`() = runBlocking {
        var reconcileCalled = false

        val (repo, _) = createStandardRepository(balance = 500) { request ->
            val path = request.url.encodedPath
            if (path.contains("create_campaign_tester_bonus")) {
                // Ambiguous failure: 500 Internal Server Error
                reply("""{"code":"500","message":"Internal Server Error"}""", HttpStatusCode.InternalServerError)
            } else if (path.contains("reconcile_campaign_tester_bonus")) {
                reconcileCalled = true
                // Reconcile confirms it was actually recorded!
                val pending = CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId)
                reply(
                    """
                    {
                        "found": true,
                        "replayed": true,
                        "round_id": "round-reconciled-777",
                        "client_request_id": "${pending?.clientRequestId}",
                        "campaign_id": "$campaignId",
                        "reward_credits": 10,
                        "max_recipients": 10,
                        "paid_count": 0,
                        "remaining_slots": 10,
                        "reserved_credits": 100,
                        "remaining_credits": 100,
                        "spent_credits": 0,
                        "refunded_credits": 0,
                        "owner_balance": 400,
                        "status": "active"
                    }
                    """
                )
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        vm.submitCreateBonus()

        waitUntil { !vm.uiState.value.isActionLoading && !vm.uiState.value.isReconciling }

        assertTrue(reconcileCalled)
        assertTrue(vm.uiState.value.isSuccess)
        assertEquals(400, vm.uiState.value.currentBalance)
        assertNull(CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }

    @Test
    fun `corrupted store state flags isStoreCorrupted and retains record`() = runBlocking {
        val prefs = context.getSharedPreferences("crosstest_campaign_tester_bonus_requests", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("${userId}_$campaignId", "{ bad json").commit()

        val (repo, _) = createStandardRepository(balance = 500)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        assertTrue(vm.uiState.value.isStoreCorrupted)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `reconcile pending intent executes independently of deleted campaign`() = runBlocking {
        // Pre-save a pending intent
        val existingIntent = CampaignTesterBonusStore.getOrCreatePendingIntent(
            context, userId, campaignId, rewardCredits = 5, maxRecipients = 10,
        )

        val client = createTestClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("reconcile_campaign_tester_bonus") -> {
                    // Confirms committed!
                    reply(
                        """
                        {
                            "found": true,
                            "replayed": true,
                            "round_id": "round-deleted-app-1",
                            "client_request_id": "${existingIntent.clientRequestId}",
                            "campaign_id": "$campaignId",
                            "reward_credits": 5,
                            "max_recipients": 10,
                            "paid_count": 0,
                            "remaining_slots": 10,
                            "reserved_credits": 50,
                            "remaining_credits": 50,
                            "spent_credits": 0,
                            "refunded_credits": 0,
                            "owner_balance": 450,
                            "status": "active"
                        }
                        """
                    )
                }
                path.contains("campaigns") -> {
                    // Campaign was physically deleted on server (empty array / 404)
                    reply("[]")
                }
                path.contains("profiles") -> {
                    reply("""[{"id": "$userId", "credits": 450, "role": "member"}]""")
                }
                path.contains("get_campaign_tester_bonus") -> {
                    reply("""{"has_round": false, "status": "none"}""")
                }
                else -> reply("{}", HttpStatusCode.NotFound)
            }
        }
        val repo = CampaignRepository(client, localizer)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))

        waitUntil { !vm.uiState.value.isLoading }

        // Historical result was confirmed and intent cleared!
        assertTrue(vm.uiState.value.isSuccess)
        assertEquals("round-deleted-app-1", vm.uiState.value.confirmedResult?.roundId)
        assertNull(CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }

    @Test
    fun `terminal closed or exhausted prior round allows creating next round`() = runBlocking {
        val client = createTestClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("campaigns") -> {
                    reply(
                        """
                        [
                            {
                                "id": "$campaignId",
                                "owner_id": "$userId",
                                "app_name": "Test App",
                                "package_name": "com.test.app",
                                "status": "open",
                                "target_testers": 20,
                                "target_days": 14
                            }
                        ]
                        """
                    )
                }
                path.contains("profiles") -> {
                    reply("""[{"id": "$userId", "credits": 500, "role": "member"}]""")
                }
                path.contains("get_campaign_tester_bonus") -> {
                    // Terminal closed round
                    reply(
                        """
                        {
                            "has_round": true,
                            "is_owner": true,
                            "round_id": "round-terminal-1",
                            "campaign_id": "$campaignId",
                            "reward_credits": 5,
                            "max_recipients": 10,
                            "paid_count": 10,
                            "remaining_slots": 0,
                            "status": "closed",
                            "reserved_credits": 50,
                            "remaining_credits": 0,
                            "spent_credits": 50,
                            "refunded_credits": 0
                        }
                        """
                    )
                }
                else -> reply("{}", HttpStatusCode.NotFound)
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))

        waitUntil { !vm.uiState.value.isLoading }

        // Must show terminal round history, but NOT block creating a next round!
        assertTrue("hasTerminalRound should be true", vm.uiState.value.hasTerminalRound)
        assertFalse("hasActiveOrClosingRound should be false for closed round", vm.uiState.value.hasActiveOrClosingRound)

        // Owner can enter new parameters
        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("15")
        assertEquals(150L, vm.uiState.value.calculatedBudget)
        assertTrue(vm.uiState.value.isFormValid)
    }

    @Test
    fun `getCampaignTesterBonus with SQL error CAMPAIGN_NOT_FOUND propagates and does not offer create`() = runBlocking {
        val client = createTestClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("campaigns") -> {
                    reply("[]")
                }
                path.contains("profiles") -> {
                    reply("""[{"id": "$userId", "credits": 500, "role": "member"}]""")
                }
                path.contains("get_campaign_tester_bonus") -> {
                    // SQL returns has_round: false with error
                    reply("""{"has_round": false, "error": "CAMPAIGN_NOT_FOUND"}""")
                }
                else -> reply("{}", HttpStatusCode.NotFound)
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))

        waitUntil { !vm.uiState.value.isLoading }

        assertNotNull("Should surface error instead of treating as normal empty round", vm.uiState.value.error)
        assertTrue(vm.uiState.value.isCampaignNotFound || vm.uiState.value.error?.contains("CAMPAIGN_NOT_FOUND") == true)
    }

    @Test
    fun `missing RPC get_campaign_tester_bonus sets isNotSupported`() = runBlocking {
        val client = createTestClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("campaigns") -> {
                    reply(
                        """
                        [
                            {
                                "id": "$campaignId",
                                "owner_id": "$userId",
                                "app_name": "Test App",
                                "package_name": "com.test.app",
                                "status": "open",
                                "target_testers": 20,
                                "target_days": 14
                            }
                        ]
                        """
                    )
                }
                path.contains("profiles") -> {
                    reply("""[{"id": "$userId", "credits": 500, "role": "member"}]""")
                }
                path.contains("get_campaign_tester_bonus") -> {
                    // PostgREST 404 / PGRST202
                    reply("""{"code": "PGRST202", "message": "Could not find the function"}""", HttpStatusCode.NotFound)
                }
                else -> reply("{}", HttpStatusCode.NotFound)
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))

        waitUntil { !vm.uiState.value.isLoading }

        assertTrue("isNotSupported should be true when RPC is missing", vm.uiState.value.isNotSupported)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `delayed create response arriving after account change is safely gated`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = createTestClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("campaigns") -> {
                    reply(
                        """
                        [
                            {
                                "id": "$campaignId",
                                "owner_id": "$userId",
                                "app_name": "Test App",
                                "package_name": "com.test.app",
                                "status": "open",
                                "target_testers": 20,
                                "target_days": 14
                            }
                        ]
                        """
                    )
                }
                path.contains("profiles") -> {
                    reply("""[{"id": "$userId", "credits": 500, "role": "member"}]""")
                }
                path.contains("get_campaign_tester_bonus") -> {
                    reply("""{"has_round": false, "status": "none"}""")
                }
                path.contains("create_campaign_tester_bonus") -> {
                    gate.await()
                    reply(
                        """
                        {
                            "success": true,
                            "replayed": false,
                            "round_id": "round-stale-user",
                            "client_request_id": "req-stale-1",
                            "campaign_id": "$campaignId",
                            "reward_credits": 5,
                            "max_recipients": 10,
                            "paid_count": 0,
                            "remaining_slots": 10,
                            "reserved_credits": 50,
                            "remaining_credits": 50,
                            "spent_credits": 0,
                            "refunded_credits": 0,
                            "owner_balance": 450,
                            "status": "active"
                        }
                        """
                    )
                }
                else -> reply("{}", HttpStatusCode.NotFound)
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))

        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("5")
        vm.onMaxRecipientsChanged("10")
        vm.submitCreateBonus()

        assertTrue(vm.uiState.value.isActionLoading)

        // Account generation bumps (e.g. user logs out or switches accounts)
        SessionCacheManager.generation.incrementAndGet()

        // Release delayed network response
        gate.complete(Unit)

        waitUntil(timeoutMs = 1500) { !vm.uiState.value.isActionLoading }

        // Because session generation changed, the stale create response must NOT acknowledge success on the UI
        assertFalse("Stale create must not show success", vm.uiState.value.isSuccess)
    }

    @Test
    fun `typed HTTP 503 with INSUFFICIENT_CREDITS retains UUID and does not clear intent`() = runBlocking {
        val (repo, _) = createStandardRepository(balance = 500) { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                reply(
                    """{"code":"P0001","message":"INSUFFICIENT_CREDITS: Required 600 but available 500"}""",
                    HttpStatusCode.ServiceUnavailable
                )
            } else if (request.url.encodedPath.contains("reconcile_campaign_tester_bonus")) {
                reply("""{"found": false}""")
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        vm.submitCreateBonus()

        waitUntil { !vm.uiState.value.isActionLoading && !vm.uiState.value.isReconciling }

        // HTTP 503 is NOT a definitive 400 rollback -> intent must be retained!
        assertFalse(vm.uiState.value.isSuccess)
        val pending = CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId)
        assertNotNull("Intent must be retained on 503 error", pending)
        assertEquals(10, pending?.rewardCredits)
        assertEquals(10, pending?.maxRecipients)
    }

    @Test
    fun `non-typed or non-P0001 JSON validation error retains intent`() = runBlocking {
        val (repo, _) = createStandardRepository(balance = 500) { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                // Not P0001 (e.g. 42703 or 400 with non-P0001 code)
                reply(
                    """{"code":"42703","message":"INSUFFICIENT_CREDITS"}""",
                    HttpStatusCode.BadRequest
                )
            } else if (request.url.encodedPath.contains("reconcile_campaign_tester_bonus")) {
                reply("""{"found": false}""")
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        vm.submitCreateBonus()

        waitUntil { !vm.uiState.value.isActionLoading && !vm.uiState.value.isReconciling }

        assertFalse(vm.uiState.value.isSuccess)
        assertNotNull("Non-P0001 error must retain intent", CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }

    @Test
    fun `CLIENT_REQUEST_ID_CONFLICT retains original UUID and intent`() = runBlocking {
        val (repo, _) = createStandardRepository(balance = 500) { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                reply(
                    """{"code":"P0001","message":"CLIENT_REQUEST_ID_CONFLICT: round already registered"}""",
                    HttpStatusCode.BadRequest
                )
            } else if (request.url.encodedPath.contains("reconcile_campaign_tester_bonus")) {
                reply("""{"found": false}""")
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        vm.submitCreateBonus()

        waitUntil { !vm.uiState.value.isActionLoading && !vm.uiState.value.isReconciling }

        // Must NEVER clear intent on conflict!
        assertFalse(vm.uiState.value.isSuccess)
        assertNotNull("Conflict error must retain intent", CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }

    @Test
    fun `genuine P0001 400 fresh rejection known handled and releases intent`() = runBlocking {
        val (repo, _) = createStandardRepository(balance = 500) { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                reply(
                    """{"code":"P0001","message":"CAMPAIGN_NOT_OPEN: cannot create bonus"}""",
                    HttpStatusCode.BadRequest
                )
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        vm.submitCreateBonus()

        waitUntil { !vm.uiState.value.isActionLoading }

        assertFalse(vm.uiState.value.isSuccess)
        assertNotNull(vm.uiState.value.error)
        assertNull("Genuine P0001 400 rollback must release intent", CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }

    @Test
    fun `coherent wrong reconcile payload retains intent and does not acknowledge success`() = runBlocking {
        val (repo, _) = createStandardRepository(balance = 500) { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                reply("""{"code":"500","message":"Internal Server Error"}""", HttpStatusCode.InternalServerError)
            } else if (request.url.encodedPath.contains("reconcile_campaign_tester_bonus")) {
                // Wrong payload: reward is 99 instead of 10
                val pending = CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId)
                reply(
                    """
                    {
                        "found": true,
                        "replayed": true,
                        "round_id": "round-wrong-payload",
                        "client_request_id": "${pending?.clientRequestId}",
                        "campaign_id": "$campaignId",
                        "reward_credits": 99,
                        "max_recipients": 10,
                        "paid_count": 0,
                        "remaining_slots": 10,
                        "reserved_credits": 990,
                        "remaining_credits": 990,
                        "spent_credits": 0,
                        "refunded_credits": 0,
                        "owner_balance": 400,
                        "status": "active"
                    }
                    """
                )
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val vm = track(TesterBonusViewModel(campaignId, repo, localizer, context))
        waitUntil { !vm.uiState.value.isLoading }

        vm.onRewardCreditsChanged("10")
        vm.onMaxRecipientsChanged("10")
        vm.submitCreateBonus()

        waitUntil { !vm.uiState.value.isActionLoading && !vm.uiState.value.isReconciling }

        // Wrong reconcile evidence must NOT be treated as success! Intent must be retained!
        assertFalse(vm.uiState.value.isSuccess)
        assertTrue(vm.uiState.value.hasUnresolvedIntent)
        assertNotNull("Intent must be retained when reconcile payload does not match", CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }
}
