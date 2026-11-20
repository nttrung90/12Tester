package com.ntt.crosstest.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AdminDateTimeCursor(
    @SerialName("created_at") val createdAt: String,
    @SerialName("id") val id: String,
)

@Serializable
data class AdminDateCursor(
    @SerialName("date") val date: String,
)

// ------------------------------------------------------------- Group 1: Audit & Capabilities

@Serializable
data class AdminActionEvent(
    @SerialName("id") val id: String,
    @SerialName("actor_id") val actorId: String,
    @SerialName("actor_email") val actorEmail: String? = null,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("action") val action: String,
    @SerialName("target_type") val targetType: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("old_state") val oldState: JsonElement? = null,
    @SerialName("new_state") val newState: JsonElement? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AdminActionEventsEnvelope(
    @SerialName("items") val items: List<AdminActionEvent> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: AdminDateTimeCursor? = null,
)

@Serializable
data class AdminGetActionEventsParams(
    @SerialName("p_cursor_created_at") val cursorCreatedAt: String? = null,
    @SerialName("p_cursor_id") val cursorId: String? = null,
    @SerialName("p_limit") val limit: Int? = 50,
    @SerialName("p_action") val action: String? = null,
    @SerialName("p_target_type") val targetType: String? = null,
    @SerialName("p_actor_id") val actorId: String? = null,
)

@Serializable
data class AdminMyCapabilities(
    @SerialName("caller_id") val callerId: String,
    @SerialName("role") val role: String,
    @SerialName("is_locked") val isLocked: Boolean = false,
    @SerialName("capabilities") val capabilities: List<String> = emptyList(),
    @SerialName("can_manage_admins") val canManageAdmins: Boolean = false,
    @SerialName("can_manage_moderators") val canManageModerators: Boolean = false,
)

// ------------------------------------------------------------- Group 2: Advanced Members & Lookup

@Serializable
data class AdminMemberItem(
    @SerialName("id") val id: String,
    @SerialName("email") val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("credits") val credits: Int = 0,
    @SerialName("role") val role: String = "member",
    @SerialName("is_locked") val isLocked: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_login_date") val lastLoginDate: String? = null,
    @SerialName("daily_missed_streak") val dailyMissedStreak: Int = 0,
) {
    fun toProfile(): Profile = Profile(
        id = id,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        credits = credits,
        dailyMissedStreak = dailyMissedStreak,
        lastLoginDate = lastLoginDate,
        role = role,
        isLocked = isLocked,
        createdAt = createdAt,
    )
}

@Serializable
data class AdminSearchMembersEnvelope(
    @SerialName("items") val items: List<AdminMemberItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: AdminDateTimeCursor? = null,
)

@Serializable
data class AdminSearchMembersParams(
    @SerialName("p_query") val query: String? = null,
    @SerialName("p_role") val role: String? = null,
    @SerialName("p_is_locked") val isLocked: Boolean? = null,
    @SerialName("p_limit") val limit: Int? = 50,
    @SerialName("p_cursor_created_at") val cursorCreatedAt: String? = null,
    @SerialName("p_cursor_id") val cursorId: String? = null,
)

@Serializable
data class AdminUserIdParams(
    @SerialName("p_user_id") val userId: String,
)

@Serializable
data class AdminMemberSlotCampaign(
    @SerialName("id") val id: String,
    @SerialName("app_name") val appName: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("status") val status: String,
)

@Serializable
data class AdminMemberSlotDetail(
    @SerialName("slot_number") val slotNumber: Int,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("unlocked_at") val unlockedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("current_campaign") val currentCampaign: AdminMemberSlotCampaign? = null,
)

@Serializable
data class AdminMemberSlotsResult(
    @SerialName("user_id") val userId: String,
    @SerialName("slots") val slots: List<AdminMemberSlotDetail> = emptyList(),
    @SerialName("slots_total") val slotsTotal: Int = 0,
    @SerialName("slots_active") val slotsActive: Int = 0,
)

@Serializable
data class AdminMemberOverview(
    @SerialName("id") val id: String,
    @SerialName("email") val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("credits") val credits: Int = 0,
    @SerialName("role") val role: String = "member",
    @SerialName("is_locked") val isLocked: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_login_date") val lastLoginDate: String? = null,
    @SerialName("daily_missed_streak") val dailyMissedStreak: Int = 0,
    @SerialName("campaigns_total") val campaignsTotal: Int = 0,
    @SerialName("campaigns_open") val campaignsOpen: Int = 0,
    @SerialName("slots_total") val slotsTotal: Int = 0,
    @SerialName("slots_active") val slotsActive: Int = 0,
    @SerialName("slots") val slots: List<AdminMemberSlotDetail> = emptyList(),
    @SerialName("warnings_active") val warningsActive: Int = 0,
    @SerialName("warnings_revoked") val warningsRevoked: Int = 0,
    @SerialName("tester_reports_count") val testerReportsCount: Int = 0,
    @SerialName("contact_reports_count") val contactReportsCount: Int = 0,
)

@Serializable
data class AdminCreditHistoryItem(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("amount") val amount: Int,
    @SerialName("action") val action: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("balance") val balance: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("metadata") val metadata: JsonElement? = null,
)

@Serializable
data class AdminCreditHistoryEnvelope(
    @SerialName("items") val items: List<AdminCreditHistoryItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: AdminDateTimeCursor? = null,
)

@Serializable
data class AdminGetMemberCreditHistoryParams(
    @SerialName("p_user_id") val userId: String,
    @SerialName("p_limit") val limit: Int? = 50,
    @SerialName("p_cursor_created_at") val cursorCreatedAt: String? = null,
    @SerialName("p_cursor_id") val cursorId: String? = null,
)

@Serializable
data class AdminMemberDailyTaskExplanation(
    @SerialName("status_date") val statusDate: String? = null,
    @SerialName("mode") val mode: String? = null,
    @SerialName("active_app_count") val activeAppCount: Int = 0,
    @SerialName("required_tests") val requiredTests: Int = 0,
    @SerialName("effective_required_tests") val effectiveRequiredTests: Int = 0,
    @SerialName("completed_tests") val completedTests: Int = 0,
    @SerialName("available_apps") val availableApps: Int = 0,
    @SerialName("logged_in_today") val loggedInToday: Boolean = false,
    @SerialName("is_penalty_exempt") val isPenaltyExempt: Boolean = false,
    @SerialName("missed_streak") val missedStreak: Int = 0,
    @SerialName("met") val met: Boolean = false,
    @SerialName("carry_in_tests") val carryInTests: Int = 0,
    @SerialName("carryover_remaining_tests") val carryoverRemainingTests: Int = 0,
    @SerialName("carryover_ready") val carryoverReady: Boolean = false,
    @SerialName("history_ready") val historyReady: Boolean = false,
)

@Serializable
data class AdminMemberDailyTaskItem(
    @SerialName("task_date") val taskDate: String,
    @SerialName("is_finalized") val isFinalized: Boolean = false,
    @SerialName("finalized_at") val finalizedAt: String? = null,
    @SerialName("penalty_credits") val penaltyCredits: Int = 0,
    @SerialName("incomplete_history") val incompleteHistory: Boolean = false,
    @SerialName("status") val status: AdminMemberDailyTaskExplanation? = null,
)

@Serializable
data class AdminMemberDailyTasksEnvelope(
    @SerialName("items") val items: List<AdminMemberDailyTaskItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: AdminDateCursor? = null,
)

@Serializable
data class AdminGetMemberDailyTasksParams(
    @SerialName("p_user_id") val userId: String,
    @SerialName("p_limit") val limit: Int? = 14,
    @SerialName("p_cursor_date") val cursorDate: String? = null,
)

// ------------------------------------------------------------- Group 3: Carryover Policies

@Serializable
data class AdminCarryoverPolicyItem(
    @SerialName("id") val id: String? = null,
    @SerialName("effective_from") val effectiveFrom: String? = null,
    @SerialName("is_enabled") val isEnabled: Boolean = false,
    @SerialName("operator_note") val operatorNote: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class AdminCarryoverPoliciesResult(
    @SerialName("active") val active: AdminCarryoverPolicyItem? = null,
    @SerialName("future") val future: List<AdminCarryoverPolicyItem> = emptyList(),
    @SerialName("history") val history: List<AdminCarryoverPolicyItem> = emptyList(),
    @SerialName("revision") val revision: String? = null,
)

@Serializable
data class AdminCarryoverAuditHistoryItem(
    @SerialName("id") val id: Long,
    @SerialName("policy_id") val policyId: Long? = null,
    @SerialName("effective_from") val effectiveFrom: String? = null,
    @SerialName("old_is_enabled") val oldIsEnabled: Boolean? = null,
    @SerialName("new_is_enabled") val newIsEnabled: Boolean? = null,
    @SerialName("old_operator_note") val oldOperatorNote: String? = null,
    @SerialName("new_operator_note") val newOperatorNote: String? = null,
    @SerialName("is_cancelled") val isCancelled: Boolean = false,
    @SerialName("clean_note") val cleanNote: String? = null,
    @SerialName("changed_by") val changedBy: String? = null,
    @SerialName("changed_at") val changedAt: String? = null,
)

@Serializable
data class AdminCarryoverAuditHistoryEnvelope(
    @SerialName("items") val items: List<AdminCarryoverAuditHistoryItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: Long? = null,
)

@Serializable
data class AdminGetCarryoverPolicyHistoryParams(
    @SerialName("p_limit") val limit: Int = 50,
    @SerialName("p_cursor_id") val cursorId: Long? = null,
    @SerialName("p_include_cancelled") val includeCancelled: Boolean = true,
)

@Serializable
data class AdminListFutureCarryoverPoliciesEnvelope(
    @SerialName("items") val items: List<AdminCarryoverPolicyItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: AdminDateCursor? = null,
)

@Serializable
data class AdminListFutureCarryoverPoliciesParams(
    @SerialName("p_limit") val limit: Int = 50,
    @SerialName("p_cursor_date") val cursorDate: String? = null,
)

@Serializable
data class AdminScheduleCarryoverPolicyParams(
    @SerialName("p_effective_from") val effectiveFrom: String,
    @SerialName("p_is_enabled") val isEnabled: Boolean,
    @SerialName("p_operator_note") val operatorNote: String,
    @SerialName("p_expected_revision") val expectedRevision: Long? = null,
    @SerialName("p_request_id") val requestId: String? = null,
)

@Serializable
data class AdminScheduleCarryoverResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("effective_from") val effectiveFrom: String? = null,
    @SerialName("is_enabled") val isEnabled: Boolean = false,
    @SerialName("operator_note") val operatorNote: String? = null,
    @SerialName("revision") val revision: String? = null,
)

@Serializable
data class AdminCancelFutureCarryoverPolicyParams(
    @SerialName("p_effective_from") val effectiveFrom: String,
    @SerialName("p_reason") val reason: String,
    @SerialName("p_expected_revision") val expectedRevision: Long? = null,
    @SerialName("p_request_id") val requestId: String? = null,
)

@Serializable
data class AdminCancelCarryoverResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("cancelled_date") val cancelledDate: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("revision") val revision: String? = null,
)

