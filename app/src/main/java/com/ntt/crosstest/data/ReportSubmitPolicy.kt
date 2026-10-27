package com.ntt.crosstest.data

import com.google.firebase.storage.StorageException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException

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
        if (error is CancellationException) return false

        // Phân loại StorageException theo errorCode
        if (error is StorageException) {
            when (error.errorCode) {
                StorageException.ERROR_NOT_AUTHORIZED,
                StorageException.ERROR_NOT_AUTHENTICATED,
                StorageException.ERROR_PROJECT_NOT_FOUND,
                StorageException.ERROR_BUCKET_NOT_FOUND,
                StorageException.ERROR_INVALID_CHECKSUM -> return false
                else -> {}
            }
        }

        // Phân loại Supabase RestException theo status code
        if (error is RestException) {
            val status = error.statusCode
            if (status in listOf(400, 401, 403, 404, 422)) {
                return false
            }
        }

        val message = error.message.orEmpty().lowercase()
        if (permanentLocalErrors.any { it in message }) return false
        return permanentServerCodes.none { it in message }
    }
}

