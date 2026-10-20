package com.ntt.crosstest.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.ReportSubmitPolicy
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.util.FirebaseStorageHelper
import com.ntt.crosstest.util.PreparedScreenshot
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Upload ảnh + gọi RPC nộp báo cáo trong WorkManager.
 *
 * Request được lưu trong [PendingReportStore] trước khi enqueue, nên process
 * chết giữa chừng vẫn retry được. Ảnh dùng path cố định theo request id;
 * retry chỉ ghi đè đúng object cũ, không tạo thêm file rác.
 */
class ReportUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        val requestId = inputData.getString(EXTRA_REQUEST_ID)
            ?: return failure("missing_request_id")
        val request = PendingReportStore.read(applicationContext, requestId)
            ?: return failure("pending_request_not_found")

        if (!SupabaseHolder.isInitialized()) {
            return if (runAttemptCount < MAX_ATTEMPTS - 1) {
                ListenableWorker.Result.retry()
            } else {
                failure("supabase_not_ready")
            }
        }

        val repository = CampaignRepository(SupabaseHolder.get(), Localizer(applicationContext))
        var uploadedUrl = request.uploadedUrl
        var uploadedHash = request.uploadedHash

        try {
            if (!request.screenshotPath.isNullOrBlank() && uploadedUrl.isNullOrBlank()) {
                val imageHash = request.imageHash
                if (imageHash.isNullOrBlank()) {
                    return failure("image_hash_missing")
                }
                val pendingFile = File(request.screenshotPath)
                if (!pendingFile.isFile) {
                    return failure("screenshot_file_missing")
                }
                val prepared = PreparedScreenshot(
                    uploadId = request.id,
                    file = pendingFile,
                    imageHash = imageHash,
                    sizeBytes = pendingFile.length(),
                    contentType = request.contentType,
                )
                uploadedUrl = FirebaseStorageHelper.uploadReportScreenshot(
                    prepared = prepared,
                    campaignId = request.campaignId,
                    userId = request.userId,
                )
                uploadedHash = imageHash
                // Lưu URL ngay: nếu RPC fail thì lần retry không upload lại.
                PendingReportStore.markUploaded(
                    context = applicationContext,
                    id = request.id,
                    url = uploadedUrl,
                    hash = uploadedHash,
                )
            }

            if (request.needsJoin) {
                // Không chặn nộp báo cáo chỉ vì join thất bại; RPC vẫn kiểm tra quyền.
                runCatching { repository.joinCampaign(request.campaignId) }
            }

            val result = repository.submitFeedback(
                campaignId = request.campaignId,
                rating = request.rating,
                comment = request.comment,
                screenshotUrl = uploadedUrl ?: request.fallbackScreenshotUrl,
                imageHash = uploadedHash ?: request.fallbackImageHash,
                clientSubmitId = request.id,
            )

            PendingReportStore.delete(applicationContext, request.id)

            val output = Data.Builder()
                .putBoolean(KEY_SUCCESS, true)
                .putString(KEY_STATUS, result.status)
                .putBoolean(KEY_PROVISIONAL_GRANTED, result.provisionalGranted)
                .putInt(KEY_PROVISIONAL_AMOUNT, result.provisionalAmount)
                .putString(KEY_REPORT_ID, result.reportId)
            (result.screenshotUrl ?: uploadedUrl)?.let {
                output.putString(KEY_SCREENSHOT_URL, it)
            }
            (result.imageHash ?: uploadedHash)?.let {
                output.putString(KEY_IMAGE_HASH, it)
            }
            return ListenableWorker.Result.success(output.build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ReportSubmitPolicy.isRetryable(e) && runAttemptCount < MAX_ATTEMPTS - 1) {
                return ListenableWorker.Result.retry()
            }
            return failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun failure(message: String): ListenableWorker.Result =
        ListenableWorker.Result.failure(
            workDataOf(KEY_ERROR to message.take(MAX_ERROR_LENGTH))
        )

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val KEY_SUCCESS = "success"
        const val KEY_STATUS = "status"
        const val KEY_PROVISIONAL_GRANTED = "provisional_granted"
        const val KEY_PROVISIONAL_AMOUNT = "provisional_amount"
        const val KEY_REPORT_ID = "report_id"
        const val KEY_SCREENSHOT_URL = "screenshot_url"
        const val KEY_IMAGE_HASH = "image_hash"
        const val KEY_ERROR = "error"

        private const val MAX_ATTEMPTS = 3
        private const val MAX_ERROR_LENGTH = 1_000
    }
}
