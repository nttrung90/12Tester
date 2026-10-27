package com.ntt.crosstest.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Database models. Column names use snake_case via @SerialName.
 * Nullable ids default to null so PostgREST omits them on insert
 * and the database default (gen_random_uuid() / current_date) applies.
 */
@Serializable
data class Profile(
    @SerialName("id") val id: String,
    @SerialName("email") val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("credits") val credits: Int = 100,
    @SerialName("last_active_date") val lastActiveDate: String? = null,
    @SerialName("missed_streak") val missedStreak: Int = 0,
    @SerialName("daily_missed_streak") val dailyMissedStreak: Int? = null,
    @SerialName("last_login_date") val lastLoginDate: String? = null,
    @SerialName("role") val role: String = "member",
    @SerialName("is_locked") val isLocked: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isAdmin: Boolean get() = role.equals("admin", ignoreCase = true)
    val isModerator: Boolean get() = role.equals("moderator", ignoreCase = true)
    val isMember: Boolean get() = !isAdmin && !isModerator
    val canModerate: Boolean get() = isAdmin || isModerator

    /** Ưu tiên chuỗi nhiệm vụ ngày mới; fallback cột legacy cho server cũ. */
    val effectiveMissedStreak: Int get() = dailyMissedStreak ?: missedStreak
}

/**
 * Dedicated DTO for Auth sign-in upsert so that existing credits,
 * last_active_date, and missed_streak are not overwritten by sign-in.
 */