// ------------------------------------------------------------- Group 4: Campaigns & Tester Reports Queue

@Serializable
data class AdminCampaignItem(
    @SerialName("id") val id: String,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("owner_email") val ownerEmail: String? = null,
    @SerialName("app_name") val appName: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("status") val status: String,
    @SerialName("target_testers") val targetTesters: Int = 0,
    @SerialName("current_testers") val currentTesters: Int = 0,
    @SerialName("paused_reason") val pausedReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("pending_reports_count") val pendingReportsCount: Int = 0,
)

@Serializable
data class AdminSearchCampaignsEnvelope(
    @SerialName("items") val items: List<AdminCampaignItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: AdminDateTimeCursor? = null,
)

@Serializable
data class AdminSearchCampaignsParams(
    @SerialName("p_query") val query: String? = null,
    @SerialName("p_status") val status: String? = null,
    @SerialName("p_owner_id") val ownerId: String? = null,
    @SerialName("p_limit") val limit: Int? = 50,
    @SerialName("p_cursor_created_at") val cursorCreatedAt: String? = null,
    @SerialName("p_cursor_id") val cursorId: String? = null,
)

@Serializable
data class AdminTesterReportQueueItem(
    @SerialName("id") val id: String,
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("campaign_name") val campaignName: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("tester_id") val testerId: String? = null,
    @SerialName("tester_name") val testerName: String? = null,
    @SerialName("tester_email") val testerEmail: String? = null,
    @SerialName("report_type") val reportType: String? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("screenshot_url") val screenshotUrl: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("media_expired") val mediaExpired: Boolean? = null,
    @SerialName("rating") val rating: Int? = null,
    @SerialName("status") val status: String,
    @SerialName("submission_context") val submissionContext: String? = null,
    @SerialName("mission_title") val missionTitle: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("review_tester_bonus") val reviewTesterBonus: Int? = null,
    @SerialName("review_owner_bonus") val reviewOwnerBonus: Int? = null,
    @SerialName("business_policy_id") val businessPolicyId: Long? = null,
    @SerialName("provisional_granted_credits") val provisionalGrantedCredits: Int? = null,
    @SerialName("provisional_clawed") val provisionalClawed: Int? = null,
    @SerialName("bonus_awarded") val bonusAwarded: Int? = null,
) {
    val effectiveTesterBonus: Int get() = reviewTesterBonus ?: 2

    fun toTesterReport(): TesterReport = TesterReport(
        id = id,
        campaignId = campaignId,
        userId = testerId ?: "",
        reportType = reportType ?: "feedback",
        content = content ?: "",
        screenshotUrl = screenshotUrl,
        thumbnailUrl = thumbnailUrl,
        mediaExpired = mediaExpired,
        rating = rating ?: 5,
        status = status,
        rejectionReason = rejectionReason,
        createdAt = createdAt,
        reviewedAt = reviewedAt,
        reviewTesterBonus = reviewTesterBonus,
        reviewOwnerBonus = reviewOwnerBonus,
        policyId = businessPolicyId,
        provisionalGrantedCredits = provisionalGrantedCredits ?: 0,
        bonusAwarded = bonusAwarded ?: 0,
        submissionContext = submissionContext,
        missionTitle = missionTitle,
    )

    fun toScreenshotDescriptor(variant: com.ntt.crosstest.util.ScreenshotVariant = com.ntt.crosstest.util.ScreenshotVariant.THUMBNAIL): com.ntt.crosstest.util.ScreenshotDescriptor? {
        val url = if (variant == com.ntt.crosstest.util.ScreenshotVariant.THUMBNAIL && !thumbnailUrl.isNullOrBlank()) thumbnailUrl else screenshotUrl
        if (url.isNullOrBlank()) return null
        return com.ntt.crosstest.util.ScreenshotDescriptor(
            url = url,
            createdAt = createdAt,
            mediaExpired = mediaExpired ?: false,
            variant = variant,
            fallbackUrl = if (variant == com.ntt.crosstest.util.ScreenshotVariant.THUMBNAIL) screenshotUrl else null,
            reportId = id,
        )
    }
}

