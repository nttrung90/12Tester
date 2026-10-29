package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.AppUpdateRelease
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class AppUpdateReleaseUiState(
    val isLoading: Boolean = true,
    val isPublishing: Boolean = false,
    val isClosing: Boolean = false,
    val campaign: Campaign? = null,
    val ownerProfile: Profile? = null,
    val currentRelease: AppUpdateRelease? = null,
    val releaseHistory: List<AppUpdateRelease> = emptyList(),
    val versionName: String = "",
    val versionCode: String = "",
    val releaseNotes: String = "",
    val clientOperationId: String = UUID.randomUUID().toString(),
    val error: String? = null,
    val successMessage: String? = null,
) {
    val availableCredits: Int get() = ownerProfile?.credits ?: 0
    val refundFromCurrent: Int get() = if (currentRelease?.status == "active") currentRelease.remainingCredits else 0
    val effectiveBudget: Int get() = availableCredits + refundFromCurrent
    val projectedRemaining: Int get() = effectiveBudget - 120
    val hasEnoughCredits: Boolean get() = effectiveBudget >= 120

    val canPublish: Boolean
        get() {
            val code = versionCode.trim().toLongOrNull() ?: return false
            val historyHighest = releaseHistory.maxOfOrNull { it.targetVersionCode } ?: 0L
            val highest = maxOf(currentRelease?.targetVersionCode ?: 0L, historyHighest)
            return hasEnoughCredits &&
                versionName.isNotBlank() &&
                versionName.length <= 100 &&
                code > 0 &&
                code > highest &&
                releaseNotes.isNotBlank() &&
                releaseNotes.length <= 4000 &&
                !isPublishing &&
                !isClosing
        }
}

class AppUpdateReleaseViewModel(
    val campaignId: String,
    private val repository: CampaignRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUpdateReleaseUiState())
    val uiState: StateFlow<AppUpdateReleaseUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun refresh() {
        loadData()
    }

    fun onVersionNameChange(name: String) {
        _uiState.update { it.copy(versionName = name, error = null) }
    }

    fun onVersionCodeChange(code: String) {
        // Chỉ nhận chữ số
        val filtered = code.filter { it.isDigit() }
        _uiState.update { it.copy(versionCode = filtered, error = null) }
    }

    fun onReleaseNotesChange(notes: String) {
        _uiState.update { it.copy(releaseNotes = notes, error = null) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val detail = repository.getCampaignDetailPage(campaignId, forceRefresh = true)
                val historyResult = repository.getCampaignUpdateHistory(campaignId)
                val history = historyResult.getOrDefault(emptyList())
                val myProfile = repository.getMyProfile(forceRefresh = true)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        campaign = detail?.campaign,
                        ownerProfile = myProfile ?: detail?.myProfile,
                        currentRelease = detail?.appUpdate ?: history.firstOrNull { r -> r.status == "active" },
                        releaseHistory = history,
                        clientOperationId = it.clientOperationId.takeIf { id -> id.isNotBlank() } ?: UUID.randomUUID().toString(),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.localizedMessage ?: "Không thể tải thông tin phiên bản app",
                    )
                }
            }
        }
    }

    fun publishUpdate() {
        val state = _uiState.value
        if (!state.canPublish) return

        val targetCode = state.versionCode.trim().toLongOrNull() ?: return
        val currentExpectedId = state.currentRelease?.id

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true, error = null, successMessage = null) }
            try {
                val result = repository.publishAppUpdate(
                    campaignId = campaignId,
                    versionName = state.versionName.trim(),
                    targetVersionCode = targetCode,
                    releaseNotes = state.releaseNotes.trim(),
                    clientOperationId = state.clientOperationId,
                    expectedCurrentReleaseId = currentExpectedId,
                ).getOrThrow()

                if (result.success && result.release != null) {
                    val updatedHistory = repository.getCampaignUpdateHistory(campaignId).getOrDefault(emptyList())
                    val updatedProfile = repository.getMyProfile(forceRefresh = true)

                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            currentRelease = result.release,
                            releaseHistory = updatedHistory,
                            ownerProfile = updatedProfile ?: it.ownerProfile?.copy(credits = result.ownerCredits),
                            versionName = "",
                            versionCode = "",
                            releaseNotes = "",
                            clientOperationId = UUID.randomUUID().toString(),
                            successMessage = "Đã áp dụng thành công phiên bản ${result.release.versionName}!",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            error = result.message ?: "Không thể công bố phiên bản mới.",
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isPublishing = false,
                        error = e.localizedMessage ?: "Lỗi khi áp dụng phiên bản mới. Vui lòng thử lại.",
                    )
                }
            }
        }
    }

    fun closeCurrentRelease() {
        val current = _uiState.value.currentRelease ?: return
        if (current.status != "active") return

        viewModelScope.launch {
            _uiState.update { it.copy(isClosing = true, error = null, successMessage = null) }
            try {
                val result = repository.closeAppUpdateRelease(current.id, campaignId = campaignId).getOrThrow()
                if (result.success) {
                    val updatedHistory = repository.getCampaignUpdateHistory(campaignId).getOrDefault(emptyList())
                    val updatedProfile = repository.getMyProfile(forceRefresh = true)

                    _uiState.update {
                        it.copy(
                            isClosing = false,
                            currentRelease = updatedHistory.firstOrNull { r -> r.id == current.id }?.copy(status = "closed"),
                            releaseHistory = updatedHistory,
                            ownerProfile = updatedProfile ?: it.ownerProfile?.copy(credits = result.ownerCredits),
                            successMessage = "Đã kết thúc đợt và hoàn ${result.refundAmount} Credit chưa dùng!",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isClosing = false,
                            error = "Không thể kết thúc đợt cập nhật.",
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isClosing = false,
                        error = e.localizedMessage ?: "Lỗi khi kết thúc đợt. Vui lòng thử lại.",
                    )
                }
            }
        }
    }
}
