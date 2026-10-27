package com.ntt.crosstest.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ntt.crosstest.util.PreparedScreenshot
import com.ntt.crosstest.work.ReportUploadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Điều phối nộp báo cáo qua WorkManager.
 *
 * - Request + ảnh pending được ghi xuống noBackupFilesDir trước khi enqueue,
 *   nên process chết vẫn có thể retry.
 * - Upload ảnh dùng path cố định theo request id, retry không tạo object rác.
 * - State/event vẫn được giữ ở Application để UI hiển thị tiến trình.
 */
class ReportSubmitManager(
    private val appContext: Context,
) {

    enum class Stage { SENDING, RETRYING, DONE, FAILED }

    data class SubmitState(
        val campaignId: String,
        val stage: Stage,
        val attempt: Int = 1,
        val error: Exception? = null,
        /** Kết quả RPC khi nộp thành công (số Credit tạm nhận thực tế, trạng thái báo cáo). */
        val result: SubmitTesterReportResult? = null,
        val requestId: String? = null,
    )

    data class SubmitRequest(
        val campaignId: String,
        val appName: String,
        val userId: String,
        val rating: Int,
        val comment: String,
        val preparedScreenshot: PreparedScreenshot?,
        val fallbackScreenshotUrl: String?,
        val fallbackImageHash: String?,
        val needsJoin: Boolean,
        val clientSubmitId: String = preparedScreenshot?.uploadId ?: UUID.randomUUID().toString(),
    )

    sealed interface SubmitEvent {
        data class Success(
            val campaignId: String,
            val appName: String,
            val provisionalAmount: Int,
        ) : SubmitEvent

        data class Failed(val campaignId: String, val appName: String, val error: Exception?) : SubmitEvent
    }

    /** Scope sống theo process; không cancel để bài nộp chạy nền qua điều hướng. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager = WorkManager.getInstance(appContext)

    private val _state = MutableStateFlow<SubmitState?>(null)
    val state: StateFlow<SubmitState?> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SubmitEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SubmitEvent> = _events.asSharedFlow()

    @Volatile
    private var lastFailedRequestId: String? = null

    /** Nộp mới; bỏ qua nếu cùng campaign đang gửi/thử lại (chống double-tap). */
    fun submit(request: SubmitRequest) {
        val current = _state.value
        if (current != null && current.campaignId == request.campaignId &&
            (current.stage == Stage.SENDING || current.stage == Stage.RETRYING)
        ) {
            return
        }
        lastFailedRequestId = null
        _state.value = SubmitState(
            campaignId = request.campaignId,
            stage = Stage.SENDING,
            attempt = 1,
            requestId = request.clientSubmitId,
        )
        scope.launch { prepareAndEnqueue(request) }
    }

    /** Gửi lại request thất bại gần nhất (nút "Thử lại" trong dialog). */
    fun retry() {
        val requestId = lastFailedRequestId ?: _state.value?.requestId ?: return
        lastFailedRequestId = null
        scope.launch {
            val report = PendingReportStore.read(appContext, requestId)
            if (report == null) {
                handleFailure(
                    campaignId = _state.value?.campaignId.orEmpty(),
                    appName = "",
                    requestId = requestId,
                    attempt = _state.value?.attempt ?: 1,
                    error = Exception("pending_report_expired"),
                )
                return@launch
            }
            _state.value = SubmitState(
                campaignId = report.campaignId,
                stage = Stage.SENDING,
                attempt = 1,
                requestId = report.id,
            )
            try {
                enqueueAndObserve(report, replace = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFailure(
                    campaignId = report.campaignId,
                    appName = report.appName,
                    requestId = report.id,
                    attempt = 1,
                    error = e,
                )
            }
        }
    }

    /** Huỷ mọi request đang chờ/chạy; dùng khi logout để không lộ dữ liệu tài khoản cũ. */
    fun cancelAllPendingWork() {
        workManager.cancelAllWorkByTag(TAG)
        lastFailedRequestId = null
        _state.value = null
    }

    private suspend fun prepareAndEnqueue(request: SubmitRequest) {
        try {
            val report = withContext(Dispatchers.IO) { persist(request) }
            enqueueAndObserve(report, replace = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(
                campaignId = request.campaignId,
                appName = request.appName,
                requestId = request.clientSubmitId,
                attempt = 1,
                error = e,
            )
        }
    }

    /**
     * Copy ảnh đã nén sang vùng pending và ghi metadata. Ảnh prepared vẫn
     * được giữ để preview; PendingReportStore sẽ dọn cả hai khi thành công.
     */
    private fun persist(request: SubmitRequest): PendingReport {
        val prepared = request.preparedScreenshot
        var pendingScreenshotPath: String? = null
        if (prepared != null) {
            if (!prepared.file.isFile) {
                throw IllegalStateException("screenshot_file_missing")
            }
            val extension = if (prepared.contentType == "image/webp") "webp" else "jpg"
            val pendingFile = PendingReportStore.newScreenshotFile(
                context = appContext,
                id = request.clientSubmitId,
                extension = extension,
            )
            prepared.file.copyTo(pendingFile, overwrite = true)
            pendingScreenshotPath = pendingFile.absolutePath
        }

        val report = PendingReport(
            id = request.clientSubmitId,
            campaignId = request.campaignId,
            appName = request.appName,
            userId = request.userId,
            rating = request.rating,
            comment = request.comment,
            screenshotPath = pendingScreenshotPath,
            preparedPath = prepared?.file?.absolutePath,
            imageHash = prepared?.imageHash,
            contentType = prepared?.contentType ?: "image/jpeg",
            fallbackScreenshotUrl = request.fallbackScreenshotUrl,
            fallbackImageHash = request.fallbackImageHash,
            needsJoin = request.needsJoin,
        )
        PendingReportStore.write(appContext, report)
        return report
    }

    private suspend fun enqueueAndObserve(report: PendingReport, replace: Boolean) {
        val workRequest = OneTimeWorkRequestBuilder<ReportUploadWorker>()
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to report.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(report.id),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            workRequest,
        )
        observeWork(workRequest.id, report)
    }

    private suspend fun observeWork(workId: UUID, report: PendingReport) {
        while (true) {
            val info = withContext(Dispatchers.IO) {
                runCatching { workManager.getWorkInfoById(workId).get() }.getOrNull()
            }
            if (info == null) {
                handleFailure(
                    campaignId = report.campaignId,
                    appName = report.appName,
                    requestId = report.id,
                    attempt = 1,
                    error = Exception("work_info_not_found"),
                )
                return
            }

            val attempt = (info.runAttemptCount + 1).coerceAtLeast(1)
            Log.d(TAG, "observeWork: req=${report.id.take(8)} state=${info.state} runAttemptCount=${info.runAttemptCount}")
            when (info.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED,
                WorkInfo.State.RUNNING -> updateRunningState(report, info.runAttemptCount)

                WorkInfo.State.SUCCEEDED -> {
                    handleSuccess(report, info.outputData, attempt)
                    return
                }

                WorkInfo.State.FAILED -> {
                    handleFailure(
                        campaignId = report.campaignId,
                        appName = report.appName,
                        requestId = report.id,
                        attempt = attempt,
                        error = Exception(
                            info.outputData.getString(ReportUploadWorker.KEY_ERROR)
                                ?: "report_submit_failed"
                        ),
                    )
                    return
                }

                WorkInfo.State.CANCELLED -> {
                    handleFailure(
                        campaignId = report.campaignId,
                        appName = report.appName,
                        requestId = report.id,
                        attempt = attempt,
                        error = Exception("report_submit_cancelled"),
                    )
                    return
                }
            }
            delay(OBSERVE_INTERVAL_MS)
        }
    }

    private fun updateRunningState(report: PendingReport, runAttemptCount: Int) {
        if (!isCurrentRequest(report.id)) return
        val isRetry = runAttemptCount > 0
        val attempt = (runAttemptCount + 1).coerceAtLeast(1)
        _state.value = SubmitState(
            campaignId = report.campaignId,
            stage = if (isRetry) Stage.RETRYING else Stage.SENDING,
            attempt = attempt,
            requestId = report.id,
        )
    }

    private fun handleSuccess(report: PendingReport, data: Data, attempt: Int) {
        // Worker đã xoá; gọi lại để chắc chắn kể cả khi worker bị kill sau RPC.
        scope.launch {
            withContext(Dispatchers.IO) { PendingReportStore.delete(appContext, report.id) }
        }

        val result = SubmitTesterReportResult(
            success = data.getBoolean(ReportUploadWorker.KEY_SUCCESS, true),
            reportId = data.getString(ReportUploadWorker.KEY_REPORT_ID).orEmpty(),
            status = data.getString(ReportUploadWorker.KEY_STATUS) ?: "pending",
            provisionalGranted = data.getBoolean(ReportUploadWorker.KEY_PROVISIONAL_GRANTED, false),
            provisionalAmount = data.getInt(ReportUploadWorker.KEY_PROVISIONAL_AMOUNT, 0),
            screenshotUrl = data.getString(ReportUploadWorker.KEY_SCREENSHOT_URL),
            imageHash = data.getString(ReportUploadWorker.KEY_IMAGE_HASH),
        )
        // Phát event TRƯỚC khi guard request hiện tại: khi tester nộp 2 app liên tiếp,
        // success của app đầu không được nuốt (Credit server vẫn cộng, overlay phải hiện).
        _events.tryEmit(
            SubmitEvent.Success(
                campaignId = report.campaignId,
                appName = report.appName,
                provisionalAmount = if (result.provisionalGranted) result.provisionalAmount else 0,
            )
        )
        if (!isCurrentRequest(report.id)) return
        lastFailedRequestId = null
        _state.value = SubmitState(
            campaignId = report.campaignId,
            stage = Stage.DONE,
            attempt = attempt,
            result = result,
            requestId = report.id,
        )
    }

    private fun handleFailure(
        campaignId: String,
        appName: String,
        requestId: String,
        attempt: Int,
        error: Exception,
    ) {
        if (isCurrentRequest(requestId)) {
            lastFailedRequestId = requestId
            _state.value = SubmitState(
                campaignId = campaignId,
                stage = Stage.FAILED,
                attempt = attempt,
                error = error,
                requestId = requestId,
            )
        }
        _events.tryEmit(SubmitEvent.Failed(campaignId, appName, error))
    }

    private fun isCurrentRequest(requestId: String): Boolean =
        _state.value?.requestId == requestId

    private fun uniqueWorkName(requestId: String): String = "report_submit_$requestId"

    companion object {
        const val TAG = "report_submit"
        private const val OBSERVE_INTERVAL_MS = 500L

        /** Credit tạm nhận cho báo cáo mới, khớp migration 037 (submit_tester_report). */
        const val PROVISIONAL_CREDITS = 10
    }
}
