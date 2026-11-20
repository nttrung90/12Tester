package com.ntt.crosstest.util

import com.ntt.crosstest.R
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.UserWarning

/**
 * Localizes system warnings according to warning_type or message template,
 * preventing fixed-language system warnings across locale switches.
 */
object WarningRenderer {

    fun render(warning: UserWarning, localizer: Localizer): String {
        val typeLower = warning.warningType.trim().lowercase()
        return when {
            typeLower == "tester_rejection_streak" || typeLower == "tester_warning" || typeLower == "tester" ->
                localizer.get(R.string.warning_tester_msg)
            typeLower == "owner_rejection_streak" || typeLower == "owner_warning" || typeLower == "owner" ->
                localizer.get(R.string.warning_owner_msg)
            typeLower == "daily_penalty" || typeLower == "penalty" ->
                localizer.get(R.string.notif_msg_daily_penalty)
            else -> {
                val msgLower = warning.message.lowercase()
                when {
                    "tham gia kiểm thử chưa nghiêm túc" in msgLower || "testing was not done seriously" in msgLower ->
                        localizer.get(R.string.warning_tester_msg)
                    "xác nhận kết quả của người kiểm thử chưa nghiêm túc" in msgLower || "confirmation was not done seriously" in msgLower ->
                        localizer.get(R.string.warning_owner_msg)
                    else -> warning.message
                }
            }
        }
    }

    fun renderOwnerWarning(rawMsg: String?, localizer: Localizer): String {
        val msgLower = rawMsg?.lowercase().orEmpty()
        return when {
            "xác nhận kết quả của người kiểm thử chưa nghiêm túc" in msgLower ||
            "confirmation was not done seriously" in msgLower ||
            "chưa nghiêm túc" in msgLower -> localizer.get(R.string.warning_owner_msg)
            !rawMsg.isNullOrBlank() -> rawMsg
            else -> localizer.get(R.string.owner_approve_warning_fallback)
        }
    }
}
