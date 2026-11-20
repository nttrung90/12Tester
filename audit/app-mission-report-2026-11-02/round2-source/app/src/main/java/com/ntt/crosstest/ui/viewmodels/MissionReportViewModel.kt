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
import com.ntt.crosstest.data.Mission
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
)

class MissionReportViewModel(
    private val campaignId: String,
    private val missionId: String,
    private val repository: CampaignRepository,
    private val reportSubmitManager: ReportSubmitManager,
    private val appContext: Context,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(MissionReportUiState())
    val uiState: StateFlow<MissionReportUiState> = _uiState.asStateFlow()

    private var compressionJob: Job? = null
    private var timerJob: Job? = null

    init {
        restoreDraft()
        loadData()
        observeSubmitState()
    }

    private fun draftKey(field: String): String =
        "draft_${campaignId}_${missionId}_$field"

    private fun restoreDraft() {
        val savedComment = savedStateHandle.get<String>(draftKey("comment"))
        val savedRating = savedStateHandle.get<Int>(draftKey("rating"))
        _uiState.update {
            it.copy(
                comment = savedComment ?: it.comment,
                rating = savedRating ?: it.rating,
            )
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val camp = repository.getCampaign(campaignId)
                val missionList = repository.getOpenMissions(forceRefresh = true)
                val targetMission = missionList.firstOrNull { it.id == missionId }
                    ?: repository.getCampaignMission(campaignId, missionId)

                val myTodayMissionRep = repository.getMyTodayMissionReport(campaignId, missionId)
                val isJoined = targetMission?.hasJoined == true || myTodayMissionRep != null

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        campaign = camp,
                        mission = targetMission,
                        isJoined = isJoined,
                        todayReport = myTodayMissionRep,
                        rating = myTodayMissionRep?.rating ?: it.rating,
                        comment = if (it.comment.isBlank()) myTodayMissionRep?.content.orEmpty() else it.comment,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    private fun observeSubmitState() {
        viewModelScope.launch {
            reportSubmitManager.stateForMission(campaignId, missionId).collect { state ->
                _uiState.update { it.copy(submitState = state) }
                if (state?.stage == ReportSubmitManager.Stage.DONE) {
                    // Refresh data to show submitted / approved state
                    loadData()
                }
            }
        }
    }

    fun updateRating(newRating: Int) {
        val clamped = newRating.coerceIn(1, 5)
        savedStateHandle[draftKey("rating")] = clamped
        _uiState.update { it.copy(rating = clamped) }
    }

    fun updateComment(newComment: String) {
        savedStateHandle[draftKey("comment")] = newComment
        _uiState.update { it.copy(comment = newComment) }
    }

    fun joinMission() {
        if (_uiState.value.isJoining) return
        _uiState.update { it.copy(isJoining = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val res = repository.joinMission(missionId)
                if (res.success) {
                    _uiState.update { it.copy(isJoining = false, isJoined = true, infoMessage = appContext.getString(R.string.mission_join_success)) }
                } else {
                    _uiState.update { it.copy(isJoining = false, errorMessage = "Không thể nhận nhiệm vụ") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isJoining = false, errorMessage = e.message) }
            }
        }
    }

    fun recordTestStart() {
        val now = SystemClock.elapsedRealtime()
        _uiState.update { it.copy(testStartedAtMs = now, minTestDurationRemainingSeconds = 60) }
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            for (sec in 60 downTo 0) {
                _uiState.update { it.copy(minTestDurationRemainingSeconds = sec) }
                if (sec > 0) delay(1000L)
            }
        }
    }

    fun addScreenshots(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val current = _uiState.value.preparedScreenshots
        val available = 3 - current.size
        if (available <= 0) {
            _uiState.update { it.copy(errorMessage = appContext.getString(R.string.mission_report_max_photos_reached)) }
            return
        }

        val toProcess = uris.take(available)
        if (uris.size > available) {
            _uiState.update { it.copy(infoMessage = appContext.getString(R.string.mission_report_photos_truncated, available)) }
        }

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
                _uiState.update {
                    it.copy(
                        isCompressing = false,
                        preparedScreenshots = it.preparedScreenshots + newPrepared,
                    )
                }
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
                _uiState.update {
                    it.copy(
                        isCompressing = false,
                        compressionError = e.message ?: appContext.getString(R.string.error_image_read_failed),
                    )
                }
            }
        }
    }

    fun removeScreenshot(index: Int) {
        val current = _uiState.value.preparedScreenshots
        if (index !in current.indices) return
        val removed = current[index]
        PreparedScreenshotCache.releaseLease(removed.file.absolutePath)
        removed.thumbnailFile?.let { PreparedScreenshotCache.releaseLease(it.absolutePath) }

        _uiState.update {
            it.copy(preparedScreenshots = current.filterIndexed { i, _ -> i != index })
        }
    }

    fun setPreviewScreenshot(screenshot: PreparedScreenshot?) {
        _uiState.update { it.copy(previewScreenshot = screenshot, previewUrl = null) }
    }

    fun setPreviewUrl(url: String?) {
        _uiState.update { it.copy(previewUrl = url, previewScreenshot = null) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun submitReport() {
        val state = _uiState.value
        val camp = state.campaign ?: return
        val mission = state.mission ?: return

        if (!state.isJoined) {
            _uiState.update { it.copy(errorMessage = appContext.getString(R.string.mission_not_joined_error)) }
            return
        }

        if (state.isCompressing) {
            _uiState.update { it.copy(errorMessage = appContext.getString(R.string.mission_report_compressing_wait)) }
            return
        }

        val isApproved = state.todayReport?.isApproved == true
        if (!isApproved && state.preparedScreenshots.isEmpty() && state.todayReport?.screenshotUrl == null) {
            _uiState.update { it.copy(errorMessage = appContext.getString(R.string.mission_report_photo_required)) }
            return
        }

        val uid = repository.currentUserId() ?: return
        val clientSubmitId = UUID.randomUUID().toString()

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
            expectedDate = VnTime.todayString(),
        )

        viewModelScope.launch {
            val result = reportSubmitManager.submit(request)
            when (result) {
                is ReportSubmitManager.SubmitAdmissionResult.Accepted -> {
                    // Clear local draft upon successful submission admission
                    savedStateHandle.remove<String>(draftKey("comment"))
                    savedStateHandle.remove<Int>(draftKey("rating"))
                }
                is ReportSubmitManager.SubmitAdmissionResult.AlreadyInFlight -> {
                    _uiState.update { it.copy(infoMessage = appContext.getString(R.string.campaign_report_already_in_flight)) }
                }
                is ReportSubmitManager.SubmitAdmissionResult.Blocked -> {
                    _uiState.update { it.copy(errorMessage = result.message ?: result.code) }
                }
            }
        }
    }

    fun retrySubmit() {
        reportSubmitManager.retry(campaignId, missionId)
    }

    override fun onCleared() {
        super.onCleared()
        compressionJob?.cancel()
        timerJob?.cancel()
        _uiState.value.preparedScreenshots.forEach {
            PreparedScreenshotCache.releaseLease(it.file.absolutePath)
            it.thumbnailFile?.let { t -> PreparedScreenshotCache.releaseLease(t.absolutePath) }
        }
    }
}