@Serializable
data class AdminTesterReportsQueueEnvelope(
    @SerialName("items") val items: List<AdminTesterReportQueueItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: AdminDateTimeCursor? = null,
)

@Serializable
data class AdminGetTesterReportsQueueParams(
    @SerialName("p_status") val status: String? = "pending",
    @SerialName("p_campaign_id") val campaignId: String? = null,
    @SerialName("p_tester_id") val testerId: String? = null,
    @SerialName("p_owner_id") val ownerId: String? = null,
    @SerialName("p_submission_context") val submissionContext: String? = null,
    @SerialName("p_limit") val limit: Int? = 50,
    @SerialName("p_cursor_created_at") val cursorCreatedAt: String? = null,
    @SerialName("p_cursor_id") val cursorId: String? = null,
)

// ------------------------------------------------------------- Group 5: Credit Compensation

@Serializable
data class AdminCompensateCreditsParams(
    @SerialName("p_request_id") val requestId: String,
    @SerialName("p_target_user_id") val targetUserId: String,
    @SerialName("p_amount") val amount: Int,
    @SerialName("p_reason") val reason: String,
    @SerialName("p_original_credit_log_id") val originalCreditLogId: String? = null,
    @SerialName("p_contact_report_id") val contactReportId: String? = null,
)

