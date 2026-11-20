package com.ntt.crosstest.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AdminNotificationCampaign(
    @SerialName("id") val id: String,
    @SerialName("title_vi") val titleVi: String,
    @SerialName("title_en") val titleEn: String,
    @SerialName("body_vi") val bodyVi: String,
    @SerialName("body_en") val bodyEn: String,
    @SerialName("default_locale") val defaultLocale: String = "vi",
    @SerialName("audience_type") val audienceType: String = "all",
    @SerialName("target_role") val targetRole: String? = null,
    @SerialName("selected_user_ids") val selectedUserIds: List<String> = emptyList(),
    @SerialName("status") val status: String = "draft",
    @SerialName("revision") val revision: Int = 1,
    @SerialName("total_recipients") val totalRecipients: Int = 0,
    @SerialName("recipient_snapshot_taken") val recipientSnapshotTaken: Boolean = false,
    @SerialName("snapshot_taken_at") val snapshotTakenAt: String? = null,
    @SerialName("sent_count") val sentCount: Int = 0,
    @SerialName("read_count") val readCount: Int = 0,
    @SerialName("failed_count") val failedCount: Int = 0,
    @SerialName("scheduled_for") val scheduledFor: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("cancelled_by") val cancelledBy: String? = null,
    @SerialName("cancel_reason") val cancelReason: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class AdminNotificationCampaignMutationResponse(
    @SerialName("success") val success: Boolean = false,
    @SerialName("replayed") val replayed: Boolean = false,
    @SerialName("campaign") val campaign: AdminNotificationCampaign,
)

@Serializable
data class AdminCreateNotificationCampaignParams(
    @SerialName("p_request_id") val requestId: String,
    @SerialName("p_title_vi") val titleVi: String,
    @SerialName("p_title_en") val titleEn: String,
    @SerialName("p_body_vi") val bodyVi: String,
    @SerialName("p_body_en") val bodyEn: String,
    @SerialName("p_default_locale") val defaultLocale: String = "vi",
    @SerialName("p_audience_type") val audienceType: String = "all",
    @SerialName("p_target_role") val targetRole: String? = null,
    @SerialName("p_selected_user_ids") val selectedUserIds: List<String>? = null,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
data class AdminUpdateNotificationCampaignParams(
    @SerialName("p_request_id") val requestId: String,
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_expected_revision") val expectedRevision: Int,
    @SerialName("p_title_vi") val titleVi: String,
    @SerialName("p_title_en") val titleEn: String,
    @SerialName("p_body_vi") val bodyVi: String,
    @SerialName("p_body_en") val bodyEn: String,
    @SerialName("p_default_locale") val defaultLocale: String = "vi",
    @SerialName("p_audience_type") val audienceType: String = "all",
    @SerialName("p_target_role") val targetRole: String? = null,
    @SerialName("p_selected_user_ids") val selectedUserIds: List<String>? = null,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
data class AdminScheduleNotificationCampaignParams(
    @SerialName("p_request_id") val requestId: String,
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_expected_revision") val expectedRevision: Int,
    @SerialName("p_scheduled_for") val scheduledFor: String,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
data class AdminSendNowNotificationCampaignParams(
    @SerialName("p_request_id") val requestId: String,
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_expected_revision") val expectedRevision: Int,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
data class AdminCancelNotificationCampaignParams(
    @SerialName("p_request_id") val requestId: String,
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_expected_revision") val expectedRevision: Int,
    @SerialName("p_reason") val reason: String,
)

@Serializable
data class AdminPreviewContent(
    @SerialName("title") val title: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("locale") val locale: String? = null,
)

@Serializable
data class AdminNotificationCampaignPreviewResponse(
    @SerialName("success") val success: Boolean = false,
    @SerialName("recipient_count") val recipientCount: Int = 0,
    @SerialName("audience_type") val audienceType: String? = null,
    @SerialName("target_role") val targetRole: String? = null,
    @SerialName("default_locale") val defaultLocale: String = "vi",
    @SerialName("preview_vi") val previewVi: AdminPreviewContent? = null,
    @SerialName("preview_en") val previewEn: AdminPreviewContent? = null,
    @SerialName("preview_effective") val previewEffective: AdminPreviewContent? = null,
)

@Serializable
data class AdminPreviewNotificationCampaignParams(
    @SerialName("p_campaign_id") val campaignId: String? = null,
    @SerialName("p_audience_type") val audienceType: String? = null,
    @SerialName("p_target_role") val targetRole: String? = null,
    @SerialName("p_selected_user_ids") val selectedUserIds: List<String>? = null,
    @SerialName("p_default_locale") val defaultLocale: String = "vi",
    @SerialName("p_title_vi") val titleVi: String = "",
    @SerialName("p_title_en") val titleEn: String = "",
    @SerialName("p_body_vi") val bodyVi: String = "",
    @SerialName("p_body_en") val bodyEn: String = "",
)

@Serializable
data class AdminListNotificationCampaignsParams(
    @SerialName("p_status") val status: String? = null,
    @SerialName("p_audience_type") val audienceType: String? = null,
    @SerialName("p_limit") val limit: Int = 20,
    @SerialName("p_cursor") val cursor: String? = null,
)

@Serializable
data class AdminListNotificationCampaignsEnvelope(
    @SerialName("items") val items: List<AdminNotificationCampaign> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class AdminCampaignAuditEvent(
    @SerialName("id") val id: String,
    @SerialName("action") val action: String,
    @SerialName("admin_id") val adminId: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("details") val details: JsonElement? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AdminGetNotificationCampaignDetailParams(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_events_limit") val eventsLimit: Int = 50,
)

@Serializable
data class AdminNotificationCampaignDetailResponse(
    @SerialName("campaign") val campaign: AdminNotificationCampaign,
    @SerialName("preview") val preview: AdminNotificationCampaignPreviewResponse? = null,
    @SerialName("events") val events: List<AdminCampaignAuditEvent> = emptyList(),
)

@Serializable
data class AdminCampaignRecipientItem(
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("user_email") val userEmail: String? = null,
    @SerialName("user_display_name") val userDisplayName: String? = null,
    @SerialName("user_role") val userRole: String? = null,
    @SerialName("status") val status: String,
    @SerialName("notification_id") val notificationId: String? = null,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("error_message") val errorMessage: String? = null,
)

@Serializable
data class AdminListNotificationCampaignRecipientsParams(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_status") val status: String? = null,
    @SerialName("p_limit") val limit: Int = 50,
    @SerialName("p_cursor") val cursor: String? = null,
)

@Serializable
data class AdminListNotificationCampaignRecipientsEnvelope(
    @SerialName("items") val items: List<AdminCampaignRecipientItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class AdminNotificationSchedulerHealth(
    @SerialName("pg_cron_available") val pgCronAvailable: Boolean = false,
    @SerialName("pg_cron_job_active") val pgCronJobActive: Boolean = false,
    @SerialName("scheduler_ready") val schedulerReady: Boolean = false,
    @SerialName("pending_due_campaigns_count") val pendingDueCampaignsCount: Int = 0,
    @SerialName("in_flight_campaigns_count") val inFlightCampaignsCount: Int = 0,
    @SerialName("last_worker_run_at") val lastWorkerRunAt: String? = null,
    @SerialName("lifetime_campaigns_processed") val lifetimeCampaignsProcessed: Int = 0,
    @SerialName("lifetime_notifications_sent") val lifetimeNotificationsSent: Int = 0,
    @SerialName("health_status") val healthStatus: String = "healthy",
)

@Serializable
data class AdminProcessDueCampaignsParams(
    @SerialName("p_batch_size") val batchSize: Int = 100,
    @SerialName("p_campaign_id") val campaignId: String? = null,
)

@Serializable
data class AdminProcessDueCampaignsResponse(
    @SerialName("success") val success: Boolean = false,
    @SerialName("campaigns_processed") val campaignsProcessed: Int = 0,
    @SerialName("notifications_sent") val notificationsSent: Int = 0,
    @SerialName("triggered_by_admin") val triggeredByAdmin: String? = null,
)
