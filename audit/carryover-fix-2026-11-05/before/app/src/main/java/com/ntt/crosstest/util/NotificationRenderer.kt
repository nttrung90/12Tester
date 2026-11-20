package com.ntt.crosstest.util

import com.ntt.crosstest.R
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.UserNotification
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renders user notifications dynamically in the active locale.
 * Prioritizes structured event_code and parameters when available,
 * maps legacy system triggers cleanly, and strictly preserves admin/custom messages.
 */
object NotificationRenderer {

    private val CREDIT_CHANGE_TITLE_REGEX = Regex("""(?:Biến động số dư|Balance change):\s*([+-]?\d+)\s*Credit""", RegexOption.IGNORE_CASE)
    private val CREDIT_BALANCE_MSG_REGEX = Regex("""(?:Số dư hiện tại|Current balance):\s*(\d+)\s*Credit""", RegexOption.IGNORE_CASE)
    private val APP_NAME_REGEX = Regex("""(?:Ứng dụng|App)\s+"([^"]+)"""", RegexOption.IGNORE_CASE)

    private fun extractJsonString(element: JsonElement?): String? {
        if (element == null) return null
        return (element as? JsonPrimitive)?.content
    }

    fun renderTitle(notification: UserNotification, localizer: Localizer): String {
        // R03: Admin or custom broadcast notifications must be preserved as-is
        val type = notification.type.trim().lowercase()
        if (type == "admin" || type == "broadcast" || type == "custom") {
            return notification.title
        }

        val eventCode = extractJsonString(notification.data?.get("event_code"))?.lowercase()
        if (!eventCode.isNullOrBlank()) {
            when (eventCode) {
                "credit_change" -> {
                    val amount = extractJsonString(notification.data?.get("amount"))
                        ?: CREDIT_CHANGE_TITLE_REGEX.find(notification.title)?.groupValues?.get(1)
                    if (amount != null) {
                        val formattedAmount = if (!amount.startsWith("+") && !amount.startsWith("-")) "+$amount" else amount
                        return localizer.get(R.string.notif_title_credit_change, formattedAmount)
                    }
                }
                "new_report" -> return localizer.get(R.string.notif_title_new_report)
                "resubmit_report", "report_edited" -> return localizer.get(R.string.notif_title_resubmit_report)
                "test_approved", "report_approved", "tester_reviewed_accepted" -> return localizer.get(R.string.notif_title_test_approved)
                "test_rejected", "report_rejected", "tester_reviewed_rejected" -> return localizer.get(R.string.notif_title_test_rejected)
                "test_bonus" -> return localizer.get(R.string.notif_title_test_bonus)
                "warning" -> return localizer.get(R.string.notif_title_warning)
                "daily_penalty" -> return localizer.get(R.string.notif_title_daily_penalty)
                "app_update_reward" -> return localizer.get(R.string.notif_title_app_update_reward)
                "mission_reward" -> return localizer.get(R.string.notif_title_mission_reward)
            }
        }

        val status = extractJsonString(notification.data?.get("status"))?.lowercase()
        if (status == "approved") return localizer.get(R.string.notif_title_test_approved)
        if (status == "rejected") return localizer.get(R.string.notif_title_test_rejected)

        // Legacy matching: check structured trigger formats
        val matchCredit = CREDIT_CHANGE_TITLE_REGEX.find(notification.title)
        if (matchCredit != null || type == "credit") {
            val amount = matchCredit?.groupValues?.get(1)
                ?: extractJsonString(notification.data?.get("amount"))
            if (amount != null) {
                val formattedAmount = if (!amount.startsWith("+") && !amount.startsWith("-")) "+$amount" else amount
                return localizer.get(R.string.notif_title_credit_change, formattedAmount)
            }
        }

        val titleTrimmed = notification.title.trim()
        val titleLower = titleTrimmed.lowercase()
        return when {
            titleLower.contains("được phê duyệt") || titleLower.contains("đã được duyệt") ||
            titleLower == "kết quả kiểm thử đã được duyệt" || titleLower == "report approved" ->
                localizer.get(R.string.notif_title_test_approved)
            titleLower.contains("bị từ chối") ||
            titleLower == "kết quả kiểm thử bị từ chối" || titleLower == "report rejected" ->
                localizer.get(R.string.notif_title_test_rejected)
            titleLower.startsWith("báo cáo kiểm thử mới") || titleLower.startsWith("new test report") ->
                localizer.get(R.string.notif_title_new_report)
            titleLower.startsWith("báo cáo nộp lại") || titleLower.startsWith("resubmitted test report") ||
            titleLower.contains("cập nhật báo cáo") ->
                localizer.get(R.string.notif_title_resubmit_report)
            titleLower == "nhận được tiền boa" || titleLower == "tip received" ->
                localizer.get(R.string.notif_title_test_bonus)
            titleLower == "cảnh báo từ hệ thống" || titleLower == "system warning" ->
                localizer.get(R.string.notif_title_warning)
            titleLower == "thông báo phạt ngày" || titleLower == "daily penalty notice" ->
                localizer.get(R.string.notif_title_daily_penalty)
            titleLower == "thưởng cập nhật app" || titleLower == "app update reward" ->
                localizer.get(R.string.notif_title_app_update_reward)
            titleLower == "thưởng nhiệm vụ" || titleLower == "mission reward" ->
                localizer.get(R.string.notif_title_mission_reward)
            else -> notification.title
        }
    }

    fun renderMessage(notification: UserNotification, localizer: Localizer): String {
        // R03: Admin or custom broadcast notifications must be preserved as-is
        val type = notification.type.trim().lowercase()
        if (type == "admin" || type == "broadcast" || type == "custom") {
            return notification.message
        }

        val eventCode = extractJsonString(notification.data?.get("event_code"))?.lowercase()
        if (!eventCode.isNullOrBlank()) {
            when (eventCode) {
                "credit_change" -> {
                    val balance = extractJsonString(notification.data?.get("balance"))
                        ?: CREDIT_BALANCE_MSG_REGEX.find(notification.message)?.groupValues?.get(1)
                    if (balance != null) {
                        return localizer.get(R.string.notif_msg_credit_balance, balance)
                    }
                }
                "new_report" -> {
                    val appName = extractJsonString(notification.data?.get("app_name"))
                        ?: APP_NAME_REGEX.find(notification.message)?.groupValues?.get(1)
                        ?: localizer.get(R.string.campaign_this_app)
                    return localizer.get(R.string.notif_msg_new_report, appName)
                }
                "resubmit_report", "report_edited" -> {
                    val appName = extractJsonString(notification.data?.get("app_name"))
                        ?: APP_NAME_REGEX.find(notification.message)?.groupValues?.get(1)
                        ?: localizer.get(R.string.campaign_this_app)
                    return localizer.get(R.string.notif_msg_resubmit_report, appName)
                }
                "test_approved", "report_approved", "tester_reviewed_accepted" -> return localizer.get(R.string.notif_msg_test_approved)
                "test_rejected", "report_rejected", "tester_reviewed_rejected" -> return localizer.get(R.string.notif_msg_test_rejected)
                "test_bonus" -> return localizer.get(R.string.notif_msg_test_bonus)
                "warning" -> return localizer.get(R.string.notif_msg_warning)
                "daily_penalty" -> return localizer.get(R.string.notif_msg_daily_penalty)
                "app_update_reward" -> return localizer.get(R.string.notif_msg_app_update_reward)
                "mission_reward" -> return localizer.get(R.string.notif_msg_mission_reward)
            }
        }

        val status = extractJsonString(notification.data?.get("status"))?.lowercase()
        if (status == "approved") return localizer.get(R.string.notif_msg_test_approved)
        if (status == "rejected") return localizer.get(R.string.notif_msg_test_rejected)

        // Legacy matching: check credit balance message
        val matchBalance = CREDIT_BALANCE_MSG_REGEX.find(notification.message)
        if (matchBalance != null) {
            val balance = matchBalance.groupValues[1]
            return localizer.get(R.string.notif_msg_credit_balance, balance)
        }

        val msgTrimmed = notification.message.trim()
        val msgLower = msgTrimmed.lowercase()

        // 1. Approved test report
        if (msgLower.contains("đã được duyệt thành công") ||
            msgLower.contains("đã được chủ app duyệt thành công") ||
            msgLower == "báo cáo kiểm thử ứng dụng của bạn đã được duyệt thành công." ||
            msgLower == "your app test report has been approved successfully."
        ) {
            return localizer.get(R.string.notif_msg_test_approved)
        }

        // 2. Rejected test report
        if (msgLower.contains("đã bị từ chối") ||
            msgLower.contains("đã bị chủ app từ chối") ||
            msgLower == "báo cáo kiểm thử ứng dụng của bạn đã bị từ chối." ||
            msgLower == "your app test report has been rejected."
        ) {
            return localizer.get(R.string.notif_msg_test_rejected)
        }

        // 3. Resubmitted / edited test report
        if (msgLower.contains("đã nộp lại báo cáo") ||
            msgLower.contains("vừa nộp lại kết quả kiểm thử") ||
            msgLower.contains("vừa cập nhật báo cáo kiểm thử") ||
            notification.data?.get("is_edited")?.toString() == "true"
        ) {
            val appName = extractJsonString(notification.data?.get("app_name"))
                ?: APP_NAME_REGEX.find(notification.message)?.groupValues?.get(1)
                ?: localizer.get(R.string.campaign_this_app)
            return localizer.get(R.string.notif_msg_resubmit_report, appName)
        }

        // 4. New test report
        if (msgLower.contains("vừa nhận được 1 báo cáo kiểm thử mới") ||
            (type == "report" && msgLower.contains("8 giờ"))
        ) {
            val appName = extractJsonString(notification.data?.get("app_name"))
                ?: APP_NAME_REGEX.find(notification.message)?.groupValues?.get(1)
                ?: localizer.get(R.string.campaign_this_app)
            return localizer.get(R.string.notif_msg_new_report, appName)
        }

        return when {
            msgLower == "chủ ứng dụng đã tặng thêm tiền boa cho bạn!" ||
            msgLower == "the app owner sent you an extra tip!" ->
                localizer.get(R.string.notif_msg_test_bonus)
            msgLower == "tài khoản của bạn nhận một cảnh báo từ hệ thống." ||
            msgLower == "your account received a system warning." ->
                localizer.get(R.string.notif_msg_warning)
            msgLower == "bạn bị trừ credit do chưa hoàn thành chỉ tiêu kiểm thử ngày." ||
            msgLower == "credits were deducted due to incomplete daily test quota." ->
                localizer.get(R.string.notif_msg_daily_penalty)
            msgLower == "bạn đã nhận được thưởng cập nhật phiên bản mới!" ||
            msgLower == "you received a reward for updating to the new app version!" ->
                localizer.get(R.string.notif_msg_app_update_reward)
            msgLower == "bạn đã hoàn thành nhiệm vụ và nhận được credit thưởng!" ||
            msgLower == "you completed a mission and received bonus credits!" ->
                localizer.get(R.string.notif_msg_mission_reward)
            else -> notification.message
        }
    }
}
