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
            _uiState.update { it.copy(currentAdminId = curUid, homeStatsConfig = null, isHomeStatsLoading = false, isHomeStatsSaving = false) }
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
