package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc

open class AdminManagementRepository(
    private val supabase: SupabaseClient,
) {
    // ------------------------------------------------------------- Group 1: Audit & Capabilities

    open suspend fun getActionEvents(
        limit: Int = 50,
        cursorCreatedAt: String? = null,
        cursorId: String? = null,
        action: String? = null,
        targetType: String? = null,
        actorId: String? = null,
    ): AdminActionEventsEnvelope {
        return supabase.postgrest.rpc(
            "admin_get_action_events",
            AdminGetActionEventsParams(
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
                limit = limit,
                action = action,
                targetType = targetType,
                actorId = actorId,
            )
        ).decodeAs<AdminActionEventsEnvelope>()
    }

    open suspend fun getMyCapabilities(): AdminMyCapabilities {
        return supabase.postgrest.rpc("admin_get_my_capabilities")
            .decodeAs<AdminMyCapabilities>()
    }

    // ------------------------------------------------------------- Group 2: Advanced Members & Lookup

    open suspend fun searchMembers(
        query: String? = null,
        role: String? = null,
        isLocked: Boolean? = null,
        limit: Int = 50,
        cursorCreatedAt: String? = null,
        cursorId: String? = null,
    ): AdminSearchMembersEnvelope {
        return supabase.postgrest.rpc(
            "admin_search_members",
            AdminSearchMembersParams(
                query = query?.trim()?.ifBlank { null },
                role = role?.trim()?.ifBlank { null },
                isLocked = isLocked,
                limit = limit,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
            )
        ).decodeAs<AdminSearchMembersEnvelope>()
    }

    open suspend fun getMemberOverview(userId: String): AdminMemberOverview {
        return supabase.postgrest.rpc(
            "admin_get_member_overview",
            AdminUserIdParams(userId = userId)
        ).decodeAs<AdminMemberOverview>()
    }

    open suspend fun getMemberSlots(userId: String): AdminMemberSlotsResult {
        return supabase.postgrest.rpc(
            "admin_get_member_slots",
            AdminUserIdParams(userId = userId)
        ).decodeAs<AdminMemberSlotsResult>()
    }

    open suspend fun getMemberCreditHistory(
        userId: String,
        limit: Int = 50,
        cursorCreatedAt: String? = null,
        cursorId: String? = null,
    ): AdminCreditHistoryEnvelope {
        return supabase.postgrest.rpc(
            "admin_get_member_credit_history",
            AdminGetMemberCreditHistoryParams(
                userId = userId,
                limit = limit,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
            )
        ).decodeAs<AdminCreditHistoryEnvelope>()
    }

    open suspend fun getMemberDailyTasks(
        userId: String,
        limit: Int = 14,
        cursorDate: String? = null,
    ): AdminMemberDailyTasksEnvelope {
        return supabase.postgrest.rpc(
            "admin_get_member_daily_tasks",
            AdminGetMemberDailyTasksParams(
                userId = userId,
                limit = limit,
                cursorDate = cursorDate,
            )
        ).decodeAs<AdminMemberDailyTasksEnvelope>()
    }

    // ------------------------------------------------------------- Group 3: Carryover Policies

    open suspend fun getCarryoverPolicies(): AdminCarryoverPoliciesResult {
        return supabase.postgrest.rpc("admin_get_carryover_policies")
            .decodeAs<AdminCarryoverPoliciesResult>()
    }

    open suspend fun getCarryoverPolicyHistory(
        limit: Int = 50,
        cursorId: Long? = null,
        includeCancelled: Boolean = true,
    ): AdminCarryoverAuditHistoryEnvelope {
        return supabase.postgrest.rpc(
            "admin_get_carryover_policy_history",
            AdminGetCarryoverPolicyHistoryParams(
                limit = limit,
                cursorId = cursorId,
                includeCancelled = includeCancelled,
            )
        ).decodeAs<AdminCarryoverAuditHistoryEnvelope>()
    }

    open suspend fun listFutureCarryoverPolicies(
        limit: Int = 50,
        cursorDate: String? = null,
    ): AdminListFutureCarryoverPoliciesEnvelope {
        return supabase.postgrest.rpc(
            "admin_list_future_carryover_policies",
            AdminListFutureCarryoverPoliciesParams(
                limit = limit,
                cursorDate = cursorDate,
            )
        ).decodeAs<AdminListFutureCarryoverPoliciesEnvelope>()
    }

    open suspend fun scheduleCarryoverPolicy(
        effectiveFrom: String,
        isEnabled: Boolean,
        operatorNote: String,
        expectedRevision: Long? = null,
        requestId: String? = null,
    ): AdminScheduleCarryoverResult {
        return supabase.postgrest.rpc(
            "admin_schedule_carryover_policy",
            AdminScheduleCarryoverPolicyParams(
                effectiveFrom = effectiveFrom,
                isEnabled = isEnabled,
                operatorNote = operatorNote.trim(),
                expectedRevision = expectedRevision,
                requestId = requestId,
            )
        ).decodeAs<AdminScheduleCarryoverResult>()
    }

    open suspend fun cancelFutureCarryoverPolicy(
        effectiveFrom: String,
        reason: String,
        expectedRevision: Long? = null,
        requestId: String? = null,
    ): AdminCancelCarryoverResult {
        return supabase.postgrest.rpc(
            "admin_cancel_future_carryover_policy",
            AdminCancelFutureCarryoverPolicyParams(
                effectiveFrom = effectiveFrom,
                reason = reason.trim(),
                expectedRevision = expectedRevision,
                requestId = requestId,
            )
        ).decodeAs<AdminCancelCarryoverResult>()
    }

    // ------------------------------------------------------------- Group 4: Campaigns & Tester Reports Queue

    open suspend fun searchCampaigns(
        query: String? = null,
        status: String? = null,
        ownerId: String? = null,
        limit: Int = 50,
        cursorCreatedAt: String? = null,
        cursorId: String? = null,
    ): AdminSearchCampaignsEnvelope {
        return supabase.postgrest.rpc(
            "admin_search_campaigns",
            AdminSearchCampaignsParams(
                query = query?.trim()?.ifBlank { null },
                status = status?.trim()?.ifBlank { null },
                ownerId = ownerId?.trim()?.ifBlank { null },
                limit = limit,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
            )
        ).decodeAs<AdminSearchCampaignsEnvelope>()
    }

    open suspend fun overrideCampaignPlayProduction(
        campaignId: String,
        isProduction: Boolean,
        lock: Boolean = true,
        note: String? = null,
    ): AdminOverrideCampaignPlayProductionResult {
        return supabase.postgrest.rpc(
            "admin_override_campaign_play_production",
            AdminOverrideCampaignPlayProductionParams(
                campaignId = campaignId,
                isProduction = isProduction,
                lock = lock,
                note = note?.trim()?.ifBlank { null },
            )
        ).decodeAs<AdminOverrideCampaignPlayProductionResult>()
    }

    open suspend fun getTesterReportsQueue(
        status: String? = "pending",
        campaignId: String? = null,
        testerId: String? = null,
        ownerId: String? = null,
        submissionContext: String? = null,
        limit: Int = 50,
        cursorCreatedAt: String? = null,
        cursorId: String? = null,
    ): AdminTesterReportsQueueEnvelope {
        return supabase.postgrest.rpc(
            "admin_get_tester_reports_queue",
            AdminGetTesterReportsQueueParams(
                status = status?.trim()?.ifBlank { null },
                campaignId = campaignId?.trim()?.ifBlank { null },
                testerId = testerId?.trim()?.ifBlank { null },
                ownerId = ownerId?.trim()?.ifBlank { null },
                submissionContext = submissionContext?.trim()?.ifBlank { null },
                limit = limit,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
            )
        ).decodeAs<AdminTesterReportsQueueEnvelope>()
    }

    open suspend fun approveTesterReport(
        reportId: String,
        bonusCredits: Int = 0,
    ): ApproveReportResult {
        return supabase.postgrest.rpc(
            "approve_tester_report",
            ApproveReportParams(reportId = reportId, bonus = bonusCredits)
        ).decodeAs<ApproveReportResult>()
    }

    open suspend fun rejectTesterReport(
        reportId: String,
        reason: String? = null,
    ): RejectReportResult {
        return supabase.postgrest.rpc(
            "reject_tester_report",
            RejectReportParams(reportId = reportId, reason = reason?.trim())
        ).decodeAs<RejectReportResult>()
    }

    // ------------------------------------------------------------- Group 5: Credit Compensation

    open suspend fun compensateCredits(
        requestId: String,
        targetUserId: String,
        amount: Int,
        reason: String,
        originalCreditLogId: String? = null,
        contactReportId: String? = null,
    ): AdminCompensateCreditsResult {
        return supabase.postgrest.rpc(
            "admin_compensate_credits",
            AdminCompensateCreditsParams(
                requestId = requestId,
                targetUserId = targetUserId,
                amount = amount,
                reason = reason.trim(),
                originalCreditLogId = originalCreditLogId?.trim()?.ifBlank { null },
                contactReportId = contactReportId?.trim()?.ifBlank { null },
            )
        ).decodeAs<AdminCompensateCreditsResult>()
    }

    // ------------------------------------------------------------- Group 6: Role Management

    open suspend fun setUserRole(
        targetUserId: String,
        newRole: String,
        reason: String,
        requestId: String? = null,
    ): AdminSetUserRoleResult {
        return supabase.postgrest.rpc(
            "admin_set_user_role",
            AdminSetUserRoleParams(
                targetUserId = targetUserId,
                newRole = newRole.trim(),
                reason = reason.trim(),
                requestId = requestId,
            )
        ).decodeAs<AdminSetUserRoleResult>()
    }

    // ------------------------------------------------------------- Group 7: Warning Management

    open suspend fun getMemberWarnings(
        userId: String,
        limit: Int = 50,
        cursorCreatedAt: String? = null,
        cursorId: String? = null,
    ): AdminMemberWarningsEnvelope {
        return supabase.postgrest.rpc(
            "admin_get_member_warnings",
            AdminGetMemberWarningsParams(
                userId = userId,
                limit = limit,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
            )
        ).decodeAs<AdminMemberWarningsEnvelope>()
    }

    open suspend fun revokeWarning(
        warningId: String,
        reason: String,
        requestId: String? = null,
    ): AdminRevokeWarningResult {
        return supabase.postgrest.rpc(
            "admin_revoke_warning",
            AdminRevokeWarningParams(
                warningId = warningId,
                reason = reason.trim(),
                requestId = requestId,
            )
        ).decodeAs<AdminRevokeWarningResult>()
    }

    // ------------------------------------------------------------- Group 8: System Monitor

    open suspend fun getSystemMonitorSummary(): AdminSystemMonitorSummary {
        return supabase.postgrest.rpc("admin_get_system_monitor_summary")
            .decodeAs<AdminSystemMonitorSummary>()
    }

    open suspend fun getJobRunsQueue(
        jobType: String? = "mission_settlement",
        status: String? = null,
        limit: Int = 50,
        cursorCreatedAt: String? = null,
        cursorId: String? = null,
    ): AdminJobRunsQueueEnvelope {
        return supabase.postgrest.rpc(
            "admin_get_job_runs_queue",
            AdminGetJobRunsQueueParams(
                jobType = jobType?.trim()?.ifBlank { null },
                status = status?.trim()?.ifBlank { null },
                limit = limit,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
            )
        ).decodeAs<AdminJobRunsQueueEnvelope>()
    }

    open suspend fun retryMissionSettlementJobs(
        jobId: String? = null,
        batchLimit: Int = 20,
        requestId: String? = null,
    ): AdminRetryMissionSettlementResult {
        return supabase.postgrest.rpc(
            "admin_retry_mission_settlement_jobs",
            AdminRetryMissionSettlementJobsParams(
                jobId = jobId?.trim()?.ifBlank { null },
                batchLimit = batchLimit,
                requestId = requestId,
            )
        ).decodeAs<AdminRetryMissionSettlementResult>()
    }
}
