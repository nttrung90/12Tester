package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MissionCreateUiState(
    val myCampaigns: List<Campaign> = emptyList(),
    val selectedCampaignId: String? = null,
    val title: String = "",
    val description: String = "",
    val rewardMode: String = "once", // "once" or "daily"
    val participants: Int = 5,
    val days: Int = 1,
    val rewardCredits: String = "10",
    val userCredits: Int = 0,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val created: Boolean = false,
    val initialLoadError: String? = null,
    val allAppsBusy: Boolean = false,
) {
    val rewardValue: Int? get() = rewardCredits.trim().toIntOrNull()

    /** Phí nền tảng 50 Credit × số ngày. */
    val feeCredits: Int get() = days * 50

    /** Quỹ thưởng tối đa giữ trước: số chỗ × Credit/lượt (once) hoặc số chỗ × số ngày × Credit/ngày (daily). */
    val escrowCredits: Int
        get() {
            val r = rewardValue ?: 0
            return if (rewardMode == "daily") participants * days * r else participants * r
        }

    /** Tổng Credit cần có (phí nền tảng + quỹ thưởng giữ trước). */
    val totalCostCredits: Int get() = feeCredits + escrowCredits

    /** Thời điểm hết hạn ước tính để hiển thị cho người dùng. */
    val estimatedEndsAtText: String
        get() {
            return try {
                val zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh")
                val nowZoned = java.time.ZonedDateTime.now(zone)
                if (rewardMode == "daily") {
                    val endDate = nowZoned.toLocalDate().plusDays(days.toLong())
                    val endZoned = endDate.atStartOfDay(zone)
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").format(endZoned)
                } else {
                    val endZoned = nowZoned.plusDays(days.toLong())
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").format(endZoned)
                }
            } catch (_: Exception) {
                ""
            }
        }
}

