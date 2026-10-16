package com.ntt.crosstest.ui.screens

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

private const val TAG = "GoogleSignIn"

/**
 * Result of the Credential Manager Google flow. Display name / avatar come
 * straight from the Google credential so we don't depend on Supabase
 * user-metadata field names.
 */
data class GoogleSignInResult(
    val idToken: String,
    val displayName: String?,
    val avatarUrl: String?,
)

sealed interface GoogleSignInOutcome {
    data class Success(val result: GoogleSignInResult) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data class Error(val message: String) : GoogleSignInOutcome
}

/**
 * Shows the Google account picker via Credential Manager and returns the
 * Google ID token for Supabase Auth. [webClientId] is the **Web** OAuth client
 * ID from Google Cloud Console (the one also configured in Supabase Auth).
 *
 * [context] must be an Activity context (pass `LocalActivity.current`) so the
 * picker UI can be displayed and the result is routed back to that Activity.
 *
 * Note: Credential Manager throws [GetCredentialCancellationException] both
 * when the user backs out AND when Google rejects the request (for example a
 * SHA-1 that is not registered for the app package in the Google Cloud project
 * that owns [webClientId]). Such configuration errors are logged below.
 */

suspend fun requestGoogleSignIn(context: Context, webClientId: String): GoogleSignInOutcome {
    val cleanClientId = webClientId.trim()
    if (cleanClientId.isBlank()) {
        return GoogleSignInOutcome.Error(
            "Thiếu GOOGLE_WEB_CLIENT_ID. Hãy điền vào local.properties rồi build lại (xem README)."
        )
    }

    val credentialManager = CredentialManager.create(context)

    // Flow 1: Explicit button click flow (GetSignInWithGoogleOption) - recommended by Google for button taps
    try {
        val signInOption = GetSignInWithGoogleOption.Builder(cleanClientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        val response = credentialManager.getCredential(context, request)
        val googleCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
        return GoogleSignInOutcome.Success(
            GoogleSignInResult(
                idToken = googleCredential.idToken,
                displayName = googleCredential.displayName,
                avatarUrl = googleCredential.profilePictureUri?.toString(),
            )
        )
    } catch (e: GetCredentialCancellationException) {
        Log.w(TAG, "Flow 1 cancelled/rejected by Credential Manager", e)
        return GoogleSignInOutcome.Cancelled
    } catch (eFirst: Exception) {
        // Flow 2: Fallback to GetGoogleIdOption (One Tap flow)
        Log.w(TAG, "Flow 1 failed (${eFirst.javaClass.simpleName}), trying flow 2", eFirst)
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(cleanClientId)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response = credentialManager.getCredential(context, request)
            val googleCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
            GoogleSignInOutcome.Success(
                GoogleSignInResult(
                    idToken = googleCredential.idToken,
                    displayName = googleCredential.displayName,
                    avatarUrl = googleCredential.profilePictureUri?.toString(),
                )
            )
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Flow 2 cancelled/rejected by Credential Manager", e)
            GoogleSignInOutcome.Cancelled
        } catch (e: NoCredentialException) {
            Log.e(TAG, "No Google credential available on this device", e)
            GoogleSignInOutcome.Error(
                "Không tìm thấy tài khoản Google phù hợp trên thiết bị. " +
                    "Hãy chắc chắn máy đã đăng nhập ít nhất một tài khoản Google, " +
                    "và SHA-1/package name đã đăng ký đúng trong Google Cloud Console."
            )
        } catch (e: GetCredentialException) {
            val msg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "GetCredentialException: $msg", e)
            GoogleSignInOutcome.Error(
                "Không lấy được tài khoản Google: $msg. " +
                    "Kiểm tra: Package name (com.ntt.crosstest), SHA-1 trong Google Cloud Console."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Google sign-in", e)
            GoogleSignInOutcome.Error("Lỗi không mong đợi: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
