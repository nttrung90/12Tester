package com.ntt.crosstest.data

import android.app.Application
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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TesterBonusRepositoryTest {

    private val clientsToClose = mutableListOf<SupabaseClient>()
    private val localizer by lazy { Localizer(RuntimeEnvironment.getApplication()) }

    @After
    fun tearDown() = runBlocking<Unit> {
        for (c in clientsToClose) {
            try { c.close() } catch (_: Exception) {}
        }
        clientsToClose.clear()
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

    @Test
    fun `createCampaignTesterBonus wire decoding and payload submission`() = runBlocking {
        var capturedBody = ""
        val mockClient = createTestClient { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                capturedBody = extractBodyText(request)
                reply(
                    """
                    {
                        "success": true,
                        "replayed": false,
                        "round_id": "round-uuid-123",
                        "client_request_id": "req-uuid-789",
                        "campaign_id": "camp-test-1",
                        "reward_credits": 5,
                        "max_recipients": 10,
                        "paid_count": 0,
                        "remaining_slots": 10,
                        "reserved_credits": 50,
                        "remaining_credits": 50,
                        "spent_credits": 0,
                        "refunded_credits": 0,
                        "owner_balance": 150,
                        "status": "active"
                    }
                    """
                )
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val repository = CampaignRepository(mockClient, localizer)
        val result = repository.createCampaignTesterBonus(
            campaignId = "camp-test-1",
            rewardCredits = 5,
            maxRecipients = 10,
            clientRequestId = "req-uuid-789"
        )

        assertTrue(capturedBody.contains(""""p_campaign_id":"camp-test-1""""))
        assertTrue(capturedBody.contains(""""p_reward_credits":5"""))
        assertTrue(capturedBody.contains(""""p_max_recipients":10"""))
        assertTrue(capturedBody.contains(""""p_client_request_id":"req-uuid-789""""))

        assertTrue(result.success)
        assertFalse(result.replayed)
        assertEquals("round-uuid-123", result.roundId)
        assertEquals("req-uuid-789", result.clientRequestId)
        assertEquals(5, result.rewardCredits)
        assertEquals(10, result.maxRecipients)
        assertEquals(10, result.remainingSlots)
        assertEquals(50L, result.reservedCredits)
        assertEquals(150L, result.ownerBalance)
        assertEquals(150, result.safeOwnerBalanceInt)
        assertEquals("active", result.status)
        assertTrue(result.isValidEvidenceFor("req-uuid-789", "camp-test-1", 5, 10))
    }

    @Test
    fun `reconcileCampaignTesterBonus found true decoding`() = runBlocking {
        var capturedBody = ""
        val mockClient = createTestClient { request ->
            if (request.url.encodedPath.contains("reconcile_campaign_tester_bonus")) {
                capturedBody = extractBodyText(request)
                reply(
                    """
                    {
                        "found": true,
                        "replayed": true,
                        "round_id": "round-uuid-123",
                        "client_request_id": "req-uuid-789",
                        "campaign_id": "camp-test-1",
                        "reward_credits": 5,
                        "max_recipients": 10,
                        "paid_count": 1,
                        "remaining_slots": 9,
                        "reserved_credits": 50,
                        "remaining_credits": 45,
                        "spent_credits": 5,
                        "refunded_credits": 0,
                        "owner_balance": 150,
                        "status": "active"
                    }
                    """
                )
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val repository = CampaignRepository(mockClient, localizer)
        val result = repository.reconcileCampaignTesterBonus(
            campaignId = "camp-test-1",
            clientRequestId = "req-uuid-789",
            expectedRewardCredits = 5,
            expectedMaxRecipients = 10,
        )

        assertTrue(capturedBody.contains(""""p_campaign_id":"camp-test-1""""))
        assertTrue(capturedBody.contains(""""p_client_request_id":"req-uuid-789""""))

        assertTrue(result.found)
        assertTrue(result.replayed)
        assertEquals("round-uuid-123", result.roundId)
        assertEquals(9, result.remainingSlots)
        assertEquals(45L, result.remainingCredits)
        assertTrue(result.isValidEvidenceFor("req-uuid-789", "camp-test-1", 5, 10))
    }

    @Test
    fun `reconcileCampaignTesterBonus found false decoding`() = runBlocking {
        val mockClient = createTestClient { request ->
            if (request.url.encodedPath.contains("reconcile_campaign_tester_bonus")) {
                reply("""{"found": false}""")
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val repository = CampaignRepository(mockClient, localizer)
        val result = repository.reconcileCampaignTesterBonus(
            campaignId = "camp-test-1",
            clientRequestId = "req-uuid-unknown",
            expectedRewardCredits = 5,
            expectedMaxRecipients = 10,
        )

        assertFalse(result.found)
        assertFalse(result.replayed)
        assertNull(result.roundId)
        assertFalse(result.isValidEvidenceFor("req-uuid-unknown", "camp-test-1", 5, 10))
    }

    @Test
    fun `getCampaignTesterBonus returns dual projection for owner and tester`() = runBlocking {
        val mockClient = createTestClient { request ->
            if (request.url.encodedPath.contains("get_campaign_tester_bonus")) {
                reply(
                    """
                    {
                        "has_round": true,
                        "is_owner": true,
                        "round_id": "round-uuid-123",
                        "campaign_id": "camp-test-1",
                        "reward_credits": 4,
                        "max_recipients": 15,
                        "paid_count": 3,
                        "remaining_slots": 12,
                        "status": "active",
                        "reserved_credits": 60,
                        "remaining_credits": 48,
                        "spent_credits": 12,
                        "refunded_credits": 0,
                        "my_awarded": true,
                        "my_award_amount": 4,
                        "my_awarded_at": "2026-11-11T12:00:00Z",
                        "my_pending_eligible": false
                    }
                    """
                )
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val repository = CampaignRepository(mockClient, localizer)
        val result = repository.getCampaignTesterBonus("camp-test-1")

        assertTrue(result.hasRound)
        assertTrue(result.isOwner)
        assertTrue(result.isUnsettled)
        assertFalse(result.isTerminal)
        assertEquals("round-uuid-123", result.roundId)
        assertEquals(4, result.rewardCredits)
        assertEquals(15, result.maxRecipients)
        assertEquals(3, result.paidCount)
        assertEquals(12, result.remainingSlots)
        assertEquals(60L, result.reservedCredits)
        assertTrue(result.myAwarded)
        assertEquals(4, result.myAwardAmount)
    }

    @Test
    fun `missing RPC 404 or PGRST202 is converted to server_update_required`() = runBlocking {
        val mockClient = createTestClient { _ ->
            reply(
                body = """{"code":"PGRST202","message":"Could not find the function public.create_campaign_tester_bonus"}""",
                status = HttpStatusCode.NotFound
            )
        }

        val repository = CampaignRepository(mockClient, localizer)
        try {
            repository.createCampaignTesterBonus("camp-1", 5, 10, "req-1")
            fail("Expected exception for missing RPC")
        } catch (e: Exception) {
            assertTrue("Expected server_update_required error but got ${e.message}", e.message?.contains("server_update_required") == true)
        }
    }

    @Test
    fun `legacy schema projection fallback on tester_bonus column missing 42703 error`() = runBlocking {
        var firstQueryRan = false
        var fallbackQueryRan = false

        val mockClient = createTestClient { request ->
            if (request.url.encodedPath.contains("campaigns")) {
                val select = request.url.parameters["select"] ?: ""
                if (select.contains("tester_bonus_credits")) {
                    firstQueryRan = true
                    // Explicitly mentions tester_bonus_credits
                    reply(
                        body = """{"code":"42703","message":"column campaigns.tester_bonus_credits does not exist"}""",
                        status = HttpStatusCode.BadRequest
                    )
                } else {
                    fallbackQueryRan = true
                    reply(
                        """
                        [
                            {
                                "id": "camp-legacy-1",
                                "app_name": "Legacy App",
                                "package_name": "com.legacy.app",
                                "status": "open",
                                "target_testers": 12,
                                "target_days": 14
                            }
                        ]
                        """
                    )
                }
            } else {
                reply("[]")
            }
        }

        val repository = CampaignRepository(mockClient, localizer)
        val openCampaigns = repository.listOpenCampaigns(forceRefresh = true)

        assertTrue("First query with bonus columns should have been attempted", firstQueryRan)
        assertTrue("Fallback query without bonus columns should have run", fallbackQueryRan)
        assertEquals(1, openCampaigns.size)
        assertEquals("camp-legacy-1", openCampaigns[0].id)
        assertFalse(openCampaigns[0].hasActiveTesterBonus)
    }

    @Test
    fun `error 42703 for unrelated column does NOT trigger bonus fallback and propagates`() = runBlocking {
        val mockClient = createTestClient { request ->
            if (request.url.encodedPath.contains("campaigns")) {
                // 42703 for unrelated column
                reply(
                    body = """{"code":"42703","message":"column campaigns.unrelated_field does not exist"}""",
                    status = HttpStatusCode.BadRequest
                )
            } else {
                reply("[]")
            }
        }

        val repository = CampaignRepository(mockClient, localizer)
        try {
            repository.listOpenCampaigns(forceRefresh = true)
            fail("Expected 42703 for unrelated field to propagate without fallback")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("unrelated_field") == true)
            assertTrue("Capability should still remain supported", repository.bonusColumnsSupported)
        }
    }

    @Test
    fun `cumulative earned Long arithmetic boundary handles values exceeding Int MAX_VALUE without wrap`() {
        val report = TesterReport(
            id = "rep-large-1",
            campaignId = "camp-1",
            userId = "tester-1",
            bonusAwarded = 12,
            provisionalGrantedCredits = 0,
            testerBonusAwarded = 2147483640,
            status = "approved"
        )

        // 12 + 2147483640 = 2147483652L (> Int.MAX_VALUE 2147483647)
        val approvedReward = report.approvedReward
        assertEquals(2147483652L, approvedReward)
        assertTrue(approvedReward > Int.MAX_VALUE.toLong())
        assertEquals(2147483652L, report.totalEarned)
    }

    @Test
    fun `newer wallet mutation during create RPC prevents stale cached balance overwrite`() = runBlocking {
        val rpcStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val userId = "user-owner-1"
        val mockClient = createTestClient(userId = userId) { request ->
            if (request.url.encodedPath.contains("create_campaign_tester_bonus")) {
                rpcStarted.complete(Unit)
                gate.await()
                reply(
                    """
                    {
                        "success": true,
                        "replayed": false,
                        "round_id": "round-new-1",
                        "client_request_id": "req-1",
                        "campaign_id": "camp-1",
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

        val repository = CampaignRepository(mockClient, localizer)

        val currentGen = SessionCacheManager.currentGeneration()
        val initialData = CachedSessionData(
            userId = userId,
            userCredits = 500,
            userProfile = Profile(id = userId, credits = 500),
            sessionGeneration = currentGen,
        )
        SessionCacheManager.save(RuntimeEnvironment.getApplication(), initialData)

        val createDeferred = async(Dispatchers.IO) {
            repository.createCampaignTesterBonus("camp-1", 10, 10, "req-1")
        }

        rpcStarted.await()

        // Simulate a newer wallet mutation (e.g., check-in credit or purchase arrived with 450 credits)
        repository.bumpMutationVersion("profile")
        SessionCacheManager.updateCachedData(userId, currentGen) {
            it.copy(userCredits = 450, userProfile = Profile(id = userId, credits = 450))
        }

        // Release delayed RPC response
        gate.complete(Unit)
        val result = createDeferred.await()

        assertTrue(result.success)
        // Because a newer mutation occurred, the cached session data must retain 450 instead of being overwritten with 400!
        val cached = SessionCacheManager.peekMemory()
        assertEquals(450, cached?.userCredits)
    }

    @Test
    fun `budget, wallet, and counter inconsistencies reject evidence and safeOwnerBalanceInt never clamps`() {
        val base = CreateTesterBonusResult(
            success = true,
            roundId = "round-1",
            clientRequestId = "req-1",
            campaignId = "camp-1",
            rewardCredits = 10,
            maxRecipients = 5,
            paidCount = 1,
            remainingSlots = 4,
            reservedCredits = 50L,
            remainingCredits = 40L,
            spentCredits = 10L,
            refundedCredits = 0L,
            ownerBalance = 100L,
            status = "active",
        )
        assertTrue(base.isValidEvidenceFor("req-1", "camp-1", 10, 5))

        // Negative wallet
        val negWallet = base.copy(ownerBalance = -5L)
        assertFalse(negWallet.isValidEvidenceFor("req-1", "camp-1", 10, 5))
        assertNull(negWallet.safeOwnerBalanceInt)

        // Wallet exceeding Int.MAX_VALUE
        val overflowWallet = base.copy(ownerBalance = Int.MAX_VALUE.toLong() + 100L)
        assertFalse(overflowWallet.isValidEvidenceFor("req-1", "camp-1", 10, 5))
        assertNull(overflowWallet.safeOwnerBalanceInt)

        // Missing/null wallet
        val nullWallet = base.copy(ownerBalance = null)
        assertFalse(nullWallet.isValidEvidenceFor("req-1", "camp-1", 10, 5))
        assertNull(nullWallet.safeOwnerBalanceInt)

        // Reserved != product
        assertFalse(base.copy(reservedCredits = 51L).isValidEvidenceFor("req-1", "camp-1", 10, 5))

        // Product exceeds Int.MAX_VALUE
        val hugeProduct = base.copy(rewardCredits = 1_000_000, maxRecipients = 3000, reservedCredits = 3_000_000_000L)
        assertFalse(hugeProduct.isValidEvidenceFor("req-1", "camp-1", 1_000_000, 3000))

        // Inconsistent remaining slots (remainingSlots != max - paid)
        assertFalse(base.copy(remainingSlots = 3).isValidEvidenceFor("req-1", "camp-1", 10, 5))

        // Inconsistent spent credits (spent != reward * paid)
        assertFalse(base.copy(spentCredits = 15L).isValidEvidenceFor("req-1", "camp-1", 10, 5))

        // Inconsistent conservation: reserved != remaining + spent + refunded
        assertFalse(base.copy(remainingCredits = 35L).isValidEvidenceFor("req-1", "camp-1", 10, 5))

        // Negative counter fields
        assertFalse(base.copy(remainingCredits = -1L).isValidEvidenceFor("req-1", "camp-1", 10, 5))
        assertFalse(base.copy(spentCredits = -1L).isValidEvidenceFor("req-1", "camp-1", 10, 5))
        assertFalse(base.copy(refundedCredits = -1L).isValidEvidenceFor("req-1", "camp-1", 10, 5))

        // Unrecognized status
        assertFalse(base.copy(status = "unknown_status").isValidEvidenceFor("req-1", "camp-1", 10, 5))

        // Closed status with remaining credits > 0
        assertFalse(base.copy(status = "closed", remainingCredits = 40L).isValidEvidenceFor("req-1", "camp-1", 10, 5))

        // Exhausted status with paid < max
        assertFalse(base.copy(status = "exhausted", paidCount = 1).isValidEvidenceFor("req-1", "camp-1", 10, 5))

        // Active status with non-zero refunded
        assertFalse(base.copy(status = "active", refundedCredits = 10L, remainingCredits = 30L).isValidEvidenceFor("req-1", "camp-1", 10, 5))
    }

    @Test
    fun `coherent wrong reconcile payload retains intent and does not write wallet`() = runBlocking {
        val userId = "user-owner-1"
        val mockClient = createTestClient(userId = userId) { request ->
            if (request.url.encodedPath.contains("reconcile_campaign_tester_bonus")) {
                reply(
                    """
                    {
                        "found": true,
                        "replayed": true,
                        "round_id": "round-wrong-payload",
                        "client_request_id": "req-mismatch-1",
                        "campaign_id": "camp-test-1",
                        "reward_credits": 99,
                        "max_recipients": 99,
                        "paid_count": 0,
                        "remaining_slots": 99,
                        "reserved_credits": 9801,
                        "remaining_credits": 9801,
                        "spent_credits": 0,
                        "refunded_credits": 0,
                        "owner_balance": 9999,
                        "status": "active"
                    }
                    """
                )
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }

        val repository = CampaignRepository(mockClient, localizer)
        val initialData = CachedSessionData(
            userId = userId,
            userCredits = 500,
            userProfile = Profile(id = userId, credits = 500),
            sessionGeneration = SessionCacheManager.currentGeneration(),
        )
        SessionCacheManager.save(RuntimeEnvironment.getApplication(), initialData)

        val result = repository.reconcileCampaignTesterBonus(
            campaignId = "camp-test-1",
            clientRequestId = "req-mismatch-1",
            expectedRewardCredits = 5,
            expectedMaxRecipients = 10,
        )

        // Evidence validation must fail due to reward/max mismatch
        assertFalse(result.isValidEvidenceFor("req-mismatch-1", "camp-test-1", 5, 10))

        // Cache must NOT have been updated with 9999
        val cached = SessionCacheManager.peekMemory()
        assertEquals(500, cached?.userCredits)
    }

    @Test
    fun `valid closed partial refund replay passes strict validation`() {
        val closedReconcile = ReconcileTesterBonusResult(
            found = true,
            replayed = true,
            roundId = "round-closed-1",
            clientRequestId = "req-closed-1",
            campaignId = "camp-test-1",
            rewardCredits = 10,
            maxRecipients = 5,
            paidCount = 3,
            remainingSlots = 2,
            reservedCredits = 50L,
            remainingCredits = 0L,
            spentCredits = 30L,
            refundedCredits = 20L,
            ownerBalance = 480L,
            status = "closed",
        )

        assertTrue(
            "Valid closed partial refund replay must pass strict evidence check",
            closedReconcile.isValidEvidenceFor("req-closed-1", "camp-test-1", 10, 5)
        )
        assertEquals(480, closedReconcile.safeOwnerBalanceInt)
    }
}
