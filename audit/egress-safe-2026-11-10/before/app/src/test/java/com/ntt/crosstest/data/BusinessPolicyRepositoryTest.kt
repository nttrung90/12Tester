package com.ntt.crosstest.data

import android.app.Application
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BusinessPolicyRepositoryTest {

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

    private val sampleConfigJson = """
        {
            "today": "2026-11-01",
            "timezone": "Asia/Ho_Chi_Minh",
            "revision": "rev-cas-hash-12345",
            "active_policy": {
                "id": 1,
                "effective_from": null,
                "is_baseline": true,
                "welcome_credits": 100,
                "daily_penalty_tiers": {
                    "tier1_pct": 1,
                    "tier2_pct": 2,
                    "tier3_pct": 5,
                    "tier4_pct": 10
                },
                "push_fee": 100,
                "slot_fees": {
                    "slot_2": 50,
                    "slot_3": 100,
                    "slot_4_plus": 200,
                    "renewal_days": 30
                },
                "report_rewards": {
                    "provisional_reward": 10,
                    "review_tester_bonus": 2,
                    "review_owner_bonus": 2
                },
                "app_update_rewards": {
                    "reward_per_claim": 6,
                    "max_claims": 20,
                    "reserved_budget": 120
                },
                "operator_note": "Baseline default policy",
                "created_at": "2026-11-01T00:00:00Z",
                "updated_at": "2026-11-01T00:00:00Z"
            },
            "future_policies": [
                {
                    "id": 2,
                    "effective_from": "2026-12-01",
                    "is_baseline": false,
                    "welcome_credits": 150,
                    "daily_penalty_tiers": {
                        "tier1_pct": 2,
                        "tier2_pct": 3,
                        "tier3_pct": 6,
                        "tier4_pct": 12
                    },
                    "push_fee": 120,
                    "slot_fees": {
                        "slot_2": 60,
                        "slot_3": 120,
                        "slot_4_plus": 250,
                        "renewal_days": 45
                    },
                    "report_rewards": {
                        "provisional_reward": 15,
                        "review_tester_bonus": 3,
                        "review_owner_bonus": 3
                    },
                    "app_update_rewards": {
                        "reward_per_claim": 8,
                        "max_claims": 25,
                        "reserved_budget": 200
                    },
                    "operator_note": "December adjustment",
                    "created_at": "2026-11-01T05:00:00Z",
                    "updated_at": "2026-11-01T05:00:00Z"
                }
            ],
            "future_has_more": false,
            "future_next_cursor": null
        }
    """.trimIndent()

    @Test
    fun getBusinessPolicyConfig_invokesCorrectRpcAndDecodesPayload() = runTest {
        var requestedRpc = ""
        val client = createClient { request ->
            requestedRpc = request.url.encodedPath
            reply(sampleConfigJson)
        }
        val repository = BusinessPolicyRepository(client)

        val config = repository.getBusinessPolicyConfig(futureLimit = 20)

        assertTrue(requestedRpc.endsWith("/rpc/admin_get_business_policy_config"))
        assertEquals("2026-11-01", config.today)
        assertEquals("rev-cas-hash-12345", config.revision)
        assertNotNull(config.activePolicy)
        assertEquals(100, config.activePolicy!!.welcomeCredits)
        assertEquals(100, config.activePolicy!!.pushFee)
        assertEquals(50, config.activePolicy!!.slotFees.slot2)
        assertEquals(30, config.activePolicy!!.slotFees.renewalDays)
        assertEquals(120, config.activePolicy!!.derivedUpdateBudget)

        assertEquals(1, config.futurePolicies.size)
        val future = config.futurePolicies[0]
        assertEquals("2026-12-01", future.effectiveFrom)
        assertEquals(150, future.welcomeCredits)
        assertEquals(120, future.pushFee)
        assertEquals(60, future.slotFees.slot2)
        assertEquals(45, future.slotFees.renewalDays)
        assertEquals(8 * 25, future.derivedUpdateBudget)
    }

    @Test
    fun scheduleBusinessPolicy_sendsExactParametersIncludingRequestIdAndRevision() = runTest {
        var capturedBody = ""
        val client = createClient { request ->
            capturedBody = extractBodyText(request)
            reply(sampleConfigJson)
        }
        val repository = BusinessPolicyRepository(client)

        val params = AdminScheduleBusinessPolicyParams(
            effectiveFrom = "2026-12-01",
            operatorNote = "Schedule adjustment",
            expectedRevision = "rev-cas-hash-12345",
            welcomeCredits = 150,
            penaltyTier1Pct = 2,
            penaltyTier2Pct = 3,
            penaltyTier3Pct = 6,
            penaltyTier4Pct = 12,
            pushFee = 120,
            slotFee2 = 60,
            slotFee3 = 120,
            slotFee4Plus = 250,
            slotRenewalDays = 45,
            reportProvisionalReward = 15,
            reportReviewTesterBonus = 3,
            reportReviewOwnerBonus = 3,
            appUpdateRewardPerClaim = 8,
            appUpdateMaxClaims = 25,
            requestId = "req-uuid-schedule-1",
        )

        val response = repository.scheduleBusinessPolicy(params)
        assertNotNull(response)

        val jsonObj = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("2026-12-01", jsonObj["p_effective_from"]?.jsonPrimitive?.content)
        assertEquals("Schedule adjustment", jsonObj["p_operator_note"]?.jsonPrimitive?.content)
        assertEquals("rev-cas-hash-12345", jsonObj["p_expected_revision"]?.jsonPrimitive?.content)
        assertEquals("150", jsonObj["p_welcome_credits"]?.jsonPrimitive?.content)
        assertEquals("120", jsonObj["p_push_fee"]?.jsonPrimitive?.content)
        assertEquals("60", jsonObj["p_slot_fee_2"]?.jsonPrimitive?.content)
        assertEquals("45", jsonObj["p_slot_renewal_days"]?.jsonPrimitive?.content)
        assertEquals("8", jsonObj["p_app_update_reward_per_claim"]?.jsonPrimitive?.content)
        assertEquals("25", jsonObj["p_app_update_max_claims"]?.jsonPrimitive?.content)
        assertEquals("req-uuid-schedule-1", jsonObj["p_request_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun cancelBusinessPolicy_sendsExactParameters() = runTest {
        var capturedBody = ""
        val client = createClient { request ->
            capturedBody = extractBodyText(request)
            reply(sampleConfigJson)
        }
        val repository = BusinessPolicyRepository(client)

        val response = repository.cancelBusinessPolicy(
            effectiveFrom = "2026-12-01",
            operatorNote = "Cancel reason",
            expectedRevision = "rev-cas-hash-12345",
            requestId = "req-uuid-cancel-1"
        )
        assertNotNull(response)

        val jsonObj = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("2026-12-01", jsonObj["p_effective_from"]?.jsonPrimitive?.content)
        assertEquals("Cancel reason", jsonObj["p_operator_note"]?.jsonPrimitive?.content)
        assertEquals("rev-cas-hash-12345", jsonObj["p_expected_revision"]?.jsonPrimitive?.content)
        assertEquals("req-uuid-cancel-1", jsonObj["p_request_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun getPushQuote_decodesCostAndOpaqueRevisionToken() = runTest {
        val client = createClient {
            reply("""{"quote_type":"push","cost":150,"revision":"quote-token-push-99"}""")
        }
        val repository = BusinessPolicyRepository(client)

        val quote = repository.getPushQuote()
        assertEquals("push", quote.quoteType)
        assertEquals(150, quote.cost)
        assertEquals("quote-token-push-99", quote.revision)
    }

    @Test
    fun getSlotQuote_sendsSlotNumberAndDecodesNonDefaultDuration() = runTest {
        var capturedBody = ""
        val client = createClient { request ->
            capturedBody = extractBodyText(request)
            reply("""{"quote_type":"slot","slot_number":3,"cost":120,"renewal_days":45,"revision":"quote-token-slot-45"}""")
        }
        val repository = BusinessPolicyRepository(client)

        val quote = repository.getSlotQuote(slotNumber = 3)
        assertEquals("slot", quote.quoteType)
        assertEquals(3, quote.slotNumber)
        assertEquals(120, quote.cost)
        assertEquals(45, quote.renewalDays)
        assertEquals("quote-token-slot-45", quote.revision)

        val jsonObj = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("slot", jsonObj["p_quote_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun getAppUpdateQuote_decodesDerivedBudgetAndTerms() = runTest {
        val client = createClient {
            reply("""{"quote_type":"app_update","reward_per_claim":8,"max_claims":25,"reserved_budget":200,"revision":"quote-token-appupdate-200"}""")
        }
        val repository = BusinessPolicyRepository(client)

        val quote = repository.getAppUpdateQuote()
        assertEquals("app_update", quote.quoteType)
        assertEquals(8, quote.rewardPerClaim)
        assertEquals(25, quote.maxClaims)
        assertEquals(200, quote.reservedBudget)
        assertEquals("quote-token-appupdate-200", quote.revision)
    }

    @Test
    fun pushCampaignV2_sendsExpectedCostAndRevisionAndHandlesReplayed() = runTest {
        var capturedBody = ""
        val client = createClient { request ->
            capturedBody = extractBodyText(request)
            reply("""{
                "success": true,
                "receipt_id": "receipt-1",
                "client_request_id": "req-push-1",
                "campaign_id": "camp-1",
                "charged_credits": 150,
                "balance_after": 500,
                "business_date": "2026-11-01",
                "expires_at": "2026-11-01T23:59:59Z",
                "push_order": 7,
                "replayed": true
            }""")
        }
        val repository = BusinessPolicyRepository(client)

        val result = repository.pushCampaignV2(
            campaignId = "camp-1",
            clientRequestId = "req-push-1",
            expectedCost = 150,
            expectedRevision = "quote-token-push-99",
        )

        assertTrue(result.success)
        assertTrue(result.replayed)
        assertEquals(150, result.chargedCredits)
        assertEquals(500, result.balanceAfter)

        val jsonObj = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("camp-1", jsonObj["p_campaign_id"]?.jsonPrimitive?.content)
        assertEquals("req-push-1", jsonObj["p_client_request_id"]?.jsonPrimitive?.content)
        assertEquals("150", jsonObj["p_expected_cost"]?.jsonPrimitive?.content)
        assertEquals("quote-token-push-99", jsonObj["p_expected_revision"]?.jsonPrimitive?.content)
    }

    @Test
    fun unlockOrRenewSlotV2_sendsExactParameters() = runTest {
        var capturedBody = ""
        val client = createClient { request ->
            capturedBody = extractBodyText(request)
            reply("""{
                "success": true,
                "slot_number": 3,
                "cost": 120,
                "balance": 380,
                "expires_at": "2026-12-16T00:00:00Z",
                "replayed": false,
                "renewal_days": 45
            }""")
        }
        val repository = BusinessPolicyRepository(client)

        val result = repository.unlockOrRenewSlotV2(
            slotNumber = 3,
            clientRequestId = "req-slot-1",
            expectedCost = 120,
            expectedRevision = "quote-token-slot-45",
        )

        assertTrue(result.success)
        assertFalse(result.replayed)
        assertEquals(3, result.slotNumber)
        assertEquals(120, result.cost)
        assertEquals(380, result.balance)
        assertEquals(45, result.renewalDays)

        val jsonObj = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("3", jsonObj["p_slot_number"]?.jsonPrimitive?.content)
        assertEquals("req-slot-1", jsonObj["p_client_request_id"]?.jsonPrimitive?.content)
        assertEquals("120", jsonObj["p_expected_cost"]?.jsonPrimitive?.content)
        assertEquals("quote-token-slot-45", jsonObj["p_expected_revision"]?.jsonPrimitive?.content)
    }

    @Test
    fun publishAppUpdateV2_sendsExpectedBudgetAndRevision() = runTest {
        var capturedBody = ""
        val client = createClient { request ->
            capturedBody = extractBodyText(request)
            reply("""{
                "success": true,
                "owner_credits": 800,
                "refund_amount": 0,
                "idempotent_replay": false,
                "release": {
                    "id": "rel-new-1",
                    "campaign_id": "camp-1",
                    "version_name": "2.0.0",
                    "target_version_code": 20,
                    "release_notes": "Notes",
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
            versionName = "2.0.0",
            targetVersionCode = 20L,
            releaseNotes = "Notes",
            clientOperationId = "op-uuid-1",
            expectedBudget = 200,
            expectedRevision = "quote-token-appupdate-200",
        )

        assertTrue(result.success)
        assertNotNull(result.release)
        assertEquals(200, result.release!!.reservedCredits)
        assertEquals(8, result.release!!.rewardPerClaim)
        assertEquals(25, result.release!!.maxClaims)

        val jsonObj = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("camp-1", jsonObj["p_campaign_id"]?.jsonPrimitive?.content)
        assertEquals("200", jsonObj["p_expected_budget"]?.jsonPrimitive?.content)
        assertEquals("quote-token-appupdate-200", jsonObj["p_expected_revision"]?.jsonPrimitive?.content)
        assertTrue(jsonObj.containsKey("p_expected_current_release_id"))
        assertTrue(jsonObj["p_expected_current_release_id"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun noFakedDefaultPolicyOnNetworkErrorForPurchases() = runTest {
        val client = createClient {
            respond("Internal Server Error", HttpStatusCode.InternalServerError)
        }
        val repository = BusinessPolicyRepository(client)

        try {
            repository.getEffectiveBusinessPolicy(forceRefresh = true)
            fail("Expected exception on network failure, must not return fake default policy")
        } catch (e: Exception) {
            assertTrue(e !is NullPointerException)
        }

        try {
            repository.getPushQuote()
            fail("Expected exception on quote failure, must not return fake default quote")
        } catch (e: Exception) {
            assertTrue(e !is NullPointerException)
        }
    }
}
