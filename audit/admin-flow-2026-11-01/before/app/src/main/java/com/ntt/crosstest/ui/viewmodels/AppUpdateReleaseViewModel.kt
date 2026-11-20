package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AppUpdateRelease
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Profile
import kotlinx.coroutines.CancellationException
import com.ntt.crosstest.util.UiStateMessageResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class AppUpdateReleaseUiState(
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
    error: String? = null,
    successMessage: String? = null,
) {
    private val rawError: String? = error
    private val rawSuccessMessage: String? = successMessage

    val error: String?
        get() = UiStateMessageResolver.resolve(rawError)

    val successMessage: String?
        get() = UiStateMessageResolver.resolve(rawSuccessMessage)

    fun copy(
        isLoading: Boolean = this.isLoading,
        isPublishing: Boolean = this.isPublishing,
        isClosing: Boolean = this.isClosing,
        campaign: Campaign? = this.campaign,
        ownerProfile: Profile? = this.ownerProfile,
        currentRelease: AppUpdateRelease? = this.currentRelease,
        releaseHistory: List<AppUpdateRelease> = this.releaseHistory,
        versionName: String = this.versionName,
        versionCode: String = this.versionCode,
        releaseNotes: String = this.releaseNotes,
        clientOperationId: String = this.clientOperationId,
        error: String? = this.rawError,
        successMessage: String? = this.rawSuccessMessage,
    ): AppUpdateReleaseUiState = AppUpdateReleaseUiState(
        isLoading = isLoading,
        isPublishing = isPublishing,
        isClosing = isClosing,
        campaign = campaign,
        ownerProfile = ownerProfile,
        currentRelease = currentRelease,
        releaseHistory = releaseHistory,
        versionName = versionName,
        versionCode = versionCode,
        releaseNotes = releaseNotes,
        clientOperationId = clientOperationId,
        error = error,
        successMessage = successMessage,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppUpdateReleaseUiState) return false
        return isLoading == other.isLoading &&
            isPublishing == other.isPublishing &&
            isClosing == other.isClosing &&
            campaign == other.campaign &&
            ownerProfile == other.ownerProfile &&
            currentRelease == other.currentRelease &&
            releaseHistory == other.releaseHistory &&
            versionName == other.versionName &&
            versionCode == other.versionCode &&
            releaseNotes == other.releaseNotes &&
            clientOperationId == other.clientOperationId &&
            rawError == other.rawError &&
            rawSuccessMessage == other.rawSuccessMessage
    }

    override fun hashCode(): Int {
        var result = isLoading.hashCode()
        result = 31 * result + isPublishing.hashCode()
        result = 31 * result + isClosing.hashCode()
        result = 31 * result + (campaign?.hashCode() ?: 0)
        result = 31 * result + (ownerProfile?.hashCode() ?: 0)
        result = 31 * result + (currentRelease?.hashCode() ?: 0)
        result = 31 * result + releaseHistory.hashCode()
        result = 31 * result + versionName.hashCode()
        result = 31 * result + versionCode.hashCode()
        result = 31 * result + releaseNotes.hashCode()
        result = 31 * result + clientOperationId.hashCode()
        result = 31 * result + (rawError?.hashCode() ?: 0)
        result = 31 * result + (rawSuccessMessage?.hashCode() ?: 0)
        return result
    }

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
                        error = friendlyError(e, localizer).ifBlank { localizer.get(R.string.app_update_load_failed) },
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
                            successMessage = localizer.get(R.string.app_update_apply_success_format, result.release.versionName),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            error = friendlyError(result.message, localizer).ifBlank { localizer.get(R.string.app_update_publish_failed) },
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isPublishing = false,
                        error = friendlyError(e, localizer).ifBlank { localizer.get(R.string.app_update_apply_error) },
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
                            successMessage = localizer.get(R.string.app_update_close_success_format, result.refundAmount),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isClosing = false,
                            error = localizer.get(R.string.app_update_close_failed),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isClosing = false,
                        error = friendlyError(e, localizer).ifBlank { localizer.get(R.string.app_update_close_error) },
                    )
                }
            }
        }
    }
}