@Serializable
data class ProfileUpsert(
    @SerialName("id") val id: String,
    @SerialName("email") val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class CreditLog(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("amount") val amount: Int,
    @SerialName("action") val action: String = "",
    @SerialName("note") val note: String? = null,
    @SerialName("balance") val balance: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)


@Serializable
data class Campaign(
    @SerialName("id") val id: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("app_name") val appName: String = "",
    @SerialName("package_name") val packageName: String = "",
    @SerialName("play_url") val playUrl: String = "",
    @SerialName("testing_url") val testingUrl: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("target_testers") val targetTesters: Int = 12,
    @SerialName("target_days") val targetDays: Int = 14,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("status") val status: String = "open",
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("test_username") val testUsername: String? = null,
    @SerialName("test_password") val testPassword: String? = null,
    @SerialName("tip_credits") val tipCredits: Int = 0,
    @SerialName("is_boosted") val isBoosted: Boolean = false,
    @SerialName("slot_number") val slotNumber: Int = 1,
    @SerialName("paused_reason") val pausedReason: String? = null,
    @SerialName("admin_note") val adminNote: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isOpen: Boolean get() = status == "open"
    val isPaused: Boolean get() = status == "paused"
    val isProduction: Boolean get() = status == "production"
    val isCompleted: Boolean get() = status == "completed"

    /** App đã ở tab "Đã hoàn thành Kiểm thử" (lên production hoặc hết 30 ngày hiển thị). */
    val isFinalized: Boolean get() = isProduction || isCompleted

    /** App bị hệ thống tự tạm dừng vì chủ app thiếu nhiệm vụ ngày 7 ngày liên tục. */
    val isAutoPaused: Boolean get() = isPaused && pausedReason == "owner_inactive"

    /** App bị tạm dừng do Slot đã hết hạn. */
    val isSlotExpiredPause: Boolean get() = isPaused && pausedReason == "slot_expired"

    /** App bị Admin tạm dừng; chủ app không được tự mở lại. */
    val isAdminPaused: Boolean get() = isPaused && pausedReason == "admin_manual"

    val safeIconUrl: String get() = iconUrl.orEmpty()
    val safeTestUsername: String get() = testUsername.orEmpty()
    val safeTestPassword: String get() = testPassword.orEmpty()
    val dailyTarget: Int get() = if (targetTesters in listOf(12, 15, 20)) targetTesters else 12
    val targetCost: Int get() = when (dailyTarget) {
        15 -> 10
        20 -> 20
        else -> 0
    }

    /** Ngày app được đăng lên (parse created_at). */
    val postedDate: java.time.LocalDate?
        get() {
            val raw = createdAt ?: return null
            return try {
                java.time.OffsetDateTime.parse(raw).toLocalDate()
            } catch (_: Exception) {
                try {
                    java.time.Instant.parse(raw)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                } catch (_: Exception) {
                    try {
                        java.time.LocalDate.parse(raw.take(10))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

    /** Số ngày còn lại trong cửa sổ hiển thị [TESTING_WINDOW_DAYS] ngày kể từ lúc đăng. */
    val testingDaysRemaining: Int?
        get() {
            val start = postedDate ?: return null
            val today = java.time.LocalDate.now()
            val remaining = java.time.temporal.ChronoUnit.DAYS
                .between(today, start.plusDays(TESTING_WINDOW_DAYS.toLong()))
            return remaining.toInt().coerceAtLeast(0)
        }

    companion object {
        const val TESTING_WINDOW_DAYS = 30
    }
}

@Serializable
data class ResumeCampaignParams(
    @SerialName("p_campaign_id") val campaignId: String,
)

@Serializable
data class ResumeCampaignResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("campaign_id") val campaignId: String = "",
    @SerialName("status") val status: String = "",
)

@Serializable
data class LifecycleSyncResult(
    @SerialName("completed") val completed: Int = 0,
    @SerialName("paused") val paused: Int = 0,
    @SerialName("slot_paused") val slotPaused: Int = 0,
)

@Serializable
data class CampaignMember(
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("joined_at") val joinedAt: String? = null,
)

@Serializable
data class Checkin(
    @SerialName("id") val id: String? = null,
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("checkin_date") val checkinDate: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class Feedback(
    @SerialName("id") val id: String? = null,
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("rating") val rating: Int = 5,
    @SerialName("comment") val comment: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class LeaderboardUser(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val credits: Int = 0,
    val checkinCount: Int = 0,
    val campaignsCount: Int = 0,
    val rank: Int = 0,
)

/** Hạng của người dùng hiện tại (RPC get_my_rank_v1) — rank 0 = chưa xác định. */
@Serializable
data class MyRank(
    val rank: Int = 0,
    val credits: Int = 0,
    val checkinCount: Int = 0,
    val campaignsCount: Int = 0,
    val totalUsers: Int = 0,
)

@Serializable
data class TesterReport(
    @SerialName("id") val id: String? = null,
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("report_type") val reportType: String = "feedback",
    @SerialName("content") val content: String = "",
    @SerialName("screenshot_url") val screenshotUrl: String? = null,
    @SerialName("rating") val rating: Int? = null,
    @SerialName("status") val status: String = "pending", // pending, approved, rejected
    @SerialName("bonus_awarded") val bonusAwarded: Int = 0,
    @SerialName("provisional_granted_credits") val provisionalGrantedCredits: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("image_hash") val imageHash: String? = null,
    @SerialName("client_submit_id") val clientSubmitId: String? = null,
    @SerialName("tester_name") val testerName: String? = null,
    @SerialName("tester_avatar") val testerAvatar: String? = null,
    @SerialName("tester_credits") val testerCredits: Int? = null,
    @SerialName("media_expired") val mediaExpired: Boolean? = null,
    @kotlinx.serialization.Transient val testerProfile: Profile? = null,
) {
    val isPending: Boolean get() = status == "pending"
    val isApproved: Boolean get() = status == "approved"
    val isRejected: Boolean get() = status == "rejected"

    /**
     * Screenshot hết hạn sau 30 ngày (GCS lifecycle / worker cleanup) hoặc đã đánh dấu media_expired.
     */
    val isScreenshotExpired: Boolean
        get() {
            if (mediaExpired == true) return true
            if (createdAt.isNullOrBlank()) return false
            return try {
                val created = java.time.Instant.parse(createdAt)
                val cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(30))
                created.isBefore(cutoff)
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Số Credit hiển thị cho báo cáo đã duyệt.
     * - Báo cáo mới: bonus_awarded (10/12/... + tip).
     * - Báo cáo cũ chưa có bonus_awarded: provisional_granted_credits (mặc định 2).
     * - Báo cáo mới bị clawback toàn bộ rồi regrant 0: 0 (không còn fallback 2 sai).
     */
    val approvedReward: Int
        get() = when {
            bonusAwarded > 0 -> bonusAwarded
            provisionalGrantedCredits > 0 -> provisionalGrantedCredits
            else -> 0
        }

    val resolvedTesterName: String
        get() = testerProfile?.displayName ?: testerName?.ifBlank { null } ?: "Tester"

    fun isCreatedToday(): Boolean {
        if (createdAt.isNullOrBlank()) return false
        return try {
            val createdDate = java.time.Instant.parse(createdAt)
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .toLocalDate()
            val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
            createdDate == today
        } catch (_: Exception) {
            try {
                val todayStr = com.ntt.crosstest.util.VnTime.today().toString()
                createdAt.take(10) == todayStr
            } catch (_: Exception) {
                false
            }
        }
    }

    fun remainingHoursUntilAutoAccept(): Int {
        if (createdAt.isNullOrBlank()) return 8
        return try {
            val created = java.time.Instant.parse(createdAt)
            val deadline = created.plus(java.time.Duration.ofHours(8))
            val now = java.time.Instant.now()
            if (now.isAfter(deadline)) 0
            else {
                val hours = java.time.Duration.between(now, deadline).toHours().toInt()
                (hours + 1).coerceAtLeast(1)
            }
        } catch (_: Exception) {
            8
        }
    }
}

@Serializable
data class CampaignOwnerStats(
    val installedTestersCount: Int = 0,
    val testedTodayCount: Int = 0,
    val totalReportsCount: Int = 0,
    val pendingReportsCount: Int = 0,
    val averageRating: Float? = null,
)

data class TesterItem(
    val member: CampaignMember,
    val profile: Profile? = null,
    val checkinCount: Int = 0,
    val lastCheckinDate: String? = null,
)

@Serializable
data class UserSlot(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String = "",
    @SerialName("slot_number") val slotNumber: Int,
    @SerialName("unlocked_at") val unlockedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isExpired: Boolean
        get() {
            if (expiresAt.isNullOrBlank()) return false
            return try {
                val exp = java.time.Instant.parse(expiresAt)
                java.time.Instant.now().isAfter(exp)
            } catch (_: Exception) {
                false
            }
        }

    val remainingDays: Int
        get() {
            if (expiresAt.isNullOrBlank()) return 0
            return try {
                val exp = java.time.Instant.parse(expiresAt)
                val now = java.time.Instant.now()
                if (now.isAfter(exp)) 0
                else {
                    val days = java.time.Duration.between(now, exp).toDays().toInt()
                    (days + 1).coerceAtLeast(1)
                }
            } catch (_: Exception) {
                0
            }
        }
}

@Serializable
data class UnlockSlotParams(
    @SerialName("p_slot_number") val slotNumber: Int,
)

@Serializable
data class UnlockSlotResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("slot_number") val slotNumber: Int = 0,
    @SerialName("cost") val cost: Int = 0,
    @SerialName("balance") val balance: Int = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
)

data class SlotInfo(
    val slotNumber: Int,
    val isPermanent: Boolean = false,
    val isUnlocked: Boolean = false,
    val isExpired: Boolean = false,
    val cost: Int = 0,
    val expiresAt: String? = null,
    val remainingDays: Int = 0,
    val currentCampaign: Campaign? = null,
) {
    val isOccupied: Boolean get() = currentCampaign != null
    val isAvailable: Boolean get() = (isPermanent || (isUnlocked && !isExpired)) && !isOccupied
    val canUnlock: Boolean get() = !isPermanent && (!isUnlocked || isExpired)

    companion object {
        fun costForSlot(slotNumber: Int): Int = when (slotNumber) {
            1 -> 0
            2 -> 50
            3 -> 100
            4, 5, 6 -> 200
            else -> 200
        }
    }
}

@Serializable
data class AdminAdjustCreditsParams(
    @SerialName("p_target_user_id") val targetUserId: String,
    @SerialName("p_amount") val amount: Int,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
data class AdminAdjustCreditsResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    @SerialName("previous_credits") val previousCredits: Int = 0,
    @SerialName("new_credits") val newCredits: Int = 0,
    @SerialName("amount") val amount: Int = 0,
)

@Serializable
data class AdminSetUserLockParams(
    @SerialName("p_target_user_id") val targetUserId: String,
    @SerialName("p_locked") val locked: Boolean,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
data class AdminSetUserLockResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    @SerialName("is_locked") val isLocked: Boolean = false,
)

@Serializable
data class UserWarning(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String = "",
    @SerialName("warning_type") val warningType: String = "",
    @SerialName("message") val message: String = "",
    @SerialName("warning_date") val warningDate: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("acknowledged") val acknowledged: Boolean = false,
)

@Serializable
data class ApproveReportParams(
    @SerialName("p_report_id") val reportId: String,
    @SerialName("p_bonus") val bonus: Int = 0,
)

@Serializable
data class ApproveReportResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("report_id") val reportId: String = "",
    @SerialName("tester_id") val testerId: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("credits_awarded") val creditsAwarded: Int = 0,
    @SerialName("tip_awarded") val tipAwarded: Int = 0,
    @SerialName("owner_credits_awarded") val ownerCreditsAwarded: Int = 0,
    @SerialName("tester_balance") val testerBalance: Int = 0,
)

@Serializable
data class RejectReportParams(
    @SerialName("p_report_id") val reportId: String,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
data class RejectReportResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("report_id") val reportId: String = "",
    @SerialName("tester_id") val testerId: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("credits_deducted") val creditsDeducted: Int = 0,
    @SerialName("tester_balance") val testerBalance: Int = 0,
    @SerialName("tester_today_rejections") val testerTodayRejections: Int = 0,
    @SerialName("tester_warned") val testerWarned: Boolean = false,
    @SerialName("tester_warning_msg") val testerWarningMsg: String? = null,
    @SerialName("is_tester_locked") val isTesterLocked: Boolean = false,
    @SerialName("owner_today_rejections") val ownerTodayRejections: Int = 0,
    @SerialName("owner_warned") val ownerWarned: Boolean = false,
    @SerialName("owner_warning_msg") val ownerWarningMsg: String? = null,
    @SerialName("is_owner_locked") val isOwnerLocked: Boolean = false,
)

@Serializable
data class SelfUnlockResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    @SerialName("previous_credits") val previousCredits: Int = 0,
    @SerialName("new_credits") val newCredits: Int = 0,
    @SerialName("is_locked") val isLocked: Boolean = false,
)

@Serializable
data class SetCampaignTipParams(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_tip_credits") val tipCredits: Int,
)

@Serializable
data class SetCampaignTipResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("campaign_id") val campaignId: String = "",
    @SerialName("tip_credits") val tipCredits: Int = 0,
    @SerialName("is_boosted") val isBoosted: Boolean = false,
)

@Serializable
data class AcknowledgeWarningParams(
    @SerialName("p_warning_id") val warningId: String,
)

@Serializable
data class TopupPackage(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("credits") val credits: Int,
    @SerialName("bonus_credits") val bonusCredits: Int = 0,
    @SerialName("price_usd") val priceUsd: Double,
    @SerialName("price_vnd") val priceVnd: Long = 0L,
    @SerialName("badge_text") val badgeText: String? = null,
    @SerialName("is_popular") val isPopular: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
) {
    val totalCredits: Int get() = credits + bonusCredits
}

@Serializable
data class TopupOrder(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String = "",
    @SerialName("package_id") val packageId: String? = null,
    @SerialName("credits_awarded") val creditsAwarded: Int = 0,
    @SerialName("price_usd") val priceUsd: Double = 0.0,
    @SerialName("price_vnd") val priceVnd: Long = 0L,
    @SerialName("payment_method") val paymentMethod: String = "google_play",
    @SerialName("payment_status") val paymentStatus: String = "pending",
    @SerialName("purchase_token") val purchaseToken: String? = null,
    @SerialName("order_reference") val orderReference: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class ProcessTopupParams(
    @SerialName("p_package_id") val packageId: String,
    @SerialName("p_purchase_token") val purchaseToken: String? = null,
    @SerialName("p_order_ref") val orderRef: String? = null,
    @SerialName("p_payment_method") val paymentMethod: String = "google_play",
    @SerialName("p_raw_receipt") val rawReceipt: String? = null,
)

@Serializable
data class ProcessTopupResult(
    @SerialName("status") val status: String = "",
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("credits_awarded") val creditsAwarded: Int = 0,
    @SerialName("current_balance") val currentBalance: Int = 0,
    @SerialName("message") val message: String = "",
)

@Serializable
data class UserNotification(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("message") val message: String = "",
    @SerialName("type") val type: String = "general", // 'credit', 'report', 'warning', 'account', 'campaign', 'admin', 'system'
    @SerialName("data") val data: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isCredit: Boolean get() = type == "credit"
    val isReport: Boolean get() = type == "report"
    val isWarning: Boolean get() = type == "warning"
    val isAccount: Boolean get() = type == "account"
    val isCampaign: Boolean get() = type == "campaign"
    val isAdmin: Boolean get() = type == "admin" || type == "system"

    val campaignId: String?
        get() = data?.get("campaign_id")?.let {
            it.toString().trim('"').takeIf { str -> str.isNotBlank() && str != "null" }
        }
}

@Serializable
data class AdminSendNotificationParams(
    @SerialName("p_target_user_id") val targetUserId: String? = null,
    @SerialName("p_title") val title: String,
    @SerialName("p_message") val message: String,
    @SerialName("p_type") val type: String = "admin",
)

@Serializable
data class AdminSendNotificationResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("sent_count") val sentCount: Int = 0,
    @SerialName("target") val target: String = "",
)

@Serializable
data class QuestMilestone(
    @SerialName("milestone") val milestone: Int,
    @SerialName("reward") val reward: Int,
)

@Serializable
data class QuestSummary(
    @SerialName("daily_count") val dailyCount: Int = 0,
    @SerialName("weekly_count") val weeklyCount: Int = 0,
    @SerialName("daily_claimed") val dailyClaimed: List<Int> = emptyList(),
    @SerialName("weekly_claimed") val weeklyClaimed: List<Int> = emptyList(),
    @SerialName("today_date") val todayDate: String? = null,
    @SerialName("week_start_date") val weekStartDate: String? = null,
    @SerialName("daily_milestones") val dailyMilestones: List<QuestMilestone> = listOf(
        QuestMilestone(10, 5),
        QuestMilestone(20, 15),
        QuestMilestone(50, 30),
    ),
    @SerialName("weekly_milestones") val weeklyMilestones: List<QuestMilestone> = listOf(
        QuestMilestone(150, 50),
        QuestMilestone(300, 100),
    ),
) {
    fun isDailyMilestoneClaimed(milestone: Int): Boolean = milestone in dailyClaimed
    fun isWeeklyMilestoneClaimed(milestone: Int): Boolean = milestone in weeklyClaimed

    fun canClaimDaily(milestone: Int): Boolean = dailyCount >= milestone && !isDailyMilestoneClaimed(milestone)
    fun canClaimWeekly(milestone: Int): Boolean = weeklyCount >= milestone && !isWeeklyMilestoneClaimed(milestone)

    val dailyClaimableCount: Int
        get() = dailyMilestones.count { canClaimDaily(it.milestone) }

    val weeklyClaimableCount: Int
        get() = weeklyMilestones.count { canClaimWeekly(it.milestone) }

    val totalClaimableCount: Int
        get() = dailyClaimableCount + weeklyClaimableCount
}

@Serializable
data class ClaimQuestParams(
    @SerialName("p_quest_type") val questType: String,
    @SerialName("p_milestone") val milestone: Int,
)

@Serializable
data class ClaimQuestResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("quest_type") val questType: String = "",
    @SerialName("milestone") val milestone: Int = 0,
    @SerialName("reward") val reward: Int = 0,
    @SerialName("new_balance") val newBalance: Int = 0,
    @SerialName("error") val error: String? = null,
    @SerialName("message") val message: String = "",
)

@Serializable
data class AdminDeleteCampaignParams(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
data class AdminDeleteCampaignResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("campaign_id") val campaignId: String = "",
    @SerialName("app_name") val appName: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("reason") val reason: String = "",
)

@Serializable
data class AdminPauseCampaignParams(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
data class AdminCampaignControlResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("campaign_id") val campaignId: String = "",
    @SerialName("previous_status") val previousStatus: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("paused_reason") val pausedReason: String? = null,
    @SerialName("app_name") val appName: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("admin_note") val adminNote: String? = null,
    @SerialName("admin_resumed") val adminResumed: Boolean = false,
)

@Serializable
data class SubmitTesterReportParams(
    @SerialName("p_campaign_id") val campaignId: String,
    // null = không sửa nội dung (server giữ nguyên comment cũ khi chỉ đổi ảnh)
    @SerialName("p_content") val content: String? = null,
    @SerialName("p_screenshot_url") val screenshotUrl: String? = null,
    @SerialName("p_rating") val rating: Int = 5,
    @SerialName("p_image_hash") val imageHash: String? = null,
    @SerialName("p_client_submit_id") val clientSubmitId: String? = null,
)

/** Fallback cho Supabase chưa deploy migration 033 (RPC chỉ có 5 tham số). */
@Serializable
data class SubmitTesterReportLegacyParams(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_content") val content: String? = null,
    @SerialName("p_screenshot_url") val screenshotUrl: String? = null,
    @SerialName("p_rating") val rating: Int = 5,
    @SerialName("p_image_hash") val imageHash: String? = null,
)

@Serializable
data class SubmitTesterReportResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("report_id") val reportId: String = "",
    @SerialName("status") val status: String = "pending",
    @SerialName("provisional_granted") val provisionalGranted: Boolean = false,
    @SerialName("provisional_amount") val provisionalAmount: Int = 0,
    /** URL thực tế server đang giữ; dùng để tránh xoá nhầm ảnh cũ khi báo cáo đã duyệt. */
    @SerialName("screenshot_url") val screenshotUrl: String? = null,
    @SerialName("image_hash") val imageHash: String? = null,
)

/**
 * Tham số RPC `apply_report_credit` (migration 022) — cộng/trừ credit nguyên tử
 * cho tester của một báo cáo; server tự kiểm tra quyền chủ app/admin.
 */
@Serializable
data class ApplyReportCreditParams(
    @SerialName("p_report_id") val reportId: String,
    @SerialName("p_amount") val amount: Int,
    @SerialName("p_action") val action: String = "",
    @SerialName("p_note") val note: String? = null,
)

@Serializable
data class ApplyReportCreditResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    @SerialName("previous_credits") val previousCredits: Int = 0,
    @SerialName("new_credits") val newCredits: Int = 0,
    @SerialName("amount") val amount: Int = 0,
)

