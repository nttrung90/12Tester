package com.ntt.crosstest.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.ReportSubmissionFailure
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
fun friendlyError(rawMessage: String?, localizer: Localizer): String {
    if (rawMessage.isNullOrBlank()) return localizer.get(R.string.error_generic)
    return friendlyError(Exception(rawMessage), localizer)
}

fun sanitizeErrorMessage(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw
        .replace(Regex("(?i)bearer\\s+[a-zA-Z0-9._~+/-]+"), "Bearer [REDACTED]")
        .replace(Regex("(?i)authorization[:=]\\s*[^,\\s\r\n]+"), "Authorization: [REDACTED]")
        .replace(Regex("(?i)apikey[:=]\\s*[^,\\s\r\n]+"), "apikey: [REDACTED]")
        .replace(Regex("(?i)password[:=]\\s*[^,\\s\r\n]+"), "password: [REDACTED]")
        .replace(Regex("(?i)https?://[^\\s]+"), "[URL]")
}

fun friendlyError(e: Throwable, localizer: Localizer): String {
    val msg = e.message.orEmpty()
    val lower = msg.lowercase()
    return when {
        "mission_paused" in lower -> localizer.get(R.string.error_mission_paused)
        "mission_stopped_no_credits" in lower -> localizer.get(R.string.error_mission_no_credits)
        "mission_deleted" in lower -> localizer.get(R.string.error_mission_deleted)
        "report_context_conflict" in lower || "invalid_report_context" in lower -> localizer.get(R.string.error_report_context_conflict)
        "report_request_requires_review" in lower -> localizer.get(R.string.error_report_requires_review)
        "report_client_update_required" in lower -> localizer.get(R.string.error_rpc_not_available)
        (e as? ReportSubmissionFailure)?.code == ReportSubmissionFailure.SERVER_CONFIG ||
            ReportSubmissionFailure.isCryptoConfigurationError(msg) ->
            localizer.get(R.string.error_report_server_configuration)
        e is ReportSubmissionFailure && e.code.startsWith("report_storage_") ->
            localizer.get(R.string.error_report_storage)
        (e as? ReportSubmissionFailure)?.code == "report_rpc_PGRST202" ||
            (e as? ReportSubmissionFailure)?.originalCode.equals("PGRST202", ignoreCase = true) ||
            "server_update_required" in lower -> localizer.get(R.string.error_rpc_not_available)
        "daily_task_policy_conflict" in lower ->
            localizer.get(R.string.admin_daily_task_err_conflict)
        "daily_task_policy_future_date_required" in lower ->
            localizer.get(R.string.admin_daily_task_err_future_date)
        "daily_task_policy_invalid_ratio" in lower ->
            localizer.get(R.string.admin_daily_task_err_invalid_ratio)
        "daily_task_policy_note_required" in lower ->
            localizer.get(R.string.admin_daily_task_err_note_required)
        "daily_task_policy_not_found" in lower ->
            localizer.get(R.string.admin_daily_task_err_not_found)
        "daily_task_policy_immutable" in lower ->
            localizer.get(R.string.admin_daily_task_err_immutable)
        "daily_task_policy_revision_required" in lower ->
            localizer.get(R.string.admin_daily_task_err_revision_required)
        "account_locked" in lower || "account is locked" in lower ->
            localizer.get(R.string.error_account_locked)
        "insufficient_credits" in lower || "not enough credit" in lower ->
            localizer.get(R.string.locked_insufficient_credits)
        "same_screenshot_rejected" in lower || "duplicate_screenshot" in lower || "image_hash_duplicate" in lower ->
            localizer.get(R.string.error_same_screenshot)
        "report_already_reviewed" in lower ->
            localizer.get(R.string.error_report_already_reviewed)
        "cannot_convert_existing_pending_report" in lower ->
            localizer.get(R.string.campaign_cannot_convert_pending_report)
        "report_already_approved_as_app_test" in lower ->
            localizer.get(R.string.campaign_mission_conflict_with_approved_app_test)
        "report_already_approved_for_another_mission" in lower || "cannot_change_mission_identity" in lower ->
            localizer.get(R.string.campaign_mission_conflict_with_approved_app_test)
        "client_submit_id_payload_mismatch" in lower || "client_submit_id_mismatch" in lower ->
            localizer.get(R.string.campaign_client_submit_id_payload_mismatch)
        "already_in_flight" in lower ->
            localizer.get(R.string.campaign_report_already_in_flight)
        "mission_not_joined" in lower ->
            localizer.get(R.string.mission_joined_cta)
        "report_expired" in lower ->
            localizer.get(R.string.error_report_expired)
        "report_not_found" in lower ->
            localizer.get(R.string.error_report_not_found)
        "report_resubmit_new_photo_required" in lower ->
            localizer.get(R.string.campaign_resubmit_new_photo_required)
        "permission_denied" in lower || "permission denied" in lower || "unauthorized" in lower ->
            localizer.get(R.string.error_permission_denied)
        "mission_not_found" in lower ->
            localizer.get(R.string.mission_err_not_found)
        "mission_expired" in lower ->
            localizer.get(R.string.mission_status_expired)
        "mission_not_paused" in lower ->
            localizer.get(R.string.mission_manage_not_paused)
        "mission_not_open" in lower ->
            localizer.get(R.string.mission_status_stopped)
        "mission_full" in lower ->
            localizer.get(R.string.mission_status_full)
        "campaign_not_found" in lower ->
            localizer.get(R.string.campaign_not_found)
        "campaign_not_open" in lower ->
            localizer.get(R.string.error_campaign_not_open)
        "campaign_owner_cant_join" in lower ->
            localizer.get(R.string.campaign_owner_cant_join)
        "campaign_owner_cant_report" in lower ->
            localizer.get(R.string.campaign_owner_cant_report)
        "email not confirmed" in lower || "email_not_confirmed" in lower || "not confirmed" in lower || ("verified" in lower && "email" in lower) ->
            localizer.get(R.string.error_email_not_confirmed)
        "unlock_or_renew_slot" in lower || ("pgrst202" in lower && "slot" in lower) || "multi_slot_not_configured" in lower ->
            localizer.get(R.string.error_multi_slot_not_configured)
        "pgrst202" in lower || "schema cache" in lower || "report_idempotency_unavailable" in lower ->
            localizer.get(R.string.error_rpc_not_available)
        "already registered" in lower || "user_already_exists" in lower ->
            localizer.get(R.string.error_email_already_registered)
        "invalid login credentials" in lower || "invalid_grant" in lower || "wrong password" in lower ->
            localizer.get(R.string.error_invalid_credentials)
        "password should be at least" in lower ->
            localizer.get(R.string.error_password_too_short)
        "rate limit" in lower || "over_email_send_rate_limit" in lower ->
            localizer.get(R.string.error_rate_limited)
        "not_authenticated" in lower || "report_account_mismatch" in lower || "report_session_not_ready" in lower ->
            localizer.get(R.string.campaign_login_required)
        "network" in lower || "unable to resolve host" in lower || "timeout" in lower || "connectexception" in lower || "sockettimeout" in lower || "unknownhost" in lower ->
            localizer.get(R.string.error_network)
        else -> {
            val safeMsg = sanitizeErrorMessage(msg)
            if (e is ReportSubmissionFailure) {
                Log.w("TestCheoError", "Report failed req=${e.requestId} stage=${e.stage} code=${e.code}")
            } else {
                Log.w("TestCheoError", "Unhandled error mapped to generic fallback: $safeMsg")
            }
            localizer.get(R.string.error_generic)
        }
    }
}
