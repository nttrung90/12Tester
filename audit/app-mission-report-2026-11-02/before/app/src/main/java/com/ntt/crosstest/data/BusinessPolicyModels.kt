package com.ntt.crosstest.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DailyPenaltyTiers(
    @SerialName("tier1_pct") val tier1Pct: Int = 1,
    @SerialName("tier2_pct") val tier2Pct: Int = 2,
    @SerialName("tier3_pct") val tier3Pct: Int = 5,
    @SerialName("tier4_pct") val tier4Pct: Int = 10,
) {
    fun isValid(): Boolean {
        return tier1Pct in 0..100 &&
                tier2Pct in 0..100 &&
                tier3Pct in 0..100 &&
                tier4Pct in 0..100 &&
                tier1Pct <= tier2Pct &&
                tier2Pct <= tier3Pct &&
                tier3Pct <= tier4Pct
    }
}

@Serializable
data class SlotFees(
    @SerialName("slot_2") val slot2: Int = 50,
    @SerialName("slot_3") val slot3: Int = 100,
    @SerialName("slot_4_plus") val slot4Plus: Int = 200,
    @SerialName("renewal_days") val renewalDays: Int = 30,
) {
    fun isValid(): Boolean {
        return slot2 in 1..10000 &&
                slot3 in 1..10000 &&
                slot4Plus in 1..10000 &&
                renewalDays in 1..365
    }

    fun feeForSlot(slotNumber: Int): Int = when (slotNumber) {
        1 -> 0
        2 -> slot2
        3 -> slot3
        else -> slot4Plus
    }
}

@Serializable
data class ReportRewards(
    @SerialName("provisional_reward") val provisionalReward: Int = 10,
    @SerialName("review_tester_bonus") val reviewTesterBonus: Int = 2,
    @SerialName("review_owner_bonus") val reviewOwnerBonus: Int = 2,
) {
    fun isValid(): Boolean {
        return provisionalReward in 0..1000 &&
                reviewTesterBonus in 0..1000 &&
                reviewOwnerBonus in 0..1000
    }
}

@Serializable
data class AppUpdateRewards(
    @SerialName("reward_per_claim") val rewardPerClaim: Int = 6,
    @SerialName("max_claims") val maxClaims: Int = 20,
    @SerialName("reserved_budget") val reservedBudget: Int = 120,
) {
    fun isValid(): Boolean {
        return rewardPerClaim in 1..1000 &&
                maxClaims in 1..1000 &&
                reservedBudget == rewardPerClaim * maxClaims
    }
}

@Serializable
data class BusinessPolicy(
    @SerialName("id") val id: Long = 0,
    @SerialName("effective_from") val effectiveFrom: String? = null,
    @SerialName("is_baseline") val isBaseline: Boolean = false,
    @SerialName("welcome_credits") val welcomeCredits: Int = 100,
    @SerialName("daily_penalty_tiers") val dailyPenaltyTiers: DailyPenaltyTiers = DailyPenaltyTiers(),
    @SerialName("push_fee") val pushFee: Int = 100,
    @SerialName("slot_fees") val slotFees: SlotFees = SlotFees(),
    @SerialName("report_rewards") val reportRewards: ReportRewards = ReportRewards(),
    @SerialName("app_update_rewards") val appUpdateRewards: AppUpdateRewards = AppUpdateRewards(),
    @SerialName("operator_note") val operatorNote: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val derivedUpdateBudget: Int get() = appUpdateRewards.rewardPerClaim * appUpdateRewards.maxClaims

    fun isAllValid(): Boolean {
        return welcomeCredits in 0..10000 &&
                pushFee in 1..10000 &&
                dailyPenaltyTiers.isValid() &&
                slotFees.isValid() &&
                reportRewards.isValid() &&
                appUpdateRewards.rewardPerClaim in 1..1000 &&
                appUpdateRewards.maxClaims in 1..1000 &&
                operatorNote.isNotBlank() && operatorNote.length <= 1000
    }
}

@Serializable
data class BusinessPolicyConfigResponse(
    @SerialName("today") val today: String = "",
    @SerialName("timezone") val timezone: String = "Asia/Ho_Chi_Minh",
    @SerialName("revision") val revision: String = "",
    @SerialName("active_policy") val activePolicy: BusinessPolicy? = null,
    @SerialName("future_policies") val futurePolicies: List<BusinessPolicy> = emptyList(),
    @SerialName("future_has_more") val futureHasMore: Boolean = false,
    @SerialName("future_next_cursor") val futureNextCursor: String? = null,
)

