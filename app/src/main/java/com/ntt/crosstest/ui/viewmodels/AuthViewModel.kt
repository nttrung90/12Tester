package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.ui.screens.GoogleSignInOutcome
import com.ntt.crosstest.ui.screens.requestGoogleSignIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AuthViewModel"

data class AuthUiState(
    val isLoading: Boolean = false,
    val isResetLoading: Boolean = false,
    val error: String? = null,
    val infoMessage: String? = null,
    val isSignUpSuccess: Boolean = false,
    val signedIn: Boolean = false,
)

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null, isSignUpSuccess = false) }
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
                is GoogleSignInOutcome.Cancelled -> {
                    Log.w(
                        TAG,
                        "Credential Manager reported cancellation. Configuration problems " +
                            "(SHA-1/package not registered in the project that owns the web " +
                            "client ID) also surface as cancellation - see GoogleSignIn logs."
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = localizer.get(R.string.auth_google_cancelled),
                        )
                    }
                }
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
                                error = localizer.get(R.string.auth_sign_in_failed_format, friendlyError(e, localizer))
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
            _uiState.update { it.copy(error = localizer.get(R.string.auth_email_required)) }
            return
        }
        if (pass.isBlank()) {
            _uiState.update { it.copy(error = localizer.get(R.string.auth_password_required)) }
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
                        error = localizer.get(R.string.auth_sign_in_failed_format, friendlyError(e, localizer))
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
            _uiState.update { it.copy(error = localizer.get(R.string.auth_email_required)) }
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _uiState.update { it.copy(error = localizer.get(R.string.auth_email_invalid)) }
            return
        }
        if (pass.length < 6) {
            _uiState.update { it.copy(error = localizer.get(R.string.auth_password_min_length)) }
            return
        }
        if (pass != confirmPass) {
            _uiState.update { it.copy(error = localizer.get(R.string.auth_password_mismatch)) }
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
                                isSignUpSuccess = true,
                                infoMessage = localizer.get(R.string.auth_sign_up_success_info)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = localizer.get(R.string.auth_sign_up_failed_format, friendlyError(e, localizer))
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
            _uiState.update { it.copy(error = localizer.get(R.string.auth_reset_email_invalid)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isResetLoading = true, error = null) }
            try {
                authRepository.resetPasswordForEmail(trimmedEmail)
                _uiState.update {
                    it.copy(
                        isResetLoading = false,
                        infoMessage = localizer.get(R.string.auth_reset_email_sent)
                    )
                }
                onSuccess()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isResetLoading = false,
                        error = localizer.get(R.string.auth_reset_send_failed_format, friendlyError(e, localizer))
                    )
                }
            }
        }
    }
}

/** Short, user-friendly error text, localized via [localizer]. */
fun friendlyError(e: Exception, localizer: Localizer): String {
    val msg = e.message.orEmpty()
    val lower = msg.lowercase()
    return when {
        "account_locked" in lower ->
            localizer.get(R.string.error_account_locked)
        "same_screenshot_rejected" in lower ->
            localizer.get(R.string.error_same_screenshot)
        "report_already_reviewed" in lower ->
            localizer.get(R.string.error_report_already_reviewed)
        "report_expired" in lower ->
            localizer.get(R.string.error_report_expired)
        "report_not_found" in lower ->
            localizer.get(R.string.error_report_not_found)
        "permission_denied" in lower || "permission denied" in lower ->
            localizer.get(R.string.error_permission_denied)
        "campaign_not_open" in lower ->
            localizer.get(R.string.error_campaign_not_open)
        "email not confirmed" in lower || "email_not_confirmed" in lower || "not confirmed" in lower || "verified" in lower && "email" in lower ->
            localizer.get(R.string.error_email_not_confirmed)
        "unlock_or_renew_slot" in lower || ("pgrst202" in lower && "slot" in lower) ->
            localizer.get(R.string.error_multi_slot_not_configured)
        "pgrst202" in lower || "schema cache" in lower ->
            localizer.get(R.string.error_rpc_not_available)
        "already registered" in lower || "user_already_exists" in lower ->
            localizer.get(R.string.error_email_already_registered)
        "invalid login credentials" in lower || "invalid_grant" in lower || "wrong password" in lower ->
            localizer.get(R.string.error_invalid_credentials)
        "password should be at least" in lower ->
            localizer.get(R.string.error_password_too_short)
        "rate limit" in lower || "over_email_send_rate_limit" in lower ->
            localizer.get(R.string.error_rate_limited)
        "not_authenticated" in lower ->
            localizer.get(R.string.campaign_login_required)
        "network" in lower || "unable to resolve host" in lower || "timeout" in lower ->
            localizer.get(R.string.error_network)
        else -> msg.ifBlank { localizer.get(R.string.error_generic) }.take(200)
    }
}
