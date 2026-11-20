package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AppConfig
import com.ntt.crosstest.data.AppConfigRepository
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.SessionCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AdminFilter {
    ALL, ACTIVE, LOCKED, ADMIN
}

data class AdminUsersUiState(
    val isLoading: Boolean = true,
    val isActionLoading: Boolean = false,
    val allUsers: List<Profile> = emptyList(),
    val filteredUsers: List<Profile> = emptyList(),
    val query: String = "",
    val filter: AdminFilter = AdminFilter.ALL,
    val error: String? = null,
    val actionMessage: String? = null,
    val currentAdminId: String? = null,
    val appConfig: AppConfig? = null,
    val isConfigLoading: Boolean = false,
    val configSaveSignal: Int = 0,
    val homeStatsConfig: com.ntt.crosstest.data.HomeStatsPolicyConfig? = null,
    val isHomeStatsLoading: Boolean = false,
    val isHomeStatsSaving: Boolean = false,
    val homeStatsSaveSignal: Int = 0,
    val dailyTaskConfig: com.ntt.crosstest.data.AdminDailyTaskConfig? = null,
    val isDailyTaskLoading: Boolean = false,
    val isDailyTaskSaving: Boolean = false,
    val isDailyTaskCancelling: Boolean = false,
    val dailyTaskSaveSignal: Int = 0,
    val dailyTaskDraftRatio: Int? = null,
    val dailyTaskDraftEffectiveFrom: String? = null,
    val dailyTaskDraftNote: String = "",
    val dailyTaskFuturePolicies: List<com.ntt.crosstest.data.DailyTaskPolicyItem> = emptyList(),
    val isDailyTaskFutureLoading: Boolean = false,
    val dailyTaskFutureHasMore: Boolean = false,
    val dailyTaskFutureCursorDate: String? = null,
    val dailyTaskFutureCursorId: Long? = null,
    val dailyTaskHistory: List<com.ntt.crosstest.data.AdminDailyTaskPolicyHistoryItem> = emptyList(),
    val isDailyTaskHistoryLoading: Boolean = false,
    val dailyTaskHistoryHasMore: Boolean = false,
    val dailyTaskHistoryCursorId: Long? = null,
    val dailyTaskActionMessage: String? = null,
    val dailyTaskError: String? = null,
)

