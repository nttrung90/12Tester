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
    @SerialName("role") val role: String = "member",
    @SerialName("is_locked") val isLocked: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isAdmin: Boolean get() = role.equals("admin", ignoreCase = true)
    val isModerator: Boolean get() = role.equals("moderator", ignoreCase = true)
    val isMember: Boolean get() = !isAdmin && !isModerator
    val canModerate: Boolean get() = isAdmin || isModerator
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
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isOpen: Boolean get() = status == "open"
    val isPaused: Boolean get() = status == "paused"
    val isProduction: Boolean get() = status == "production"
    val safeIconUrl: String get() = iconUrl.orEmpty()
    val safeTestUsername: String get() = testUsername.orEmpty()
    val safeTestPassword: String get() = testPassword.orEmpty()
    val dailyTarget: Int get() = if (targetTesters in listOf(12, 15, 20)) targetTesters else 12
    val targetCost: Int get() = when (dailyTarget) {
        15 -> 10
        20 -> 20
        else -> 0
    }
}

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
    val credits: Int = 100,
    val checkinCount: Int = 0,
    val campaignsCount: Int = 0,
    val rank: Int = 0,
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
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("tester_name") val testerName: String? = null,
    @SerialName("tester_avatar") val testerAvatar: String? = null,
    @SerialName("tester_credits") val testerCredits: Int? = null,
    @kotlinx.serialization.Transient val testerProfile: Profile? = null,
) {
    val isPending: Boolean get() = status == "pending"
    val isApproved: Boolean get() = status == "approved"
    val isRejected: Boolean get() = status == "rejected"

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
                val todayStr = java.time.LocalDate.now().toString()
                createdAt.take(10) == todayStr
            } catch (_: Exception) {
                false
            }
        }
    }

    fun remainingHoursUntilAutoAccept(): Int {
        if (createdAt.isNullOrBlank()) return 24
        return try {
            val created = java.time.Instant.parse(createdAt)
            val deadline = created.plus(java.time.Duration.ofHours(24))
            val now = java.time.Instant.now()
            if (now.isAfter(deadline)) 0
            else {
                val hours = java.time.Duration.between(now, deadline).toHours().toInt()
                (hours + 1).coerceAtLeast(1)
            }
        } catch (_: Exception) {
            24
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
data class SubmitTesterReportParams(
    @SerialName("p_campaign_id") val campaignId: String,
    @SerialName("p_content") val content: String = "",
    @SerialName("p_screenshot_url") val screenshotUrl: String? = null,
    @SerialName("p_rating") val rating: Int = 5,
)

@Serializable
data class SubmitTesterReportResult(
    @SerialName("success") val success: Boolean = false,
    @SerialName("report_id") val reportId: String = "",
    @SerialName("status") val status: String = "pending",
)

@Serializable
data class GetOwnerReportsParams(
    @SerialName("p_campaign_id") val campaignId: String,
)





