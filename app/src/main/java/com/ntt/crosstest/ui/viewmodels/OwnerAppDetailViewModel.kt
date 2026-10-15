package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.SlotInfo
import com.ntt.crosstest.data.TesterItem
import com.ntt.crosstest.data.TesterReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OwnerAppDetailUiState(
    val isLoading: Boolean = true,
    val isBackgroundSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val isActionLoading: Boolean = false,
    val campaign: Campaign? = null,
    val ownerProfile: Profile? = null,
    val slotInfo: SlotInfo? = null,
    val stats: CampaignOwnerStats = CampaignOwnerStats(),
    val testers: List<TesterItem> = emptyList(),
    val reports: List<TesterReport> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val warningMessage: String? = null,
    val isOwnerLocked: Boolean = false,
)

class OwnerAppDetailViewModel(
    private val campaignId: String,
    private val repository: CampaignRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OwnerAppDetailUiState())
    val uiState: StateFlow<OwnerAppDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val cachedCamp = repository.getCampaign(campaignId, forceRefresh = false)
            val cachedStats = repository.getCampaignOwnerStats(campaignId, forceRefresh = false)
            val cachedReports = repository.getCampaignReports(campaignId, isOwner = true, forceRefresh = false)
            val cachedTesters = repository.getCampaignTesters(campaignId, forceRefresh = false)
            if (cachedCamp != null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = true,
                        campaign = cachedCamp,
                        stats = cachedStats,
                        reports = cachedReports,
                        testers = cachedTesters,
                    )
                }
            }
            refresh(forceRefresh = false)
        }
    }

    fun refresh(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _uiState.value.campaign == null) {
                _uiState.update { it.copy(isLoading = true, isBackgroundSyncing = true, error = null) }
            } else {
                _uiState.update { it.copy(isBackgroundSyncing = true, error = null) }
            }
            try {
                val camp = repository.getCampaign(campaignId, forceRefresh = forceRefresh)
                val stats = repository.getCampaignOwnerStats(campaignId, forceRefresh = forceRefresh)
                val testers = repository.getCampaignTesters(campaignId, forceRefresh = forceRefresh)
                val reports = repository.getCampaignReports(campaignId, isOwner = true, forceRefresh = forceRefresh)
                val owner = camp?.ownerId?.let { id ->
                    repository.getProfiles(listOf(id), forceRefresh = forceRefresh)[id]
                } ?: repository.getMyProfile(forceRefresh = forceRefresh)
                val slots = try { repository.getMySlotsInfo(forceRefresh = forceRefresh) } catch (_: Exception) { emptyList() }
                val slot = slots.find { it.slotNumber == camp?.slotNumber }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        campaign = camp,
                        ownerProfile = owner,
                        slotInfo = slot,
                        stats = stats,
                        testers = testers,
                        reports = reports,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        error = if (_uiState.value.campaign == null) friendlyError(e) else null,
                    )
                }
            }
        }
    }

    fun togglePauseResume() {
        val camp = _uiState.value.campaign ?: return
        val newStatus = if (camp.isPaused) "open" else "paused"
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                repository.updateCampaignStatus(camp.id.orEmpty(), newStatus)
                refresh()
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("SLOT_NOT_ACTIVE_OR_EXPIRED") == true ->
                        "Slot ${camp.slotNumber} đã hết hạn. Vui lòng gia hạn Slot để tiếp tục mở nhận tester."
                    e.message?.contains("SLOT_ALREADY_IN_USE") == true ->
                        "Slot này đang có một ứng dụng khác chạy."
                    else -> friendlyError(e)
                }
                _uiState.update { it.copy(isActionLoading = false, error = msg) }
            }
        }
    }

    fun renewCurrentSlot() {
        val camp = _uiState.value.campaign ?: return
        if (camp.slotNumber <= 1) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                repository.unlockOrRenewSlot(camp.slotNumber)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        message = "Gia hạn Slot ${camp.slotNumber} thành công (+16 ngày)!"
                    )
                }
                refresh()
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("INSUFFICIENT_CREDITS") == true ->
                        "Bạn không đủ Credit để gia hạn Slot (cần ${SlotInfo.costForSlot(camp.slotNumber)} Credit)."
                    else -> friendlyError(e)
                }
                _uiState.update { it.copy(isActionLoading = false, error = msg) }
            }
        }
    }

    fun updateTip(tipCredits: Int) {
        val camp = _uiState.value.campaign ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            try {
                repository.updateCampaignTip(
                    campaignId = camp.id.orEmpty(),
                    tipCredits = tipCredits,
                    isBoosted = tipCredits > 0,
                )
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = e.localizedMessage) }
            }
        }
    }

    fun markProduction() {
        val camp = _uiState.value.campaign ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            try {
                repository.updateCampaignStatus(camp.id.orEmpty(), "production")
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = e.localizedMessage) }
            }
        }
    }

    fun deleteCampaign(onDeleted: () -> Unit) {
        val camp = _uiState.value.campaign ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            try {
                repository.deleteCampaign(camp.id.orEmpty())
                onDeleted()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = e.localizedMessage) }
            }
        }
    }

    fun updateDetails(
        appName: String,
        description: String,
        playUrl: String,
        testingUrl: String,
        testUsername: String,
        testPassword: String,
    ) {
        val camp = _uiState.value.campaign ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            try {
                repository.updateCampaignDetails(
                    campaignId = camp.id.orEmpty(),
                    appName = appName,
                    description = description,
                    playUrl = playUrl,
                    testingUrl = testingUrl,
                    testUsername = testUsername,
                    testPassword = testPassword,
                )
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(isActionLoading = false, error = e.localizedMessage) }
            }
        }
    }

    fun approveReport(report: TesterReport, bonusCredits: Int = 0) {
        viewModelScope.launch {
            try {
                repository.approveReport(report, bonusCredits)
                _uiState.update { it.copy(message = "Đã duyệt kết quả kiểm thử (+2 Credit cho tester)") }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage) }
            }
        }
    }

    fun rejectReport(report: TesterReport, reason: String? = null) {
        viewModelScope.launch {
            try {
                val result = repository.rejectReport(report, reason)
                if (result.ownerWarned) {
                    _uiState.update {
                        it.copy(
                            warningMessage = result.ownerWarningMsg ?: "Hệ thống ghi nhận bạn xác nhận kết quả của người kiểm thử chưa nghiêm túc, đề nghị đối chiếu kỹ ảnh chụp của người kiểm thử chụp các ngày khác nhau để duyệt cho chính xác. Nếu trong 1 tuần bạn bị 3 lần cảnh báo như thế này thì tài khoản của bạn sẽ bị tạm khóa",
                            isOwnerLocked = result.isOwnerLocked,
                        )
                    }
                } else {
                    _uiState.update { it.copy(message = "Đã từ chối báo cáo kiểm thử") }
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage) }
            }
        }
    }

    fun clearWarning() {
        _uiState.update { it.copy(warningMessage = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
