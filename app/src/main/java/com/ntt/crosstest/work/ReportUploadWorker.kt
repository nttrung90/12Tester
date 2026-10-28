package com.ntt.crosstest.work

import android.content.Context
import android.util.Log
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.data.PendingReport
import com.ntt.crosstest.data.SubmitTesterReportResult
import com.google.firebase.storage.StorageException
import io.github.jan.supabase.exceptions.RestException
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.ReportSubmitPolicy
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.util.FirebaseStorageHelper
import com.ntt.crosstest.util.PreparedScreenshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.File

/**
 * Upload ảnh + gọi RPC nộp báo cáo trong WorkManager.
 *
 * Request được lưu trong [PendingReportStore] trước khi enqueue, nên process
 * chết giữa chừng vẫn retry được. Ảnh dùng path cố định theo request id;
 * retry đọc lại object cũ khi cần, không ghi đè hoặc tạo file rác.
 */
class ReportUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private var operationsOverride: ReportUploadOperations? = null

    internal constructor(context: Context, params: WorkerParameters, operations: ReportUploadOperations) :
        this(context, params) {
        operationsOverride = operations
    }

    override suspend fun doWork(): ListenableWorker.Result {
        val requestId = inputData.getString(EXTRA_REQUEST_ID)
            ?: return failure("missing_request_id")
        val request = PendingReportStore.read(applicationContext, requestId)
            ?: return failure("pending_request_not_found")

        // Replay works offline and even before Supabase is initialized after restart.
        request.completedResult?.let { return success(it) }
        val operations = operationsOverride ?: productionOperations()
        trace(request.id, "start attempt=$runAttemptCount")

        if (!operations.isReady) {
            return if (runAttemptCount < MAX_ATTEMPTS - 1) {
                trace(request.id, "supabase_not_ready retry=true")
                ListenableWorker.Result.retry()
            } else {
                failure("supabase_not_ready")
            }
        }

        // Dùng repository chung của app: submitFeedback() invalidate các cache
        // (dashboard, profile, detail...) mà Home/CampaignDetail đang đọc;
        // tạo instance riêng ở đây khiến badge Credit bị cũ tới hết TTL cache.
        var uploadedUrl = request.uploadedUrl
        var uploadedHash = request.uploadedHash
        var uploadedThumbUrl = request.uploadedThumbnailUrl

        try {
            verifyAccount(operations, request.userId)
            if (!request.screenshotPath.isNullOrBlank() && uploadedUrl.isNullOrBlank()) {
                val imageHash = request.imageHash
                if (imageHash.isNullOrBlank()) {
                    return failure("image_hash_missing")
                }
                val pendingFile = File(request.screenshotPath)
                if (!pendingFile.isFile) {
                    return failure("screenshot_file_missing")
                }
                if (request.screenshotSha256 != null &&
                    PendingReportStore.screenshotSha256(pendingFile) != request.screenshotSha256
                ) return failure("screenshot_file_changed")
                val thumbFile = request.thumbnailPath?.let { File(it) }?.takeIf { it.isFile }
                val prepared = PreparedScreenshot(
                    uploadId = request.id,
                    file = pendingFile,
                    imageHash = imageHash,
                    sizeBytes = pendingFile.length(),
                    contentType = request.contentType,
                    thumbnailFile = thumbFile,
                    thumbnailSizeBytes = thumbFile?.length(),
                )
                uploadedUrl = timed(request.id, "upload") { operations.upload(
                    prepared = prepared,
                    campaignId = request.campaignId,
                    userId = request.userId,
                ) }
                uploadedHash = imageHash

                if (thumbFile != null && uploadedThumbUrl.isNullOrBlank()) {
                    uploadedThumbUrl = try {
                        timed(request.id, "upload_thumb") {
                            operations.uploadThumbnail(thumbFile, request.campaignId, request.userId, request.id)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        trace(request.id, "thumb_upload_failed non-fatal: ${e.message}")
                        null
                    }
                }

                // Lưu URL ngay: nếu RPC fail thì lần retry không upload lại.
                PendingReportStore.markUploaded(
                    context = applicationContext,
                    id = request.id,
                    url = uploadedUrl,
                    hash = uploadedHash,
                    thumbnailUrl = uploadedThumbUrl,
                )
                trace(request.id, "mark_uploaded")
            }

            if (request.needsJoin) {
                // Không chặn nộp báo cáo chỉ vì join thất bại; RPC vẫn kiểm tra quyền.
                // Bảo toàn CancellationException, không dùng runCatching nuốt cancellation.
                try {
                    timed(request.id, "join") { operations.join(request.campaignId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Lỗi join tạm thời không chặn submitFeedback, server RPC sẽ xác thực
                }
            }

            currentCoroutineContext().ensureActive()
            verifyAccount(operations, request.userId)
            val response = timed(request.id, "rpc") {
                operations.submit(request, uploadedUrl ?: request.fallbackScreenshotUrl,
                    uploadedHash ?: request.fallbackImageHash, uploadedThumbUrl)
            }
            val result = response.copy(
                screenshotUrl = response.screenshotUrl ?: uploadedUrl ?: request.fallbackScreenshotUrl,
                imageHash = response.imageHash ?: uploadedHash ?: request.fallbackImageHash,
                thumbnailUrl = response.thumbnailUrl ?: uploadedThumbUrl,
            )
            // Keep the request and receipt until WorkManager commits SUCCEEDED.
            PendingReportStore.markCompleted(applicationContext, request.id, result)
            trace(request.id, "receipt_saved")
            return success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReportSubmitPolicy.cancellationCause(e)?.let { throw it }
            val retryable = ReportSubmitPolicy.isRetryable(e) && runAttemptCount < MAX_ATTEMPTS - 1
            val code = when (e) {
                is StorageException -> "storage=${e.errorCode} http=${e.httpResultCode}"
                is RestException -> "http=${e.statusCode}"
                else -> ""
            }
            trace(request.id, "error=${e.javaClass.simpleName} $code retry=$retryable attempt=$runAttemptCount")
            if (retryable) return ListenableWorker.Result.retry()
            return failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun success(result: SubmitTesterReportResult): ListenableWorker.Result {
        val output = Data.Builder()
            .putBoolean(KEY_SUCCESS, true)
            .putString(KEY_STATUS, result.status)
            .putBoolean(KEY_PROVISIONAL_GRANTED, result.provisionalGranted)
            .putInt(KEY_PROVISIONAL_AMOUNT, result.provisionalAmount)
            .putString(KEY_REPORT_ID, result.reportId)
        result.screenshotUrl?.let { output.putString(KEY_SCREENSHOT_URL, it) }
        result.imageHash?.let { output.putString(KEY_IMAGE_HASH, it) }
        result.thumbnailUrl?.let { output.putString(KEY_THUMBNAIL_URL, it) }
        return ListenableWorker.Result.success(output.build())
    }

    private fun verifyAccount(operations: ReportUploadOperations, userId: String) {
        val active = operations.currentUserId ?: throw IOException("report_session_not_ready")
        check(active == userId) { "report_account_mismatch" }
    }

    private fun productionOperations() = object : ReportUploadOperations {
        override val isReady get() = SupabaseHolder.isInitialized()
        private val repository get() = (applicationContext as TestCheoApp).campaignRepository
        override val currentUserId get() = repository.currentUserId()
        override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String) =
            FirebaseStorageHelper.uploadReportScreenshot(prepared, campaignId, userId)
        override suspend fun uploadThumbnail(thumbnailFile: File, campaignId: String, userId: String, uploadId: String): String? =
            FirebaseStorageHelper.uploadReportThumbnail(thumbnailFile, campaignId, userId, uploadId)
        override suspend fun join(campaignId: String) { repository.joinCampaign(campaignId) }
        override suspend fun submit(request: PendingReport, url: String?, hash: String?) =
            repository.submitFeedback(request.campaignId, request.rating, request.comment, url, hash,
                request.id, requireIdempotency = true)
        override suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?) =
            repository.submitFeedback(request.campaignId, request.rating, request.comment, url, hash,
                request.id, requireIdempotency = true, thumbnailUrl = thumbnailUrl)
    }

    private suspend fun <T> timed(id: String, stage: String, action: suspend () -> T): T {
        val start = SystemClock.elapsedRealtime()
        trace(id, "${stage}_start")
        try { return action() } finally {
            trace(id, "${stage}_end elapsed_ms=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private fun trace(id: String, event: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "req=${id.take(8)} $event")
    }

    private fun failure(message: String): ListenableWorker.Result =
        ListenableWorker.Result.failure(
            workDataOf(KEY_ERROR to message.take(MAX_ERROR_LENGTH))
        )

    companion object {
        private const val TAG = "ReportUploadWorker"
        const val EXTRA_REQUEST_ID = "request_id"
        const val KEY_SUCCESS = "success"
        const val KEY_STATUS = "status"
        const val KEY_PROVISIONAL_GRANTED = "provisional_granted"
        const val KEY_PROVISIONAL_AMOUNT = "provisional_amount"
        const val KEY_REPORT_ID = "report_id"
        const val KEY_SCREENSHOT_URL = "screenshot_url"
        const val KEY_IMAGE_HASH = "image_hash"
        const val KEY_THUMBNAIL_URL = "thumbnail_url"
        const val KEY_ERROR = "error"

        private const val MAX_ATTEMPTS = 3
        private const val MAX_ERROR_LENGTH = 1_000
    }
}

internal interface ReportUploadOperations {
    val isReady: Boolean
    val currentUserId: String?
    suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String
    suspend fun uploadThumbnail(thumbnailFile: File, campaignId: String, userId: String, uploadId: String): String? = null
    suspend fun join(campaignId: String)
    suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult
    suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?): SubmitTesterReportResult =
        submit(request, url, hash)
}