@Serializable
data class AdminCompensateCreditsResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("request_id") val requestId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("previous_credits") val previousCredits: Int = 0,
    @SerialName("new_credits") val newCredits: Int = 0,
    @SerialName("amount") val amount: Int = 0,
    @SerialName("reason") val reason: String,
    @SerialName("original_credit_log_id") val originalCreditLogId: String? = null,
    @SerialName("contact_report_id") val contactReportId: String? = null,
    @SerialName("is_replay") val isReplay: Boolean = false,
)

// ------------------------------------------------------------- Group 6: Role Management

@Serializable
data class AdminSetUserRoleParams(
    @SerialName("p_target_user_id") val targetUserId: String,
    @SerialName("p_new_role") val newRole: String,
    @SerialName("p_reason") val reason: String,
    @SerialName("p_request_id") val requestId: String? = null,
)

@Serializable
data class AdminSetUserRoleResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("user_id") val userId: String,
    @SerialName("previous_role") val previousRole: String? = null,
    @SerialName("new_role") val newRole: String,
    @SerialName("reason") val reason: String? = null,
)

// ------------------------------------------------------------- Group 7: Warning Management

@Serializable
data class AdminWarningRevocation(
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("revoked_by") val revokedBy: String? = null,
    @SerialName("reason") val reason: String? = null,
)

