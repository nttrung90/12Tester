package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.CreateMissionFromSourceParams
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class MissionCreateUiState(
    val isCopyMode: Boolean = false,
    val sourceMissionId: String? = null,
    val lockedCampaign: Campaign? = null,
    val blockPreviousParticipants: Boolean = false,
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
    private val sourceMissionId: String? = null,
    private val sourceCampaignId: String? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val isCopyMode: Boolean = !sourceMissionId.isNullOrBlank()
    private val draftPrefix: String = "mission_draft_${sourceMissionId.orEmpty()}_${sourceCampaignId.orEmpty()}"

    private val _uiState = MutableStateFlow(
        MissionCreateUiState(
            isCopyMode = isCopyMode,
            sourceMissionId = sourceMissionId?.takeIf { it.isNotBlank() },
            title = savedStateHandle.get<String>("${draftPrefix}_title") ?: "",
            description = savedStateHandle.get<String>("${draftPrefix}_desc") ?: "",
            rewardMode = savedStateHandle.get<String>("${draftPrefix}_mode") ?: "once",
            participants = savedStateHandle.get<Int>("${draftPrefix}_participants") ?: 5,
            days = savedStateHandle.get<Int>("${draftPrefix}_days") ?: 1,
            rewardCredits = savedStateHandle.get<String>("${draftPrefix}_reward") ?: "10",
            blockPreviousParticipants = if (isCopyMode) {
                savedStateHandle.get<Boolean>("${draftPrefix}_block_prev") ?: false
            } else {
                false
            },
        )
    )
    val uiState: StateFlow<MissionCreateUiState> = _uiState.asStateFlow()

    private val loadSequence = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        if (savedStateHandle.get<String>("${draftPrefix}_op_id").isNullOrBlank()) {
            savedStateHandle["${draftPrefix}_op_id"] = UUID.randomUUID().toString()
        }
        loadInitialData()
    }

    private fun currentOperationId(): String {
        val existing = savedStateHandle.get<String>("${draftPrefix}_op_id")
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        savedStateHandle["${draftPrefix}_op_id"] = created
        return created
    }

    private fun rotateOperationId() {
        savedStateHandle["${draftPrefix}_op_id"] = UUID.randomUUID().toString()
    }

    private fun persistEditedDraft(state: MissionCreateUiState) {
        savedStateHandle["${draftPrefix}_edited"] = true
        savedStateHandle["${draftPrefix}_title"] = state.title
        savedStateHandle["${draftPrefix}_desc"] = state.description
        savedStateHandle["${draftPrefix}_mode"] = state.rewardMode
        savedStateHandle["${draftPrefix}_participants"] = state.participants
        savedStateHandle["${draftPrefix}_days"] = state.days
        savedStateHandle["${draftPrefix}_reward"] = state.rewardCredits
        savedStateHandle["${draftPrefix}_block_prev"] = if (isCopyMode) state.blockPreviousParticipants else false
    }

    fun loadInitialData(forceRefresh: Boolean = false) {
        val seq = loadSequence.incrementAndGet()
        val initialUid = repository.currentUserId()
        val initialGen = SessionCacheManager.currentGeneration()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, initialLoadError = null, allAppsBusy = false) }
            try {
                if (isCopyMode) {
                    val srcId = sourceMissionId!!
                    val draft = repository.getMissionCopyDraft(srcId, sourceCampaignId)
                    val profile = runCatching { repository.getMyProfile(forceRefresh = forceRefresh) }.getOrNull()
                    if (seq != loadSequence.get() ||
                        initialGen != SessionCacheManager.currentGeneration() ||
                        initialUid != repository.currentUserId()
                    ) {
                        return@launch
                    }
                    val lockedCampaign = Campaign(
                        id = draft.campaignId,
                        appName = draft.appName,
                        packageName = draft.packageName,
                        iconUrl = draft.iconUrl,
                        status = draft.campaignStatus,
                    )
                    val preserveEdited = savedStateHandle.get<Boolean>("${draftPrefix}_edited") == true
                    val effectiveCredits = profile?.credits ?: draft.userCredits
                    _uiState.update { state ->
                        if (preserveEdited) {
                            state.copy(
                                isCopyMode = true,
                                sourceMissionId = draft.sourceMissionId,
                                lockedCampaign = lockedCampaign,
                                myCampaigns = listOf(lockedCampaign),
                                selectedCampaignId = draft.campaignId,
                                userCredits = effectiveCredits,
                                isLoading = false,
                                initialLoadError = null,
                                allAppsBusy = false,
                            )
                        } else {
                            val normalizedMode = if (draft.rewardMode == "daily") "daily" else "once"
                            state.copy(
                                isCopyMode = true,
                                sourceMissionId = draft.sourceMissionId,
                                lockedCampaign = lockedCampaign,
                                myCampaigns = listOf(lockedCampaign),
                                selectedCampaignId = draft.campaignId,
                                title = draft.title.take(80),
                                description = draft.description.take(500),
                                rewardMode = normalizedMode,
                                participants = draft.maxParticipants.coerceIn(1, 50),
                                days = draft.durationDays.coerceIn(1, 7),
                                rewardCredits = draft.rewardCredits.coerceAtLeast(10).toString(),
                                blockPreviousParticipants = false,
                                userCredits = effectiveCredits,
                                isLoading = false,
                                initialLoadError = null,
                                allAppsBusy = false,
                            )
                        }
                    }
                    return@launch
                }

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
                _uiState.update { state ->
                    val keptCampaignId = state.selectedCampaignId?.takeIf { id -> selectable.any { it.id == id } }
                    state.copy(
                        isCopyMode = false,
                        sourceMissionId = null,
                        lockedCampaign = null,
                        blockPreviousParticipants = false,
                        myCampaigns = selectable,
                        selectedCampaignId = keptCampaignId ?: selectable.firstOrNull()?.id,
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
        rotateOperationId()
        _uiState.update { state ->
            val updated = state.copy(
                title = title?.take(80) ?: state.title,
                description = description?.take(500) ?: state.description,
                rewardCredits = rewardCredits?.filter(Char::isDigit)?.take(6) ?: state.rewardCredits,
                error = null,
            )
            persistEditedDraft(updated)
            updated
        }
    }

    fun updateTitle(title: String) = update(title = title)
    fun updateDescription(description: String) = update(description = description)
    fun updateReward(reward: String) = update(rewardCredits = reward)

    fun selectRewardMode(mode: String) {
        if (mode == "once" || mode == "daily") {
            rotateOperationId()
            _uiState.update { state ->
                val updated = state.copy(rewardMode = mode, error = null)
                persistEditedDraft(updated)
                updated
            }
        }
    }

    fun selectCampaign(campaignId: String) {
        if (isCopyMode) return
        _uiState.update { it.copy(selectedCampaignId = campaignId, error = null) }
    }

    fun adjustParticipants(delta: Int) {
        rotateOperationId()
        _uiState.update { state ->
            val updated = state.copy(
                participants = (state.participants + delta).coerceIn(1, 50),
                error = null,
            )
            persistEditedDraft(updated)
            updated
        }
    }

    fun adjustDays(delta: Int) {
        rotateOperationId()
        _uiState.update { state ->
            val updated = state.copy(
                days = (state.days + delta).coerceIn(1, 7),
                error = null,
            )
            persistEditedDraft(updated)
            updated
        }
    }

    fun toggleBlockPreviousParticipants(enabled: Boolean) {
        if (!isCopyMode) return
        rotateOperationId()
        _uiState.update { state ->
            val updated = state.copy(blockPreviousParticipants = enabled, error = null)
            persistEditedDraft(updated)
            updated
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
            ?: s.lockedCampaign?.takeIf { it.id == campaignId }
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
        val operationId = currentOperationId()
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                if (s.isCopyMode) {
                    val srcId = s.sourceMissionId ?: sourceMissionId.orEmpty()
                    repository.createMissionFromSource(
                        CreateMissionFromSourceParams(
                            sourceMissionId = srcId,
                            campaignId = campaignId,
                            title = s.title.trim(),
                            description = s.description.trim(),
                            maxParticipants = s.participants,
                            durationDays = s.days,
                            rewardCredits = reward,
                            rewardMode = s.rewardMode,
                            blockPreviousParticipants = s.blockPreviousParticipants,
                            clientOperationId = operationId,
                        )
                    )
                } else {
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
                }
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
                val isCreditErr = e.message?.contains("INSUFFICIENT_MIN_CREDITS") == true ||
                    e.message?.contains("INSUFFICIENT_CREDITS") == true
                val latestCredits = if (isCreditErr) {
                    runCatching { repository.getMyProfile(forceRefresh = true)?.credits }.getOrNull() ?: s.userCredits
                } else {
                    s.userCredits
                }
                if (e.message?.contains("CLIENT_OPERATION_ID_CONFLICT") == true) {
                    rotateOperationId()
                }
                val errorMsg = when {
                    e.message?.contains("INSUFFICIENT_MIN_CREDITS") == true ->
                        localizer.get(R.string.mission_err_min_balance_format, latestCredits)
                    e.message?.contains("INSUFFICIENT_CREDITS") == true ->
                        localizer.get(R.string.mission_total_cost_format, s.totalCostCredits, s.feeCredits, s.escrowCredits)
                    e.message?.contains("MISSION_ALREADY_EXISTS") == true ->
                        localizer.get(R.string.mission_err_already_exists)
                    e.message?.contains("CAMPAIGN_NOT_OPEN") == true ->
                        localizer.get(R.string.mission_err_app_required)
                    else -> friendlyError(e, localizer)
                }
                _uiState.update { it.copy(isSaving = false, userCredits = latestCredits, error = errorMsg) }
            }
        }
    }
}