@Serializable
data class GetOwnerReportsParams(
    @SerialName("p_campaign_id") val campaignId: String,
)

/**
 * Remote app configuration from Supabase for version & update management.
 */
@Serializable
data class AppConfig(
    @SerialName("id") val id: String = "android_client",
    @SerialName("min_version_code") val minVersionCode: Int = 1,
    @SerialName("latest_version_code") val latestVersionCode: Int = 4,
    @SerialName("latest_version_name") val latestVersionName: String = "0.2.0",
    @SerialName("force_update") val forceUpdate: Boolean = false,
    @SerialName("title") val title: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("store_url") val storeUrl: String? = null,
) {
    fun checkUpdateStatus(currentVersionCode: Int): UpdateStatus {
        return when {
            currentVersionCode < minVersionCode || (forceUpdate && currentVersionCode < latestVersionCode) -> {
                UpdateStatus.FORCE_UPDATE
            }
            currentVersionCode < latestVersionCode -> {
                UpdateStatus.OPTIONAL_UPDATE
            }
            else -> {
                UpdateStatus.NO_UPDATE
            }
        }
    }
}

enum class UpdateStatus {
    NO_UPDATE,
    OPTIONAL_UPDATE,
    FORCE_UPDATE,
}

/**
 * RPC params for admin_update_app_config (migration 019).
 */
