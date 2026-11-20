package com.ntt.crosstest.data

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.postgrest.from
import com.ntt.crosstest.util.ScreenshotManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Authentication repository supporting Google sign-in and Email/Password sign-in/up
 * through Supabase Auth, along with role and account lock verification.
 */
class AuthRepository(
    private val supabase: SupabaseClient,
    private val context: Context? = null
) {

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
        if (uid != null && ensureProfileRow(uid, displayName, avatarUrl)?.isLocked == true) {
            signOutQuietly()
            throw IllegalStateException("ACCOUNT_LOCKED")
        }
        if (uid != null) {
            ScreenshotManager.onUserSessionChanged(uid)
        }
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
        if (uid != null && ensureProfileRow(uid, null, null)?.isLocked == true) {
            signOutQuietly()
            throw IllegalStateException("ACCOUNT_LOCKED")
        }
        if (uid != null) {
            ScreenshotManager.onUserSessionChanged(uid)
        }
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

    /**
     * Má»™t vÃ²ng Ä‘á»c profile duy nháº¥t: kiá»ƒm tra khoÃ¡ + chá»‰ ghi khi profile thiáº¿u
     * hoáº·c tÃªn/avatar Ä‘á»•i (bá» háº³n ghi vÃ´ Ä‘iá»u kiá»‡n má»—i láº§n Ä‘Äƒng nháº­p).
     * Tráº£ vá» profile hiá»‡n cÃ³ (ká»ƒ cáº£ khi Ä‘ang bá»‹ khoÃ¡) hoáº·c null náº¿u chÆ°a Ä‘á»c Ä‘Æ°á»£c.
     */
    private suspend fun ensureProfileRow(userId: String, displayName: String?, avatarUrl: String?): Profile? {
        val existing = try {
            supabase.from("profiles").select {
                filter { eq("id", userId) }
                limit(1)
            }.decodeList<Profile>().firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (existing?.isLocked == true) return existing

        val user = supabase.auth.currentUserOrNull()
        val needsWrite = existing == null ||
            (!displayName.isNullOrBlank() && displayName != existing.displayName) ||
            (!avatarUrl.isNullOrBlank() && avatarUrl != existing.avatarUrl)
        if (needsWrite) {
            val profile = ProfileUpsert(
                id = userId,
                email = user?.email ?: existing?.email,
                displayName = displayName?.takeIf { it.isNotBlank() }
                    ?: existing?.displayName
                    ?: user?.email?.substringBefore("@"),
                avatarUrl = avatarUrl?.takeIf { it.isNotBlank() } ?: existing?.avatarUrl,
            )
            supabase.from("profiles").upsert(profile)
        }
        return existing
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
        try {
            supabase.auth.signOut()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Supabase chá»‰ tá»± xoÃ¡ session cá»¥c bá»™ khi gáº·p RestException; khi máº¥t máº¡ng
            // (IOException) nÃ³ nÃ©m lá»—i mÃ  váº«n giá»¯ session trÃªn mÃ¡y â†’ láº§n má»Ÿ sau tá»±
            // Ä‘Äƒng nháº­p láº¡i. Chá»§ Ä‘á»™ng xoÃ¡ Ä‘á»ƒ Ä‘Äƒng xuáº¥t luÃ´n dá»©t khoÃ¡t.
            try {
                supabase.auth.clearSession()
            } catch (_: Exception) {
                // Local session cÃ³ thá»ƒ Ä‘Ã£ bá»‹ xoÃ¡; bá» qua Ä‘á»ƒ khÃ´ng che lá»—i gá»‘c.
            }
            throw e
        } finally {
            ScreenshotManager.onUserSignedOut(context)
        }
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