class AdminUsersViewModel(
    private val campaignRepository: CampaignRepository,
    private val authRepository: AuthRepository,
    private val appConfigRepository: AppConfigRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUsersUiState())
    val uiState: StateFlow<AdminUsersUiState> = _uiState.asStateFlow()
    private val homeStatsSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private val dailyTaskConfigSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private val dailyTaskMutationSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private val dailyTaskHistorySeq = java.util.concurrent.atomic.AtomicLong(0L)
    private val dailyTaskFutureSeq = java.util.concurrent.atomic.AtomicLong(0L)
    private var latestMutationRevision: String? = null

    init {
        _uiState.update { it.copy(currentAdminId = authRepository.currentUserId()) }
        refresh(forceRefresh = false)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    fun refresh(forceRefresh: Boolean = false) {
        val curUid = authRepository.currentUserId()
        if (_uiState.value.currentAdminId != curUid) {
            homeStatsSeq.incrementAndGet()
            dailyTaskConfigSeq.incrementAndGet()
            dailyTaskMutationSeq.incrementAndGet()
            dailyTaskHistorySeq.incrementAndGet()
            dailyTaskFutureSeq.incrementAndGet()
            latestMutationRevision = null
            _uiState.update {
                it.copy(
                    currentAdminId = curUid,
                    homeStatsConfig = null,
                    isHomeStatsLoading = false,
                    isHomeStatsSaving = false,
                    dailyTaskConfig = null,
                    isDailyTaskLoading = false,
                    isDailyTaskSaving = false,
                    isDailyTaskCancelling = false,
                    dailyTaskDraftRatio = null,
                    dailyTaskDraftEffectiveFrom = null,
                    dailyTaskDraftNote = "",
                    dailyTaskFuturePolicies = emptyList(),
                    isDailyTaskFutureLoading = false,
                    dailyTaskFutureHasMore = false,
                    dailyTaskFutureCursorDate = null,
                    dailyTaskFutureCursorId = null,
                    dailyTaskHistory = emptyList(),
                    isDailyTaskHistoryLoading = false,
                    dailyTaskHistoryHasMore = false,
                    dailyTaskHistoryCursorId = null,
                    dailyTaskActionMessage = null,
                    dailyTaskError = null,
                )
            }
        }
        viewModelScope.launch {
            if (forceRefresh || _uiState.value.allUsers.isEmpty()) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val profiles = campaignRepository.getAllProfiles(forceRefresh = forceRefresh)
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        allUsers = profiles,
                        filteredUsers = filterUsers(profiles, state.query, state.filter),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = if (_uiState.value.allUsers.isEmpty()) friendlyError(e, localizer) else null
                    )
                }
            }
        }
    }

    fun setQuery(q: String) {
        _uiState.update { state ->
            state.copy(
                query = q,
                filteredUsers = filterUsers(state.allUsers, q, state.filter),
            )
        }
    }

    fun setFilter(f: AdminFilter) {
        _uiState.update { state ->
            state.copy(
                filter = f,
                filteredUsers = filterUsers(state.allUsers, state.query, f),
            )
        }
    }

    private fun filterUsers(
        users: List<Profile>,
        query: String,
        filter: AdminFilter,
    ): List<Profile> {
        val q = query.trim().lowercase()
        return users.filter { u ->
            // Filter by search text
            val matchesQuery = q.isEmpty() ||
                (u.email?.lowercase()?.contains(q) == true) ||
                (u.displayName?.lowercase()?.contains(q) == true) ||
                u.id.lowercase().contains(q)

            // Filter by category
            val matchesFilter = when (filter) {
                AdminFilter.ALL -> true
                AdminFilter.ACTIVE -> !u.isLocked
                AdminFilter.LOCKED -> u.isLocked
                AdminFilter.ADMIN -> u.isAdmin
            }

            matchesQuery && matchesFilter
        }
    }

    fun setUserLock(targetUserId: String, locked: Boolean, reason: String? = null) {
        if (_uiState.value.isActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                campaignRepository.adminSetUserLock(targetUserId, locked, reason)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        actionMessage = if (locked) localizer.get(R.string.admin_user_locked_success) else localizer.get(R.string.admin_user_unlocked_success)
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_action_failed_format, friendlyError(e, localizer))
                    )
                }
            }
        }
    }

    fun adjustCredits(targetUserId: String, amount: Int, reason: String? = null) {
        if (_uiState.value.isActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                val res = campaignRepository.adminAdjustCredits(targetUserId, amount, reason)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        actionMessage = localizer.get(R.string.admin_credit_adjusted_format, res.newCredits)
                    )
                }
                refresh(forceRefresh = true)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_credit_adjust_failed_format, friendlyError(e, localizer))
                    )
                }
            }
        }
    }

    fun sendNotification(
        targetUserId: String?,
        title: String,
        message: String,
        type: String = "admin",
    ) {
        if (_uiState.value.isActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                val res = campaignRepository.adminSendNotification(
                    targetUserId = targetUserId,
                    title = title,
                    message = message,
                    type = type,
                )
                val msg = if (targetUserId == null) {
                    localizer.get(R.string.admin_notif_sent_all_format, res.sentCount)
                } else {
                    localizer.get(R.string.admin_notif_sent)
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        actionMessage = msg,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_notif_failed_format, friendlyError(e, localizer))
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- app config

    fun loadHomeStatsConfig() {
        if (_uiState.value.isHomeStatsLoading) return
        val reqUid = authRepository.currentUserId()
        val seq = homeStatsSeq.incrementAndGet()
        val initialGen = SessionCacheManager.currentGeneration()
        viewModelScope.launch {
            _uiState.update { it.copy(isHomeStatsLoading = true, error = null) }
            try {
                val config = campaignRepository.adminGetHomeStatsConfig()
                if (seq != homeStatsSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isHomeStatsLoading = false, homeStatsConfig = null) }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isHomeStatsLoading = false,
                        homeStatsConfig = config,
                    )
                }
            } catch (e: Exception) {
                if (seq != homeStatsSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isHomeStatsLoading = false, homeStatsConfig = null) }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isHomeStatsLoading = false,
                        error = localizer.get(R.string.admin_home_stats_load_failed, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }

    fun saveHomeStatsCycle(cycleDays: Int) {
        if (_uiState.value.isHomeStatsSaving) return
        val reqUid = authRepository.currentUserId()
        val seq = homeStatsSeq.incrementAndGet()
        val initialGen = SessionCacheManager.currentGeneration()
        viewModelScope.launch {
            _uiState.update { it.copy(isHomeStatsSaving = true, error = null) }
            try {
                val currentRev = _uiState.value.homeStatsConfig?.revision
                val updated = campaignRepository.adminSetHomeStatsCycle(cycleDays, currentRev)
                if (seq != homeStatsSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isHomeStatsSaving = false, homeStatsConfig = null) }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isHomeStatsSaving = false,
                        homeStatsConfig = updated,
                        actionMessage = localizer.get(R.string.admin_home_stats_saved_success),
                        homeStatsSaveSignal = it.homeStatsSaveSignal + 1,
                    )
                }
            } catch (e: Exception) {
                if (seq != homeStatsSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isHomeStatsSaving = false, homeStatsConfig = null) }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isHomeStatsSaving = false,
                        error = localizer.get(R.string.admin_home_stats_save_failed, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- daily task policy (migration 058 & 060)

    fun clearDailyTaskMessages() {
        _uiState.update { it.copy(dailyTaskActionMessage = null, dailyTaskError = null) }
    }

    fun setDailyTaskDraft(ratio: Int? = null, effectiveFrom: String? = null, note: String? = null) {
        _uiState.update {
            it.copy(
                dailyTaskDraftRatio = ratio ?: it.dailyTaskDraftRatio,
                dailyTaskDraftEffectiveFrom = effectiveFrom ?: it.dailyTaskDraftEffectiveFrom,
                dailyTaskDraftNote = note ?: it.dailyTaskDraftNote,
            )
        }
    }

    fun loadDailyTaskConfig(clearError: Boolean = true, resetDraft: Boolean = false) {
        if (_uiState.value.isDailyTaskLoading) return
        val reqUid = authRepository.currentUserId()
        val seq = dailyTaskConfigSeq.incrementAndGet()
        val initialGen = SessionCacheManager.currentGeneration()
        viewModelScope.launch {
            _uiState.update { it.copy(isDailyTaskLoading = true, dailyTaskError = if (clearError) null else it.dailyTaskError) }
            try {
                val config = campaignRepository.adminGetDailyTaskConfig()
                if (seq != dailyTaskConfigSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update { state ->
                    val defaultTomorrow = try {
                        java.time.LocalDate.parse(config.today).plusDays(1).toString()
                    } catch (_: Exception) {
                        com.ntt.crosstest.util.VnTime.today().plusDays(1).toString()
                    }
                    val currentDraftRatio = if (resetDraft || state.dailyTaskDraftRatio == null) {
                        config.activePolicy?.testsPerActiveApp ?: 3
                    } else {
                        state.dailyTaskDraftRatio
                    }
                    val currentDraftDate = if (resetDraft || state.dailyTaskDraftEffectiveFrom.isNullOrBlank()) {
                        defaultTomorrow
                    } else {
                        state.dailyTaskDraftEffectiveFrom
                    }
                    val keepCurrentConfig = latestMutationRevision != null &&
                            state.dailyTaskConfig?.revision == latestMutationRevision &&
                            config.revision != latestMutationRevision
                    val effectiveConfig = if (keepCurrentConfig) state.dailyTaskConfig else config
                    state.copy(
                        isDailyTaskLoading = false,
                        dailyTaskConfig = effectiveConfig,
                        dailyTaskFuturePolicies = effectiveConfig?.futurePolicies ?: config.futurePolicies,
                        dailyTaskFutureHasMore = effectiveConfig?.hasMore ?: config.hasMore,
                        dailyTaskFutureCursorDate = effectiveConfig?.nextCursorDate ?: config.nextCursorDate,
                        dailyTaskFutureCursorId = effectiveConfig?.nextCursorId ?: config.nextCursorId,
                        dailyTaskDraftRatio = currentDraftRatio,
                        dailyTaskDraftEffectiveFrom = currentDraftDate,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                if (seq != dailyTaskConfigSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isDailyTaskLoading = false,
                        dailyTaskError = localizer.get(R.string.admin_daily_task_load_failed, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }

    fun saveDailyTaskSchedule(ratio: Int, effectiveFrom: String, note: String) {
        if (_uiState.value.isDailyTaskSaving || _uiState.value.isDailyTaskCancelling) return
        val reqUid = authRepository.currentUserId()
        val seq = dailyTaskMutationSeq.incrementAndGet()
        val initialGen = SessionCacheManager.currentGeneration()
        val currentRev = _uiState.value.dailyTaskConfig?.revision
        if (currentRev.isNullOrBlank()) {
            _uiState.update { it.copy(dailyTaskError = localizer.get(R.string.admin_daily_task_err_revision_required)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isDailyTaskSaving = true, dailyTaskError = null, dailyTaskActionMessage = null) }
            try {
                val mutation = campaignRepository.adminScheduleDailyTaskPolicy(
                    testsPerActiveApp = ratio,
                    effectiveFrom = effectiveFrom,
                    operatorNote = note.trim(),
                    expectedRevision = currentRev,
                )
                if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                val newConfig = mutation.config
                latestMutationRevision = newConfig?.revision ?: mutation.revision
                val defaultTomorrow = newConfig?.today?.let {
                    try { java.time.LocalDate.parse(it).plusDays(1).toString() } catch (_: Exception) { null }
                } ?: com.ntt.crosstest.util.VnTime.today().plusDays(1).toString()

                _uiState.update {
                    it.copy(
                        isDailyTaskSaving = false,
                        dailyTaskConfig = newConfig ?: it.dailyTaskConfig,
                        dailyTaskFuturePolicies = newConfig?.futurePolicies ?: it.dailyTaskFuturePolicies,
                        dailyTaskFutureHasMore = newConfig?.hasMore ?: false,
                        dailyTaskFutureCursorDate = newConfig?.nextCursorDate,
                        dailyTaskFutureCursorId = newConfig?.nextCursorId,
                        dailyTaskSaveSignal = it.dailyTaskSaveSignal + 1,
                        dailyTaskActionMessage = localizer.get(R.string.admin_daily_task_save_success),
                        dailyTaskDraftRatio = newConfig?.activePolicy?.testsPerActiveApp ?: ratio,
                        dailyTaskDraftEffectiveFrom = defaultTomorrow,
                        dailyTaskDraftNote = "",
                        dailyTaskHistory = emptyList(),
                        dailyTaskHistoryCursorId = null,
                        dailyTaskHistoryHasMore = false,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                // Giữ bản nháp khi lỗi
                _uiState.update {
                    it.copy(
                        isDailyTaskSaving = false,
                        dailyTaskDraftRatio = ratio,
                        dailyTaskDraftEffectiveFrom = effectiveFrom,
                        dailyTaskDraftNote = note,
                        dailyTaskError = localizer.get(R.string.admin_daily_task_save_failed, friendlyError(e, localizer)),
                    )
                }
                // Nếu bị conflict: tải lại config mới ngầm để admin đối chiếu, nhưng giữ bản nháp và giữ lỗi conflict
                val errStr = e.message.orEmpty().lowercase()
                if ("daily_task_policy_conflict" in errStr) {
                    loadDailyTaskConfig(clearError = false, resetDraft = false)
                }
            }
        }
    }

    fun cancelDailyTaskSchedule(effectiveFrom: String, note: String) {
        if (_uiState.value.isDailyTaskSaving || _uiState.value.isDailyTaskCancelling) return
        val reqUid = authRepository.currentUserId()
        val seq = dailyTaskMutationSeq.incrementAndGet()
        val initialGen = SessionCacheManager.currentGeneration()
        val currentRev = _uiState.value.dailyTaskConfig?.revision
        if (currentRev.isNullOrBlank()) {
            _uiState.update { it.copy(dailyTaskError = localizer.get(R.string.admin_daily_task_err_revision_required)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isDailyTaskCancelling = true, dailyTaskError = null, dailyTaskActionMessage = null) }
            try {
                val mutation = campaignRepository.adminCancelDailyTaskPolicy(
                    effectiveFrom = effectiveFrom,
                    operatorNote = note.trim(),
                    expectedRevision = currentRev,
                )
                if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                val newConfig = mutation.config
                latestMutationRevision = newConfig?.revision ?: mutation.revision
                _uiState.update {
                    it.copy(
                        isDailyTaskCancelling = false,
                        dailyTaskConfig = newConfig ?: it.dailyTaskConfig,
                        dailyTaskFuturePolicies = newConfig?.futurePolicies ?: it.dailyTaskFuturePolicies,
                        dailyTaskFutureHasMore = newConfig?.hasMore ?: false,
                        dailyTaskFutureCursorDate = newConfig?.nextCursorDate,
                        dailyTaskFutureCursorId = newConfig?.nextCursorId,
                        dailyTaskSaveSignal = it.dailyTaskSaveSignal + 1,
                        dailyTaskActionMessage = localizer.get(R.string.admin_daily_task_cancel_success),
                        dailyTaskHistory = emptyList(),
                        dailyTaskHistoryCursorId = null,
                        dailyTaskHistoryHasMore = false,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isDailyTaskCancelling = false,
                        dailyTaskError = localizer.get(R.string.admin_daily_task_cancel_failed, friendlyError(e, localizer)),
                    )
                }
                val errStr = e.message.orEmpty().lowercase()
                if ("daily_task_policy_conflict" in errStr) {
                    loadDailyTaskConfig(clearError = false, resetDraft = false)
                }
            }
        }
    }

    fun loadDailyTaskHistory(reset: Boolean = false) {
        if (_uiState.value.isDailyTaskHistoryLoading) return
        val reqUid = authRepository.currentUserId()
        val seq = dailyTaskHistorySeq.incrementAndGet()
        val initialGen = SessionCacheManager.currentGeneration()
        val cursorId = if (reset) null else _uiState.value.dailyTaskHistoryCursorId
        viewModelScope.launch {
            _uiState.update { it.copy(isDailyTaskHistoryLoading = true) }
            try {
                val result = campaignRepository.adminGetDailyTaskPolicyHistory(limit = 20, cursorId = cursorId)
                if (seq != dailyTaskHistorySeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update { state ->
                    val combined = if (reset) result.items else state.dailyTaskHistory + result.items
                    state.copy(
                        isDailyTaskHistoryLoading = false,
                        dailyTaskHistory = combined,
                        dailyTaskHistoryHasMore = result.hasMore,
                        dailyTaskHistoryCursorId = result.nextCursorId,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                if (seq != dailyTaskHistorySeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isDailyTaskHistoryLoading = false,
                        dailyTaskError = localizer.get(R.string.admin_daily_task_load_failed, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }

    fun loadMoreDailyTaskPolicies() {
        if (_uiState.value.isDailyTaskFutureLoading || !_uiState.value.dailyTaskFutureHasMore) return
        val reqUid = authRepository.currentUserId()
        val seq = dailyTaskFutureSeq.incrementAndGet()
        val initialGen = SessionCacheManager.currentGeneration()
        val cursorDate = _uiState.value.dailyTaskFutureCursorDate
        val cursorId = _uiState.value.dailyTaskFutureCursorId
        viewModelScope.launch {
            _uiState.update { it.copy(isDailyTaskFutureLoading = true) }
            try {
                val result = campaignRepository.adminListDailyTaskPolicies(
                    limit = 50,
                    cursorDate = cursorDate,
                    cursorId = cursorId,
                )
                if (seq != dailyTaskFutureSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update { state ->
                    val existing = state.dailyTaskFuturePolicies
                    val existingIds = existing.map { it.id }.toSet()
                    val newItems = result.items.filter { it.id !in existingIds }
                    val combined = (existing + newItems).sortedWith(
                        compareBy<com.ntt.crosstest.data.DailyTaskPolicyItem> { it.effectiveFrom ?: "" }.thenBy { it.id }
                    )
                    state.copy(
                        isDailyTaskFutureLoading = false,
                        dailyTaskFuturePolicies = combined,
                        dailyTaskFutureHasMore = result.hasMore,
                        dailyTaskFutureCursorDate = result.nextCursorDate,
                        dailyTaskFutureCursorId = result.nextCursorId,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                if (seq != dailyTaskFutureSeq.get() || reqUid != authRepository.currentUserId() || initialGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isDailyTaskFutureLoading = false,
                        dailyTaskError = localizer.get(R.string.admin_daily_task_load_failed, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }

    fun loadAppConfig() {
        if (_uiState.value.isConfigLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isConfigLoading = true, error = null) }
            val config = appConfigRepository.getAppConfig(forceRefresh = true)
            _uiState.update {
                it.copy(
                    isConfigLoading = false,
                    appConfig = config ?: it.appConfig,
                    error = if (config == null) localizer.get(R.string.admin_config_load_failed) else null,
                )
            }
        }
    }

    fun saveAppConfig(
        minVersionCode: Int,
        latestVersionCode: Int,
        latestVersionName: String,
        forceUpdate: Boolean,
        title: String?,
        message: String?,
        storeUrl: String?,
        titleVi: String? = null,
        titleEn: String? = null,
        messageVi: String? = null,
        messageEn: String? = null,
    ) {
        if (_uiState.value.isActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                val updated = appConfigRepository.updateAppConfig(
                    minVersionCode = minVersionCode,
                    latestVersionCode = latestVersionCode,
                    latestVersionName = latestVersionName,
                    forceUpdate = forceUpdate,
                    title = title,
                    message = message,
                    storeUrl = storeUrl,
                    titleVi = titleVi,
                    titleEn = titleEn,
                    messageVi = messageVi,
                    messageEn = messageEn,
                )
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        appConfig = updated,
                        actionMessage = localizer.get(R.string.admin_config_saved),
                        configSaveSignal = it.configSaveSignal + 1,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_config_save_failed_format, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }
}
