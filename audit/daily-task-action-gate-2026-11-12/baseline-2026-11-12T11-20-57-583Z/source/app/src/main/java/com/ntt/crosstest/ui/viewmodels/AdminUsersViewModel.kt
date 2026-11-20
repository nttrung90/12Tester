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
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class AdminFilter {
    ALL, ACTIVE, LOCKED, ADMIN, MODERATOR
}

data class AdminUsersUiState(
    val isLoading: Boolean = true,
    val isActionLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMoreMembers: Boolean = false,
    val nextCursorCreatedAt: String? = null,
    val nextCursorId: String? = null,
    val allUsers: List<Profile> = emptyList(),
    val filteredUsers: List<Profile> = emptyList(),
    val query: String = "",
    val filter: AdminFilter = AdminFilter.ALL,
    val error: String? = null,
    val actionMessage: String? = null,
    val currentAdminId: String? = null,
    val sessionGeneration: Long = 0L,
    val appConfig: AppConfig? = null,
    val isConfigLoading: Boolean = false,
    val configSaveSignal: Int = 0,
    val homeStatsConfig: com.ntt.crosstest.data.HomeStatsPolicyConfig? = null,
    val isHomeStatsLoading: Boolean = false,
    val isHomeStatsSaving: Boolean = false,
    val homeStatsSaveSignal: Int = 0,
    val ownerAutoPauseConfig: com.ntt.crosstest.data.OwnerAutoPauseConfig? = null,
    val isOwnerAutoPauseLoading: Boolean = false,
    val isOwnerAutoPauseSaving: Boolean = false,
    val ownerAutoPauseSaveSignal: Int = 0,
    val ownerAutoPauseError: String? = null,
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
    private val adminManagementRepository: com.ntt.crosstest.data.AdminManagementRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUsersUiState())
    val uiState: StateFlow<AdminUsersUiState> = _uiState.asStateFlow()
    private val usersSeq = AtomicLong(0L)
    private val loadMoreSeq = AtomicLong(0L)
    private var searchJob: kotlinx.coroutines.Job? = null
    private val actionSeq = AtomicLong(0L)
    private val appConfigLoadSeq = AtomicLong(0L)
    private val appConfigSaveSeq = AtomicLong(0L)
    private val homeStatsLoadSeq = AtomicLong(0L)
    private val homeStatsSaveSeq = AtomicLong(0L)
    private val ownerAutoPauseLoadSeq = AtomicLong(0L)
    private val ownerAutoPauseSaveSeq = AtomicLong(0L)
    private val dailyTaskConfigSeq = AtomicLong(0L)
    private val dailyTaskMutationSeq = AtomicLong(0L)
    private val dailyTaskHistorySeq = AtomicLong(0L)
    private val dailyTaskFutureSeq = AtomicLong(0L)
    private var latestMutationRevision: String? = null
    private var activeUserId: String? = authRepository.currentUserId()
    private var activeGeneration: Long = SessionCacheManager.currentGeneration()

    init {
        _uiState.update { it.copy(currentAdminId = activeUserId, sessionGeneration = activeGeneration) }
        observeSessionStatus()
        refresh(forceRefresh = false)
    }

    private fun reconcileSession(): Pair<String?, Long> {
        val curUid = authRepository.currentUserId()
        val curGen = SessionCacheManager.currentGeneration()
        if (activeUserId != curUid || activeGeneration != curGen) {
            handleSessionReset(curUid, curGen)
        }
        return Pair(curUid, curGen)
    }

    private fun checkSessionChanged(reqUid: String?, reqGen: Long): Boolean {
        val curUid = authRepository.currentUserId()
        val curGen = SessionCacheManager.currentGeneration()
        if (activeUserId != curUid || activeGeneration != curGen) {
            handleSessionReset(curUid, curGen)
            return true
        }
        if (reqUid != curUid || reqGen != curGen) {
            return true
        }
        return false
    }

    private fun isStale(reqUid: String?, reqGen: Long): Boolean {
        return checkSessionChanged(reqUid, reqGen)
    }

    private fun observeSessionStatus() {
        viewModelScope.launch {
            authRepository.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val newUid = authRepository.currentUserId()
                        val newGen = SessionCacheManager.currentGeneration()
                        if (newUid != activeUserId || newGen != activeGeneration) {
                            handleSessionReset(newUid, newGen)
                            if (newUid != null) {
                                refresh(forceRefresh = true)
                            }
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        val newGen = SessionCacheManager.currentGeneration()
                        if (activeUserId != null || activeGeneration != newGen) {
                            handleSessionReset(null, newGen)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleSessionReset(newUid: String?, newGen: Long) {
        if (activeUserId == newUid && activeGeneration == newGen) return
        activeUserId = newUid
        activeGeneration = newGen
        usersSeq.incrementAndGet()
        loadMoreSeq.incrementAndGet()
        searchJob?.cancel()
        actionSeq.incrementAndGet()
        appConfigLoadSeq.incrementAndGet()
        appConfigSaveSeq.incrementAndGet()
        homeStatsLoadSeq.incrementAndGet()
        homeStatsSaveSeq.incrementAndGet()
        ownerAutoPauseLoadSeq.incrementAndGet()
        ownerAutoPauseSaveSeq.incrementAndGet()
        dailyTaskConfigSeq.incrementAndGet()
        dailyTaskMutationSeq.incrementAndGet()
        dailyTaskHistorySeq.incrementAndGet()
        dailyTaskFutureSeq.incrementAndGet()
        latestMutationRevision = null

        _uiState.update {
            AdminUsersUiState(
                currentAdminId = newUid,
                sessionGeneration = newGen,
                isLoading = false,
                isActionLoading = false,
                isLoadingMore = false,
                hasMoreMembers = false,
                nextCursorCreatedAt = null,
                nextCursorId = null,
                isConfigLoading = false,
                isHomeStatsLoading = false,
                isHomeStatsSaving = false,
                isDailyTaskLoading = false,
                isDailyTaskSaving = false,
                isDailyTaskCancelling = false,
                isDailyTaskFutureLoading = false,
                isDailyTaskHistoryLoading = false,
                allUsers = emptyList(),
                filteredUsers = emptyList(),
                query = "",
                filter = AdminFilter.ALL,
                error = null,
                actionMessage = null,
                appConfig = null,
                homeStatsConfig = null,
                ownerAutoPauseConfig = null,
                isOwnerAutoPauseLoading = false,
                isOwnerAutoPauseSaving = false,
                ownerAutoPauseError = null,
                dailyTaskConfig = null,
                dailyTaskFuturePolicies = emptyList(),
                dailyTaskHistory = emptyList(),
                dailyTaskDraftRatio = null,
                dailyTaskDraftEffectiveFrom = null,
                dailyTaskDraftNote = "",
                dailyTaskActionMessage = null,
                dailyTaskError = null,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    fun refresh(forceRefresh: Boolean = false) {
        val (reqUid, reqGen) = reconcileSession()
        val shouldShowLoading = forceRefresh || _uiState.value.filteredUsers.isEmpty()
        if (shouldShowLoading) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        val seq = usersSeq.incrementAndGet()
        loadMoreSeq.incrementAndGet()
        val queryAtStart = _uiState.value.query
        val filterAtStart = _uiState.value.filter

        viewModelScope.launch {
            if (seq != usersSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                if (adminManagementRepository != null) {
                    val roleParam = when (filterAtStart) {
                        AdminFilter.ADMIN -> "admin"
                        AdminFilter.MODERATOR -> "moderator"
                        else -> null
                    }
                    val isLockedParam = when (filterAtStart) {
                        AdminFilter.ACTIVE -> false
                        AdminFilter.LOCKED -> true
                        else -> null
                    }
                    val envelope = adminManagementRepository.searchMembers(
                        query = queryAtStart,
                        role = roleParam,
                        isLocked = isLockedParam,
                        limit = 50,
                    )
                    if (seq != usersSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration() ||
                        queryAtStart != _uiState.value.query || filterAtStart != _uiState.value.filter
                    ) {
                        return@launch
                    }
                    val profiles = envelope.items.map { it.toProfile() }
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            filteredUsers = profiles,
                            allUsers = profiles,
                            hasMoreMembers = envelope.hasMore,
                            nextCursorCreatedAt = envelope.nextCursor?.createdAt,
                            nextCursorId = envelope.nextCursor?.id,
                        )
                    }
                } else {
                    val profiles = campaignRepository.getAllProfiles(forceRefresh = forceRefresh)
                    if (seq != usersSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                        return@launch
                    }
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            allUsers = profiles,
                            filteredUsers = filterUsers(profiles, state.query, state.filter),
                            hasMoreMembers = false,
                            nextCursorCreatedAt = null,
                            nextCursorId = null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (seq == usersSeq.get() && reqUid == authRepository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isLoading = false, isLoadingMore = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != usersSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = if (forceRefresh || it.filteredUsers.isEmpty()) friendlyError(e, localizer) else null
                    )
                }
            }
        }
    }

    fun loadMoreMembers() {
        val (reqUid, reqGen) = reconcileSession()
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMoreMembers || adminManagementRepository == null) return
        val cursorCreatedAt = state.nextCursorCreatedAt ?: return
        val cursorId = state.nextCursorId ?: return
        _uiState.update { it.copy(isLoadingMore = true) }
        val seq = loadMoreSeq.incrementAndGet()
        val queryAtStart = state.query
        val filterAtStart = state.filter

        viewModelScope.launch {
            if (seq != loadMoreSeq.get() || checkSessionChanged(reqUid, reqGen)) return@launch
            try {
                val roleParam = when (filterAtStart) {
                    AdminFilter.ADMIN -> "admin"
                    AdminFilter.MODERATOR -> "moderator"
                    else -> null
                }
                val isLockedParam = when (filterAtStart) {
                    AdminFilter.ACTIVE -> false
                    AdminFilter.LOCKED -> true
                    else -> null
                }
                val result = adminManagementRepository.searchMembers(
                    query = queryAtStart,
                    role = roleParam,
                    isLocked = isLockedParam,
                    limit = 50,
                    cursorCreatedAt = cursorCreatedAt,
                    cursorId = cursorId,
                )
                if (seq != loadMoreSeq.get() || checkSessionChanged(reqUid, reqGen) ||
                    queryAtStart != _uiState.value.query || filterAtStart != _uiState.value.filter
                ) {
                    return@launch
                }
                _uiState.update { s ->
                    val existingIds = s.filteredUsers.map { it.id }.toSet()
                    val newProfiles = result.items.map { it.toProfile() }.filter { it.id !in existingIds }
                    val updatedFiltered = s.filteredUsers + newProfiles
                    s.copy(
                        isLoadingMore = false,
                        filteredUsers = updatedFiltered,
                        allUsers = (s.allUsers + newProfiles).distinctBy { it.id },
                        hasMoreMembers = result.hasMore,
                        nextCursorCreatedAt = result.nextCursor?.createdAt,
                        nextCursorId = result.nextCursor?.id,
                    )
                }
            } catch (e: CancellationException) {
                if (seq == loadMoreSeq.get() && !checkSessionChanged(reqUid, reqGen)) {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != loadMoreSeq.get() || checkSessionChanged(reqUid, reqGen)) return@launch
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        error = friendlyError(e, localizer),
                    )
                }
            }
        }
    }

    fun setQuery(q: String) {
        _uiState.update { state ->
            state.copy(
                query = q,
                hasMoreMembers = false,
                nextCursorCreatedAt = null,
                nextCursorId = null,
            )
        }
        loadMoreSeq.incrementAndGet()
        if (adminManagementRepository != null) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                refresh(forceRefresh = false)
            }
        } else {
            _uiState.update { state ->
                state.copy(filteredUsers = filterUsers(state.allUsers, q, state.filter))
            }
        }
    }

    fun setFilter(f: AdminFilter) {
        _uiState.update { state ->
            state.copy(
                filter = f,
                hasMoreMembers = false,
                nextCursorCreatedAt = null,
                nextCursorId = null,
            )
        }
        loadMoreSeq.incrementAndGet()
        if (adminManagementRepository != null) {
            searchJob?.cancel()
            refresh(forceRefresh = false)
        } else {
            _uiState.update { state ->
                state.copy(filteredUsers = filterUsers(state.allUsers, state.query, f))
            }
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
                AdminFilter.MODERATOR -> u.isModerator
            }

            matchesQuery && matchesFilter
        }
    }

    fun setUserLock(
        targetUserId: String,
        locked: Boolean,
        reason: String? = null,
        confirmingAdminId: String? = null,
        confirmingSessionGen: Long = 0L,
    ) {
        val (reqUid, reqGen) = reconcileSession()
        if (confirmingAdminId != null && (confirmingAdminId != reqUid || confirmingSessionGen != reqGen)) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_action_aborted_session_changed)) }
            return
        }
        if (_uiState.value.isActionLoading) return
        _uiState.update { it.copy(isActionLoading = true, error = null) }
        val seq = actionSeq.incrementAndGet()

        viewModelScope.launch {
            if (seq != actionSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                campaignRepository.adminSetUserLock(targetUserId, locked, reason)
                if (seq != actionSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        actionMessage = if (locked) localizer.get(R.string.admin_user_locked_success) else localizer.get(R.string.admin_user_unlocked_success)
                    )
                }
                usersSeq.incrementAndGet()
                refresh(forceRefresh = true)
            } catch (e: CancellationException) {
                if (seq == actionSeq.get() && reqUid == authRepository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != actionSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_action_failed_format, friendlyError(e, localizer))
                    )
                }
            }
        }
    }

    fun adjustCredits(
        targetUserId: String,
        amount: Int,
        reason: String? = null,
        confirmingAdminId: String? = null,
        confirmingSessionGen: Long = 0L,
    ) {
        val (reqUid, reqGen) = reconcileSession()
        if (confirmingAdminId != null && (confirmingAdminId != reqUid || confirmingSessionGen != reqGen)) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_action_aborted_session_changed)) }
            return
        }
        if (_uiState.value.isActionLoading) return
        _uiState.update { it.copy(isActionLoading = true, error = null) }
        val seq = actionSeq.incrementAndGet()

        viewModelScope.launch {
            if (seq != actionSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                val res = campaignRepository.adminAdjustCredits(targetUserId, amount, reason)
                if (seq != actionSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        actionMessage = localizer.get(R.string.admin_credit_adjusted_format, res.newCredits)
                    )
                }
                usersSeq.incrementAndGet()
                refresh(forceRefresh = true)
            } catch (e: CancellationException) {
                if (seq == actionSeq.get() && reqUid == authRepository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != actionSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
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
        confirmingAdminId: String? = null,
        confirmingSessionGen: Long = 0L,
    ) {
        val (reqUid, reqGen) = reconcileSession()
        if (confirmingAdminId != null && (confirmingAdminId != reqUid || confirmingSessionGen != reqGen)) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_action_aborted_session_changed)) }
            return
        }
        if (_uiState.value.isActionLoading) return
        _uiState.update { it.copy(isActionLoading = true, error = null) }
        val seq = actionSeq.incrementAndGet()

        viewModelScope.launch {
            if (seq != actionSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                val res = campaignRepository.adminSendNotification(
                    targetUserId = targetUserId,
                    title = title,
                    message = message,
                    type = type,
                )
                if (seq != actionSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
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
            } catch (e: CancellationException) {
                if (seq == actionSeq.get() && reqUid == authRepository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != actionSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
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
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isHomeStatsLoading || _uiState.value.isHomeStatsSaving) return
        _uiState.update { it.copy(isHomeStatsLoading = true, error = null) }
        val loadSeq = homeStatsLoadSeq.incrementAndGet()
        val saveSeqAtStart = homeStatsSaveSeq.get()

        viewModelScope.launch {
            if (checkSessionChanged(reqUid, reqGen) || loadSeq != homeStatsLoadSeq.get()) {
                return@launch
            }
            try {
                val config = campaignRepository.adminGetHomeStatsConfig()
                if (checkSessionChanged(reqUid, reqGen) || loadSeq != homeStatsLoadSeq.get()) {
                    return@launch
                }
                _uiState.update { state ->
                    val keepCurrentConfig = saveSeqAtStart != homeStatsSaveSeq.get()
                    state.copy(
                        isHomeStatsLoading = false,
                        homeStatsConfig = if (keepCurrentConfig) state.homeStatsConfig else config,
                    )
                }
            } catch (e: CancellationException) {
                if (!checkSessionChanged(reqUid, reqGen) && loadSeq == homeStatsLoadSeq.get()) {
                    _uiState.update { it.copy(isHomeStatsLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (checkSessionChanged(reqUid, reqGen) || loadSeq != homeStatsLoadSeq.get()) {
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
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isHomeStatsSaving) return
        _uiState.update { it.copy(isHomeStatsSaving = true, error = null) }
        val saveSeq = homeStatsSaveSeq.incrementAndGet()

        viewModelScope.launch {
            if (checkSessionChanged(reqUid, reqGen) || saveSeq != homeStatsSaveSeq.get()) {
                return@launch
            }
            try {
                val currentRev = _uiState.value.homeStatsConfig?.revision
                val updated = campaignRepository.adminSetHomeStatsCycle(cycleDays, currentRev)
                if (checkSessionChanged(reqUid, reqGen) || saveSeq != homeStatsSaveSeq.get()) {
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
            } catch (e: CancellationException) {
                if (!checkSessionChanged(reqUid, reqGen) && saveSeq == homeStatsSaveSeq.get()) {
                    _uiState.update { it.copy(isHomeStatsSaving = false) }
                }
                throw e
            } catch (e: Exception) {
                if (checkSessionChanged(reqUid, reqGen) || saveSeq != homeStatsSaveSeq.get()) {
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

    // ------------------------------------------------------------- owner auto pause config (migration 075)

    fun clearOwnerAutoPauseError() {
        _uiState.update { it.copy(ownerAutoPauseError = null) }
    }

    fun loadOwnerAutoPauseConfig(clearError: Boolean = true) {
        val (reqUid, reqGen) = reconcileSession()
        if (reqUid == null || _uiState.value.isOwnerAutoPauseLoading || _uiState.value.isOwnerAutoPauseSaving) return
        _uiState.update { it.copy(isOwnerAutoPauseLoading = true, ownerAutoPauseError = if (clearError) null else it.ownerAutoPauseError) }
        val loadSeq = ownerAutoPauseLoadSeq.incrementAndGet()

        viewModelScope.launch {
            if (checkSessionChanged(reqUid, reqGen) || loadSeq != ownerAutoPauseLoadSeq.get()) {
                return@launch
            }
            try {
                val config = campaignRepository.adminGetOwnerAutoPauseConfig()
                if (checkSessionChanged(reqUid, reqGen) || loadSeq != ownerAutoPauseLoadSeq.get()) {
                    return@launch
                }
                check(config.missedDays in 1..7 && config.revision.isNotBlank())
                _uiState.update {
                    it.copy(isOwnerAutoPauseLoading = false, ownerAutoPauseConfig = config)
                }
            } catch (e: CancellationException) {
                if (!checkSessionChanged(reqUid, reqGen) && loadSeq == ownerAutoPauseLoadSeq.get()) {
                    _uiState.update { it.copy(isOwnerAutoPauseLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (checkSessionChanged(reqUid, reqGen) || loadSeq != ownerAutoPauseLoadSeq.get()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isOwnerAutoPauseLoading = false,
                        ownerAutoPauseConfig = null,
                        ownerAutoPauseError = localizer.get(R.string.admin_owner_auto_pause_load_failed, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }

    fun saveOwnerAutoPauseConfig(missedDays: Int) {
        val (reqUid, reqGen) = reconcileSession()
        val state = _uiState.value
        if (reqUid == null || state.isOwnerAutoPauseSaving || state.isOwnerAutoPauseLoading || missedDays !in 1..7) return
        val config = state.ownerAutoPauseConfig
        if (config == null) {
            _uiState.update { it.copy(ownerAutoPauseError = localizer.get(R.string.admin_daily_task_err_revision_required)) }
            return
        }
        if (missedDays == config.missedDays) return
        _uiState.update { it.copy(isOwnerAutoPauseSaving = true, ownerAutoPauseError = null) }
        val saveSeq = ownerAutoPauseSaveSeq.incrementAndGet()

        viewModelScope.launch {
            if (checkSessionChanged(reqUid, reqGen) || saveSeq != ownerAutoPauseSaveSeq.get()) {
                return@launch
            }
            try {
                val result = campaignRepository.adminSetOwnerAutoPauseConfig(missedDays, config.revision)
                if (checkSessionChanged(reqUid, reqGen) || saveSeq != ownerAutoPauseSaveSeq.get()) {
                    return@launch
                }
                check(result.success && result.missedDays in 1..7 && result.revision.isNotBlank())
                _uiState.update {
                    it.copy(
                        isOwnerAutoPauseSaving = false,
                        ownerAutoPauseConfig = com.ntt.crosstest.data.OwnerAutoPauseConfig(
                            missedDays = result.missedDays,
                            revision = result.revision,
                        ),
                        actionMessage = localizer.get(R.string.admin_owner_auto_pause_save_success),
                        ownerAutoPauseSaveSignal = it.ownerAutoPauseSaveSignal + 1,
                    )
                }
            } catch (e: CancellationException) {
                if (!checkSessionChanged(reqUid, reqGen) && saveSeq == ownerAutoPauseSaveSeq.get()) {
                    _uiState.update { it.copy(isOwnerAutoPauseSaving = false) }
                }
                throw e
            } catch (e: Exception) {
                if (checkSessionChanged(reqUid, reqGen) || saveSeq != ownerAutoPauseSaveSeq.get()) {
                    return@launch
                }
                val isConflict = e.message?.contains("CONCURRENCY_CONFLICT") == true
                _uiState.update {
                    it.copy(
                        isOwnerAutoPauseSaving = false,
                        ownerAutoPauseConfig = if (isConflict) null else it.ownerAutoPauseConfig,
                        ownerAutoPauseError = if (isConflict) {
                            localizer.get(R.string.admin_owner_auto_pause_conflict)
                        } else {
                            localizer.get(R.string.admin_owner_auto_pause_save_failed, friendlyError(e, localizer))
                        },
                    )
                }
                if (isConflict) loadOwnerAutoPauseConfig(clearError = false)
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
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isDailyTaskLoading) return
        _uiState.update { it.copy(isDailyTaskLoading = true, dailyTaskError = if (clearError) null else it.dailyTaskError) }
        val seq = dailyTaskConfigSeq.incrementAndGet()

        viewModelScope.launch {
            if (seq != dailyTaskConfigSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                val config = campaignRepository.adminGetDailyTaskConfig()
                if (seq != dailyTaskConfigSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
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
            } catch (e: CancellationException) {
                if (seq == dailyTaskConfigSeq.get() && reqUid == authRepository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isDailyTaskLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != dailyTaskConfigSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
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
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isDailyTaskSaving || _uiState.value.isDailyTaskCancelling) return
        val currentRev = _uiState.value.dailyTaskConfig?.revision
        if (currentRev.isNullOrBlank()) {
            _uiState.update { it.copy(dailyTaskError = localizer.get(R.string.admin_daily_task_err_revision_required)) }
            return
        }
        _uiState.update { it.copy(isDailyTaskSaving = true, dailyTaskError = null, dailyTaskActionMessage = null) }
        val seq = dailyTaskMutationSeq.incrementAndGet()

        viewModelScope.launch {
            if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                val mutation = campaignRepository.adminScheduleDailyTaskPolicy(
                    testsPerActiveApp = ratio,
                    effectiveFrom = effectiveFrom,
                    operatorNote = note.trim(),
                    expectedRevision = currentRev,
                )
                if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
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
            } catch (e: CancellationException) {
                if (seq == dailyTaskMutationSeq.get() && reqUid == authRepository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isDailyTaskSaving = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isDailyTaskSaving = false,
                        dailyTaskDraftRatio = ratio,
                        dailyTaskDraftEffectiveFrom = effectiveFrom,
                        dailyTaskDraftNote = note,
                        dailyTaskError = localizer.get(R.string.admin_daily_task_save_failed, friendlyError(e, localizer)),
                    )
                }
                val errStr = e.message.orEmpty().lowercase()
                if ("daily_task_policy_conflict" in errStr) {
                    loadDailyTaskConfig(clearError = false, resetDraft = false)
                }
            }
        }
    }

    fun cancelDailyTaskSchedule(effectiveFrom: String, note: String) {
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isDailyTaskSaving || _uiState.value.isDailyTaskCancelling) return
        val currentRev = _uiState.value.dailyTaskConfig?.revision
        if (currentRev.isNullOrBlank()) {
            _uiState.update { it.copy(dailyTaskError = localizer.get(R.string.admin_daily_task_err_revision_required)) }
            return
        }
        _uiState.update { it.copy(isDailyTaskCancelling = true, dailyTaskError = null, dailyTaskActionMessage = null) }
        val seq = dailyTaskMutationSeq.incrementAndGet()

        viewModelScope.launch {
            if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                val mutation = campaignRepository.adminCancelDailyTaskPolicy(
                    effectiveFrom = effectiveFrom,
                    operatorNote = note.trim(),
                    expectedRevision = currentRev,
                )
                if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
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
            } catch (e: CancellationException) {
                if (seq == dailyTaskMutationSeq.get() && reqUid == authRepository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isDailyTaskCancelling = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != dailyTaskMutationSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
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
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isDailyTaskHistoryLoading) return
        _uiState.update { it.copy(isDailyTaskHistoryLoading = true) }
        val seq = dailyTaskHistorySeq.incrementAndGet()
        val cursorId = if (reset) null else _uiState.value.dailyTaskHistoryCursorId

        viewModelScope.launch {
            if (seq != dailyTaskHistorySeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                val result = campaignRepository.adminGetDailyTaskPolicyHistory(limit = 20, cursorId = cursorId)
                if (seq != dailyTaskHistorySeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
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
            } catch (e: CancellationException) {
                if (seq == dailyTaskHistorySeq.get() && reqUid == authRepository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isDailyTaskHistoryLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != dailyTaskHistorySeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
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
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isDailyTaskFutureLoading || !_uiState.value.dailyTaskFutureHasMore) return
        _uiState.update { it.copy(isDailyTaskFutureLoading = true) }
        val seq = dailyTaskFutureSeq.incrementAndGet()
        val cursorDate = _uiState.value.dailyTaskFutureCursorDate
        val cursorId = _uiState.value.dailyTaskFutureCursorId

        viewModelScope.launch {
            if (seq != dailyTaskFutureSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                val result = campaignRepository.adminListDailyTaskPolicies(
                    limit = 50,
                    cursorDate = cursorDate,
                    cursorId = cursorId,
                )
                if (seq != dailyTaskFutureSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
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
            } catch (e: CancellationException) {
                if (seq == dailyTaskFutureSeq.get() && reqUid == authRepository.currentUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isDailyTaskFutureLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != dailyTaskFutureSeq.get() || reqUid != authRepository.currentUserId() || reqGen != SessionCacheManager.currentGeneration()) {
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
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isConfigLoading) return
        _uiState.update { it.copy(isConfigLoading = true, error = null) }
        val loadSeq = appConfigLoadSeq.incrementAndGet()
        val saveSeqAtStart = appConfigSaveSeq.get()

        viewModelScope.launch {
            if (checkSessionChanged(reqUid, reqGen) || loadSeq != appConfigLoadSeq.get()) {
                return@launch
            }
            try {
                val config = appConfigRepository.getAppConfig(forceRefresh = true)
                if (checkSessionChanged(reqUid, reqGen) || loadSeq != appConfigLoadSeq.get()) {
                    return@launch
                }
                _uiState.update { state ->
                    val keepCurrentConfig = saveSeqAtStart != appConfigSaveSeq.get()
                    state.copy(
                        isConfigLoading = false,
                        appConfig = if (keepCurrentConfig) state.appConfig else (config ?: state.appConfig),
                        error = if (config == null && state.appConfig == null) localizer.get(R.string.admin_config_load_failed) else null,
                    )
                }
            } catch (e: CancellationException) {
                if (!checkSessionChanged(reqUid, reqGen) && loadSeq == appConfigLoadSeq.get()) {
                    _uiState.update { it.copy(isConfigLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (checkSessionChanged(reqUid, reqGen) || loadSeq != appConfigLoadSeq.get()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isConfigLoading = false,
                        error = localizer.get(R.string.admin_config_load_failed),
                    )
                }
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
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isActionLoading) return
        _uiState.update { it.copy(isActionLoading = true, error = null) }
        val saveSeq = appConfigSaveSeq.incrementAndGet()

        viewModelScope.launch {
            if (checkSessionChanged(reqUid, reqGen) || saveSeq != appConfigSaveSeq.get()) {
                return@launch
            }
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
                if (checkSessionChanged(reqUid, reqGen) || saveSeq != appConfigSaveSeq.get()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        appConfig = updated,
                        actionMessage = localizer.get(R.string.admin_config_saved),
                        configSaveSignal = it.configSaveSignal + 1,
                    )
                }
            } catch (e: CancellationException) {
                if (!checkSessionChanged(reqUid, reqGen) && saveSeq == appConfigSaveSeq.get()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (checkSessionChanged(reqUid, reqGen) || saveSeq != appConfigSaveSeq.get()) {
                    return@launch
                }
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
