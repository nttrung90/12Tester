package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc

open class AdminNotificationCampaignRepository(
    private val supabase: SupabaseClient,
) {
    open suspend fun createCampaign(
        requestId: String,
        titleVi: String,
        titleEn: String,
        bodyVi: String,
        bodyEn: String,
        defaultLocale: String = "vi",
        audienceType: String = "all",
        targetRole: String? = null,
        selectedUserIds: List<String>? = null,
        reason: String? = null,
    ): AdminNotificationCampaignMutationResponse {
        return supabase.postgrest.rpc(
            "admin_create_notification_campaign",
            AdminCreateNotificationCampaignParams(
                requestId = requestId,
                titleVi = titleVi,
                titleEn = titleEn,
                bodyVi = bodyVi,
                bodyEn = bodyEn,
                defaultLocale = defaultLocale,
                audienceType = audienceType,
                targetRole = targetRole,
                selectedUserIds = selectedUserIds,
                reason = reason,
            )
        ).decodeAs<AdminNotificationCampaignMutationResponse>()
    }

    open suspend fun updateCampaign(
        requestId: String,
        campaignId: String,
        expectedRevision: Int,
        titleVi: String,
        titleEn: String,
        bodyVi: String,
        bodyEn: String,
        defaultLocale: String = "vi",
        audienceType: String = "all",
        targetRole: String? = null,
        selectedUserIds: List<String>? = null,
        reason: String? = null,
    ): AdminNotificationCampaignMutationResponse {
        return supabase.postgrest.rpc(
            "admin_update_notification_campaign",
            AdminUpdateNotificationCampaignParams(
                requestId = requestId,
                campaignId = campaignId,
                expectedRevision = expectedRevision,
                titleVi = titleVi,
                titleEn = titleEn,
                bodyVi = bodyVi,
                bodyEn = bodyEn,
                defaultLocale = defaultLocale,
                audienceType = audienceType,
                targetRole = targetRole,
                selectedUserIds = selectedUserIds,
                reason = reason,
            )
        ).decodeAs<AdminNotificationCampaignMutationResponse>()
    }

    open suspend fun previewCampaign(
        campaignId: String? = null,
        audienceType: String? = null,
        targetRole: String? = null,
        selectedUserIds: List<String>? = null,
        defaultLocale: String = "vi",
        titleVi: String = "",
        titleEn: String = "",
        bodyVi: String = "",
        bodyEn: String = "",
    ): AdminNotificationCampaignPreviewResponse {
        return supabase.postgrest.rpc(
            "admin_preview_notification_campaign",
            AdminPreviewNotificationCampaignParams(
                campaignId = campaignId,
                audienceType = audienceType,
                targetRole = targetRole,
                selectedUserIds = selectedUserIds,
                defaultLocale = defaultLocale,
                titleVi = titleVi,
                titleEn = titleEn,
                bodyVi = bodyVi,
                bodyEn = bodyEn,
            )
        ).decodeAs<AdminNotificationCampaignPreviewResponse>()
    }

    open suspend fun scheduleCampaign(
        requestId: String,
        campaignId: String,
        expectedRevision: Int,
        scheduledForIso: String,
        reason: String? = null,
    ): AdminNotificationCampaignMutationResponse {
        return supabase.postgrest.rpc(
            "admin_schedule_notification_campaign",
            AdminScheduleNotificationCampaignParams(
                requestId = requestId,
                campaignId = campaignId,
                expectedRevision = expectedRevision,
                scheduledFor = scheduledForIso,
                reason = reason,
            )
        ).decodeAs<AdminNotificationCampaignMutationResponse>()
    }

    open suspend fun sendNowCampaign(
        requestId: String,
        campaignId: String,
        expectedRevision: Int,
        reason: String? = null,
    ): AdminNotificationCampaignMutationResponse {
        return supabase.postgrest.rpc(
            "admin_send_now_notification_campaign",
            AdminSendNowNotificationCampaignParams(
                requestId = requestId,
                campaignId = campaignId,
                expectedRevision = expectedRevision,
                reason = reason,
            )
        ).decodeAs<AdminNotificationCampaignMutationResponse>()
    }

    open suspend fun cancelCampaign(
        requestId: String,
        campaignId: String,
        expectedRevision: Int,
        reason: String,
    ): AdminNotificationCampaignMutationResponse {
        return supabase.postgrest.rpc(
            "admin_cancel_notification_campaign",
            AdminCancelNotificationCampaignParams(
                requestId = requestId,
                campaignId = campaignId,
                expectedRevision = expectedRevision,
                reason = reason,
            )
        ).decodeAs<AdminNotificationCampaignMutationResponse>()
    }

    open suspend fun listCampaigns(
        status: String? = null,
        audienceType: String? = null,
        limit: Int = 20,
        cursor: String? = null,
    ): AdminListNotificationCampaignsEnvelope {
        return supabase.postgrest.rpc(
            "admin_list_notification_campaigns",
            AdminListNotificationCampaignsParams(
                status = status?.trim()?.ifBlank { null },
                audienceType = audienceType?.trim()?.ifBlank { null },
                limit = limit,
                cursor = cursor,
            )
        ).decodeAs<AdminListNotificationCampaignsEnvelope>()
    }

    open suspend fun getCampaignDetail(
        campaignId: String,
        eventsLimit: Int = 50,
    ): AdminNotificationCampaignDetailResponse {
        return supabase.postgrest.rpc(
            "admin_get_notification_campaign_detail",
            AdminGetNotificationCampaignDetailParams(
                campaignId = campaignId,
                eventsLimit = eventsLimit,
            )
        ).decodeAs<AdminNotificationCampaignDetailResponse>()
    }

    open suspend fun listCampaignRecipients(
        campaignId: String,
        status: String? = null,
        limit: Int = 50,
        cursor: String? = null,
    ): AdminListNotificationCampaignRecipientsEnvelope {
        return supabase.postgrest.rpc(
            "admin_list_notification_campaign_recipients",
            AdminListNotificationCampaignRecipientsParams(
                campaignId = campaignId,
                status = status?.trim()?.ifBlank { null },
                limit = limit,
                cursor = cursor,
            )
        ).decodeAs<AdminListNotificationCampaignRecipientsEnvelope>()
    }

    open suspend fun getSchedulerHealth(): AdminNotificationSchedulerHealth {
        return supabase.postgrest.rpc("admin_get_notification_scheduler_health")
            .decodeAs<AdminNotificationSchedulerHealth>()
    }

    open suspend fun processDueCampaigns(
        batchSize: Int = 100,
        campaignId: String? = null,
    ): AdminProcessDueCampaignsResponse {
        return supabase.postgrest.rpc(
            "admin_process_due_notification_campaigns",
            AdminProcessDueCampaignsParams(
                batchSize = batchSize,
                campaignId = campaignId,
            )
        ).decodeAs<AdminProcessDueCampaignsResponse>()
    }

    open suspend fun searchMembers(
        query: String? = null,
        limit: Int = 20,
        cursorCreatedAt: String? = null,
        cursorId: String? = null,
    ): AdminSearchMembersEnvelope {
        return supabase.postgrest.rpc(
            "admin_search_members",
            AdminSearchMembersParams(
                query = query?.trim()?.ifBlank { null },
                limit = limit,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
            )
        ).decodeAs<AdminSearchMembersEnvelope>()
    }
}
