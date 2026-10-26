package com.ntt.crosstest.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.ntt.crosstest.R
import com.ntt.crosstest.data.SettingsRepository

private const val TAG = "GoogleSignIn"

/** Walks the context chain to find the hosting Activity, if any. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

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
    val localizedContext = SettingsRepository.wrap(context)
    val activityContext = context.findActivity()
    if (activityContext == null) {
        Log.w(
            TAG,
            "No Activity context (${context.javaClass.name}); the Google selector UI " +
                "fails on the Play Services backend (Android 13 and below)"
        )
    }
    // Play Services launches its selector UI via startActivity on this context,
    // which requires a real Activity on Android 13 and below.
    val credentialContext = activityContext ?: context
    val cleanClientId = webClientId.trim()
    if (cleanClientId.isBlank()) {
        return GoogleSignInOutcome.Error(
            localizedContext.getString(R.string.google_signin_missing_client_id)
        )
    }

    val credentialManager = CredentialManager.create(credentialContext)

    // Flow 1: Explicit button click flow (GetSignInWithGoogleOption) - recommended by Google for button taps
    try {
        val signInOption = GetSignInWithGoogleOption.Builder(cleanClientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        val response = credentialManager.getCredential(credentialContext, request)
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

            val response = credentialManager.getCredential(credentialContext, request)
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
                localizedContext.getString(R.string.google_signin_no_account)
            )
        } catch (e: GetCredentialException) {
            val msg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "GetCredentialException: $msg (flow 1: ${eFirst.message})", e)
            GoogleSignInOutcome.Error(
                localizedContext.getString(R.string.google_signin_fetch_error_format, msg)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Google sign-in", e)
            GoogleSignInOutcome.Error(
                localizedContext.getString(
                    R.string.google_signin_unexpected_error_format,
                    e.message ?: e.javaClass.simpleName,
                )
            )
        }
    }
}