@Serializable
data class BusinessPolicyListEnvelope(
    @SerialName("active_policy") val activePolicy: BusinessPolicy? = null,
    @SerialName("future_policies") val futurePolicies: List<BusinessPolicy> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class BusinessPolicyHistoryItem(
    @SerialName("id") val id: Long,
    @SerialName("policy_id") val policyId: Long,
    @SerialName("operation") val operation: String,
    @SerialName("operator_note") val operatorNote: String,
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("changed_at") val changedAt: String,
    @SerialName("old_row") val oldRow: JsonElement? = null,
    @SerialName("new_row") val newRow: JsonElement? = null,
)

@Serializable
data class BusinessPolicyHistoryEnvelope(
    @SerialName("items") val items: List<BusinessPolicyHistoryItem> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

// -----------------------------------------------------------------------------
// Quotes (get_business_quote)
// -----------------------------------------------------------------------------

@Serializable
data class PushQuote(
    @SerialName("quote_type") val quoteType: String = "push",
    @SerialName("cost") val cost: Int = 100,
    @SerialName("revision") val revision: String = "",
)

@Serializable
data class SlotQuote(
    @SerialName("quote_type") val quoteType: String = "slot",
    @SerialName("slot_number") val slotNumber: Int = 2,
    @SerialName("cost") val cost: Int = 50,
    @SerialName("renewal_days") val renewalDays: Int = 30,
    @SerialName("revision") val revision: String = "",
)

@Serializable
data class AppUpdateQuote(
    @SerialName("quote_type") val quoteType: String = "app_update",
    @SerialName("reward_per_claim") val rewardPerClaim: Int = 6,
    @SerialName("max_claims") val maxClaims: Int = 20,
    @SerialName("reserved_budget") val reservedBudget: Int = 120,
    @SerialName("revision") val revision: String = "",
)

@Serializable
data class AllBusinessQuote(
    @SerialName("quote_type") val quoteType: String = "all",
    @SerialName("revision") val revision: String = "",
    @SerialName("push_fee") val pushFee: Int = 100,
    @SerialName("slot_fees") val slotFees: SlotFees = SlotFees(),
    @SerialName("app_update_rewards") val appUpdateRewards: AppUpdateRewards = AppUpdateRewards(),
    @SerialName("report_rewards") val reportRewards: ReportRewards = ReportRewards(),
)

// -----------------------------------------------------------------------------
// RPC Parameter DTOs
// -----------------------------------------------------------------------------

@Serializable
data class AdminGetBusinessPolicyConfigParams(
    @SerialName("p_future_limit") val futureLimit: Int = 20,
)

@Serializable
data class AdminScheduleBusinessPolicyParams(
    @SerialName("p_effective_from") val effectiveFrom: String,
    @SerialName("p_operator_note") val operatorNote: String,
    @SerialName("p_expected_revision") val expectedRevision: String,
    @SerialName("p_welcome_credits") val welcomeCredits: Int = 100,
    @SerialName("p_penalty_tier1_pct") val penaltyTier1Pct: Int = 1,
    @SerialName("p_penalty_tier2_pct") val penaltyTier2Pct: Int = 2,
    @SerialName("p_penalty_tier3_pct") val penaltyTier3Pct: Int = 5,
    @SerialName("p_penalty_tier4_pct") val penaltyTier4Pct: Int = 10,
    @SerialName("p_push_fee") val pushFee: Int = 100,
    @SerialName("p_slot_fee_2") val slotFee2: Int = 50,
    @SerialName("p_slot_fee_3") val slotFee3: Int = 100,
    @SerialName("p_slot_fee_4_plus") val slotFee4Plus: Int = 200,
    @SerialName("p_slot_renewal_days") val slotRenewalDays: Int = 30,
    @SerialName("p_report_provisional_reward") val reportProvisionalReward: Int = 10,
    @SerialName("p_report_review_tester_bonus") val reportReviewTesterBonus: Int = 2,
    @SerialName("p_report_review_owner_bonus") val reportReviewOwnerBonus: Int = 2,
    @SerialName("p_app_update_reward_per_claim") val appUpdateRewardPerClaim: Int = 6,
    @SerialName("p_app_update_max_claims") val appUpdateMaxClaims: Int = 20,
    @SerialName("p_request_id") val requestId: String? = null,
)

@Serializable
data class AdminCancelBusinessPolicyParams(
    @SerialName("p_effective_from") val effectiveFrom: String,
    @SerialName("p_operator_note") val operatorNote: String,
    @SerialName("p_expected_revision") val expectedRevision: String,
    @SerialName("p_request_id") val requestId: String? = null,
)

@Serializable
data class AdminListBusinessPoliciesParams(
    @SerialName("p_limit") val limit: Int = 20,
    @SerialName("p_cursor_date") val cursorDate: String? = null,
)

@Serializable
data class AdminGetBusinessPolicyHistoryParams(
    @SerialName("p_limit") val limit: Int = 20,
    @SerialName("p_cursor_id") val cursorId: Long? = null,
)

@Serializable
data class GetEffectiveBusinessPolicyParams(
    @SerialName("p_date") val date: String? = null,
)

@Serializable
data class GetBusinessQuoteParams(
    @SerialName("p_quote_type") val quoteType: String,
    @SerialName("p_params") val params: JsonElement? = null,
)

@Serializable
data class PushCampaignV2Params(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_client_request_id") val clientRequestId: String,
    @SerialName("p_expected_cost") val expectedCost: Int? = null,
    @SerialName("p_expected_revision") val expectedRevision: String? = null,
)

@Serializable
data class UnlockOrRenewSlotV2Params(
    @SerialName("p_slot_number") val slotNumber: Int,
    @SerialName("p_client_request_id") val clientRequestId: String,
    @SerialName("p_expected_cost") val expectedCost: Int? = null,
    @SerialName("p_expected_revision") val expectedRevision: String? = null,
)

@Serializable
data class PublishAppUpdateV2Params(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_version_name") val versionName: String,
    @SerialName("p_target_version_code") val targetVersionCode: Long,
    @SerialName("p_release_notes") val releaseNotes: String,
    @SerialName("p_client_operation_id") val clientOperationId: String,
    @SerialName("p_expected_budget") val expectedBudget: Int? = null,
    @SerialName("p_expected_revision") val expectedRevision: String? = null,
    @SerialName("p_expected_current_release_id") val expectedCurrentReleaseId: String? = null,
)
