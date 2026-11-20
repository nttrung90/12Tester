package com.ntt.crosstest.util

import com.ntt.crosstest.R
import com.ntt.crosstest.data.CreditLog
import com.ntt.crosstest.data.Localizer

/**
 * Localizes Credit transaction history dynamically according to the user's active locale.
 * Maps both structured actions and legacy note strings, avoiding stale creation-time language.
 */
object CreditLogRenderer {

    fun render(log: CreditLog, localizer: Localizer): String {
        val actionKey = log.action.trim().lowercase()
        return when {
            actionKey == "welcome" -> localizer.get(R.string.credit_action_welcome)
            actionKey == "daily_login" || actionKey == "daily_login_bonus" -> localizer.get(R.string.credit_action_daily_login)
            actionKey == "quest_reward" -> localizer.get(R.string.credit_action_quest_reward)
            actionKey == "topup" || actionKey == "credit_topup" -> localizer.get(R.string.credit_action_topup)
            actionKey == "post_app" || actionKey == "campaign_create" -> {
                val lowerNote = log.note.orEmpty().lowercase()
                if ("target upgrade" in lowerNote || "nâng mục tiêu" in lowerNote || "nâng cấp mục tiêu" in lowerNote) {
                    localizer.get(R.string.credit_action_target_upgrade)
                } else {
                    localizer.get(R.string.credit_action_post_app)
                }
            }
            actionKey == "target_upgrade" -> localizer.get(R.string.credit_action_target_upgrade)
            actionKey == "slot_unlock" || actionKey == "unlock_slot" -> localizer.get(R.string.credit_action_slot_unlock)
            actionKey == "test_report_provisional" || actionKey == "test_provisional_reward" -> localizer.get(R.string.credit_action_test_provisional)
            actionKey == "test_report_bonus" || actionKey == "owner_bonus" || actionKey == "tip" -> localizer.get(R.string.credit_action_test_bonus)
            actionKey == "test_review_reward" || actionKey == "owner_review_reward" -> localizer.get(R.string.credit_action_test_review)
            actionKey == "test_rejected_clawback" -> localizer.get(R.string.credit_action_clawback)
            actionKey == "daily_penalty" -> localizer.get(R.string.credit_action_daily_penalty)
            actionKey == "mission_create" || actionKey == "mission_fee" -> localizer.get(R.string.credit_action_mission_create)
            actionKey == "mission_reward" -> localizer.get(R.string.credit_action_mission_reward)
            actionKey == "mission_clawback" -> localizer.get(R.string.credit_action_mission_clawback)
            actionKey == "app_update_deposit" || actionKey == "app_update_reserve" -> localizer.get(R.string.credit_action_app_update_deposit)
            actionKey == "app_update_refund" -> localizer.get(R.string.credit_action_app_update_refund)
            actionKey == "app_update_reward" -> localizer.get(R.string.credit_action_app_update_reward)
            actionKey == "campaign_push" || actionKey == "push_campaign" -> localizer.get(R.string.credit_action_campaign_push)
            actionKey == "tester_bonus_reserve" -> localizer.get(R.string.credit_action_tester_bonus_reserve)
            actionKey == "tester_bonus_reward" -> localizer.get(R.string.credit_action_tester_bonus_reward)
            actionKey == "tester_bonus_refund" -> localizer.get(R.string.credit_action_tester_bonus_refund)
            actionKey == "admin_adjustment" || actionKey == "admin_grant" || actionKey == "admin_deduct" -> localizer.get(R.string.credit_action_admin_adjustment)
            actionKey == "self_unlock" || actionKey == "account_self_unlock" -> localizer.get(R.string.credit_action_self_unlock)
            else -> {
                val note = log.note?.trim()
                if (!note.isNullOrBlank()) {
                    detectLegacyNote(note, log.amount, localizer) ?: note
                } else if (log.amount > 0) {
                    localizer.get(R.string.credit_action_generic_reward)
                } else if (log.amount < 0) {
                    localizer.get(R.string.credit_action_generic_deduct)
                } else {
                    log.action.ifBlank { localizer.get(R.string.credit_action_generic_reward) }
                }
            }
        }
    }

    private fun detectLegacyNote(note: String, amount: Int, localizer: Localizer): String? {
        val lower = note.lowercase()
        // Priority 1: Check clawback/deductions FIRST to avoid classifying negative clawbacks as provisional rewards
        if ("thu hồi" in lower || "clawback" in lower || (amount < 0 && ("từ chối" in lower || "rejected" in lower))) {
            return localizer.get(R.string.credit_action_clawback)
        }

        return when {
            "thưởng mở tài khoản" in lower || "mở tài khoản" in lower || "welcome" in lower -> localizer.get(R.string.credit_action_welcome)
            "thưởng kiểm thử" in lower || "test reward" in lower -> localizer.get(R.string.credit_action_generic_reward)
            "thưởng đăng nhập" in lower || "daily login" in lower -> localizer.get(R.string.credit_action_daily_login)
            "thưởng nhiệm vụ" in lower || "quest reward" in lower -> localizer.get(R.string.credit_action_quest_reward)
            "nạp credit" in lower || "top-up" in lower || "topup" in lower -> localizer.get(R.string.credit_action_topup)
            "đăng ứng dụng" in lower || "phí đăng ứng dụng" in lower || "post app" in lower -> localizer.get(R.string.credit_action_post_app)
            "nâng cấp mục tiêu" in lower || "phí nâng mục tiêu" in lower || "target upgrade" in lower -> localizer.get(R.string.credit_action_target_upgrade)
            "mở khóa slot" in lower || "mở khóa vị trí" in lower || "unlock slot" in lower -> localizer.get(R.string.credit_action_slot_unlock)
            amount > 0 && ("tạm nhận" in lower || "tạm tính" in lower || "provisional" in lower) -> localizer.get(R.string.credit_action_test_provisional)
            "tiền boa" in lower || "thưởng thêm" in lower || "tip" in lower -> localizer.get(R.string.credit_action_test_bonus)
            "duyệt bài" in lower || "duyệt kết quả" in lower || "review reward" in lower -> localizer.get(R.string.credit_action_test_review)
            "phạt thiếu chỉ tiêu" in lower || "daily penalty" in lower -> localizer.get(R.string.credit_action_daily_penalty)
            "đặt cọc cập nhật" in lower || "update deposit" in lower -> localizer.get(R.string.credit_action_app_update_deposit)
            "hoàn cọc" in lower || "refund" in lower -> localizer.get(R.string.credit_action_app_update_refund)
            "thưởng cập nhật" in lower || "update reward" in lower -> localizer.get(R.string.credit_action_app_update_reward)
            "đẩy app" in lower || "push app" in lower || "ưu tiên" in lower -> localizer.get(R.string.credit_action_campaign_push)
            "giữ trước ngân sách thưởng tester" in lower || "tester bonus reserve" in lower -> localizer.get(R.string.credit_action_tester_bonus_reserve)
            "thưởng thêm cho tester" in lower || "tester bonus reward" in lower -> localizer.get(R.string.credit_action_tester_bonus_reward)
            "hoàn ngân sách thưởng tester" in lower || "tester bonus refund" in lower -> localizer.get(R.string.credit_action_tester_bonus_refund)
            "tự mở khóa" in lower || "self unlock" in lower -> localizer.get(R.string.credit_action_self_unlock)
            else -> null
        }
    }
}