@Serializable
data class AdminUpdateAppConfigParams(
    @SerialName("p_min_version_code") val minVersionCode: Int,
    @SerialName("p_latest_version_code") val latestVersionCode: Int,
    @SerialName("p_latest_version_name") val latestVersionName: String,
    @SerialName("p_force_update") val forceUpdate: Boolean,
    @SerialName("p_title") val title: String? = null,
    @SerialName("p_message") val message: String? = null,
    @SerialName("p_store_url") val storeUrl: String? = null,
)

// ============================================================================
// Phase 2 — Batched RPC results (migration 020_query_optimization.sql)
// RPC-first, các hàm legacy vẫn giữ làm fallback khi chưa deploy migration.
// ============================================================================

/** Per-campaign counters used across Home/Detail screens. */
@Serializable
data class CampaignStats(
    @SerialName("installed_count") val installedCount: Int = 0,
    @SerialName("today_tested_count") val todayTestedCount: Int = 0,
    @SerialName("has_tested_today") val hasTestedToday: Boolean = false,
)

/** Kết quả RPC `get_home_feed` — danh sách campaign 3 tab + tên chủ app + stats. */
@Serializable
data class HomeFeedResult(
    @SerialName("open") val open: List<Campaign> = emptyList(),
    @SerialName("paused") val paused: List<Campaign> = emptyList(),
    @SerialName("completed") val completed: List<Campaign> = emptyList(),
    @SerialName("owner_names") val ownerNames: Map<String, String> = emptyMap(),
    @SerialName("stats") val stats: Map<String, CampaignStats> = emptyMap(),
)

