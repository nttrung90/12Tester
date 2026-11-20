package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference

open class BusinessPolicyRepository(
    private val supabase: SupabaseClient,
) {
    private data class CachedPolicy(
        val policy: BusinessPolicy,
        val timestampMs: Long,
        val userId: String?,
    )

    private val cachedPolicyRef = AtomicReference<CachedPolicy?>(null)
    private val policyMutex = Mutex()

    companion object {
        const val POLICY_CACHE_TTL_MS = 30_000L
    }

    // -------------------------------------------------------------------------
    // Admin Operations
    // -------------------------------------------------------------------------

    open suspend fun getBusinessPolicyConfig(futureLimit: Int = 20): BusinessPolicyConfigResponse {
        return try {
            supabase.postgrest.rpc(
                "admin_get_business_policy_config",
                AdminGetBusinessPolicyConfigParams(futureLimit = futureLimit)
            ).decodeAs<BusinessPolicyConfigResponse>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open suspend fun scheduleBusinessPolicy(
        params: AdminScheduleBusinessPolicyParams,
    ): BusinessPolicyConfigResponse {
        return try {
            val response = supabase.postgrest.rpc(
                "admin_schedule_business_policy",
                params
            ).decodeAs<BusinessPolicyConfigResponse>()
            invalidateCache()
            response
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open suspend fun cancelBusinessPolicy(
        effectiveFrom: String,
        operatorNote: String,
        expectedRevision: String,
        requestId: String? = null,
    ): BusinessPolicyConfigResponse {
        return try {
            val response = supabase.postgrest.rpc(
                "admin_cancel_business_policy",
                AdminCancelBusinessPolicyParams(
                    effectiveFrom = effectiveFrom,
                    operatorNote = operatorNote,
                    expectedRevision = expectedRevision,
                    requestId = requestId,
                )
            ).decodeAs<BusinessPolicyConfigResponse>()
            invalidateCache()
            response
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open suspend fun listBusinessPolicies(
        limit: Int = 20,
        cursorDate: String? = null,
    ): BusinessPolicyListEnvelope {
        return try {
            supabase.postgrest.rpc(
                "admin_list_business_policies",
                AdminListBusinessPoliciesParams(
                    limit = limit,
                    cursorDate = cursorDate,
                )
            ).decodeAs<BusinessPolicyListEnvelope>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open suspend fun getBusinessPolicyHistory(
        limit: Int = 20,
        cursorId: Long? = null,
    ): BusinessPolicyHistoryEnvelope {
        return try {
            supabase.postgrest.rpc(
                "admin_get_business_policy_history",
                AdminGetBusinessPolicyHistoryParams(
                    limit = limit,
                    cursorId = cursorId,
                )
            ).decodeAs<BusinessPolicyHistoryEnvelope>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Public / Effective Policy & Quotes
    // -------------------------------------------------------------------------

    open suspend fun getEffectiveBusinessPolicy(
        date: String? = null,
        forceRefresh: Boolean = false,
    ): BusinessPolicy {
        val currentUid = supabase.auth.currentUserOrNull()?.id
        val now = System.currentTimeMillis()
        if (date == null && !forceRefresh) {
            val cached = cachedPolicyRef.get()
            if (cached != null && cached.userId == currentUid && (now - cached.timestampMs) < POLICY_CACHE_TTL_MS) {
                return cached.policy
            }
        }

        return policyMutex.withLock {
            if (date == null && !forceRefresh) {
                val cached = cachedPolicyRef.get()
                if (cached != null && cached.userId == currentUid && (now - cached.timestampMs) < POLICY_CACHE_TTL_MS) {
                    return@withLock cached.policy
                }
            }
            try {
                val policy = supabase.postgrest.rpc(
                    "get_effective_business_policy",
                    GetEffectiveBusinessPolicyParams(date = date)
                ).decodeAs<BusinessPolicy>()
                if (date == null) {
                    cachedPolicyRef.set(CachedPolicy(policy, System.currentTimeMillis(), currentUid))
                }
                policy
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // R01: No faked default policy on network error for purchases
                throw e
            }
        }
    }

    open suspend fun getPushQuote(): PushQuote {
        return try {
            supabase.postgrest.rpc(
                "get_business_quote",
                GetBusinessQuoteParams(quoteType = "push")
            ).decodeAs<PushQuote>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open suspend fun getSlotQuote(slotNumber: Int): SlotQuote {
        return try {
            val params = buildJsonObject {
                put("slot_number", slotNumber)
            }
            supabase.postgrest.rpc(
                "get_business_quote",
                GetBusinessQuoteParams(quoteType = "slot", params = params)
            ).decodeAs<SlotQuote>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open suspend fun getAppUpdateQuote(): AppUpdateQuote {
        return try {
            supabase.postgrest.rpc(
                "get_business_quote",
                GetBusinessQuoteParams(quoteType = "app_update")
            ).decodeAs<AppUpdateQuote>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open suspend fun getAllQuote(): AllBusinessQuote {
        return try {
            supabase.postgrest.rpc(
                "get_business_quote",
                GetBusinessQuoteParams(quoteType = "all")
            ).decodeAs<AllBusinessQuote>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Financial V2 Consumer RPCs
    // -------------------------------------------------------------------------

    open suspend fun pushCampaignV2(
        campaignId: String,
        clientRequestId: String,
        expectedCost: Int? = null,
        expectedRevision: String? = null,
    ): PushCampaignResult {
        return try {
            supabase.postgrest.rpc(
                "push_campaign_v2",
                PushCampaignV2Params(
                    campaignId = campaignId,
                    clientRequestId = clientRequestId,
                    expectedCost = expectedCost,
                    expectedRevision = expectedRevision,
                )
            ).decodeAs<PushCampaignResult>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open suspend fun unlockOrRenewSlotV2(
        slotNumber: Int,
        clientRequestId: String,
        expectedCost: Int? = null,
        expectedRevision: String? = null,
    ): UnlockSlotResult {
        return try {
            supabase.postgrest.rpc(
                "unlock_or_renew_slot_v2",
                UnlockOrRenewSlotV2Params(
                    slotNumber = slotNumber,
                    clientRequestId = clientRequestId,
                    expectedCost = expectedCost,
                    expectedRevision = expectedRevision,
                )
            ).decodeAs<UnlockSlotResult>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open suspend fun publishAppUpdateV2(
        campaignId: String,
        versionName: String,
        targetVersionCode: Long,
        releaseNotes: String,
        clientOperationId: String,
        expectedBudget: Int? = null,
        expectedRevision: String? = null,
        expectedCurrentReleaseId: String? = null,
    ): PublishAppUpdateResult {
        return try {
            val body = buildJsonObject {
                put("p_campaign_id", campaignId)
                put("p_version_name", versionName)
                put("p_target_version_code", targetVersionCode)
                put("p_release_notes", releaseNotes)
                put("p_client_operation_id", clientOperationId)
                if (expectedBudget != null) put("p_expected_budget", expectedBudget) else put("p_expected_budget", kotlinx.serialization.json.JsonNull)
                if (expectedRevision != null) put("p_expected_revision", expectedRevision) else put("p_expected_revision", kotlinx.serialization.json.JsonNull)
                if (expectedCurrentReleaseId != null) put("p_expected_current_release_id", expectedCurrentReleaseId) else put("p_expected_current_release_id", kotlinx.serialization.json.JsonNull)
            }
            supabase.postgrest.rpc("publish_app_update_v2", body).decodeAs<PublishAppUpdateResult>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    open fun invalidateCache() {
        cachedPolicyRef.set(null)
    }
}