@Serializable
data class AdminMemberWarningItem(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("warning_type") val warningType: String? = null,
    @SerialName("message") val message: String,
    @SerialName("warning_date") val warningDate: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("acknowledged") val acknowledged: Boolean = false,
    @SerialName("is_revoked") val isRevoked: Boolean = false,
    @SerialName("revocation") val revocation: AdminWarningRevocation? = null,
)

@Serializable
data class AdminMemberWarningsEnvelope(
    @SerialName("items") val items: List<AdminMemberWarningItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: AdminDateTimeCursor? = null,
)

@Serializable
data class AdminGetMemberWarningsParams(
    @SerialName("p_user_id") val userId: String,
    @SerialName("p_limit") val limit: Int? = 50,
    @SerialName("p_cursor_created_at") val cursorCreatedAt: String? = null,
    @SerialName("p_cursor_id") val cursorId: String? = null,
)

@Serializable
data class AdminRevokeWarningParams(
    @SerialName("p_warning_id") val warningId: String,
    @SerialName("p_reason") val reason: String,
    @SerialName("p_request_id") val requestId: String? = null,
)

@Serializable
data class AdminRevokeWarningResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("warning_id") val warningId: String,
    @SerialName("revoked") val revoked: Boolean = false,
    @SerialName("reason") val reason: String? = null,
)

// ------------------------------------------------------------- Group 8: System Monitor

@Serializable
data class AdminSystemMonitorMembers(
    @SerialName("total") val total: Int = 0,
    @SerialName("locked") val locked: Int = 0,
    @SerialName("admins") val admins: Int = 0,
    @SerialName("moderators") val moderators: Int = 0,
)