class MissionCreateViewModel(
    private val repository: CampaignRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MissionCreateUiState())
    val uiState: StateFlow<MissionCreateUiState> = _uiState.asStateFlow()

    private val loadSequence = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        loadInitialData()
    }

    fun loadInitialData(forceRefresh: Boolean = false) {
        val seq = loadSequence.incrementAndGet()
        val initialUid = repository.currentUserId()
        val initialGen = SessionCacheManager.currentGeneration()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, initialLoadError = null, allAppsBusy = false) }
            try {
                val profile = repository.getMyProfile(forceRefresh = forceRefresh)
                val allMyCampaigns = repository.myCampaigns(forceRefresh = forceRefresh)
                val eligibleCampaigns = allMyCampaigns.filter { it.isMissionEligible }
                // M02: Dùng endpoint độc lập getMyActiveMissionCampaignIds (không bị cắt ở 100 discovery records)
                val busyCampaignIds = repository.getMyActiveMissionCampaignIds().toSet()
                val selectable = eligibleCampaigns.filter { it.id !in busyCampaignIds }
                if (seq != loadSequence.get() ||
                    initialGen != SessionCacheManager.currentGeneration() ||
                    initialUid != repository.currentUserId()
                ) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        myCampaigns = selectable,
                        selectedCampaignId = selectable.firstOrNull()?.id,
                        userCredits = profile?.credits ?: 0,
                        isLoading = false,
                        allAppsBusy = eligibleCampaigns.isNotEmpty() && selectable.isEmpty(),
                        initialLoadError = null,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (seq != loadSequence.get() ||
                    initialGen != SessionCacheManager.currentGeneration() ||
                    initialUid != repository.currentUserId()
                ) {
                    return@launch
                }
                _uiState.update { it.copy(isLoading = false, initialLoadError = friendlyError(e, localizer)) }
            }
        }
    }

    fun retry() = loadInitialData(forceRefresh = true)

    fun update(
        title: String? = null,
        description: String? = null,
        rewardCredits: String? = null,
    ) {
        _uiState.update {
            it.copy(
                title = title?.take(80) ?: it.title,
                description = description?.take(500) ?: it.description,
                rewardCredits = rewardCredits?.filter(Char::isDigit)?.take(6) ?: it.rewardCredits,
                error = null,
            )
        }
    }

    fun updateTitle(title: String) = update(title = title)
    fun updateDescription(description: String) = update(description = description)
    fun updateReward(reward: String) = update(rewardCredits = reward)

    fun selectRewardMode(mode: String) {
        if (mode == "once" || mode == "daily") {
            _uiState.update { it.copy(rewardMode = mode, error = null) }
        }
    }

    fun selectCampaign(campaignId: String) {
        _uiState.update { it.copy(selectedCampaignId = campaignId, error = null) }
    }

    fun adjustParticipants(delta: Int) {
        _uiState.update {
            it.copy(
                participants = (it.participants + delta).coerceIn(1, 50),
                error = null,
            )
        }
    }

    fun adjustDays(delta: Int) {
        _uiState.update {
            it.copy(
                days = (it.days + delta).coerceIn(1, 7),
                error = null,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun create() {
        val s = _uiState.value
        if (s.isSaving) return

        if (s.isLoading) {
            _uiState.update { it.copy(error = localizer.get(R.string.create_loading_data)) }
            return
        }

        if (s.title.trim().isBlank()) {
            _uiState.update { it.copy(error = localizer.get(R.string.mission_err_title_required)) }
            return
        }

        if (s.description.trim().isBlank()) {
            _uiState.update { it.copy(error = localizer.get(R.string.mission_err_desc_required)) }
            return
        }

        val campaignId = s.selectedCampaignId
        if (campaignId.isNullOrBlank()) {
            _uiState.update { it.copy(error = localizer.get(R.string.mission_err_app_required)) }
            return
        }

        val selectedCampaign = s.myCampaigns.firstOrNull { it.id == campaignId }
        if (selectedCampaign == null || !selectedCampaign.isMissionEligible) {
            _uiState.update { it.copy(error = localizer.get(R.string.mission_err_app_required)) }
            return
        }

        val reward = s.rewardValue ?: 0
        if (reward < 10) {
            _uiState.update { it.copy(error = localizer.get(R.string.mission_err_reward_min)) }
            return
        }

        if (s.userCredits < 300) {
            _uiState.update {
                it.copy(error = localizer.get(R.string.mission_err_min_balance_format, s.userCredits))
            }
            return
        }

        if (s.userCredits < s.totalCostCredits) {
            _uiState.update {
                it.copy(
                    error = localizer.get(
                        R.string.mission_total_cost_format,
                        s.totalCostCredits,
                        s.feeCredits,
                        s.escrowCredits,
                    )
                )
            }
            return
        }

        val initialUid = repository.currentUserId()
        val initialGen = SessionCacheManager.currentGeneration()
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                repository.createMissionV2(
                    com.ntt.crosstest.data.CreateMissionV2Params(
                        campaignId = campaignId,
                        title = s.title.trim(),
                        description = s.description.trim(),
                        maxParticipants = s.participants,
                        durationDays = s.days,
                        rewardCredits = reward,
                        rewardMode = s.rewardMode,
                    )
                )
                if (initialGen != SessionCacheManager.currentGeneration() ||
                    initialUid != repository.currentUserId()
                ) {
                    return@launch
                }
                _uiState.update { it.copy(isSaving = false, created = true) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (initialGen != SessionCacheManager.currentGeneration() ||
                    initialUid != repository.currentUserId()
                ) {
                    return@launch
                }
                val errorMsg = when {
                    e.message?.contains("INSUFFICIENT_MIN_CREDITS") == true ->
                        localizer.get(R.string.mission_err_min_balance_format, s.userCredits)
                    e.message?.contains("INSUFFICIENT_CREDITS") == true ->
                        localizer.get(R.string.mission_total_cost_format, s.totalCostCredits, s.feeCredits, s.escrowCredits)
                    e.message?.contains("MISSION_ALREADY_EXISTS") == true ->
                        localizer.get(R.string.mission_err_already_exists)
                    e.message?.contains("CAMPAIGN_NOT_OPEN") == true ->
                        localizer.get(R.string.mission_err_app_required)
                    else -> friendlyError(e, localizer)
                }
                _uiState.update { it.copy(isSaving = false, error = errorMsg) }
            }
        }
    }
}
