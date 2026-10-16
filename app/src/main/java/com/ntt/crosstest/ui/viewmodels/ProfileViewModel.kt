package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.CreditLog
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SlotInfo
import com.ntt.crosstest.ui.screens.GoogleSignInOutcome
import com.ntt.crosstest.ui.screens.requestGoogleSignIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val isLoading: Boolean = true,
    val isBackgroundSyncing: Boolean = false,
    val isOffline: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isActionLoading: Boolean = false,
    val isChangingPassword: Boolean = false,
    val changePasswordSuccessMsg: String? = null,
    val profile: Profile? = null,
    val email: String? = null,
    val myCampaigns: List<Campaign> = emptyList(),
    val joinedCampaigns: List<Campaign> = emptyList(),
    val reminderEnabled: Boolean = true,
    val appLanguage: String = com.ntt.crosstest.data.SettingsRepository.LANGUAGE_SYSTEM,
    val signedOut: Boolean = false,
    val error: String? = null,
    val creditLogs: List<CreditLog> = emptyList(),
    val slots: List<SlotInfo> = emptyList(),
    val isUnlockingSlot: Boolean = false,
    val unlockSuccessMsg: String? = null,
)

class ProfileViewModel(
    private val authRepository: AuthRepository,
    private val campaignRepository: CampaignRepository,
    private val app: TestCheoApp,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        run {
            val cached = SessionCacheManager.loadSync(app)
            // Only hydrate from disk when the snapshot belongs to the user who
            // actually holds the current session (prevents cross-account leaks).
            if (cached != null && cached.userProfile != null && cached.userId == authRepository.currentUserId()) {
                ProfileUiState(
                    isLoading = false,
                    isBackgroundSyncing = true,
                    isAuthenticated = true,
                    profile = cached.userProfile,
                    email = cached.userProfile.email,
                    myCampaigns = cached.myCampaigns,
                    joinedCampaigns = cached.joinedCampaigns,
                )
            } else {
                ProfileUiState()
            }
        }
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        refresh(forceRefresh = false)
        viewModelScope.launch {
            app.settingsRepository.reminderEnabled.collect { enabled ->
                _uiState.update { it.copy(reminderEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            app.settingsRepository.appLanguage.collect { lang ->
                _uiState.update { it.copy(appLanguage = lang) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun refresh(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _uiState.value.profile == null) {
                _uiState.update { it.copy(isLoading = true, isBackgroundSyncing = true, error = null) }
            } else {
                _uiState.update { it.copy(isBackgroundSyncing = true, error = null) }
            }
            try {
                val isAuthed = authRepository.currentUserId() != null
                if (!isAuthed) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBackgroundSyncing = false,
                            isAuthenticated = false,
                            profile = null,
                            email = null,
                            myCampaigns = emptyList(),
                            joinedCampaigns = emptyList(),
                            creditLogs = emptyList(),
                            error = null,
                        )
                    }
                    return@launch
                }
                val profile = campaignRepository.getMyProfile(forceRefresh = forceRefresh)
                if (profile?.isLocked == true) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBackgroundSyncing = false,
                            isAuthenticated = true,
                            profile = profile,
                            error = "Tài khoản của bạn đang bị tạm khóa."
                        )
                    }
                    return@launch
                }
                val mine = campaignRepository.myCampaigns(forceRefresh = forceRefresh)
                val joined = campaignRepository.myJoinedCampaigns(forceRefresh = forceRefresh)
                val logs = campaignRepository.getMyCreditLogs()
                val slots = campaignRepository.getMySlotsInfo(forceRefresh = forceRefresh)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        isAuthenticated = true,
                        profile = profile,
                        email = authRepository.currentUserEmail(),
                        myCampaigns = mine,
                        joinedCampaigns = joined,
                        creditLogs = logs,
                        slots = slots,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        error = if (_uiState.value.profile == null) friendlyError(e) else null
                    )
                }
            }
        }
    }

    fun unlockOrRenewSlot(slotNumber: Int) {
        if (_uiState.value.isUnlockingSlot) return
        val slotCost = SlotInfo.costForSlot(slotNumber)
        val credits = _uiState.value.profile?.credits ?: 0
        if (credits < slotCost) {
            _uiState.update {
                it.copy(error = "Bạn cần $slotCost Credit để mở khóa/gia hạn Slot $slotNumber (hiện có $credits Credit).")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUnlockingSlot = true, error = null) }
            try {
                campaignRepository.unlockOrRenewSlot(slotNumber)
                _uiState.update {
                    it.copy(
                        unlockSuccessMsg = "Thao tác Slot $slotNumber thành công (+16 ngày)!",
                    )
                }
                refresh()
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("INSUFFICIENT_CREDITS") == true -> "Bạn không đủ Credit."
                    e.message?.contains("PREVIOUS_SLOT_NOT_ACTIVE") == true -> "Bạn cần mở khóa hoặc gia hạn Slot ${slotNumber - 1} trước."
                    else -> friendlyError(e)
                }
                _uiState.update { it.copy(error = msg) }
            } finally {
                _uiState.update { it.copy(isUnlockingSlot = false) }
            }
        }
    }

    fun clearUnlockSuccess() {
        _uiState.update { it.copy(unlockSuccessMsg = null) }
    }

    fun signIn(context: Context) {
        if (_uiState.value.isActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            when (val outcome = requestGoogleSignIn(context, BuildConfig.GOOGLE_WEB_CLIENT_ID)) {
                is GoogleSignInOutcome.Cancelled -> {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                is GoogleSignInOutcome.Error -> {
                    _uiState.update { it.copy(isActionLoading = false, error = outcome.message) }
                }
                is GoogleSignInOutcome.Success -> {
                    try {
                        authRepository.signInWithGoogle(
                            idToken = outcome.result.idToken,
                            displayName = outcome.result.displayName,
                            avatarUrl = outcome.result.avatarUrl,
                        )
                        _uiState.update { it.copy(isActionLoading = false) }
                        refresh()
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                isActionLoading = false,
                                error = "Đăng nhập thất bại: ${friendlyError(e)}"
                            )
                        }
                    }
                }
            }
        }
    }

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            app.settingsRepository.setReminderEnabled(enabled)
            if (enabled) app.scheduleDailyReminder() else app.cancelDailyReminder()
        }
    }

    fun setAppLanguage(languageCode: String) {
        viewModelScope.launch {
            app.settingsRepository.setAppLanguage(languageCode)
        }
    }

    fun changePassword(newPass: String, confirmPass: String, onSuccess: () -> Unit) {
        if (newPass.length < 6) {
            _uiState.update { it.copy(error = "Mật khẩu phải chứa ít nhất 6 ký tự.") }
            return
        }
        if (newPass != confirmPass) {
            _uiState.update { it.copy(error = "Mật khẩu xác nhận không khớp.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isChangingPassword = true, error = null) }
            try {
                authRepository.changePassword(newPass)
                _uiState.update {
                    it.copy(
                        isChangingPassword = false,
                        changePasswordSuccessMsg = "Đổi mật khẩu thành công!"
                    )
                }
                onSuccess()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isChangingPassword = false,
                        error = "Đổi mật khẩu thất bại: ${friendlyError(e)}"
                    )
                }
            }
        }
    }

    fun clearChangePasswordSuccess() {
        _uiState.update { it.copy(changePasswordSuccessMsg = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            // Clears remote session (best effort), RAM caches and the disk snapshot.
            app.performSignOut()
            _uiState.update {
                it.copy(
                    signedOut = true,
                    isAuthenticated = false,
                    profile = null,
                    email = null,
                    myCampaigns = emptyList(),
                    joinedCampaigns = emptyList(),
                )
            }
        }
    }
}
