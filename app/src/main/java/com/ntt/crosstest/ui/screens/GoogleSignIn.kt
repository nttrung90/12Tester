package com.ntt.crosstest.ui.screens

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

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
 * [context] must be an Activity context so the picker UI can be displayed.
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
        return GoogleSignInOutcome.Cancelled
    } catch (eFirst: Exception) {
        // Flow 2: Fallback to GetGoogleIdOption (One Tap flow)
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
            GoogleSignInOutcome.Cancelled
        } catch (e: GetCredentialException) {
            val msg = e.message ?: e.javaClass.simpleName
            GoogleSignInOutcome.Error(
                "Không lấy được tài khoản Google: $msg. " +
                    "Kiểm tra: Package name (com.ntt.crosstest), SHA-1 trong Google Cloud Console."
            )
        } catch (e: Exception) {
            GoogleSignInOutcome.Error("Lỗi không mong đợi: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
