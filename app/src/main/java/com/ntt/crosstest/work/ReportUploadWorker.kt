package com.ntt.crosstest.work

import android.content.Context
import android.util.Log
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.data.PendingReport
import com.ntt.crosstest.data.ReportAttachmentInput
import com.ntt.crosstest.data.SubmitTesterReportResult
import com.google.firebase.storage.StorageException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.ReportSubmitPolicy
import com.ntt.crosstest.data.ReportSubmissionFailure
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.util.FirebaseStorageHelper
import com.ntt.crosstest.util.SupabaseStorageHelper
import com.ntt.crosstest.data.MediaReservationResult
import com.ntt.crosstest.util.PreparedScreenshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    private var activeStage = "prepare"
    private var operationsOverride: ReportUploadOperations? = null

    internal constructor(context: Context, params: WorkerParameters, operations: ReportUploadOperations) :
        this(context, params) {
        operationsOverride = operations
    }

    // WorkManager uses this notification for expedited work before Android 12.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        NotificationHelper.reportUploadForegroundInfo(applicationContext, id)

    override suspend fun doWork(): ListenableWorker.Result {
        val requestId = inputData.getString(EXTRA_REQUEST_ID)
            ?: return failure("missing_request_id")
        val request = PendingReportStore.read(applicationContext, requestId)
            ?: return failure("pending_request_not_found")

        // Replay works offline and even before Supabase is initialized after restart.
        request.completedResult?.let { return success(it) }
        request.terminalFailure?.let {
            return failure(it.message, it.code, request.id, it.originalCode, it.httpStatus)
        }
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
            networkStage(request.id, "session", SESSION_TIMEOUT_MS) { operations.awaitSession() }
            verifyAccount(operations, request.userId)
            if (request.submissionContext == null) {
                operations.reconcileLegacy(request)?.let {
                    PendingReportStore.markCompleted(applicationContext, request.id, it)
                    return success(it)
                }
            }
            if (request.attachments.isNotEmpty()) {
                var currentReport = request
                for (att in currentReport.attachments) {
                    if (att.uploadedUrl.isNullOrBlank()) {
                        val pendingFile = File(att.screenshotPath)
                        if (!pendingFile.isFile) return failure("screenshot_file_missing")
                        if (att.sha256 != null && PendingReportStore.screenshotSha256(pendingFile) != att.sha256) {
                            return failure("screenshot_file_changed")
                        }
                        val thumbFile = att.thumbnailPath?.let { File(it) }?.takeIf { it.isFile }

                        // 1. Determine & Pin Provider via Reservation
                        val provider: String
                        val bucket: String
                        val storagePath: String
                        val thumbStoragePath: String?

                        var isReservationExpiredUpload = false
                        val isReservationFresh = (System.currentTimeMillis() - currentReport.createdAt) < RESERVATION_MAX_VALIDITY_MS
                        if (!att.targetProvider.isNullOrBlank() && !att.storagePath.isNullOrBlank() && isReservationFresh) {
                            // Pinned across retries / process death trong khung thời gian hợp lệ
                            provider = att.targetProvider
                            bucket = att.bucket ?: if (provider == "supabase") "report-screenshots" else "cross-testers.firebasestorage.app"
                            storagePath = att.storagePath
                            thumbStoragePath = att.thumbnailStoragePath
                        } else {
                            val hasThumbnailContract = (thumbFile != null) || !att.thumbnailStoragePath.isNullOrBlank()
                            val reservation = operations.reserve(
                                campaignId = currentReport.campaignId,
                                uploadId = att.id,
                                estimatedSize = pendingFile.length(),
                                hasThumbnail = hasThumbnailContract,
                                attachmentId = att.id
                            )
                            provider = reservation.provider
                            bucket = reservation.bucket
                            storagePath = reservation.objectPath
                            thumbStoragePath = reservation.thumbnailObjectPath
                            isReservationExpiredUpload = reservation.uploadExpired

                            if (reservation.reservationId != null || reservation.provider == "supabase") {
                                PendingReportStore.markAttachmentReservation(
                                    context = applicationContext,
                                    id = currentReport.id,
                                    attachmentId = att.id,
                                    provider = provider,
                                    bucket = bucket,
                                    storagePath = storagePath,
                                    thumbnailStoragePath = thumbStoragePath,
                                    reservationId = reservation.reservationId
                                )
                            }
                        }

                        val uploadedAttUrl: String
                        var uploadedAttThumbUrl: String? = null

                        if (isReservationExpiredUpload && provider == "supabase") {
                            // upload_expired: true trên Supabase -> Không POST tạo mới; chỉ GET authenticated kiểm tra bytes object đã tồn tại
                            trace(currentReport.id, "upload_expired_verify_att_${att.position}")
                            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
                            val mainDownloadUrl = "$baseUrl/storage/v1/object/authenticated/$bucket/${storagePath.trimStart('/')}"

                            val mainVerified = networkStage(currentReport.id, "verify_remote", RPC_TIMEOUT_MS) {
                                operations.verifySupabase(pendingFile, bucket, storagePath)
                            }
                            if (!mainVerified) {
                                trace(currentReport.id, "main_object_missing_or_mismatch_on_expired")
                                return failure(
                                    message = "media_upload_expired_missing_remote",
                                    code = "media_upload_expired_missing_remote",
                                    requestId = currentReport.id
                                )
                            }
                            uploadedAttUrl = mainDownloadUrl
                            PendingReportStore.markAttachmentUploaded(
                                context = applicationContext,
                                id = currentReport.id,
                                attachmentId = att.id,
                                url = uploadedAttUrl,
                                hash = att.imageHash,
                                thumbnailUrl = null,
                            )
                            trace(currentReport.id, "mark_verified_attachment_${att.position}")

                            if (thumbFile != null && !thumbStoragePath.isNullOrBlank()) {
                                val thumbDownloadUrl = "$baseUrl/storage/v1/object/authenticated/$bucket/${thumbStoragePath.trimStart('/')}"
                                val thumbVerified = try {
                                    operations.verifySupabase(thumbFile, bucket, thumbStoragePath)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: Exception) { false }
                                if (thumbVerified) {
                                    uploadedAttThumbUrl = thumbDownloadUrl
                                    PendingReportStore.markAttachmentUploaded(
                                        context = applicationContext,
                                        id = currentReport.id,
                                        attachmentId = att.id,
                                        url = uploadedAttUrl,
                                        hash = att.imageHash,
                                        thumbnailUrl = uploadedAttThumbUrl,
                                    )
                                    trace(currentReport.id, "mark_verified_thumb_${att.position}")
                                }
                            }
                        } else {
                            val prepared = PreparedScreenshot(
                                uploadId = att.id,
                                file = pendingFile,
                                imageHash = att.imageHash,
                                sizeBytes = pendingFile.length(),
                                contentType = currentReport.contentType,
                                thumbnailFile = thumbFile,
                                thumbnailSizeBytes = thumbFile?.length(),
                            )
                            uploadedAttUrl = networkStage(currentReport.id, "upload", UPLOAD_TIMEOUT_MS) {
                                if (provider == "supabase") {
                                    operations.uploadSupabase(pendingFile, bucket, storagePath)
                                } else {
                                    operations.upload(prepared, currentReport.campaignId, currentReport.userId)
                                }
                            }
                            PendingReportStore.markAttachmentUploaded(
                                context = applicationContext,
                                id = currentReport.id,
                                attachmentId = att.id,
                                url = uploadedAttUrl,
                                hash = att.imageHash,
                                thumbnailUrl = null,
                            )
                            trace(currentReport.id, "mark_uploaded_attachment_${att.position}")

                            if (thumbFile != null) {
                                uploadedAttThumbUrl = try {
                                    withTimeoutOrNull(THUMBNAIL_UPLOAD_TIMEOUT_MS) {
                                        timed(currentReport.id, "upload_thumb_${att.position}") {
                                            if (provider == "supabase" && !thumbStoragePath.isNullOrBlank()) {
                                                operations.uploadSupabaseThumbnail(thumbFile, bucket, thumbStoragePath)
                                            } else {
                                                operations.uploadThumbnail(thumbFile, currentReport.campaignId, currentReport.userId, att.id)
                                            }
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    trace(currentReport.id, "thumb_upload_failed non-fatal: ${e.message}")
                                    null
                                }
                                if (!uploadedAttThumbUrl.isNullOrBlank()) {
                                    PendingReportStore.markAttachmentUploaded(
                                        context = applicationContext,
                                        id = currentReport.id,
                                        attachmentId = att.id,
                                        url = uploadedAttUrl,
                                        hash = att.imageHash,
                                        thumbnailUrl = uploadedAttThumbUrl,
                                    )
                                }
                            }
                        }
                    }
                }

                currentCoroutineContext().ensureActive()
                verifyAccount(operations, currentReport.userId)
                val refreshed = PendingReportStore.read(applicationContext, currentReport.id) ?: currentReport
                val attachmentInputs = refreshed.attachments.map { att ->
                    ReportAttachmentInput(
                        id = att.id,
                        position = att.position,
                        screenshotUrl = att.uploadedUrl.orEmpty(),
                        thumbnailUrl = att.uploadedThumbnailUrl,
                        imageHash = att.uploadedHash ?: att.imageHash,
                        sha256 = att.sha256,
                    )
                }
                val response = networkStage(currentReport.id, "rpc", RPC_TIMEOUT_MS) {
                    operations.submitV3(
                        request = refreshed,
                        attachments = attachmentInputs,
                        missionId = refreshed.missionId,
                    )
                }
                val result = response.copy(
                    screenshotUrl = response.screenshotUrl ?: refreshed.attachments.firstOrNull()?.uploadedUrl,
                    imageHash = response.imageHash ?: refreshed.attachments.firstOrNull()?.uploadedHash ?: refreshed.attachments.firstOrNull()?.imageHash,
                    thumbnailUrl = response.thumbnailUrl ?: refreshed.attachments.firstOrNull()?.uploadedThumbnailUrl,
                    missionId = response.missionId ?: refreshed.missionId,
                    missionTitle = response.missionTitle ?: refreshed.missionTitle,
                )
                activeStage = "receipt"
                PendingReportStore.markCompleted(applicationContext, refreshed.id, result)
                trace(refreshed.id, "receipt_saved")
                return success(result)
            } else if (!request.screenshotPath.isNullOrBlank() && uploadedUrl.isNullOrBlank()) {
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

                val provider: String
                val bucket: String
                val storagePath: String
                val thumbStoragePath: String?
                var isReservationExpiredUpload = false

                val isReservationFresh = (System.currentTimeMillis() - request.createdAt) < RESERVATION_MAX_VALIDITY_MS
                if (!request.targetProvider.isNullOrBlank() && !request.storagePath.isNullOrBlank() && isReservationFresh) {
                    provider = request.targetProvider
                    bucket = request.bucket ?: if (provider == "supabase") "report-screenshots" else "cross-testers.firebasestorage.app"
                    storagePath = request.storagePath
                    thumbStoragePath = request.thumbnailStoragePath
                } else {
                    val hasThumbnailContract = (thumbFile != null) || !request.thumbnailStoragePath.isNullOrBlank()
                    val reservation = operations.reserve(
                        campaignId = request.campaignId,
                        uploadId = request.id,
                        estimatedSize = pendingFile.length(),
                        hasThumbnail = hasThumbnailContract,
                        attachmentId = null
                    )
                    provider = reservation.provider
                    bucket = reservation.bucket
                    storagePath = reservation.objectPath
                    thumbStoragePath = reservation.thumbnailObjectPath
                    isReservationExpiredUpload = reservation.uploadExpired

                    if (reservation.reservationId != null || reservation.provider == "supabase") {
                        PendingReportStore.markReportReservation(
                            context = applicationContext,
                            id = request.id,
                            provider = provider,
                            bucket = bucket,
                            storagePath = storagePath,
                            thumbnailStoragePath = thumbStoragePath,
                            reservationId = reservation.reservationId
                        )
                    }
                }

                if (isReservationExpiredUpload && provider == "supabase") {
                    // upload_expired: true trên Supabase -> Không POST tạo mới; chỉ GET authenticated kiểm tra bytes object đã tồn tại
                    trace(request.id, "upload_expired_verify_single")
                    val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
                    val mainDownloadUrl = "$baseUrl/storage/v1/object/authenticated/$bucket/${storagePath.trimStart('/')}"

                    val mainVerified = networkStage(request.id, "verify_remote", RPC_TIMEOUT_MS) {
                        operations.verifySupabase(pendingFile, bucket, storagePath)
                    }
                    if (!mainVerified) {
                        trace(request.id, "main_object_missing_or_mismatch_on_expired")
                        return failure(
                            message = "media_upload_expired_missing_remote",
                            code = "media_upload_expired_missing_remote",
                            requestId = request.id
                        )
                    }
                    uploadedUrl = mainDownloadUrl
                    uploadedHash = imageHash
                    PendingReportStore.markUploaded(
                        context = applicationContext,
                        id = request.id,
                        url = uploadedUrl,
                        hash = uploadedHash,
                        thumbnailUrl = null,
                    )
                    trace(request.id, "mark_verified_single_full")

                    if (thumbFile != null && !thumbStoragePath.isNullOrBlank()) {
                        val thumbDownloadUrl = "$baseUrl/storage/v1/object/authenticated/$bucket/${thumbStoragePath.trimStart('/')}"
                        val thumbVerified = try {
                            operations.verifySupabase(thumbFile, bucket, thumbStoragePath)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) { false }
                        if (thumbVerified) {
                            uploadedThumbUrl = thumbDownloadUrl
                            PendingReportStore.markUploaded(
                                context = applicationContext,
                                id = request.id,
                                url = uploadedUrl,
                                hash = uploadedHash,
                                thumbnailUrl = uploadedThumbUrl,
                            )
                            trace(request.id, "mark_verified_single_thumb")
                        }
                    }
                } else {
                    val prepared = PreparedScreenshot(
                        uploadId = request.id,
                        file = pendingFile,
                        imageHash = imageHash,
                        sizeBytes = pendingFile.length(),
                        contentType = request.contentType,
                        thumbnailFile = thumbFile,
                        thumbnailSizeBytes = thumbFile?.length(),
                    )
                    uploadedUrl = networkStage(request.id, "upload", UPLOAD_TIMEOUT_MS) {
                        if (provider == "supabase") {
                            operations.uploadSupabase(pendingFile, bucket, storagePath)
                        } else {
                            operations.upload(
                                prepared = prepared,
                                campaignId = request.campaignId,
                                userId = request.userId,
                            )
                        }
                    }
                    uploadedHash = imageHash

                    // Lưu URL ngay: nếu RPC hoặc upload thumbnail sau đó bị hủy/lỗi thì lần retry không upload full lại.
                    PendingReportStore.markUploaded(
                        context = applicationContext,
                        id = request.id,
                        url = uploadedUrl,
                        hash = uploadedHash,
                        thumbnailUrl = null,
                    )
                    trace(request.id, "mark_uploaded_full")

                    if (thumbFile != null && uploadedThumbUrl.isNullOrBlank()) {
                        uploadedThumbUrl = try {
                            withTimeoutOrNull(THUMBNAIL_UPLOAD_TIMEOUT_MS) {
                                timed(request.id, "upload_thumb") {
                                    if (provider == "supabase" && !thumbStoragePath.isNullOrBlank()) {
                                        operations.uploadSupabaseThumbnail(thumbFile, bucket, thumbStoragePath)
                                    } else {
                                        operations.uploadThumbnail(thumbFile, request.campaignId, request.userId, request.id)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            trace(request.id, "thumb_upload_failed non-fatal: ${e.message}")
                            null
                        }
                        if (uploadedThumbUrl == null) {
                            trace(request.id, "thumb_upload_timeout_or_failed non-fatal")
                        } else if (!uploadedThumbUrl.isNullOrBlank()) {
                            PendingReportStore.markUploaded(
                                context = applicationContext,
                                id = request.id,
                                url = uploadedUrl,
                                hash = uploadedHash,
                                thumbnailUrl = uploadedThumbUrl,
                            )
                            trace(request.id, "mark_uploaded_thumb")
                        }
                    }
                }
            }

            // submit_tester_report joins atomically (including the idempotent 6-param
            // signature). A separate membership insert adds a network round trip and
            // can wait for its timeout even though submission could already proceed.

            currentCoroutineContext().ensureActive()
            verifyAccount(operations, request.userId)
            val response = networkStage(request.id, "rpc", RPC_TIMEOUT_MS) {
                operations.submit(
                    request = request,
                    url = uploadedUrl ?: request.fallbackScreenshotUrl,
                    hash = uploadedHash ?: request.fallbackImageHash,
                    thumbnailUrl = uploadedThumbUrl,
                    missionId = request.missionId,
                )
            }
            val result = response.copy(
                screenshotUrl = response.screenshotUrl ?: uploadedUrl ?: request.fallbackScreenshotUrl,
                imageHash = response.imageHash ?: uploadedHash ?: request.fallbackImageHash,
                thumbnailUrl = response.thumbnailUrl ?: uploadedThumbUrl,
                missionId = response.missionId ?: request.missionId,
                missionTitle = response.missionTitle ?: request.missionTitle,
            )
            // Keep the request and receipt until WorkManager commits SUCCEEDED.
            activeStage = "receipt"
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
            val errorCode = ReportSubmissionFailure.codeFor(e)
            val originalCode = ReportSubmissionFailure.originalCodeFor(e)
            val httpStatus = ReportSubmissionFailure.httpStatusFor(e)
            // Release diagnostics contain identifiers/codes only, never raw server payloads.
            Log.w(TAG, "req=${request.id} stage=$activeStage code=$errorCode originalCode=$originalCode httpStatus=$httpStatus retry=false")
            return failure(
                message = e.message ?: e.javaClass.simpleName,
                code = errorCode,
                requestId = request.id,
                originalCode = originalCode,
                httpStatus = httpStatus,
            )
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
        output.putBoolean(KEY_IS_MISSION_REPORT, result.isMissionReport)
        result.missionId?.let { output.putString(KEY_MISSION_ID, it) }
        result.missionTitle?.let { output.putString(KEY_MISSION_TITLE, it) }
        result.missionRewardCredits?.let { output.putInt(KEY_MISSION_REWARD_CREDITS, it) }
        result.missionEndsAt?.let { output.putString(KEY_MISSION_ENDS_AT, it) }
        return ListenableWorker.Result.success(output.build())
    }

    private fun verifyAccount(operations: ReportUploadOperations, userId: String) {
        val active = operations.currentUserId ?: throw IOException("report_session_not_ready")
        check(active == userId) { "report_account_mismatch" }
    }

    private fun productionOperations() = object : ReportUploadOperations {
        override val isReady get() = SupabaseHolder.isInitialized()
        private val repository get() = (applicationContext as TestCheoApp).campaignRepository
        override suspend fun awaitSession() {
            SupabaseHolder.get().auth.sessionStatus.first { it !is SessionStatus.Initializing }
        }
        override val currentUserId get() = repository.currentUserId()
        override suspend fun reconcileLegacy(request: PendingReport): SubmitTesterReportResult? =
            networkStage(request.id, "reconcile", RPC_TIMEOUT_MS) { repository.reconcileLegacyReport(request.id) }
        override suspend fun reserve(
            campaignId: String,
            uploadId: String,
            estimatedSize: Long,
            hasThumbnail: Boolean,
            attachmentId: String?
        ): MediaReservationResult =
            networkStage(uploadId, "reserve", RPC_TIMEOUT_MS) {
                repository.requestMediaReservation(campaignId, uploadId, estimatedSize, hasThumbnail, attachmentId)
            }
        override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String) =
            FirebaseStorageHelper.uploadReportScreenshot(prepared, campaignId, userId)
        override suspend fun uploadThumbnail(thumbnailFile: File, campaignId: String, userId: String, uploadId: String): String? =
            FirebaseStorageHelper.uploadReportThumbnail(thumbnailFile, campaignId, userId, uploadId)
        override suspend fun uploadSupabase(file: File, bucket: String, objectPath: String): String =
            SupabaseStorageHelper.uploadReportScreenshot(file, bucket, objectPath)
        override suspend fun uploadSupabaseThumbnail(thumbnailFile: File, bucket: String, thumbnailPath: String): String? =
            SupabaseStorageHelper.uploadReportThumbnail(thumbnailFile, bucket, thumbnailPath)
        override suspend fun verifySupabase(file: File, bucket: String, objectPath: String): Boolean =
            SupabaseStorageHelper.verifyRemoteObject(file, bucket, objectPath)
        override suspend fun submit(request: PendingReport, url: String?, hash: String?) =
            repository.submitFeedback(request.campaignId, request.rating, request.comment, url, hash,
                request.id, requireIdempotency = true)
        override suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?) =
            submit(request, url, hash, thumbnailUrl, request.missionId)
        override suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?, missionId: String?) =
            repository.submitFeedback(
                campaignId = request.campaignId,
                rating = request.rating,
                comment = request.comment,
                screenshotUrl = url,
                imageHash = hash,
                clientSubmitId = request.id,
                requireIdempotency = true,
                thumbnailUrl = thumbnailUrl,
                missionId = missionId ?: request.missionId,
                submissionContext = request.submissionContext ?: throw IllegalStateException("report_request_requires_review"),
            )
        override suspend fun submitV3(request: PendingReport, attachments: List<ReportAttachmentInput>, missionId: String?): SubmitTesterReportResult =
            repository.submitFeedbackV3(
                campaignId = request.campaignId,
                rating = request.rating,
                comment = request.comment,
                attachments = attachments,
                clientSubmitId = request.id,
                missionId = missionId ?: request.missionId,
                submissionContext = request.submissionContext ?: throw IllegalStateException("report_request_requires_review"),
                expectedDate = request.expectedDate,
            )
    }

    private suspend fun <T> timed(id: String, stage: String, action: suspend () -> T): T {
        val start = SystemClock.elapsedRealtime()
        trace(id, "${stage}_start")
        try { return action() } finally {
            trace(id, "${stage}_end elapsed_ms=${SystemClock.elapsedRealtime() - start}")
        }
    }

    private suspend fun <T : Any> networkStage(
        id: String, stage: String, timeoutMs: Long, action: suspend () -> T,
    ): T = timed(id, stage) {
        activeStage = stage
        // Only our own deadline becomes a retryable network failure. Parent/worker
        // cancellation must still propagate (logout, lost constraints, process stop).
        withTimeoutOrNull(timeoutMs) { action() } ?: throw IOException("report_${stage}_timeout")
    }

    private fun trace(id: String, event: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "req=${id.take(8)} $event")
    }

    private fun failure(
        message: String,
        code: String = message,
        requestId: String? = inputData.getString(EXTRA_REQUEST_ID),
        originalCode: String? = null,
        httpStatus: Int? = null,
    ): ListenableWorker.Result {
        if (requestId != null) {
            com.ntt.crosstest.data.PendingReportStore.markTerminalFailure(applicationContext, requestId,
                com.ntt.crosstest.data.PendingReportFailure(message.take(MAX_ERROR_LENGTH), code.take(100),
                    activeStage, runAttemptCount + 1, originalCode, httpStatus))
        }
        val builder = Data.Builder()
            .putString(KEY_ERROR, message.take(MAX_ERROR_LENGTH))
            .putString(KEY_ERROR_CODE, code.take(100))
            .putString(KEY_ERROR_STAGE, activeStage)
            .putString(EXTRA_REQUEST_ID, requestId)
        if (originalCode != null) builder.putString(KEY_ORIGINAL_CODE, originalCode.take(100))
        if (httpStatus != null) builder.putInt(KEY_HTTP_STATUS, httpStatus)
        return ListenableWorker.Result.failure(builder.build())
    }

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
        const val KEY_IS_MISSION_REPORT = "is_mission_report"
        const val KEY_MISSION_ID = "mission_id"
        const val KEY_MISSION_TITLE = "mission_title"
        const val KEY_MISSION_REWARD_CREDITS = "mission_reward_credits"
        const val KEY_MISSION_ENDS_AT = "mission_ends_at"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_CODE = "error_code"
        const val KEY_ERROR_STAGE = "error_stage"
        const val KEY_ORIGINAL_CODE = "original_code"
        const val KEY_HTTP_STATUS = "http_status"

        private const val MAX_ATTEMPTS = 3
        private const val MAX_ERROR_LENGTH = 1_000
        const val RESERVATION_MAX_VALIDITY_MS = 20 * 60 * 1000L // 20 phút (server reservation TTL 30 phút)
        const val THUMBNAIL_UPLOAD_TIMEOUT_MS = 5_000L
        internal const val SESSION_TIMEOUT_MS = 30_000L
        internal const val UPLOAD_TIMEOUT_MS = 120_000L
        internal const val RPC_TIMEOUT_MS = 45_000L
    }
}

