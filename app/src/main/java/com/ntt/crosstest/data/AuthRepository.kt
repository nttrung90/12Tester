package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Authentication repository supporting Google sign-in and Email/Password sign-in/up
 * through Supabase Auth, along with role and account lock verification.
 */
class AuthRepository(private val supabase: SupabaseClient) {

    val sessionStatus: StateFlow<SessionStatus> = supabase.auth.sessionStatus

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    fun currentUserEmail(): String? = supabase.auth.currentUserOrNull()?.email

    /**
     * Checks if a user's account is locked by an administrator.
     */
    suspend fun checkAccountLocked(userId: String): Boolean {
        return try {
            val profile = supabase.from("profiles").select {
                filter { eq("id", userId) }
                limit(1)
            }.decodeList<Profile>().firstOrNull()
            profile?.isLocked == true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun isCurrentUserLocked(): Boolean {
        val uid = currentUserId() ?: return false
        return checkAccountLocked(uid)
    }

    /**
     * Signs in with the Google ID token, verifies lock status, and ensures a
     * row exists in `profiles`.
     */
    suspend fun signInWithGoogle(idToken: String, displayName: String?, avatarUrl: String?) {
        supabase.auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
        }
        val uid = currentUserId()
        if (uid != null && checkAccountLocked(uid)) {
            signOutQuietly()
            throw IllegalStateException("ACCOUNT_LOCKED")
        }
        upsertProfile(displayName, avatarUrl)
    }

    /**
     * Signs in with Email and Password, verifies lock status, and ensures profile exists.
     */
    suspend fun signInWithEmail(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        val uid = currentUserId()
        if (uid != null && checkAccountLocked(uid)) {
            signOutQuietly()
            throw IllegalStateException("ACCOUNT_LOCKED")
        }
        upsertProfile(null, null)
    }

    /**
     * Registers a new account with Email, Password and optional Display Name.
     * Default role 'member' and credits are assigned via database trigger.
     */
    suspend fun signUpWithEmail(email: String, password: String, displayName: String?) {
        supabase.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            if (!displayName.isNullOrBlank()) {
                data = buildJsonObject {
                    put("full_name", displayName)
                    put("display_name", displayName)
                }
            }
        }
        // If email confirmation is off in Supabase, user has immediate session
        val uid = currentUserId()
        if (uid != null) {
            upsertProfile(displayName, null)
        }
    }

    /**
     * Updates password for the currently authenticated user.
     */
    suspend fun changePassword(newPassword: String) {
        supabase.auth.updateUser {
            password = newPassword
        }
    }

    /**
     * Sends a password reset email via Supabase Auth.
     */
    suspend fun resetPasswordForEmail(email: String) {
        supabase.auth.resetPasswordForEmail(email)
    }

    suspend fun upsertProfile(displayName: String?, avatarUrl: String?) {
        val user = supabase.auth.currentUserOrNull() ?: return
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore("@")
        val profile = ProfileUpsert(
            id = user.id,
            email = user.email,
            displayName = name,
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
        )
        supabase.from("profiles").upsert(profile)
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    /**
     * Best-effort sign-out: guaranteed to never throw so the locked-account
     * branch always surfaces the correct error to the caller.
     */
    private suspend fun signOutQuietly() {
        try {
            signOut()
        } catch (_: Exception) {
            // The local session may already be cleared; ignore network failures.
        }
    }
}