@Serializable
data class AdminSystemMonitorCampaigns(
    @SerialName("open") val open: Int = 0,
    @SerialName("paused") val paused: Int = 0,
    @SerialName("completed") val completed: Int = 0,
    @SerialName("total") val total: Int = 0,
)

@Serializable
data class AdminSystemMonitorTesterReports(
    @SerialName("pending") val pending: Int = 0,
    @SerialName("approved") val approved: Int = 0,
    @SerialName("rejected") val rejected: Int = 0,
)

@Serializable
data class AdminSystemMonitorContactReports(
    @SerialName("pending") val pending: Int = 0,
    @SerialName("resolved") val resolved: Int = 0,
)

@Serializable
data class AdminSystemMonitorMissionJobs(
    @SerialName("pending") val pending: Int = 0,
    @SerialName("failed") val failed: Int = 0,
    @SerialName("completed") val completed: Int = 0,
    @SerialName("skipped") val skipped: Int = 0,
)

@Serializable
data class AdminCleanupRunItem(
    @SerialName("id") val id: String? = null,
    @SerialName("job_name") val jobName: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("scanned") val scanned: Int = 0,
    @SerialName("deleted") val deleted: Int = 0,
    @SerialName("error") val error: String? = null,
)

@Serializable
data class AdminSystemMonitorCleanupRuns(
    @SerialName("last_run") val lastRun: AdminCleanupRunItem? = null,
)

@Serializable
data class AdminSystemMonitorSummary(
    @SerialName("members") val members: AdminSystemMonitorMembers = AdminSystemMonitorMembers(),
    @SerialName("campaigns") val campaigns: AdminSystemMonitorCampaigns = AdminSystemMonitorCampaigns(),
    @SerialName("tester_reports") val testerReports: AdminSystemMonitorTesterReports = AdminSystemMonitorTesterReports(),
    @SerialName("contact_reports") val contactReports: AdminSystemMonitorContactReports = AdminSystemMonitorContactReports(),
    @SerialName("mission_settlement_jobs") val missionSettlementJobs: AdminSystemMonitorMissionJobs = AdminSystemMonitorMissionJobs(),
    @SerialName("cleanup_runs") val cleanupRuns: AdminSystemMonitorCleanupRuns = AdminSystemMonitorCleanupRuns(),
    @SerialName("timestamp") val timestamp: String? = null,
)

@Serializable
data class AdminJobRunItem(
    @SerialName("id") val id: String,
    @SerialName("report_id") val reportId: String? = null,
    @SerialName("mission_id") val missionId: String? = null,
    @SerialName("campaign_id") val campaignId: String? = null,
    @SerialName("tester_id") val testerId: String? = null,
    @SerialName("reward_credits") val rewardCredits: Int = 0,
    @SerialName("status") val status: String,
    @SerialName("attempt_count") val attemptCount: Int = 0,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AdminJobRunsQueueEnvelope(
    @SerialName("items") val items: List<AdminJobRunItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: AdminDateTimeCursor? = null,
)

@Serializable
data class AdminGetJobRunsQueueParams(
    @SerialName("p_job_type") val jobType: String? = "mission_settlement",
    @SerialName("p_status") val status: String? = null,
    @SerialName("p_limit") val limit: Int? = 50,
    @SerialName("p_cursor_created_at") val cursorCreatedAt: String? = null,
    @SerialName("p_cursor_id") val cursorId: String? = null,
)

@Serializable
data class AdminRetryMissionSettlementJobsParams(
    @SerialName("p_job_id") val jobId: String? = null,
    @SerialName("p_batch_limit") val batchLimit: Int? = 20,
    @SerialName("p_request_id") val requestId: String? = null,
)

@Serializable
data class AdminRetryMissionSettlementResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("processed_count") val processedCount: Int = 0,
    @SerialName("success_count") val successCount: Int = 0,
)