internal interface ReportUploadOperations {
    suspend fun reconcileLegacy(request: PendingReport): SubmitTesterReportResult? = null
    val isReady: Boolean
    val currentUserId: String?
    suspend fun awaitSession() = Unit
    suspend fun reserve(
        campaignId: String,
        uploadId: String,
        estimatedSize: Long,
        hasThumbnail: Boolean = true,
        attachmentId: String? = null
    ): MediaReservationResult = MediaReservationResult(
        success = true,
        provider = "gcs",
        uploadId = uploadId,
        bucket = "cross-testers.firebasestorage.app",
        objectPath = "reports/$campaignId/${currentUserId}/$uploadId.jpg"
    )
    suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String
    suspend fun uploadThumbnail(thumbnailFile: File, campaignId: String, userId: String, uploadId: String): String? = null
    suspend fun uploadSupabase(file: File, bucket: String, objectPath: String): String =
        throw UnsupportedOperationException("uploadSupabase not implemented")
    suspend fun uploadSupabaseThumbnail(thumbnailFile: File, bucket: String, thumbnailPath: String): String? = null
    suspend fun verifySupabase(file: File, bucket: String, objectPath: String): Boolean =
        SupabaseStorageHelper.verifyRemoteObject(file, bucket, objectPath)
    suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult =
        throw UnsupportedOperationException("submit not implemented")
    suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?): SubmitTesterReportResult =
        submit(request, url, hash)
    suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?, missionId: String?): SubmitTesterReportResult =
        submit(request, url, hash, thumbnailUrl)
    suspend fun submitV3(request: PendingReport, attachments: List<ReportAttachmentInput>, missionId: String?): SubmitTesterReportResult =
        submit(request, attachments.firstOrNull()?.screenshotUrl, attachments.firstOrNull()?.imageHash, attachments.firstOrNull()?.thumbnailUrl, missionId)
}