/**
 * Trạng thái nhiệm vụ ngày mới (migration 036).
 * `mode`: owner = có app đang hoạt động, member = chỉ cần đăng nhập.
 * Với owner, `effective_required_tests` đã được giới hạn theo số app open khả dụng.
 */
@Serializable
data class DailyTaskStatus(
    @SerialName("status_date") val statusDate: String? = null,
    @SerialName("mode") val mode: String = "member",
    @SerialName("active_app_count") val activeAppCount: Int = 0,
    @SerialName("required_tests") val requiredTests: Int = 0,
    @SerialName("available_apps") val availableApps: Int = 0,
    @SerialName("effective_required_tests") val effectiveRequiredTests: Int = 0,
    @SerialName("completed_tests") val completedTests: Int = 0,
    @SerialName("logged_in_today") val loggedInToday: Boolean = false,
    @SerialName("is_penalty_exempt") val isPenaltyExempt: Boolean = false,
    @SerialName("missed_streak") val missedStreak: Int = 0,
    @SerialName("next_penalty_percent") val nextPenaltyPercent: Int = 1,
    @SerialName("met") val met: Boolean = false,
) {
    val isOwner: Boolean get() = mode == "owner"
    val isMember: Boolean get() = mode == "member"

    /** Owner không có app open nào để test -> chỉ tiêu ngày được miễn. */
    val isWaived: Boolean get() = isOwner && effectiveRequiredTests <= 0
}

