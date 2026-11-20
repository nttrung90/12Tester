package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.DraftAttachment
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Mission
import com.ntt.crosstest.data.MissionReportDraft
import com.ntt.crosstest.data.MissionReportDraftStore
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.util.FirebaseStorageHelper
import com.ntt.crosstest.util.PreparedScreenshot
import com.ntt.crosstest.util.PreparedScreenshotCache
import com.ntt.crosstest.util.VnTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class MissionReportUiState(
    val isLoading: Boolean = true,
    val campaign: Campaign? = null,
    val mission: Mission? = null,
    val isJoined: Boolean = false,
    val isJoining: Boolean = false,
    val rating: Int = 5,
    val comment: String = "",
    val preparedScreenshots: List<PreparedScreenshot> = emptyList(),
    val isCompressing: Boolean = false,
    val compressionError: String? = null,
    val todayReport: TesterReport? = null,
    val submitState: ReportSubmitManager.SubmitState? = null,
    val testStartedAtMs: Long? = null,
    val minTestDurationRemainingSeconds: Int = 0,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val previewScreenshot: PreparedScreenshot? = null,
    val previewUrl: String? = null,
    val previewAttachmentId: String? = null,
)

class MissionReportViewModel(
    val campaignId: String,
    val missionId: String,
    private val repository: CampaignRepository,
    private val reportSubmitManager: ReportSubmitManager,
    private val appContext: Context,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val todayProvider: () -> String = { VnTime.todayString() },
) : ViewModel() {

    private val localizer = Localizer(appContext)
    private val _uiState = MutableStateFlow(MissionReportUiState())
    val uiState: StateFlow<MissionReportUiState> = _uiState.asStateFlow()

    private var compressionJob: Job? = null
    private var timerJob: Job? = null
    private var submitStateJob: Job? = null
    private var loadDataJob: Job? = null
    private var currentBoundDate: String = todayProvider()
    private var inProcessTestStartedAt: Long? = null
    private var userEditRevision: Long = 0L

    init {
        restoreDraft(currentBoundDate)
        loadData()
        observeSubmitState()
    }

    private fun getBootCount(): Int? {
        return try {
            val count = android.provider.Settings.Global.getInt(appContext.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
            if (count >= 0) count else null
        } catch (_: Exception) {
            null
        }
    }

    fun onForegroundResume() {
        val dayChanged = checkAndRefreshDay()
        if (!dayChanged) {
            loadData()
        }
    }

    fun checkAndRefreshDay(): Boolean {
        val today = todayProvider()
        if (today != currentBoundDate) {
            val isOnce = _uiState.value.mission?.isOnceMode == true
            currentBoundDate = today
            if (isOnce) {
                // Đối với once: qua nửa đêm chỉ cập nhật ngày hiện tại cho metadata request và refresh server state,
                // tuyệt đối không xóa ảnh/nháp/timer hay đổi queue scope
                loadData()
                return true
            }

            val oldScreenshots = _uiState.value.preparedScreenshots
            compressionJob?.cancel()
            timerJob?.cancel()
            currentObservedScope = null
            submitStateJob?.cancel()

            inProcessTestStartedAt = null
            userEditRevision = 0L

            // Release old form photo leases
            oldScreenshots.forEach {
                PreparedScreenshotCache.releaseLease(it.file.absolutePath)
                it.thumbnailFile?.let { t -> PreparedScreenshotCache.releaseLease(t.absolutePath) }
            }

            _uiState.update {
                it.copy(
                    testStartedAtMs = null,
                    minTestDurationRemainingSeconds = 0,
                    todayReport = null,
                    preparedScreenshots = emptyList(),
                    comment = "",
                    rating = 5,
                    submitState = null,
                    isCompressing = false,
                    compressionError = null,
                    previewScreenshot = null,
                    previewUrl = null,
                    previewAttachmentId = null,
                    errorMessage = null,
                    infoMessage = localizer.get(R.string.mission_report_day_changed_notice),
                )
            }
            restoreDraft(today)
            loadData()
            observeSubmitState()
            return true
        }
        return false
    }

    private fun draftKey(userId: String, field: String, date: String = currentBoundDate): String {
        val effectiveDate = if (_uiState.value.mission?.isOnceMode == true) "once" else date
        return "draft_${userId}_${campaignId}_${missionId}_${effectiveDate}_$field"
    }

    private fun restoreDraft(date: String = currentBoundDate) {
        val uid = repository.currentUserId().orEmpty()
        val effectiveDate = if (_uiState.value.mission?.isOnceMode == true) "once" else date
        val savedComment = savedStateHandle.get<String>(draftKey(uid, "comment", effectiveDate))
        val savedRating = savedStateHandle.get<Int>(draftKey(uid, "rating", effectiveDate))
        _uiState.update {
            it.copy(
                comment = savedComment ?: it.comment,
                rating = savedRating ?: it.rating,
            )
        }
    }

    private fun getDraftDate(mission: Mission?, fallbackDate: String): String {
        return if (mission?.isOnceMode == true) {
            "once"
        } else {
            fallbackDate
        }
    }

    private fun persistDurableDraft() {
        val uid = repository.currentUserId() ?: return
        val boundDate = currentBoundDate
        val state = _uiState.value
        val draftKeyDate = getDraftDate(state.mission, boundDate)
        val currentBoot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val bootCount = getBootCount()
        val draft = MissionReportDraft(
            userId = uid,
            campaignId = campaignId,
            missionId = missionId,
            reportDate = draftKeyDate,
            rating = state.rating,
            comment = state.comment,
            testStartedAtMs = state.testStartedAtMs,
            bootTimeMs = if (state.testStartedAtMs != null) currentBoot else null,
            bootCount = if (state.testStartedAtMs != null) bootCount else null,
            preparedImages = state.preparedScreenshots.map {
                DraftAttachment(
                    uploadId = it.uploadId,
                    filePath = it.file.absolutePath,
                    thumbnailPath = it.thumbnailFile?.absolutePath,
                    imageHash = it.imageHash,
                    sizeBytes = it.sizeBytes,
                    contentType = it.contentType,
                )
            }
        )
        MissionReportDraftStore.saveDraft(appContext, draft)
    }

    fun loadData() {
        val boundDate = currentBoundDate
        val loadRevision = userEditRevision
        loadDataJob?.cancel()
        loadDataJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val camp = repository.getCampaign(campaignId)
                val missionList = repository.getOpenMissions(forceRefresh = true)
                val targetMission = missionList.firstOrNull { it.id == missionId && it.campaignId == campaignId }
                    ?: repository.getCampaignMission(campaignId, missionId)?.takeIf { it.campaignId == campaignId }

                if (boundDate != currentBoundDate) return@launch

                if (camp == null || targetMission == null) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = localizer.get(R.string.mission_not_found)) }
                    return@launch
                }

                val myTodayMissionRep = repository.getMyMissionReport(
                    campaignId = campaignId,
                    missionId = missionId,
                    isOnceMode = targetMission.isOnceMode,
                    reportDate = boundDate
                )
                if (boundDate != currentBoundDate) return@launch

                val isJoined = targetMission.hasJoined == true || targetMission.hasCompleted == true || myTodayMissionRep != null
                val uid = repository.currentUserId().orEmpty()

                val draftKeyDate = getDraftDate(targetMission, boundDate)
                val durableDraft = MissionReportDraftStore.loadDraft(appContext, uid, campaignId, missionId, draftKeyDate)
                val restoredComment = when {
                    durableDraft != null -> durableDraft.comment
                    _uiState.value.comment.isNotBlank() -> _uiState.value.comment
                    myTodayMissionRep != null -> myTodayMissionRep.content
                    else -> ""
                }
                val restoredRating = durableDraft?.rating ?: myTodayMissionRep?.rating ?: _uiState.value.rating

                // Authoritative timer validation
                var validatedTimerStartedAt: Long? = null
                var validatedTimerRemaining = 0
                val activeInProcess = inProcessTestStartedAt
                if (activeInProcess != null) {
                    val elapsed = SystemClock.elapsedRealtime() - activeInProcess
                    if (elapsed < 0L) {
                        inProcessTestStartedAt = null
                    } else {
                        validatedTimerStartedAt = activeInProcess
                        validatedTimerRemaining = if (elapsed < 10_000L) ((10_000L - elapsed + 999) / 1000).toInt().coerceIn(0, 10) else 0
                    }
                } else if (durableDraft?.testStartedAtMs != null) {
                    val draftStartedAt = durableDraft.testStartedAtMs
                    val currentBootCount = getBootCount()
                    val currentBootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()

                    val isBootValid = when {
                        durableDraft.bootCount != null -> currentBootCount != null && currentBootCount == durableDraft.bootCount
                        durableDraft.bootTimeMs != null -> Math.abs(currentBootTimeMs - durableDraft.bootTimeMs) <= 10_000L
                        else -> false // Unknown/unavailable boot identity: invalidate safely
                    }

                    if (isBootValid) {
                        val elapsed = SystemClock.elapsedRealtime() - draftStartedAt
                        if (elapsed >= 0L) {
                            inProcessTestStartedAt = draftStartedAt
                            validatedTimerStartedAt = draftStartedAt
                            validatedTimerRemaining = if (elapsed < 10_000L) ((10_000L - elapsed + 999) / 1000).toInt().coerceIn(0, 10) else 0
                        }
                    }
                }

                if (validatedTimerRemaining > 0 && timerJob?.isActive != true) {
                    timerJob?.cancel()
                    timerJob = viewModelScope.launch {
                        for (sec in validatedTimerRemaining downTo 0) {
                            _uiState.update { it.copy(minTestDurationRemainingSeconds = sec) }
                            if (sec > 0) delay(1000L)
                        }
                    }
                }

                // Reconcile prepared images from durable draft
                val restoredScreenshots = mutableListOf<PreparedScreenshot>()
                if (durableDraft != null && durableDraft.preparedImages.isNotEmpty()) {
                    for (dAtt in durableDraft.preparedImages) {
                        val file = File(dAtt.filePath)
                        if (file.isFile) {
                            val thumbFile = dAtt.thumbnailPath?.let { File(it) }?.takeIf { it.isFile }
                            restoredScreenshots.add(
                                PreparedScreenshot(
                                    uploadId = dAtt.uploadId,
                                    file = file,
                                    imageHash = dAtt.imageHash,
                                    sizeBytes = dAtt.sizeBytes,
                                    contentType = dAtt.contentType,
                                    thumbnailFile = thumbFile,
                                    thumbnailSizeBytes = thumbFile?.length(),
                                )
                            )
                        }
                    }
                }

                // Reconcile leases: acquire only newly restored paths, release discarded paths
                val currentPaths = _uiState.value.preparedScreenshots.map { it.file.absolutePath }.toSet()
                val targetPaths = restoredScreenshots.map { it.file.absolutePath }.toSet()
                for (old in _uiState.value.preparedScreenshots) {
                    if (old.file.absolutePath !in targetPaths) {
                        PreparedScreenshotCache.releaseLease(old.file.absolutePath)
                        old.thumbnailFile?.let { PreparedScreenshotCache.releaseLease(it.absolutePath) }
                    }
                }
                for (newPrep in restoredScreenshots) {
                    if (newPrep.file.absolutePath !in currentPaths) {
                        PreparedScreenshotCache.acquireLease(newPrep.file.absolutePath)
                        newPrep.thumbnailFile?.let { PreparedScreenshotCache.acquireLease(it.absolutePath) }
                    }
                }

                val finalScreenshots = when {
                    durableDraft != null -> restoredScreenshots
                    myTodayMissionRep != null -> emptyList()
                    else -> _uiState.value.preparedScreenshots
                }

                if (boundDate != currentBoundDate) return@launch

                _uiState.update { current ->
                    if (boundDate != currentBoundDate) return@update current
                    // Preserve edits made in current bound form while loading was in flight
                    val finalComment = if (userEditRevision > loadRevision) current.comment else restoredComment
                    val finalRating = if (userEditRevision > loadRevision) current.rating else restoredRating
                    val finalPhotos = if (userEditRevision > loadRevision && current.preparedScreenshots.isNotEmpty()) {
                        current.preparedScreenshots
                    } else finalScreenshots

                    current.copy(
                        isLoading = false,
                        campaign = camp,
                        mission = targetMission,
                        isJoined = isJoined,
                        todayReport = myTodayMissionRep,
                        rating = finalRating,
                        comment = finalComment,
                        preparedScreenshots = finalPhotos,
                        testStartedAtMs = validatedTimerStartedAt,
                        minTestDurationRemainingSeconds = validatedTimerRemaining,
                    )
                }
                observeSubmitState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (boundDate == currentBoundDate) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = friendlyError(e, localizer)) }
                }
            }
        }
    }

    private var currentObservedScope: String? = null

    private fun observeSubmitState() {
        val uid = repository.currentUserId().orEmpty()
        val isOnce = _uiState.value.mission?.isOnceMode == true
        val targetDate = if (isOnce) "once" else currentBoundDate
        val targetScope = "$uid:$campaignId:mission:$missionId:$targetDate"

        if (currentObservedScope == targetScope && submitStateJob?.isActive == true) {
            return
        }

        submitStateJob?.cancel()
        val scopeChanged = currentObservedScope != null && currentObservedScope != targetScope
        currentObservedScope = targetScope

        if (scopeChanged) {
            _uiState.update { it.copy(submitState = null) }
        }

        submitStateJob = viewModelScope.launch {
            reportSubmitManager.stateForMission(campaignId, missionId, uid, date = targetDate).collect { state ->
                _uiState.update { it.copy(submitState = state) }
                if (state?.stage == ReportSubmitManager.Stage.DONE) {
                    val draftKeyDate = getDraftDate(_uiState.value.mission, currentBoundDate)
                    if (uid.isNotBlank() && MissionReportDraftStore.loadDraft(appContext, uid, campaignId, missionId, draftKeyDate) == null) {
                        savedStateHandle.remove<String>(draftKey(uid, "comment", currentBoundDate))
                        savedStateHandle.remove<Int>(draftKey(uid, "rating", currentBoundDate))
                    }
                    loadData()
                }
            }
        }
    }

    fun updateRating(newRating: Int) {
        checkAndRefreshDay()
        userEditRevision++
        val clamped = newRating.coerceIn(1, 5)
        val uid = repository.currentUserId().orEmpty()
        val effectiveDate = if (_uiState.value.mission?.isOnceMode == true) "once" else currentBoundDate
        savedStateHandle[draftKey(uid, "rating", effectiveDate)] = clamped
        _uiState.update { it.copy(rating = clamped) }
        persistDurableDraft()
    }

    fun updateComment(newComment: String) {
        checkAndRefreshDay()
        userEditRevision++
        val uid = repository.currentUserId().orEmpty()
        val effectiveDate = if (_uiState.value.mission?.isOnceMode == true) "once" else currentBoundDate
        savedStateHandle[draftKey(uid, "comment", effectiveDate)] = newComment
        _uiState.update { it.copy(comment = newComment) }
        persistDurableDraft()
    }

    fun joinMission() {
        if (_uiState.value.isJoining) return
        _uiState.update { it.copy(isJoining = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val res = repository.joinMission(missionId)
                if (res.success) {
                    _uiState.update { it.copy(isJoining = false, isJoined = true, infoMessage = localizer.get(R.string.mission_join_success)) }
                } else {
                    _uiState.update { it.copy(isJoining = false, errorMessage = localizer.get(R.string.mission_report_join_failed)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isJoining = false, errorMessage = friendlyError(e, localizer)) }
            }
        }
    }

    fun recordTestStart() {
        checkAndRefreshDay()
        val now = SystemClock.elapsedRealtime()
        inProcessTestStartedAt = now
        _uiState.update { it.copy(testStartedAtMs = now, minTestDurationRemainingSeconds = 10) }
        persistDurableDraft()
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            for (sec in 10 downTo 0) {
                _uiState.update { it.copy(minTestDurationRemainingSeconds = sec) }
                if (sec > 0) delay(1000L)
            }
        }
    }

    fun addScreenshots(uris: List<Uri>) {
        checkAndRefreshDay()
        if (uris.isEmpty()) return
        val current = _uiState.value.preparedScreenshots
        val available = 3 - current.size
        if (available <= 0) {
            _uiState.update { it.copy(errorMessage = localizer.get(R.string.mission_report_max_photos_reached)) }
            return
        }

        val toProcess = uris.take(available)
        if (uris.size > available) {
            _uiState.update { it.copy(infoMessage = localizer.get(R.string.mission_report_photos_truncated, available)) }
        }

        val isOnce = _uiState.value.mission?.isOnceMode == true
        val compressionBoundDate = if (isOnce) "once" else currentBoundDate
        userEditRevision++
        compressionJob?.cancel()
        compressionJob = viewModelScope.launch {
            _uiState.update { it.copy(isCompressing = true, compressionError = null) }
            val newPrepared = mutableListOf<PreparedScreenshot>()
            try {
                // Compress sequentially to avoid keeping multiple large bitmaps in memory
                for (uri in toProcess) {
                    val uploadId = PreparedScreenshotCache.newUploadId()
                    val prepared = FirebaseStorageHelper.prepareScreenshot(appContext, uri, uploadId)
                    PreparedScreenshotCache.acquireLease(prepared.file.absolutePath)
                    prepared.thumbnailFile?.let { PreparedScreenshotCache.acquireLease(it.absolutePath) }
                    newPrepared.add(prepared)
                }

                val currentCompDate = if (_uiState.value.mission?.isOnceMode == true) "once" else currentBoundDate
                if (compressionBoundDate != currentCompDate) {
                    // Identity changed while compressing: clean up only abandoned newly prepared photos
                    newPrepared.forEach {
                        PreparedScreenshotCache.releaseLease(it.file.absolutePath)
                        it.thumbnailFile?.let { t -> PreparedScreenshotCache.releaseLease(t.absolutePath) }
                        it.file.delete()
                        it.thumbnailFile?.delete()
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isCompressing = false,
                        preparedScreenshots = it.preparedScreenshots + newPrepared,
                    )
                }
                persistDurableDraft()
            } catch (e: CancellationException) {
                newPrepared.forEach {
                    PreparedScreenshotCache.releaseLease(it.file.absolutePath)
                    it.thumbnailFile?.let { t -> PreparedScreenshotCache.releaseLease(t.absolutePath) }
                }
                throw e
            } catch (e: Exception) {
                newPrepared.forEach {
                    PreparedScreenshotCache.releaseLease(it.file.absolutePath)
                    it.thumbnailFile?.let { t -> PreparedScreenshotCache.releaseLease(t.absolutePath) }
                }
                if (compressionBoundDate == currentBoundDate) {
                    _uiState.update {
                        it.copy(
                            isCompressing = false,
                            compressionError = e.message ?: localizer.get(R.string.error_image_read_failed),
                        )
                    }
                }
            }
        }
    }

    fun removeScreenshot(index: Int) {
        checkAndRefreshDay()
        val current = _uiState.value.preparedScreenshots
        if (index !in current.indices) return
        userEditRevision++
        val removed = current[index]
        PreparedScreenshotCache.releaseLease(removed.file.absolutePath)
        removed.thumbnailFile?.let { PreparedScreenshotCache.releaseLease(it.absolutePath) }

        _uiState.update {
            it.copy(preparedScreenshots = current.filterIndexed { i, _ -> i != index })
        }
        persistDurableDraft()
    }

    fun setPreviewScreenshot(screenshot: PreparedScreenshot?) {
        _uiState.update { it.copy(previewScreenshot = screenshot, previewUrl = null, previewAttachmentId = null) }
    }

    fun setPreviewUrl(url: String?, attachmentId: String? = null) {
        _uiState.update { it.copy(previewUrl = url, previewScreenshot = null, previewAttachmentId = attachmentId) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun submitReport() {
        checkAndRefreshDay()
        val boundDate = currentBoundDate
        val state = _uiState.value
        val camp = state.campaign ?: return
        val mission = state.mission ?: return

        val uid = repository.currentUserId() ?: return

        if (camp.ownerId == uid) {
            _uiState.update { it.copy(errorMessage = localizer.get(R.string.campaign_owner_cant_report)) }
            return
        }

        if (mission.campaignId != campaignId) {
            _uiState.update { it.copy(errorMessage = localizer.get(R.string.mission_not_found)) }
            return
        }

        if (!state.isJoined) {
            _uiState.update { it.copy(errorMessage = localizer.get(R.string.mission_not_joined_error)) }
            return
        }

        if (state.isCompressing) {
            _uiState.update { it.copy(errorMessage = localizer.get(R.string.mission_report_compressing_wait)) }
            return
        }

        val isApproved = state.todayReport?.isApproved == true

        if (!isApproved) {
            if (!camp.isMissionEligible) {
                _uiState.update { it.copy(errorMessage = localizer.get(R.string.error_campaign_not_open)) }
                return
            }

            if (!mission.isActive) {
                val msg = when {
                    mission.isPaused -> localizer.get(R.string.error_mission_paused)
                    mission.isStopped -> localizer.get(R.string.error_mission_no_credits)
                    mission.isExpired -> localizer.get(R.string.mission_expired_error)
                    else -> localizer.get(R.string.mission_status_stopped)
                }
                _uiState.update { it.copy(errorMessage = msg) }
                return
            }

            val endsAt = mission.endsAt
            if (endsAt != null) {
                val isEnded = try {
                    java.time.Instant.parse(endsAt).isBefore(java.time.Instant.now())
                } catch (_: Exception) { false }
                if (isEnded) {
                    _uiState.update { it.copy(errorMessage = localizer.get(R.string.mission_expired_error)) }
                    return
                }
            }

            // 10-second actual app open validation (pending and approved updates are exempt)
            val isExemptFromTimer = isApproved || state.todayReport?.isPending == true
            if (!isExemptFromTimer) {
                if (state.testStartedAtMs == null) {
                    _uiState.update { it.copy(errorMessage = localizer.get(R.string.mission_report_test_needed_hint)) }
                    return
                }
                val elapsed = SystemClock.elapsedRealtime() - state.testStartedAtMs
                if (elapsed < 0L) {
                    // Clock anomaly / negative elapsed: invalidate timer and require test
                    _uiState.update {
                        it.copy(
                            testStartedAtMs = null,
                            minTestDurationRemainingSeconds = 0,
                            errorMessage = localizer.get(R.string.mission_report_test_needed_hint)
                        )
                    }
                    return
                }
                if (elapsed < 10_000L) {
                    val remainingSec = ((10_000L - elapsed + 999) / 1000).toInt().coerceIn(1, 10)
                    _uiState.update { it.copy(errorMessage = localizer.get(R.string.mission_report_test_remaining_hint, remainingSec)) }
                    return
                }
            }

            // If report was rejected, must require a new primary photo
            if (state.todayReport?.isRejected == true) {
                if (state.preparedScreenshots.isEmpty()) {
                    _uiState.update { it.copy(errorMessage = localizer.get(R.string.campaign_resubmit_new_photo_required)) }
                    return
                }
            } else if (state.preparedScreenshots.isEmpty() && state.todayReport?.screenshotUrl == null) {
                _uiState.update { it.copy(errorMessage = localizer.get(R.string.mission_report_photo_required)) }
                return
            }
        }

        val clientSubmitId = UUID.randomUUID().toString()
        val isOnce = mission.isOnceMode

        val request = ReportSubmitManager.SubmitRequest(
            campaignId = campaignId,
            appName = camp.appName,
            userId = uid,
            rating = state.rating,
            comment = state.comment,
            preparedScreenshots = state.preparedScreenshots,
            fallbackScreenshotUrl = state.todayReport?.screenshotUrl,
            fallbackImageHash = state.todayReport?.imageHash,
            needsJoin = false,
            clientSubmitId = clientSubmitId,
            missionId = missionId,
            missionTitle = mission.title,
            submissionContext = "mission",
            expectedDate = boundDate,
            isOnceMode = isOnce,
            scopedKey = if (isOnce) ReportSubmitManager.submitStateKey(uid, campaignId, "mission", missionId, "once") else null,
        )

        viewModelScope.launch {
            val result = reportSubmitManager.submit(request)
            when (result) {
                is ReportSubmitManager.SubmitAdmissionResult.Accepted -> {
                }
                is ReportSubmitManager.SubmitAdmissionResult.AlreadyInFlight -> {
                    _uiState.update { it.copy(infoMessage = localizer.get(R.string.campaign_report_already_in_flight)) }
                }
                is ReportSubmitManager.SubmitAdmissionResult.Blocked -> {
                    _uiState.update { it.copy(errorMessage = friendlyError(result.message ?: result.code, localizer)) }
                }
            }
        }
    }

    fun retrySubmit() {
        val dayChanged = checkAndRefreshDay()
        if (dayChanged) return
        val uid = repository.currentUserId() ?: return
        val boundDate = currentBoundDate
        val isOnce = _uiState.value.mission?.isOnceMode == true
        val exactScopeKey = ReportSubmitManager.submitStateKey(
            userId = uid,
            campaignId = campaignId,
            context = "mission",
            missionId = missionId,
            date = if (isOnce) "once" else boundDate,
        )
        reportSubmitManager.retry(campaignId = campaignId, missionId = missionId, scopedKey = exactScopeKey)
    }

    override fun onCleared() {
        super.onCleared()
        compressionJob?.cancel()
        timerJob?.cancel()
        submitStateJob?.cancel()
        loadDataJob?.cancel()
        _uiState.value.preparedScreenshots.forEach {
            PreparedScreenshotCache.releaseLease(it.file.absolutePath)
            it.thumbnailFile?.let { t -> PreparedScreenshotCache.releaseLease(t.absolutePath) }
        }
    }
}
