package com.ntt.crosstest.util

import com.ntt.crosstest.R
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.TesterReport

/**
 * Handles localized rendering and backward compatibility for rejection reasons.
 * Decouples system reason codes from sender's language, while strictly preserving custom text.
 */
object RejectionReasonRenderer {
    const val CODE_DUPLICATE_OR_OLD = "REASON_DUPLICATE_OR_OLD"
    const val CODE_INCORRECT_APP = "REASON_INCORRECT_APP"
    const val CODE_BLURRY_OR_INVALID = "REASON_BLURRY_OR_INVALID"
    const val CODE_NOT_ACTUALLY_TESTED = "REASON_NOT_ACTUALLY_TESTED"
    const val CODE_ADMIN_REMOVED = "REASON_ADMIN_REMOVED"
    const val CODE_FALLBACK_UNMET = "REASON_FALLBACK_UNMET"

    fun getPredefinedReasonCode(index: Int): String? = when (index) {
        0 -> CODE_DUPLICATE_OR_OLD
        1 -> CODE_INCORRECT_APP
        2 -> CODE_NOT_ACTUALLY_TESTED
        else -> null
    }

    fun formatForSubmission(reasonCode: String?, customText: String?): String {
        return if (!reasonCode.isNullOrBlank()) {
            "[CODE:$reasonCode]"
        } else {
            customText.orEmpty().trim()
        }
    }

    fun render(report: TesterReport, localizer: Localizer): String =
        render(report.rejectionReason, report.reasonCode, localizer)

    fun render(rawReason: String?, reasonCode: String? = null, localizer: Localizer): String {
        if (rawReason.isNullOrBlank() && reasonCode.isNullOrBlank()) return ""
        val effectiveCode = reasonCode?.takeIf { it.isNotBlank() }
            ?: extractCode(rawReason)
            ?: detectLegacyCode(rawReason)

        return when (effectiveCode) {
            CODE_DUPLICATE_OR_OLD -> localizer.get(R.string.oad_reject_reason_1)
            CODE_INCORRECT_APP, CODE_BLURRY_OR_INVALID -> localizer.get(R.string.oad_reject_reason_2)
            CODE_NOT_ACTUALLY_TESTED -> localizer.get(R.string.oad_reject_reason_3)
            CODE_ADMIN_REMOVED -> localizer.get(R.string.admin_removed_campaign_reason)
            CODE_FALLBACK_UNMET -> localizer.get(R.string.oad_reject_fallback_reason)
            else -> {
                val cleaned = rawReason.orEmpty().replace(Regex("\\[CODE:[^\\]]+\\]"), "").trim()
                if (cleaned.isNotBlank()) cleaned else localizer.get(R.string.oad_reject_fallback_reason)
            }
        }
    }

    fun extractCode(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("[CODE:") && trimmed.contains("]")) {
            return trimmed.substringAfter("[CODE:").substringBefore("]").trim()
        }
        if (trimmed.startsWith("REASON_")) {
            return trimmed
        }
        return null
    }

    /**
     * Matches only exact known legacy template strings.
     * Never uses loose word substrings like "admin" or "duplicate" to avoid corrupting user custom text.
     */
    fun detectLegacyCode(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        return when {
            trimmed.equals("Ảnh trùng lặp với ngày hôm trước", ignoreCase = true) ||
            trimmed.equals("Photo duplicated from the previous day", ignoreCase = true) ||
            trimmed.equals("Ảnh chụp màn hình bị trùng lặp hoặc không phải của ngày hôm nay", ignoreCase = true) ||
            trimmed.equals("Screenshot is duplicated or not from today", ignoreCase = true) ||
            trimmed.equals("Ảnh trùng lặp", ignoreCase = true) ||
            trimmed.equals("Ảnh bị trùng lặp", ignoreCase = true) ||
            trimmed.equals("Duplicate screenshot", ignoreCase = true) -> CODE_DUPLICATE_OR_OLD

            trimmed.equals("Ảnh không rõ ràng / không phải app này", ignoreCase = true) ||
            trimmed.equals("Unclear photo / not this app", ignoreCase = true) ||
            trimmed.equals("Ảnh chụp không đúng ứng dụng yêu cầu kiểm thử hoặc ảnh mờ", ignoreCase = true) ||
            trimmed.equals("Screenshot is not of the required app or is blurry", ignoreCase = true) ||
            trimmed.equals("Ảnh không đúng ứng dụng cần kiểm thử", ignoreCase = true) ||
            trimmed.equals("Ảnh không đúng ứng dụng", ignoreCase = true) ||
            trimmed.equals("Not the required app", ignoreCase = true) -> CODE_INCORRECT_APP

            trimmed.equals("Chưa mở ứng dụng hoặc chưa test thực tế", ignoreCase = true) ||
            trimmed.equals("App not opened or not actually tested", ignoreCase = true) ||
            trimmed.equals("Ứng dụng chưa được mở hoặc chưa thực sự kiểm thử", ignoreCase = true) ||
            trimmed.equals("App was not opened or not actually tested", ignoreCase = true) ||
            trimmed.equals("Chưa mở ứng dụng", ignoreCase = true) -> CODE_NOT_ACTUALLY_TESTED

            trimmed.equals("Gỡ bỏ bởi admin", ignoreCase = true) ||
            trimmed.equals("Bị gỡ bởi admin", ignoreCase = true) ||
            trimmed.equals("Removed by admin", ignoreCase = true) ||
            trimmed.equals("Campaign removed by admin", ignoreCase = true) ||
            trimmed.equals("Chiến dịch đã bị quản trị viên gỡ bỏ", ignoreCase = true) -> CODE_ADMIN_REMOVED

            trimmed.equals("Chưa đạt yêu cầu kiểm thử", ignoreCase = true) ||
            trimmed.equals("Did not meet testing requirements", ignoreCase = true) ||
            trimmed.equals("Chưa đạt yêu cầu", ignoreCase = true) ||
            trimmed.equals("Does not meet requirements", ignoreCase = true) -> CODE_FALLBACK_UNMET

            else -> null
        }
    }
}