/** Kết quả RPC `get_my_dashboard` — toàn bộ dữ liệu cá nhân của Trang chủ trong 1 query. */
@Serializable
data class MyDashboardResult(
    @SerialName("profile") val profile: Profile? = null,
    @SerialName("my_campaigns") val myCampaigns: List<Campaign> = emptyList(),
    @SerialName("joined_campaigns") val joinedCampaigns: List<Campaign> = emptyList(),
    @SerialName("leaderboard") val leaderboard: List<LeaderboardUser> = emptyList(),
    @SerialName("today_checkin_count") val todayCheckinCount: Int = 0,
    @SerialName("pending_reports_count") val pendingReportsCount: Int = 0,
    @SerialName("unread_warnings") val unreadWarnings: List<UserWarning> = emptyList(),
    @SerialName("weekly_warnings_count") val weeklyWarningsCount: Int = 0,
    @SerialName("credit_logs") val creditLogs: List<CreditLog> = emptyList(),
    @SerialName("topup_packages") val topupPackages: List<TopupPackage> = emptyList(),
    @SerialName("notifications") val notifications: List<UserNotification> = emptyList(),
    @SerialName("owner_stats") val ownerStats: Map<String, CampaignOwnerStats> = emptyMap(),
    /** null khi RPC phiên bản cũ chưa trả về slots (migration 021). */
    @SerialName("slots") val slots: List<UserSlot>? = null,
    @SerialName("quest_summary") val questSummary: QuestSummary? = null,
    /** null khi server chưa deploy migration 036. */
    @SerialName("daily_task") val dailyTask: DailyTaskStatus? = null,
)

