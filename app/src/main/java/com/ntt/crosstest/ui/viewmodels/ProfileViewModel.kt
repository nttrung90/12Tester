package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.CreditLog
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Profile
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SlotInfo
import com.ntt.crosstest.ui.screens.GoogleSignInOutcome
import com.ntt.crosstest.ui.screens.requestGoogleSignIn
import io.github.jan.supabase.auth.status.SessionStatus
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
    val isSendingReport: Boolean = false,
    val reportSuccessMsg: String? = null,
    val profile: Profile? = null,
    val email: String? = null,
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
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        run {
            val cached = SessionCacheManager.loadSync(app)
            // Only hydrate from disk when the snapshot belongs to the user who
            // actually holds the current session (prevents cross-account leaks).
            // While the session is still loading (currentUserId == null) the cache
            // is kept to preserve the offline-first instant paint.
            val currentUid = authRepository.currentUserId()
            val cacheMatchesSession = cached != null && cached.userProfile != null &&
                (cached.userId == null || currentUid == null || cached.userId == currentUid)
            if (cached != null && cacheMatchesSession) {
                ProfileUiState(
                    isLoading = false,
                    isBackgroundSyncing = true,
                    isAuthenticated = true,
                    profile = cached.userProfile,
                    email = cached.userProfile.email,
                )
            } else {
                ProfileUiState()
            }
        }
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /** uid đã đồng bộ xong — tránh refresh lặp mỗi lần token làm mới. */
    private var lastSyncedUid: String? = null

    /**
     * refresh() đã bỏ dở vì phiên còn đang khôi phục. Khi phiên sẵn sàng trở lại
     * (kể cả cùng uid — ẩn app rồi mở lại) sẽ refresh đúng một lần.
     */
    private var refreshWhenSessionReady = false

    init {
        refresh(forceRefresh = false)
        observeSession()
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

    /** Session khôi phục bất đồng bộ: refresh một lần khi phiên sẵn sàng. */
    private fun observeSession() {
        viewModelScope.launch {
            authRepository.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val uid = authRepository.currentUserId()
                        if (uid != null && uid != lastSyncedUid) {
                            lastSyncedUid = uid
                            refreshWhenSessionReady = false
                            refresh(forceRefresh = false)
                        } else if (uid != null && refreshWhenSessionReady) {
                            // Phiên khôi phục xong (ẩn app rồi mở lại, mạng có lại):
                            // refresh đúng một lần dù uid không đổi.
                            refreshWhenSessionReady = false
                            refresh(forceRefresh = false)
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        refreshWhenSessionReady = false
                        if (lastSyncedUid != null) {
                            lastSyncedUid = null
                            SessionCacheManager.clear(app)
                            refresh(forceRefresh = false)
                        }
                    }
                    else -> {}
                }
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
                if (!isAuthed && SessionCacheManager.hasCachedSession(app)) {
                    // Phiên đang được khôi phục từ đĩa — giữ nguyên dữ liệu đang hiển thị
                    // thay vì xoá sạch (tránh màn hình trắng ở cold start).
                    refreshWhenSessionReady = true
                    _uiState.update { it.copy(isLoading = false, isBackgroundSyncing = false) }
                    return@launch
                }
                if (!isAuthed) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBackgroundSyncing = false,
                            isAuthenticated = false,
                            profile = null,
                            email = null,
                            creditLogs = emptyList(),
                            slots = emptyList(),
                            error = null,
                        )
                    }
                    return@launch
                }
                lastSyncedUid = authRepository.currentUserId()
                // Fast path: 1 RPC gộp (get_my_dashboard) phủ profile + logs + slots.
                val dashboard = campaignRepository.getMyDashboard(forceRefresh = forceRefresh)
                val profile = dashboard?.profile ?: campaignRepository.getMyProfile(forceRefresh = forceRefresh)
                if (profile?.isLocked == true) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isBackgroundSyncing = false,
                            isAuthenticated = true,
                            profile = profile,
                            error = localizer.get(R.string.profile_account_locked)
                        )
                    }
                    return@launch
                }
                val logs = dashboard?.creditLogs ?: campaignRepository.getMyCreditLogs()
                val slots = campaignRepository.getMySlotsInfo(forceRefresh = forceRefresh)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBackgroundSyncing = false,
                        isAuthenticated = true,
                        profile = profile,
                        email = authRepository.currentUserEmail(),
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
                        error = if (_uiState.value.profile == null) friendlyError(e, localizer) else null
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
                it.copy(error = localizer.get(R.string.profile_slot_cost_format, slotCost, slotNumber, credits))
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUnlockingSlot = true, error = null) }
            try {
                campaignRepository.unlockOrRenewSlot(slotNumber)
                _uiState.update {
                    it.copy(
                        unlockSuccessMsg = localizer.get(R.string.profile_slot_success_format, slotNumber),
                    )
                }
                refresh()
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("INSUFFICIENT_CREDITS") == true -> localizer.get(R.string.profile_insufficient_credits)
                    e.message?.contains("PREVIOUS_SLOT_NOT_ACTIVE") == true -> localizer.get(R.string.profile_prev_slot_required_format, slotNumber - 1)
                    else -> friendlyError(e, localizer)
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
                                error = localizer.get(R.string.auth_sign_in_failed_format, friendlyError(e, localizer))
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
            _uiState.update { it.copy(error = localizer.get(R.string.auth_password_min_length)) }
            return
        }
        if (newPass != confirmPass) {
            _uiState.update { it.copy(error = localizer.get(R.string.auth_password_mismatch)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isChangingPassword = true, error = null) }
            try {
                authRepository.changePassword(newPass)
                _uiState.update {
                    it.copy(
                        isChangingPassword = false,
                        changePasswordSuccessMsg = localizer.get(R.string.profile_password_changed)
                    )
                }
                onSuccess()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isChangingPassword = false,
                        error = localizer.get(R.string.profile_password_change_failed_format, friendlyError(e, localizer))
                    )
                }
            }
        }
    }

    fun clearChangePasswordSuccess() {
        _uiState.update { it.copy(changePasswordSuccessMsg = null) }
    }

    /**
     * Gửi phản hồi liên hệ/báo lỗi cho Admin. Tự đính kèm phiên bản app và
     * thông tin thiết bị để Admin dễ tái hiện lỗi.
     */
    fun submitContactReport(category: String, message: String, onSuccess: () -> Unit) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(error = localizer.get(R.string.contact_message_required)) }
            return
        }
        if (_uiState.value.isSendingReport) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSendingReport = true, error = null) }
            try {
                campaignRepository.submitContactReport(
                    category = category,
                    message = trimmed,
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    platform = "Android ${android.os.Build.VERSION.RELEASE}",
                    deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
                )
                _uiState.update {
                    it.copy(
                        isSendingReport = false,
                        reportSuccessMsg = localizer.get(R.string.contact_send_success),
                    )
                }
                onSuccess()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSendingReport = false,
                        error = localizer.get(R.string.contact_send_failed_format, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }

    fun clearReportSuccess() {
        _uiState.update { it.copy(reportSuccessMsg = null) }
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
                )
            }
        }
    }
}
