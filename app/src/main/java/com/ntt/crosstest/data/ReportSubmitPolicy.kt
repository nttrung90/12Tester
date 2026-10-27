package com.ntt.crosstest.data

import com.google.firebase.storage.StorageException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Quyết định lỗi nộp báo cáo có nên retry hay không. */
object ReportSubmitPolicy {
    enum class Operation { WORKER, UPLOAD, RESOLVE_URL }

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
        "screenshot_file_changed",
        "report_account_mismatch",
        "invalid_report_result",
        "report_idempotency_unavailable",
    )

    private fun causes(error: Throwable): List<Throwable> =
        generateSequence(error) { it.cause }.take(16).toList()

    fun cancellationCause(error: Throwable): CancellationException? =
        causes(error).filterIsInstance<CancellationException>().firstOrNull()

    fun isRetryable(error: Exception, operation: Operation = Operation.WORKER): Boolean {
        if (cancellationCause(error) != null) return false
        val chain = causes(error)

        // Phân loại StorageException theo errorCode
        val storageError = chain.filterIsInstance<StorageException>().firstOrNull()
        if (storageError != null) {
            return when (storageError.errorCode) {
                StorageException.ERROR_NOT_AUTHORIZED,
                StorageException.ERROR_NOT_AUTHENTICATED,
                StorageException.ERROR_PROJECT_NOT_FOUND,
                StorageException.ERROR_BUCKET_NOT_FOUND,
                StorageException.ERROR_OBJECT_NOT_FOUND,
                StorageException.ERROR_QUOTA_EXCEEDED,
                StorageException.ERROR_CANCELED -> false
                StorageException.ERROR_INVALID_CHECKSUM -> operation != Operation.RESOLVE_URL
                StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> true
                else -> storageError.httpResultCode == 408 || storageError.httpResultCode == 429 ||
                    storageError.httpResultCode in 500..599 || chain.any { it is IOException }
            }
        }

        // Phân loại Supabase RestException theo status code
        val restError = chain.filterIsInstance<RestException>().firstOrNull()
        if (restError != null) {
            val status = restError.statusCode
            if (status in listOf(400, 401, 403, 404, 422)) {
                return false
            }
            if (operation == Operation.RESOLVE_URL) return status == 408 || status == 429 || status in 500..599
        }

        val message = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        if (permanentLocalErrors.any { it in message }) return false
        if (permanentServerCodes.any { it in message }) return false
        return operation != Operation.RESOLVE_URL || chain.any { it is IOException }
    }
}

