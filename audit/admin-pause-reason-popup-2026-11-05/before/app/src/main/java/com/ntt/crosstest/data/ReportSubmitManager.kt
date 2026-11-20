package com.ntt.crosstest.data

import android.content.Context
import android.util.Log
import com.ntt.crosstest.BuildConfig
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ntt.crosstest.util.PreparedScreenshot
import com.ntt.crosstest.util.VnTime
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
        val isMissionReport: Boolean = false,
        val missionTitle: String? = null,
        val submissionContext: String? = null,
        val missionId: String? = null,
        val scopedKey: String? = null,
    )

    sealed interface SubmitAdmissionResult {
        data class Accepted(val requestId: String) : SubmitAdmissionResult
        data class AlreadyInFlight(val existingRequestId: String) : SubmitAdmissionResult
        data class Blocked(val code: String, val message: String? = null) : SubmitAdmissionResult
    }

    data class SubmitRequest(
        val campaignId: String,
        val appName: String,
        val userId: String,
        val rating: Int,
        val comment: String,
        val preparedScreenshot: PreparedScreenshot? = null,
        val fallbackScreenshotUrl: String? = null,
        val fallbackImageHash: String? = null,
        val needsJoin: Boolean,
        val clientSubmitId: String = UUID.randomUUID().toString(),
        val missionId: String? = null,
        val missionTitle: String? = null,
        val submissionContext: String = if (missionId == null) "app_test" else "mission",
        val preparedScreenshots: List<PreparedScreenshot> = emptyList(),
        val expectedDate: String? = null,
        val isOnceMode: Boolean = false,
        val scopedKey: String? = null,
    )

    sealed interface SubmitEvent {
        data class Success(
            val campaignId: String,
            val appName: String,
            val provisionalAmount: Int,
            val isMissionReport: Boolean = false,
            val missionTitle: String? = null,
        ) : SubmitEvent

        data class Failed(
            val campaignId: String,
            val appName: String,
            val error: Exception?,
            val isMissionReport: Boolean = false,
            val missionTitle: String? = null,
        ) : SubmitEvent
    }

    /** Scope sống theo process; không cancel để bài nộp chạy nền qua điều hướng. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager = WorkManager.getInstance(appContext)

    private val _state = MutableStateFlow<SubmitState?>(null)
    val state: StateFlow<SubmitState?> = _state.asStateFlow()
    private val campaignStates = MutableStateFlow<Map<String, SubmitState>>(emptyMap())
    private val scopedStates = MutableStateFlow<Map<String, SubmitState>>(emptyMap())

    fun submitStateKey(userId: String, campaignId: String, context: String, missionId: String?, date: String? = null): String =
        "$userId:$campaignId:$context:${missionId.orEmpty()}:${date ?: VnTime.todayString()}"

    fun currentStateForCampaign(campaignId: String): SubmitState? = campaignStates.value[campaignId]

    fun stateForCampaign(campaignId: String) =
        campaignStates.map { it[campaignId] }.distinctUntilChanged()

    fun currentStateForScope(key: String): SubmitState? = scopedStates.value[key]

    fun stateForScope(key: String) =
        scopedStates.map { it[key] }.distinctUntilChanged()

    fun stateForMission(campaignId: String, missionId: String, userId: String? = null, date: String? = null): kotlinx.coroutines.flow.Flow<SubmitState?> =
        scopedStates.map { map ->
            if (userId != null && date != null) {
                val key = submitStateKey(userId, campaignId, "mission", missionId, date)
                map[key]
            } else if (userId != null) {
                map.values.firstOrNull { it.campaignId == campaignId && it.missionId == missionId && it.scopedKey?.startsWith("$userId:") == true }
            } else {
                map.values.firstOrNull { it.campaignId == campaignId && it.missionId == missionId }
            }
        }.distinctUntilChanged()

    fun currentStateForMission(campaignId: String, missionId: String, userId: String? = null, date: String? = null): SubmitState? =
        if (userId != null && date != null) {
            val key = submitStateKey(userId, campaignId, "mission", missionId, date)
            scopedStates.value[key]
        } else if (userId != null) {
            scopedStates.value.values.firstOrNull { it.campaignId == campaignId && it.missionId == missionId && it.scopedKey?.startsWith("$userId:") == true }
        } else {
            scopedStates.value.values.firstOrNull { it.campaignId == campaignId && it.missionId == missionId }
        }

    fun currentStateForOrdinary(campaignId: String): SubmitState? =
        scopedStates.value.values.firstOrNull { it.campaignId == campaignId && !it.isMissionReport }

    fun stateForOrdinary(campaignId: String): kotlinx.coroutines.flow.Flow<SubmitState?> =
        scopedStates.map { map ->
            map.values.firstOrNull { it.campaignId == campaignId && !it.isMissionReport }
        }.distinctUntilChanged()

    private fun publishState(value: SubmitState, select: Boolean = false) {
        if (isCancelling) return
        val key = value.scopedKey ?: value.campaignId
        scopedStates.update { it + (key to value) }
        if (!value.isMissionReport) {
            campaignStates.update { it + (value.campaignId to value) }
        }
        _state.update { current ->
            if (select || current == null || current.requestId == value.requestId) value else current
        }
    }

    private val _events = MutableSharedFlow<SubmitEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SubmitEvent> = _events.asSharedFlow()

    private val sessionGeneration = AtomicLong(1L)
    @Volatile
    private var isCancelling = false
    private val activeAdmissionJobs = ConcurrentHashMap<String, Deferred<SubmitAdmissionResult>>()
    private val admissionMutex = Mutex()

    @Volatile
    private var lastFailedRequestId: String? = null
    private val deliveredRequests = ConcurrentHashMap.newKeySet<String>()
    private val activeCampaigns = ConcurrentHashMap<String, String>()

    init {
        scope.launch {
            try { reconcileCompletedReports() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "receipt_reconcile_failed type=${e.javaClass.simpleName}")
            }
        }
    }

    /** No UI reward replay at startup; only reclaim receipts for committed/pruned work. */
    internal suspend fun reconcileCompletedReports() {
        for (report in PendingReportStore.list(appContext).filter { it.completedResult != null }) {
            val infos = workManager.getWorkInfosForUniqueWork(uniqueWorkName(report.id)).get()
            if (infos.isEmpty() || (infos.all { it.state.isFinished } && infos.any { it.state == WorkInfo.State.SUCCEEDED })) {
                acknowledgeMatchingDraft(appContext, report)
                PendingReportStore.delete(appContext, report.id)
            }
        }
    }

    /** Restore observers only after the persisted user's session is authenticated. */
    fun restorePendingReports(userId: String) = scope.launch {
        try {
            restorePendingReportsForUser(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "restore_failed type=${e.javaClass.simpleName}")
        }
    }

    private suspend fun restorePendingReportsForUser(userId: String) = coroutineScope {
        val reports = withContext(Dispatchers.IO) {
            PendingReportStore.list(appContext)
                .filter { it.userId == userId && it.completedResult == null }
                .sortedByDescending { it.createdAt }
        }
        for (report in reports) {
            currentCoroutineContext().ensureActive()
            val sContext = report.submissionContext ?: (if (report.missionId == null) "app_test" else "mission")
            val isOnce = report.isOnceMode || report.scopedKey?.endsWith(":once") == true
            val reportDate = if (isOnce) "once" else (report.expectedDate ?: java.time.Instant.ofEpochMilli(report.createdAt)
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString())
            val scopeKey = report.scopedKey ?: submitStateKey(report.userId, report.campaignId, sContext, report.missionId, reportDate)
            if (activeCampaigns.putIfAbsent(scopeKey, report.id) != null) continue
            publishState(SubmitState(
                campaignId = report.campaignId,
                stage = Stage.SENDING,
                requestId = report.id,
                isMissionReport = report.missionId != null,
                missionTitle = report.missionTitle,
                submissionContext = sContext,
                missionId = report.missionId,
                scopedKey = scopeKey,
            ))
            launch {
                try {
                    val infos = workManager.getWorkInfosForUniqueWork(uniqueWorkName(report.id)).get()
                    val existing = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
                    if (existing == null) {
                        // Process died after persisting the request but before enqueue committed.
                        enqueueAndObserve(report, replace = false)
                    } else {
                        // Failed work remains available for explicit retry; do not reset its budget.
                        observeWork(existing.id, report)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFailure(
                        campaignId = report.campaignId,
                        appName = report.appName,
                        requestId = report.id,
                        attempt = 1,
                        error = e,
                        isMissionReport = report.missionId != null,
                        missionTitle = report.missionTitle,
                        submissionContext = sContext,
                        missionId = report.missionId,
                        scopedKey = scopeKey,
                    )
                } finally {
                    activeCampaigns.remove(scopeKey, report.id)
                    activeCampaigns.remove(report.campaignId, report.id)
                }
            }
        }
    }

    /** Nộp mới; admission do lifecycle manager quản lý trên IO, caller chỉ chờ kết quả. */
    suspend fun submit(request: SubmitRequest): SubmitAdmissionResult {
        if (isCancelling) {
            throw CancellationException("Report submit cancelled: cancellation in progress")
        }
        val frozenDate = request.expectedDate ?: VnTime.todayString()
        val frozenRequest = request.copy(expectedDate = frozenDate)
        val scopeDate = if (frozenRequest.isOnceMode) "once" else frozenDate
        val scopeKey = frozenRequest.scopedKey ?: submitStateKey(frozenRequest.userId, frozenRequest.campaignId, frozenRequest.submissionContext, frozenRequest.missionId, scopeDate)
        val current = _state.value
        val scopedState = currentStateForScope(scopeKey)
        val campaignState = currentStateForCampaign(frozenRequest.campaignId)
        if ((current != null && current.scopedKey == scopeKey &&
                (current.stage == Stage.SENDING || current.stage == Stage.RETRYING)) ||
            (scopedState != null && (scopedState.stage == Stage.SENDING || scopedState.stage == Stage.RETRYING))
        ) {
            val inFlightId = current?.takeIf { it.scopedKey == scopeKey }?.requestId
                ?: scopedState?.requestId.orEmpty()
            return SubmitAdmissionResult.AlreadyInFlight(inFlightId)
        }
        val existingActive = activeCampaigns.putIfAbsent(scopeKey, frozenRequest.clientSubmitId)
        if (existingActive != null) {
            return SubmitAdmissionResult.AlreadyInFlight(existingActive)
        }

        val session = sessionGeneration.get()
        val deferred = scope.async(Dispatchers.IO) {
            executeAdmission(frozenRequest, session)
        }
        activeAdmissionJobs[frozenRequest.clientSubmitId] = deferred
        deferred.invokeOnCompletion {
            activeAdmissionJobs.remove(frozenRequest.clientSubmitId, deferred)
        }
        return deferred.await()
    }

    private suspend fun checkSessionBarrier(session: Long) {
        currentCoroutineContext().ensureActive()
        if (sessionGeneration.get() != session || isCancelling) {
            throw CancellationException("Admission cancelled by session barrier (session=$session, current=${sessionGeneration.get()})")
        }
    }

    private suspend fun executeAdmission(request: SubmitRequest, session: Long): SubmitAdmissionResult {
        val scopeDate = if (request.isOnceMode) "once" else request.expectedDate
        val scopeKey = request.scopedKey ?: submitStateKey(request.userId, request.campaignId, request.submissionContext, request.missionId, scopeDate)
        var blockedResult: SubmitAdmissionResult? = null
        val report = admissionMutex.withLock {
            checkSessionBarrier(session)

            val estimatedImageBytes = if (request.preparedScreenshots.isNotEmpty()) {
                request.preparedScreenshots.sumOf {
                    it.file.length() + (it.thumbnailFile?.length() ?: 0L)
                }
            } else {
                (request.preparedScreenshot?.file?.length() ?: 0L) + (request.preparedScreenshot?.thumbnailFile?.length() ?: 0L)
            }

            val estimatedAttachments = if (request.preparedScreenshots.isNotEmpty()) {
                request.preparedScreenshots.mapIndexed { index, prep ->
                    val pendingFile = PendingReportStore.newAttachmentScreenshotFile(
                        context = appContext,
                        id = request.clientSubmitId,
                        attachmentId = prep.uploadId,
                    )
                    var pendingThumbPath: String? = null
                    val thumbFile = prep.thumbnailFile
                    if (thumbFile != null && thumbFile.isFile) {
                        val pendingThumb = PendingReportStore.newAttachmentThumbnailFile(
                            context = appContext,
                            id = request.clientSubmitId,
                            attachmentId = prep.uploadId,
                        )
                        pendingThumbPath = pendingThumb.absolutePath
                    }
                    val sha = try {
                        if (prep.file.isFile) PendingReportStore.screenshotSha256(prep.file) else "0".repeat(64)
                    } catch (_: Exception) {
                        "0".repeat(64)
                    }
                    PendingAttachment(
                        id = prep.uploadId,
                        position = index,
                        screenshotPath = pendingFile.absolutePath,
                        preparedPath = prep.file.absolutePath,
                        preparedThumbnailPath = prep.thumbnailFile?.absolutePath,
                        thumbnailPath = pendingThumbPath,
                        imageHash = prep.imageHash,
                        sha256 = sha,
                    )
                }
            } else {
                emptyList()
            }

            val primaryAttachment = estimatedAttachments.firstOrNull()
            val prepared = request.preparedScreenshot ?: request.preparedScreenshots.firstOrNull()
            var pendingScreenshotPath: String? = primaryAttachment?.screenshotPath
            var pendingThumbnailPath: String? = primaryAttachment?.thumbnailPath
            if (estimatedAttachments.isEmpty() && prepared != null) {
                val extension = if (prepared.contentType == "image/webp") "webp" else "jpg"
                val pendingFile = PendingReportStore.newScreenshotFile(
                    context = appContext,
                    id = request.clientSubmitId,
                    extension = extension,
                )
                pendingScreenshotPath = pendingFile.absolutePath

                val thumbFile = prepared.thumbnailFile
                if (thumbFile != null && thumbFile.isFile) {
                    val pendingThumb = PendingReportStore.newThumbnailFile(
                        context = appContext,
                        id = request.clientSubmitId,
                        extension = "jpg",
                    )
                    pendingThumbnailPath = pendingThumb.absolutePath
                }
            }

            val fullEstimatedReport = PendingReport(
                id = request.clientSubmitId,
                campaignId = request.campaignId,
                appName = request.appName,
                userId = request.userId,
                rating = request.rating,
                comment = request.comment,
                screenshotPath = pendingScreenshotPath,
                thumbnailPath = pendingThumbnailPath,
                preparedPath = prepared?.file?.absolutePath,
                preparedThumbnailPath = prepared?.thumbnailFile?.absolutePath,
                imageHash = prepared?.imageHash,
                contentType = prepared?.contentType ?: "image/jpeg",
                fallbackScreenshotUrl = request.fallbackScreenshotUrl,
                fallbackImageHash = request.fallbackImageHash,
                needsJoin = request.needsJoin,
                screenshotSha256 = primaryAttachment?.sha256 ?: (if (pendingScreenshotPath != null && prepared?.file?.isFile == true) {
                    try { PendingReportStore.screenshotSha256(prepared.file) } catch (_: Exception) { "0".repeat(64) }
                } else null),
                missionId = request.missionId,
                submissionContext = request.submissionContext,
                missionTitle = request.missionTitle,
                expectedDate = request.expectedDate,
                isOnceMode = request.isOnceMode,
                scopedKey = scopeKey,
                attachments = estimatedAttachments,
            )

            val estimatedJsonBytes = PendingReportStore.serializedBytes(fullEstimatedReport)
            val estimatedTotalBytes = estimatedImageBytes + estimatedJsonBytes
            if (!PendingReportStore.canAdmit(appContext, estimatedTotalBytes)) {
                activeCampaigns.remove(scopeKey, request.clientSubmitId)
                activeCampaigns.remove(request.campaignId, request.clientSubmitId)
                blockedResult = SubmitAdmissionResult.Blocked(
                    code = "capacity_exceeded",
                    message = "Bộ nhớ hàng đợi đã đầy. Vui lòng chờ các báo cáo đang gửi hoàn tất."
                )
                return@withLock null
            }

            // Publish initial state for this campaign and exact scope so duplicate UI clicks are blocked immediately
            publishState(SubmitState(
                campaignId = request.campaignId,
                stage = Stage.SENDING,
                attempt = 1,
                requestId = request.clientSubmitId,
                isMissionReport = request.missionId != null,
                missionTitle = request.missionTitle,
                submissionContext = request.submissionContext,
                missionId = request.missionId,
                scopedKey = scopeKey,
            ), select = true)

            try {
                checkSessionBarrier(session)
                persist(request)
            } catch (e: CancellationException) {
                PendingReportStore.delete(appContext, request.clientSubmitId)
                throw e
            } catch (e: Exception) {
                if (sessionGeneration.get() == session && !isCancelling && currentCoroutineContext().isActive) {
                    handleFailure(
                        campaignId = request.campaignId,
                        appName = request.appName,
                        requestId = request.clientSubmitId,
                        attempt = 1,
                        error = e,
                        isMissionReport = request.missionId != null,
                        missionTitle = request.missionTitle,
                        submissionContext = request.submissionContext,
                        missionId = request.missionId,
                        scopedKey = scopeKey,
                    )
                }
                activeCampaigns.remove(scopeKey, request.clientSubmitId)
                activeCampaigns.remove(request.campaignId, request.clientSubmitId)
                blockedResult = SubmitAdmissionResult.Blocked(e.message ?: "persist_failed")
                null
            }
        }

        if (blockedResult != null) return blockedResult
        if (report == null) return SubmitAdmissionResult.Blocked("persist_failed")

        try {
            // Post-persist & Pre-enqueue barrier check:
            checkSessionBarrier(session)

            try {
                enqueueWorkSync(report, replace = false)
            } catch (e: CancellationException) {
                PendingReportStore.delete(appContext, request.clientSubmitId)
                withContext(NonCancellable) {
                    try { workManager.cancelUniqueWork(uniqueWorkName(report.id)).result.get() } catch (_: Exception) {}
                }
                throw e
            } catch (e: Exception) {
                PendingReportStore.delete(appContext, request.clientSubmitId)
                if (sessionGeneration.get() == session && !isCancelling && currentCoroutineContext().isActive) {
                    handleFailure(
                        campaignId = request.campaignId,
                        appName = request.appName,
                        requestId = request.clientSubmitId,
                        attempt = 1,
                        error = e,
                        isMissionReport = request.missionId != null,
                        missionTitle = request.missionTitle,
                        submissionContext = request.submissionContext,
                        missionId = request.missionId,
                        scopedKey = scopeKey,
                    )
                }
                return SubmitAdmissionResult.Blocked(e.message ?: "enqueue_failed")
            }

            // Post-enqueue barrier check:
            if (sessionGeneration.get() != session || isCancelling || !currentCoroutineContext().isActive) {
                PendingReportStore.delete(appContext, request.clientSubmitId)
                withContext(NonCancellable) {
                    try { workManager.cancelUniqueWork(uniqueWorkName(report.id)).result.get() } catch (_: Exception) {}
                }
                throw CancellationException("Admission cancelled by session barrier after enqueue")
            }

            lastFailedRequestId = null
            publishState(SubmitState(
                campaignId = request.campaignId,
                stage = Stage.SENDING,
                attempt = 1,
                requestId = request.clientSubmitId,
                isMissionReport = request.missionId != null,
                missionTitle = request.missionTitle,
                submissionContext = request.submissionContext,
                missionId = request.missionId,
                scopedKey = scopeKey,
            ), select = true)

            scope.launch {
                try {
                    val actual = workManager.getWorkInfosForUniqueWork(uniqueWorkName(report.id)).get()
                        .firstOrNull { !it.state.isFinished }
                        ?: workManager.getWorkInfosForUniqueWork(uniqueWorkName(report.id)).get().firstOrNull()
                    if (actual != null && sessionGeneration.get() == session && !isCancelling) {
                        observeWork(actual.id, report, session)
                    }
                } finally {
                    activeCampaigns.remove(scopeKey, request.clientSubmitId)
                    activeCampaigns.remove(request.campaignId, request.clientSubmitId)
                }
            }
            return SubmitAdmissionResult.Accepted(request.clientSubmitId)
        } finally {
            val currentEnqueued = try {
                workManager.getWorkInfosForUniqueWork(uniqueWorkName(request.clientSubmitId)).get()
            } catch (e: Exception) { emptyList() }
            val isEnqueuedAndRunning = currentEnqueued.any { !it.state.isFinished }
            if (!isEnqueuedAndRunning || isCancelling || sessionGeneration.get() != session) {
                activeCampaigns.remove(scopeKey, request.clientSubmitId)
                activeCampaigns.remove(request.campaignId, request.clientSubmitId)
            }
        }
    }

    /** Retry the displayed campaign's request, even while a different app is sending. */
    fun retry(
        campaignId: String? = null,
        missionId: String? = null,
        scopedKey: String? = null,
    ) {
        val targetState = if (scopedKey != null) {
            scopedStates.value[scopedKey]
        } else if (missionId != null && campaignId != null) {
            currentStateForMission(campaignId, missionId)
        } else if (campaignId != null) {
            currentStateForCampaign(campaignId)
        } else {
            _state.value
        }
        val requestId = if (scopedKey != null || campaignId != null) targetState?.requestId
            else lastFailedRequestId ?: _state.value?.requestId
        if (requestId == null) return
        lastFailedRequestId = null
        val currentSession = sessionGeneration.get()
        scope.launch {
            if (isCancelling || sessionGeneration.get() != currentSession) return@launch
            val report = PendingReportStore.read(appContext, requestId)
            if (report == null) {
                handleFailure(
                    campaignId = targetState?.campaignId.orEmpty(),
                    appName = "",
                    requestId = requestId,
                    attempt = targetState?.attempt ?: 1,
                    error = Exception("pending_report_expired"),
                    isMissionReport = targetState?.isMissionReport ?: false,
                    missionTitle = targetState?.missionTitle,
                    submissionContext = targetState?.submissionContext,
                    missionId = targetState?.missionId,
                    scopedKey = targetState?.scopedKey ?: scopedKey,
                )
                return@launch
            }
            val sContext = report.submissionContext ?: (if (report.missionId == null) "app_test" else "mission")
            val repDate = effectiveReportDate(report)
            val scopeKey = submitStateKey(report.userId, report.campaignId, sContext, report.missionId, repDate)
            if (scopedKey != null && scopeKey != scopedKey) {
                return@launch
            }
            if (activeCampaigns.putIfAbsent(scopeKey, report.id) != null) return@launch
            publishState(SubmitState(
                campaignId = report.campaignId,
                stage = Stage.SENDING,
                attempt = 1,
                requestId = report.id,
                isMissionReport = report.missionId != null,
                missionTitle = report.missionTitle,
                submissionContext = sContext,
                missionId = report.missionId,
                scopedKey = scopeKey,
            ), select = true)
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
                    isMissionReport = report.missionId != null,
                    missionTitle = report.missionTitle,
                    submissionContext = sContext,
                    missionId = report.missionId,
                    scopedKey = scopeKey,
                )
            } finally {
                activeCampaigns.remove(scopeKey, report.id)
                activeCampaigns.remove(report.campaignId, report.id)
            }
        }
    }

    /** Huỷ mọi request đang chờ/chạy; dùng khi logout để không lộ dữ liệu tài khoản cũ. */
    suspend fun cancelAllPendingWork() {
        isCancelling = true
        try {
            sessionGeneration.incrementAndGet()

            val admissions = activeAdmissionJobs.values.toList()
            admissions.forEach { it.cancel() }
            val jobs = scope.coroutineContext[Job]?.children?.toList().orEmpty()
            jobs.forEach { it.cancel() }

            admissions.joinAll()
            jobs.joinAll()

            withContext(Dispatchers.IO) { workManager.cancelAllWorkByTag(TAG).result.get() }
            lastFailedRequestId = null
            _state.value = null
            campaignStates.value = emptyMap()
            scopedStates.value = emptyMap()
            deliveredRequests.clear()
            activeCampaigns.clear()
            activeAdmissionJobs.clear()
        } finally {
            isCancelling = false
        }
    }

    private suspend fun prepareAndEnqueue(request: SubmitRequest) {
        val scopeKey = submitStateKey(request.userId, request.campaignId, request.submissionContext, request.missionId, request.expectedDate)
        try {
            val report = withContext(Dispatchers.IO) { persist(request) }
            currentCoroutineContext().ensureActive()
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
                isMissionReport = request.missionId != null,
                missionTitle = request.missionTitle,
                submissionContext = request.submissionContext,
                missionId = request.missionId,
                scopedKey = scopeKey,
            )
        } finally {
            activeCampaigns.remove(scopeKey, request.clientSubmitId)
            activeCampaigns.remove(request.campaignId, request.clientSubmitId)
        }
    }

    /**
     * Copy ảnh đã nén sang vùng pending và ghi metadata. Ảnh prepared vẫn
     * được giữ để preview; PendingReportStore sẽ dọn cả hai khi thành công.
     */
    private fun persist(request: SubmitRequest): PendingReport {
        val attachments = if (request.preparedScreenshots.isNotEmpty()) {
            request.preparedScreenshots.mapIndexed { index, prep ->
                if (!prep.file.isFile) {
                    throw IllegalStateException("screenshot_file_missing")
                }
                val pendingFile = PendingReportStore.newAttachmentScreenshotFile(
                    context = appContext,
                    id = request.clientSubmitId,
                    attachmentId = prep.uploadId,
                )
                prep.file.copyTo(pendingFile, overwrite = true)
                var pendingThumbPath: String? = null
                val thumbFile = prep.thumbnailFile
                if (thumbFile != null && thumbFile.isFile) {
                    val pendingThumb = PendingReportStore.newAttachmentThumbnailFile(
                        context = appContext,
                        id = request.clientSubmitId,
                        attachmentId = prep.uploadId,
                    )
                    thumbFile.copyTo(pendingThumb, overwrite = true)
                    pendingThumbPath = pendingThumb.absolutePath
                }
                PendingAttachment(
                    id = prep.uploadId,
                    position = index,
                    screenshotPath = pendingFile.absolutePath,
                    preparedPath = prep.file.absolutePath,
                    preparedThumbnailPath = prep.thumbnailFile?.absolutePath,
                    thumbnailPath = pendingThumbPath,
                    imageHash = prep.imageHash,
                    sha256 = PendingReportStore.screenshotSha256(pendingFile),
                )
            }
        } else {
            emptyList()
        }

        val primaryAttachment = attachments.firstOrNull()
        val prepared = request.preparedScreenshot ?: request.preparedScreenshots.firstOrNull()
        var pendingScreenshotPath: String? = primaryAttachment?.screenshotPath
        var pendingThumbnailPath: String? = primaryAttachment?.thumbnailPath
        if (attachments.isEmpty() && prepared != null) {
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

            val thumbFile = prepared.thumbnailFile
            if (thumbFile != null && thumbFile.isFile) {
                val pendingThumb = PendingReportStore.newThumbnailFile(
                    context = appContext,
                    id = request.clientSubmitId,
                    extension = "jpg",
                )
                thumbFile.copyTo(pendingThumb, overwrite = true)
                pendingThumbnailPath = pendingThumb.absolutePath
            }
        }

        val report = PendingReport(
            id = request.clientSubmitId,
            campaignId = request.campaignId,
            appName = request.appName,
            userId = request.userId,
            rating = request.rating,
            comment = request.comment,
            screenshotPath = pendingScreenshotPath,
            thumbnailPath = pendingThumbnailPath,
            preparedPath = prepared?.file?.absolutePath,
            preparedThumbnailPath = prepared?.thumbnailFile?.absolutePath,
            imageHash = prepared?.imageHash,
            contentType = prepared?.contentType ?: "image/jpeg",
            fallbackScreenshotUrl = request.fallbackScreenshotUrl,
            fallbackImageHash = request.fallbackImageHash,
            needsJoin = request.needsJoin,
            screenshotSha256 = pendingScreenshotPath?.let { PendingReportStore.screenshotSha256(java.io.File(it)) },
            missionId = request.missionId,
            submissionContext = request.submissionContext,
            missionTitle = request.missionTitle,
            expectedDate = request.expectedDate,
            isOnceMode = request.isOnceMode,
            scopedKey = request.scopedKey,
            attachments = attachments,
        )
        PendingReportStore.write(appContext, report)
        return report
    }

    private fun enqueueWorkSync(report: PendingReport, replace: Boolean): UUID {
        val workRequest = OneTimeWorkRequestBuilder<ReportUploadWorker>()
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to report.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(report.id),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            workRequest,
        ).result.get()
        return workRequest.id
    }

    private suspend fun enqueueAndObserve(report: PendingReport, replace: Boolean) {
        val workId = enqueueWorkSync(report, replace)
        // KEEP can retain an existing work ID; observe the work that actually exists.
        val actual = workManager.getWorkInfosForUniqueWork(uniqueWorkName(report.id)).get()
            .firstOrNull { !it.state.isFinished } ?: workManager.getWorkInfoById(workId).get()
        observeWork(actual?.id ?: workId, report)
    }

    private suspend fun observeWork(workId: UUID, report: PendingReport, session: Long = sessionGeneration.get()) {
        while (true) {
            if (isCancelling || sessionGeneration.get() != session || !currentCoroutineContext().isActive) return
            val info = withContext(Dispatchers.IO) { workManager.getWorkInfoById(workId).get() }
            if (isCancelling || sessionGeneration.get() != session || !currentCoroutineContext().isActive) return
            if (info == null) {
                handleFailure(
                    campaignId = report.campaignId,
                    appName = report.appName,
                    requestId = report.id,
                    attempt = 1,
                    error = Exception("work_info_not_found"),
                    isMissionReport = report.missionId != null,
                    missionTitle = report.missionTitle,
                )
                return
            }

            val mapped = reportWorkState(info.state, info.runAttemptCount)
            val attempt = mapped.attempt
            if (BuildConfig.DEBUG) Log.d(TAG, "observeWork: req=${report.id.take(8)} state=${info.state} runAttemptCount=${info.runAttemptCount}")
            when (info.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED,
                WorkInfo.State.RUNNING -> updateRunningState(report, mapped)

                WorkInfo.State.SUCCEEDED -> {
                    handleSuccess(report, info.outputData, attempt)
                    return
                }

                WorkInfo.State.FAILED -> {
                    val httpStatus = if (info.outputData.keyValueMap.containsKey(ReportUploadWorker.KEY_HTTP_STATUS)) {
                        info.outputData.getInt(ReportUploadWorker.KEY_HTTP_STATUS, 0)
                    } else null
                    val sContext = report.submissionContext ?: (if (report.missionId == null) "app_test" else "mission")
                    val date = effectiveReportDate(report)
                    val sKey = submitStateKey(report.userId, report.campaignId, sContext, report.missionId, date)
                    handleFailure(
                        campaignId = report.campaignId,
                        appName = report.appName,
                        requestId = report.id,
                        attempt = attempt,
                        error = ReportSubmissionFailure(
                            code = info.outputData.getString(ReportUploadWorker.KEY_ERROR_CODE) ?: "report_submit_failed",
                            stage = info.outputData.getString(ReportUploadWorker.KEY_ERROR_STAGE) ?: "unknown",
                            requestId = report.id,
                            legacyMessage = info.outputData.getString(ReportUploadWorker.KEY_ERROR),
                            originalCode = info.outputData.getString(ReportUploadWorker.KEY_ORIGINAL_CODE),
                            httpStatus = httpStatus,
                        ),
                        isMissionReport = report.missionId != null,
                        missionTitle = report.missionTitle,
                        submissionContext = sContext,
                        missionId = report.missionId,
                        scopedKey = sKey,
                    )
                    return
                }

                WorkInfo.State.CANCELLED -> {
                    val sContext = report.submissionContext ?: (if (report.missionId == null) "app_test" else "mission")
                    val date = effectiveReportDate(report)
                    val sKey = submitStateKey(report.userId, report.campaignId, sContext, report.missionId, date)
                    handleFailure(
                        campaignId = report.campaignId,
                        appName = report.appName,
                        requestId = report.id,
                        attempt = attempt,
                        error = Exception("report_submit_cancelled"),
                        isMissionReport = report.missionId != null,
                        missionTitle = report.missionTitle,
                        submissionContext = sContext,
                        missionId = report.missionId,
                        scopedKey = sKey,
                    )
                    return
                }
            }
            delay(OBSERVE_INTERVAL_MS)
        }
    }

    private fun updateRunningState(report: PendingReport, mapped: ReportWorkState) {
        val sContext = report.submissionContext ?: (if (report.missionId == null) "app_test" else "mission")
        publishState(SubmitState(
            campaignId = report.campaignId,
            stage = mapped.stage,
            attempt = mapped.attempt,
            requestId = report.id,
            isMissionReport = report.missionId != null,
            missionTitle = report.missionTitle,
            submissionContext = sContext,
            missionId = report.missionId,
            scopedKey = submitStateKey(report.userId, report.campaignId, sContext, report.missionId, effectiveReportDate(report)),
        ))
    }

    private fun handleSuccess(report: PendingReport, data: Data, attempt: Int) {
        if (isCancelling) return

        val durableCompleted = PendingReportStore.read(appContext, report.id)?.completedResult
            ?: report.completedResult

        val isMission = data.getBoolean(ReportUploadWorker.KEY_IS_MISSION_REPORT, report.missionId != null)
        val missionId = data.getString(ReportUploadWorker.KEY_MISSION_ID) ?: report.missionId
        val missionTitle = data.getString(ReportUploadWorker.KEY_MISSION_TITLE) ?: report.missionTitle
        val missionRewardCredits = if (data.keyValueMap.containsKey(ReportUploadWorker.KEY_MISSION_REWARD_CREDITS)) {
            data.getInt(ReportUploadWorker.KEY_MISSION_REWARD_CREDITS, 0)
        } else null
        val missionEndsAt = data.getString(ReportUploadWorker.KEY_MISSION_ENDS_AT)

        val result = durableCompleted ?: SubmitTesterReportResult(
            success = data.getBoolean(ReportUploadWorker.KEY_SUCCESS, true),
            reportId = data.getString(ReportUploadWorker.KEY_REPORT_ID).orEmpty(),
            status = data.getString(ReportUploadWorker.KEY_STATUS) ?: "pending",
            provisionalGranted = data.getBoolean(ReportUploadWorker.KEY_PROVISIONAL_GRANTED, false),
            provisionalAmount = data.getInt(ReportUploadWorker.KEY_PROVISIONAL_AMOUNT, 0),
            screenshotUrl = data.getString(ReportUploadWorker.KEY_SCREENSHOT_URL),
            imageHash = data.getString(ReportUploadWorker.KEY_IMAGE_HASH),
            thumbnailUrl = data.getString(ReportUploadWorker.KEY_THUMBNAIL_URL),
            submissionContext = if (isMission) "mission" else "app_test",
            missionId = missionId,
            missionTitle = missionTitle,
            missionRewardCredits = missionRewardCredits,
            missionEndsAt = missionEndsAt,
        )

        val sContext = if (isMission) "mission" else "app_test"
        val stateKey = submitStateKey(report.userId, report.campaignId, sContext, missionId, effectiveReportDate(report))

        // Deliver completion for every campaign, including earlier background submissions.
        if (deliveredRequests.add(report.id)) _events.tryEmit(
            SubmitEvent.Success(
                campaignId = report.campaignId,
                appName = report.appName,
                provisionalAmount = if (result.provisionalGranted) result.provisionalAmount else 0,
                isMissionReport = isMission,
                missionTitle = missionTitle,
            )
        )
        if (isCurrentRequest(report.id)) lastFailedRequestId = null

        // Acknowledge matching committed draft even when no UI remains alive
        acknowledgeMatchingDraft(appContext, report)

        publishState(SubmitState(
            campaignId = report.campaignId,
            stage = Stage.DONE,
            attempt = attempt,
            result = result,
            requestId = report.id,
            isMissionReport = isMission,
            missionTitle = missionTitle,
            submissionContext = sContext,
            missionId = missionId,
            scopedKey = stateKey,
        ))

        // This callback is reached only after WorkManager has committed SUCCEEDED.
        scope.launch {
            withContext(Dispatchers.IO) { PendingReportStore.delete(appContext, report.id) }
        }
    }

    private fun acknowledgeMatchingDraft(context: Context, report: PendingReport) {
        if (report.submissionContext != "mission" || report.missionId.isNullOrBlank()) return
        val date = effectiveReportDate(report)
        synchronized(MissionReportDraftStore) {
            val currentDraft = MissionReportDraftStore.loadDraft(context, report.userId, report.campaignId, report.missionId, date)
            if (currentDraft != null && currentDraft.comment == report.comment && currentDraft.rating == report.rating) {
                val draftImgIds = currentDraft.preparedImages.map { it.uploadId }
                val reportImgIds = report.attachments.map { it.id }
                if (draftImgIds == reportImgIds) {
                    MissionReportDraftStore.clearDraft(context, report.userId, report.campaignId, report.missionId, date)
                }
            }
        }
    }

    private fun handleFailure(
        campaignId: String,
        appName: String,
        requestId: String,
        attempt: Int,
        error: Exception,
        isMissionReport: Boolean = false,
        missionTitle: String? = null,
        submissionContext: String? = null,
        missionId: String? = null,
        scopedKey: String? = null,
    ) {
        if (isCancelling) return
        if (isCurrentRequest(requestId)) lastFailedRequestId = requestId
        publishState(SubmitState(
            campaignId = campaignId,
            stage = Stage.FAILED,
            attempt = attempt,
            error = error,
            requestId = requestId,
            isMissionReport = isMissionReport,
            missionTitle = missionTitle,
            submissionContext = submissionContext,
            missionId = missionId,
            scopedKey = scopedKey,
        ))
        _events.tryEmit(SubmitEvent.Failed(campaignId, appName, error, isMissionReport, missionTitle))
    }

    private fun isCurrentRequest(requestId: String): Boolean =
        _state.value?.requestId == requestId

    private fun uniqueWorkName(requestId: String): String = "report_submit_$requestId"

    companion object {
        const val TAG = "report_submit"
        private const val OBSERVE_INTERVAL_MS = 500L

        /** Credit tạm nhận cho báo cáo mới, khớp migration 037 (submit_tester_report). */
        const val PROVISIONAL_CREDITS = 10

        fun submitStateKey(userId: String, campaignId: String, context: String, missionId: String?, date: String? = null): String =
            "$userId:$campaignId:$context:${missionId.orEmpty()}:${date ?: VnTime.todayString()}"

        fun effectiveReportDate(report: PendingReport): String =
            if (report.isOnceMode || report.scopedKey?.endsWith(":once") == true) "once"
            else report.expectedDate ?: java.time.Instant.ofEpochMilli(report.createdAt)
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString()
    }
}