/** Tham số RPC `get_my_dashboard` (migration 021). */
@Serializable
data class MyDashboardParams(
    @SerialName("p_include_leaderboard") val includeLeaderboard: Boolean = false,
)

/** Một thành viên tester kèm thống kê check-in (RPC `get_campaign_detail`). */
@Serializable
data class CampaignDetailTester(
    @SerialName("user_id") val userId: String,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("days") val days: Int = 0,
    @SerialName("checkin_count") val checkinCount: Int = 0,
    @SerialName("last_checkin_date") val lastCheckinDate: String? = null,
)

/** Kết quả RPC `get_campaign_detail` — toàn bộ dữ liệu màn chi tiết trong 1 query. */
@Serializable
data class CampaignDetailResult(
    @SerialName("campaign") val campaign: Campaign,
    @SerialName("is_member") val isMember: Boolean = false,
    /** Legacy: true với cả owner + admin/moderator. Dùng isActualOwner cho quyền test. */
    @SerialName("is_owner") val isOwner: Boolean = false,
    @SerialName("is_actual_owner") val isActualOwner: Boolean = false,
    @SerialName("can_manage") val canManage: Boolean = false,
    @SerialName("checked_in_confirmed_today") val checkedInConfirmedToday: Boolean = false,
    @SerialName("testers") val testers: List<CampaignDetailTester> = emptyList(),
    @SerialName("feedbacks") val feedbacks: List<Feedback> = emptyList(),
    @SerialName("reports") val reports: List<TesterReport> = emptyList(),
    @SerialName("profiles") val profiles: Map<String, Profile> = emptyMap(),
    @SerialName("today_tested_count") val todayTestedCount: Int = 0,
    @SerialName("reports_count") val reportsCount: Int = 0,
    @SerialName("pending_reports_count") val pendingReportsCount: Int = 0,
    @SerialName("avg_rating") val avgRating: Float? = null,
    @SerialName("my_checkin_dates") val myCheckinDates: List<String> = emptyList(),
    @SerialName("my_profile") val myProfile: Profile? = null,
    @SerialName("unread_warnings") val unreadWarnings: List<UserWarning> = emptyList(),
    @SerialName("weekly_warnings_count") val weeklyWarningsCount: Int = 0,
)

@Serializable
data class CampaignIdParams(
    @SerialName("p_campaign_id") val campaignId: String,
)

@Serializable
data class CampaignIdsParams(
    @SerialName("p_campaign_ids") val campaignIds: List<String>,
)

@Serializable
data class LeaderboardParams(
    @SerialName("p_limit") val limit: Int = 100,
    /** 'credit' = xếp theo Credits (mặc định) — 'test' = xếp theo tổng lượt test. */
    @SerialName("p_sort") val sort: String = "credit",
)

@Serializable
data class MyRankParams(
    @SerialName("p_sort") val sort: String = "credit",
)

// ============================================================================
// Contact & Bug Reports (migration 025_contact_reports.sql)
// ============================================================================

/** Dữ liệu client gửi lên khi tạo phản hồi mới (status luôn là 'new' do server set). */
@Serializable
data class ContactReportInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("category") val category: String = "bug",
    @SerialName("message") val message: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("platform") val platform: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
)

