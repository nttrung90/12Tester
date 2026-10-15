package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.ui.screens.GoogleSignInOutcome
import com.ntt.crosstest.ui.screens.requestGoogleSignIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val isResetLoading: Boolean = false,
    val error: String? = null,
    val infoMessage: String? = null,
    val signedIn: Boolean = false,
)

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    /**
     * Starts the Credential Manager Google flow. [context] must be an Activity
     * context (the composable's LocalContext) so the account picker UI can show.
     */
    fun signIn(context: Context) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, infoMessage = null) }
            when (val outcome = requestGoogleSignIn(context, BuildConfig.GOOGLE_WEB_CLIENT_ID)) {
                is GoogleSignInOutcome.Cancelled ->
                    _uiState.update { it.copy(isLoading = false, error = "Bạn đã hủy đăng nhập.") }
                is GoogleSignInOutcome.Error ->
                    _uiState.update { it.copy(isLoading = false, error = outcome.message) }
                is GoogleSignInOutcome.Success -> {
                    try {
                        authRepository.signInWithGoogle(
                            idToken = outcome.result.idToken,
                            displayName = outcome.result.displayName,
                            avatarUrl = outcome.result.avatarUrl,
                        )
                        _uiState.update { it.copy(isLoading = false, signedIn = true) }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Đăng nhập thất bại: ${friendlyError(e)}"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Signs in using email and password via Supabase Auth.
     */
    fun signInWithEmail(email: String, pass: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank()) {
            _uiState.update { it.copy(error = "Vui lòng nhập email.") }
            return
        }
        if (pass.isBlank()) {
            _uiState.update { it.copy(error = "Vui lòng nhập mật khẩu.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, infoMessage = null) }
            try {
                authRepository.signInWithEmail(trimmedEmail, pass)
                _uiState.update { it.copy(isLoading = false, signedIn = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Đăng nhập thất bại: ${friendlyError(e)}"
                    )
                }
            }
        }
    }

    /**
     * Registers a new account with email and password via Supabase Auth.
     * Default role 'member' is auto-assigned in Supabase trigger.
     */
    fun signUpWithEmail(email: String, pass: String, confirmPass: String, displayName: String?) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank()) {
            _uiState.update { it.copy(error = "Vui lòng nhập email.") }
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _uiState.update { it.copy(error = "Địa chỉ email không đúng định dạng.") }
            return
        }
        if (pass.length < 6) {
            _uiState.update { it.copy(error = "Mật khẩu phải chứa ít nhất 6 ký tự.") }
            return
        }
        if (pass != confirmPass) {
            _uiState.update { it.copy(error = "Mật khẩu xác nhận không khớp.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, infoMessage = null) }
            try {
                authRepository.signUpWithEmail(trimmedEmail, pass, displayName?.trim())
                // When email confirmation is OFF in Supabase, user gets immediate session
                if (authRepository.currentUserId() != null) {
                    _uiState.update { it.copy(isLoading = false, signedIn = true) }
                } else {
                    // Try auto-signing in immediately
                    try {
                        authRepository.signInWithEmail(trimmedEmail, pass)
                        _uiState.update { it.copy(isLoading = false, signedIn = true) }
                    } catch (_: Exception) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                infoMessage = "Đăng ký thành công! Vui lòng chuyển sang tab Đăng nhập."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Đăng ký thất bại: ${friendlyError(e)}"
                    )
                }
            }
        }
    }

    /**
     * Sends password reset email.
     */
    fun sendPasswordResetEmail(email: String, onSuccess: () -> Unit) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _uiState.update { it.copy(error = "Vui lòng nhập email hợp lệ để đặt lại mật khẩu.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isResetLoading = true, error = null) }
            try {
                authRepository.resetPasswordForEmail(trimmedEmail)
                _uiState.update {
                    it.copy(
                        isResetLoading = false,
                        infoMessage = "Đã gửi email đặt lại mật khẩu! Vui lòng kiểm tra hộp thư của bạn."
                    )
                }
                onSuccess()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isResetLoading = false,
                        error = "Gửi yêu cầu thất bại: ${friendlyError(e)}"
                    )
                }
            }
        }
    }
}

/** Short, user-friendly error text (Vietnamese). */
fun friendlyError(e: Exception): String {
    val msg = e.message.orEmpty()
    val lower = msg.lowercase()
    return when {
        "account_locked" in msg ->
            "Tài khoản của bạn đã bị khóa bởi Quản trị viên. Vui lòng liên hệ Admin để được hỗ trợ."
        "email not confirmed" in lower || "email_not_confirmed" in lower || "not confirmed" in lower || "verified" in lower && "email" in lower ->
            "Tài khoản chưa được kích hoạt email. Vui lòng chạy lệnh kích hoạt trong Supabase SQL Editor."
        "unlock_or_renew_slot" in lower || ("pgrst202" in lower && "slot" in lower) ->
            "Hệ thống Multi-Slot chưa được cấu hình trên Supabase. Vui lòng chạy file migration 005 trong Supabase SQL Editor."
        "pgrst202" in lower || "schema cache" in lower ->
            "Hàm xử lý chưa được cài đặt trên Supabase hoặc đang làm mới cache. Vui lòng kiểm tra lại SQL migration."
        "already registered" in lower || "user_already_exists" in lower ->
            "Email này đã được đăng ký tài khoản. Vui lòng chuyển sang tab Đăng nhập."
        "invalid login credentials" in lower || "invalid_grant" in lower || "wrong password" in lower ->
            "Email hoặc mật khẩu không chính xác."
        "password should be at least" in lower ->
            "Mật khẩu phải có ít nhất 6 ký tự."
        "rate limit" in lower || "over_email_send_rate_limit" in lower ->
            "Bạn đã gửi yêu cầu quá nhanh. Vui lòng đợi trong giây lát."
        "network" in lower || "unable to resolve host" in lower || "timeout" in lower ->
            "Lỗi mạng. Kiểm tra kết nối Internet rồi thử lại."
        else -> msg.ifBlank { "Đã có lỗi xảy ra." }.take(200)
    }
}
