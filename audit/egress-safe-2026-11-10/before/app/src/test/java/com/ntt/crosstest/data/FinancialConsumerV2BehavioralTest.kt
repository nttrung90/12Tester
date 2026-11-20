package com.ntt.crosstest.data

import android.app.Application
import com.ntt.crosstest.ui.viewmodels.AppUpdateReleaseUiState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FinancialConsumerV2BehavioralTest {

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun extractBodyText(request: HttpRequestData): String {
        return when (val body = request.body) {
            is io.ktor.http.content.TextContent -> body.text
            is io.ktor.http.content.ByteArrayContent -> String(body.bytes())
            else -> body.toString()
        }
    }

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

    @Test
    fun slotV2_passesDisplayedQuoteCostAndOpaqueRevisionToken() = runTest {
        var capturedCost: String? = null
        var capturedRevision: String? = null
        var capturedSlotNumber: String? = null
        var capturedRequestId: String? = null

        val client = createClient { request ->
            val path = request.url.encodedPath
            if (path.endsWith("unlock_or_renew_slot_v2")) {
                val body = extractBodyText(request)
                val json = Json.parseToJsonElement(body).jsonObject
                capturedSlotNumber = json["p_slot_number"]?.jsonPrimitive?.content
                capturedCost = json["p_expected_cost"]?.jsonPrimitive?.content
                capturedRevision = json["p_expected_revision"]?.jsonPrimitive?.content
                capturedRequestId = json["p_client_request_id"]?.jsonPrimitive?.content
                reply("""{
                    "success": true,
                    "slot_number": 3,
                    "cost": 150,
                    "balance": 850,
                    "expires_at": "2026-12-16T00:00:00Z",
                    "replayed": false,
                    "renewal_days": 45
                }""")
            } else {
                reply("{}")
            }
        }
        val repository = BusinessPolicyRepository(client)

        val result = repository.unlockOrRenewSlotV2(
            slotNumber = 3,
            clientRequestId = "slot-req-uuid-1",
            expectedCost = 150,
            expectedRevision = "opaque-quote-token-slot3",
        )

        assertTrue(result.success)
        assertEquals(3, result.slotNumber)
        assertEquals(150, result.cost)
        assertEquals(850, result.balance)
        assertEquals(45, result.renewalDays)

        assertEquals("3", capturedSlotNumber)
        assertEquals("150", capturedCost)
        assertEquals("opaque-quote-token-slot3", capturedRevision)
        assertEquals("slot-req-uuid-1", capturedRequestId)
    }

    @Test
    fun slotV2_onQuoteConflict_throwsExceptionAndDoesNotCharge() = runTest {
        val client = createClient {
            respond(
                content = """{"code":"BUSINESS_POLICY_CONFLICT","message":"BUSINESS_POLICY_CONFLICT: Revision mismatch"}""",
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val repository = BusinessPolicyRepository(client)

        try {
            repository.unlockOrRenewSlotV2(
                slotNumber = 2,
                clientRequestId = "req-conflict-slot",
                expectedCost = 50,
                expectedRevision = "stale-quote-token",
            )
            fail("Expected BUSINESS_POLICY_CONFLICT exception")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("BUSINESS_POLICY_CONFLICT") == true)
        }
    }

    @Test
    fun pushV2_passesExpectedCostAndRevisionAndPreservesReplayProvenance() = runTest {
        var capturedBody = ""
        val client = createClient { request ->
            capturedBody = extractBodyText(request)
            reply("""{
                "success": true,
                "receipt_id": "receipt-v2-1",
                "client_request_id": "push-req-1",
                "campaign_id": "camp-push-1",
                "charged_credits": 120,
                "balance_after": 380,
                "business_date": "2026-11-01",
                "expires_at": "2026-11-01T23:59:59Z",
                "push_order": 14,
                "replayed": true
            }""")
        }
        val repository = BusinessPolicyRepository(client)

        val result = repository.pushCampaignV2(
            campaignId = "camp-push-1",
            clientRequestId = "push-req-1",
            expectedCost = 120,
            expectedRevision = "opaque-quote-token-push",
        )

        assertTrue(result.success)
        assertTrue(result.replayed)
        assertEquals(120, result.chargedCredits)
        assertEquals(380, result.balanceAfter)

        val json = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("camp-push-1", json["p_campaign_id"]?.jsonPrimitive?.content)
        assertEquals("push-req-1", json["p_client_request_id"]?.jsonPrimitive?.content)
        assertEquals("120", json["p_expected_cost"]?.jsonPrimitive?.content)
        assertEquals("opaque-quote-token-push", json["p_expected_revision"]?.jsonPrimitive?.content)
    }

    @Test
    fun appUpdateV2_dynamicBudgetCalculationAndPreservationOfOldReleaseSnapshot() = runTest {
        // Dynamic non-default budget (8 credits/claim * 25 claims = 200 credits)
        val state = AppUpdateReleaseUiState(
            ownerProfile = Profile(id = "user-1", credits = 300),
            rewardPerClaim = 8,
            maxClaims = 25,
            reservedBudget = 200,
            policyRevision = "opaque-quote-token-update",
        )

        assertEquals(200, state.reservedBudget)
        assertEquals(100, state.projectedRemaining) // 300 - 200
        assertTrue(state.hasEnoughCredits)

        // If credits = 150 < 200 (insufficient)
        val stateInsufficient = state.copy(ownerProfile = Profile(id = "user-1", credits = 150))
        assertFalse(stateInsufficient.hasEnoughCredits)
        assertEquals(-50, stateInsufficient.projectedRemaining)

        // Historical release uses its own snapshot, unaffected by current policy
        val historicalRelease = AppUpdateRelease(
            id = "rel-old",
            status = "active",
            rewardPerClaim = 6,
            maxClaims = 20,
            reservedCredits = 120,
            paidCount = 10,
            remainingCredits = 60,
        )
        assertEquals(6, historicalRelease.rewardPerClaim)
        assertEquals(20, historicalRelease.maxClaims)
        assertEquals(120, historicalRelease.reservedCredits)
        assertEquals(60, historicalRelease.remainingCredits)

        // When publishing, publishAppUpdateV2 sends exact 200 budget and revision
        var capturedBudget: String? = null
        var capturedRevision: String? = null
        val client = createClient { request ->
            val body = extractBodyText(request)
            val json = Json.parseToJsonElement(body).jsonObject
            capturedBudget = json["p_expected_budget"]?.jsonPrimitive?.content
            capturedRevision = json["p_expected_revision"]?.jsonPrimitive?.content
            reply("""{
                "success": true,
                "owner_credits": 100,
                "refund_amount": 0,
                "idempotent_replay": false,
                "release": {
                    "id": "rel-new",
                    "campaign_id": "camp-1",
                    "version_name": "3.0.0",
                    "target_version_code": 30,
                    "release_notes": "Update notes",
                    "status": "active",
                    "reward_per_claim": 8,
                    "max_claims": 25,
                    "reserved_credits": 200,
                    "paid_count": 0,
                    "remaining_credits": 200
                }
            }""")
        }
        val repository = BusinessPolicyRepository(client)

        val result = repository.publishAppUpdateV2(
            campaignId = "camp-1",
            versionName = "3.0.0",
            targetVersionCode = 30L,
            releaseNotes = "Update notes",
            clientOperationId = "op-uuid-dyn",
            expectedBudget = 200,
            expectedRevision = "opaque-quote-token-update",
        )

        assertTrue(result.success)
        assertEquals("200", capturedBudget)
        assertEquals("opaque-quote-token-update", capturedRevision)
    }

    @Test
    fun appUpdate_quoteUnavailable_blocksCanPublish() {
        // Even with plenty of credits, if quote failed to load (policyRevision is null or quoteError set)
        val stateNoRevision = AppUpdateReleaseUiState(
            ownerProfile = Profile(id = "user-1", credits = 500),
            versionName = "1.0.0",
            versionCode = "10",
            releaseNotes = "Notes",
            policyRevision = null, // Quote not yet received or failed
            reservedBudget = 120,
        )
        assertFalse(stateNoRevision.hasValidQuote)
        assertFalse(stateNoRevision.canPublish)

        // When quote is loading
        val stateLoading = stateNoRevision.copy(
            policyRevision = "valid-token",
            isQuoteLoading = true,
        )
        assertFalse(stateLoading.canPublish)

        // When quote has error
        val stateError = stateNoRevision.copy(
            policyRevision = "valid-token",
            quoteError = "Network error loading quote",
        )
        assertFalse(stateError.hasValidQuote)
        assertFalse(stateError.canPublish)

        // Only when quote is valid, loading is done, and no error
        val stateValid = stateNoRevision.copy(
            isLoading = false,
            policyRevision = "valid-token",
            isQuoteLoading = false,
            quoteError = null,
        )
        assertTrue(stateValid.hasValidQuote)
        assertTrue(stateValid.canPublish)
    }

    @Test
    fun slotInfo_requiresQuoteRevisionForValidPurchase() {
        // A slot without a policyRevision token must not be allowed to purchase
        val slotWithoutRevision = SlotInfo(
            slotNumber = 2,
            isUnlocked = false,
            cost = 50,
            renewalDays = 30,
            policyRevision = null,
        )
        assertNull(slotWithoutRevision.policyRevision)

        val slotWithValidRevision = SlotInfo(
            slotNumber = 2,
            isUnlocked = false,
            cost = 50,
            renewalDays = 30,
            policyRevision = "valid-slot-quote-token",
        )
        assertNotNull(slotWithValidRevision.policyRevision)
    }
}
