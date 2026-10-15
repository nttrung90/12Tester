package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Profile
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
)

class AdminUsersViewModel(
    private val campaignRepository: CampaignRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUsersUiState())
    val uiState: StateFlow<AdminUsersUiState> = _uiState.asStateFlow()

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
                        error = if (_uiState.value.allUsers.isEmpty()) friendlyError(e) else null
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
                        actionMessage = if (locked) "Đã khóa tài khoản thành công." else "Đã mở khóa tài khoản thành công."
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = "Thao tác thất bại: ${friendlyError(e)}"
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
                        actionMessage = "Đã điều chỉnh Credit thành công! Số dư mới: ${res.newCredits} Credit."
                    )
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = "Điều chỉnh Credit thất bại: ${friendlyError(e)}"
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
                    "Đã gửi thông báo thành công tới toàn bộ ${res.sentCount} người dùng!"
                } else {
                    "Đã gửi thông báo thành công tới người dùng!"
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
                        error = "Gửi thông báo thất bại: ${friendlyError(e)}"
                    )
                }
            }
        }
    }
}