/** Một phản hồi liên hệ/báo lỗi, kèm thông tin người gửi từ join profiles (RPC admin). */
@Serializable
data class ContactReport(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("category") val category: String = "bug",
    @SerialName("message") val message: String = "",
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("platform") val platform: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("status") val status: String = "new",
    @SerialName("admin_reply") val adminReply: String? = null,
    @SerialName("replied_at") val repliedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
) {
    val isNew: Boolean get() = status == "new"
    val isReplied: Boolean get() = status == "replied"
    val isResolved: Boolean get() = status == "resolved"
}

@Serializable
data class AdminGetContactReportsParams(
    @SerialName("p_status") val status: String? = null,
)

@Serializable
data class AdminUpdateReportStatusParams(
    @SerialName("p_report_id") val reportId: String,
    @SerialName("p_resolved") val resolved: Boolean,
)

@Serializable
data class AdminReplyContactReportParams(
    @SerialName("p_report_id") val reportId: String,
    @SerialName("p_message") val message: String,
)

// ============================================================================
// Missions — nhiệm vụ do người dùng tạo (migration 039)
// ============================================================================

/** Một nhiệm vụ trong tab "Nhiệm vụ App" (kết quả RPC `get_open_missions`). */
@Serializable
data class Mission(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("reward_credits") val rewardCredits: Int = 0,
    @SerialName("max_participants") val maxParticipants: Int = 0,
    @SerialName("participant_count") val participantCount: Int = 0,
    /** open | full | stopped_no_credits | expired | paused_by_creator | deleted */
    @SerialName("status") val status: String = "open",
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("campaign_id") val campaignId: String = "",
    @SerialName("app_name") val appName: String = "",
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("package_name") val packageName: String = "",
    @SerialName("creator_id") val creatorId: String = "",
    @SerialName("creator_name") val creatorName: String = "",
    @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("has_joined") val hasJoined: Boolean = false,
    @SerialName("has_completed") val hasCompleted: Boolean = false,
) {
    val isActive: Boolean get() = status == "open" || status == "full"
    val isFull: Boolean get() = status == "full"
    val isPaused: Boolean get() = status == "paused_by_creator"
    val isDeleted: Boolean get() = status == "deleted"
    val isStopped: Boolean get() = status == "stopped_no_credits"
    val isExpired: Boolean get() = status == "expired"
    val slotsRemaining: Int get() = (maxParticipants - participantCount).coerceAtLeast(0)

    /** Số ngày nhận nhiệm vụ còn lại (làm tròn lên); null khi không parse được. */
    val daysRemaining: Int?
        get() {
            val raw = endsAt ?: return null
            return try {
                val end = java.time.OffsetDateTime.parse(raw)
                val remaining = java.time.temporal.ChronoUnit.SECONDS.between(
                    java.time.OffsetDateTime.now(),
                    end,
                )
                Math.ceil(remaining / 86400.0).toInt().coerceAtLeast(0)
            } catch (_: Exception) {
                null
            }
        }
}

@Serializable
data class CreateMissionParams(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_title") val title: String,
    @SerialName("p_description") val description: String,
    @SerialName("p_max_participants") val maxParticipants: Int,
    @SerialName("p_duration_days") val durationDays: Int,
    @SerialName("p_reward_credits") val rewardCredits: Int,
)

@Serializable
data class CreateMissionResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("mission_id") val missionId: String = "",
    @SerialName("fee") val fee: Int = 0,
    @SerialName("balance") val balance: Int = 0,
    @SerialName("ends_at") val endsAt: String? = null,
)

@Serializable
data class JoinMissionParams(
    @SerialName("p_mission_id") val missionId: String,
)

@Serializable
data class JoinMissionResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("mission_id") val missionId: String = "",
    @SerialName("campaign_id") val campaignId: String = "",
    @SerialName("status") val status: String = "open",
)

@Serializable
data class OpenMissionsResult(
    @SerialName("missions") val missions: List<Mission> = emptyList(),
)

/** Kết quả RPC `get_campaign_mission` — mission hiện tại của 1 app (null nếu chưa có/đã xóa). */
@Serializable
data class CampaignMissionResult(
    @SerialName("mission") val mission: Mission? = null,
)

@Serializable
data class MissionIdParams(
    @SerialName("p_mission_id") val missionId: String,
)

/** Kết quả chung cho pause_mission / resume_mission / delete_mission. */
@Serializable
data class MissionActionResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("mission_id") val missionId: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("code") val code: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("message") val message: String? = null,
) {
    val isExpired: Boolean get() = code == "MISSION_EXPIRED" || error == "MISSION_EXPIRED" || status == "expired"
}





