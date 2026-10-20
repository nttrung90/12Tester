package com.ntt.crosstest.data

/** Quyết định lỗi nộp báo cáo có nên retry hay không. */
object ReportSubmitPolicy {

    private val permanentServerCodes = listOf(
        "same_screenshot_rejected",
        "report_already_reviewed",
        "report_not_found",
        "permission_denied",
        "permission denied",
        "account_locked",
        "campaign_not_open",
        "cannot_test_own_app",
        "not_authenticated",
        "campaign_not_found",
        "image_hash_required",
        "invalid_image_hash",
        "invalid_screenshot_url",
    )

    private val permanentLocalErrors = listOf(
        "image_read_failed",
        "bitmap compression failed",
        "invalid bitmap dimensions",
        "screenshot_file_missing",
        "file not found",
        "decode failed",
    )

    fun isRetryable(error: Exception): Boolean {
        val message = error.message.orEmpty().lowercase()
        if (permanentLocalErrors.any { it in message }) return false
        return permanentServerCodes.none { it in message }
    }
}
