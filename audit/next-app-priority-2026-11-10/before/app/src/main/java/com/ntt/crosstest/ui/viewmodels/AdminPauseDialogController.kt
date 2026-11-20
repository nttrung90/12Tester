package com.ntt.crosstest.ui.viewmodels

import com.ntt.crosstest.data.AdminPauseReason
import com.ntt.crosstest.data.SessionCacheManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * State for Admin Campaign Pause Reason Dialog.
 * Scoped to active admin identity (UID + SessionCacheManager generation).
 */
data class AdminPauseDialogState(
    val isOpen: Boolean = false,
    val reasonCode: String = AdminPauseReason.CUSTOM.code,
    val customReason: String = "",
    val error: String? = null,
    val dialogInstanceId: Long = 0L,
    val sessionUserId: String? = null,
    val sessionGeneration: Long = 0L,
)

/**
 * Controller managing Admin Campaign Pause Reason Dialog session isolation,
 * draft persistence, in-modal error presentation, and action callback safety.
 */
class AdminPauseDialogController(
    private val generationProvider: () -> Long = { SessionCacheManager.currentGeneration() }
) {
    private val _state = MutableStateFlow(AdminPauseDialogState())
    val state: StateFlow<AdminPauseDialogState> = _state.asStateFlow()

    private val instanceSequence = AtomicLong(0L)

    @Volatile
    private var activeUserId: String? = null

    @Volatile
    private var activeGeneration: Long = generationProvider()

    @Volatile
    private var activeIsAdmin: Boolean = false

    /**
     * Synchronize with live auth identity (UID, generation, isAdmin).
     * If identity changes (different UID, different generation, or not admin/logged out),
     * the open dialog, preset selection, custom draft, and in-modal error are immediately reset.
     */
    fun syncIdentity(userId: String?, generation: Long = generationProvider(), isAdmin: Boolean) {
        val identityChanged = userId != activeUserId || generation != activeGeneration || isAdmin != activeIsAdmin
        activeUserId = userId
        activeGeneration = generation
        activeIsAdmin = isAdmin

        if (identityChanged) {
            _state.value = AdminPauseDialogState(
                sessionUserId = userId,
                sessionGeneration = generation,
            )
        }
    }

    /**
     * Open the pause dialog for the current admin identity.
     */
    fun open(userId: String?, generation: Long = generationProvider(), isAdmin: Boolean = true) {
        if (!isAdmin || userId == null) {
            _state.value = AdminPauseDialogState(
                sessionUserId = userId,
                sessionGeneration = generation,
            )
            return
        }
        activeUserId = userId
        activeGeneration = generation
        activeIsAdmin = isAdmin

        val newInstanceId = instanceSequence.incrementAndGet()
        _state.value = AdminPauseDialogState(
            isOpen = true,
            reasonCode = AdminPauseReason.CUSTOM.code,
            customReason = "",
            error = null,
            dialogInstanceId = newInstanceId,
            sessionUserId = userId,
            sessionGeneration = generation,
        )
    }

    /**
     * Dismiss the dialog without losing identity scoping.
     */
    fun dismiss() {
        val current = _state.value
        _state.value = current.copy(
            isOpen = false,
            error = null,
        )
    }

    /**
     * Update selected preset reason code.
     */
    fun setReasonCode(code: String) {
        val current = _state.value
        if (current.isOpen) {
            _state.value = current.copy(
                reasonCode = code,
                error = null,
            )
        }
    }

    /**
     * Update custom reason text draft.
     */
    fun setCustomReason(reason: String) {
        val current = _state.value
        if (current.isOpen) {
            _state.value = current.copy(
                customReason = reason,
                error = null,
            )
        }
    }

    /**
     * Clear the error inside the dialog (e.g. before retrying).
     */
    fun clearError() {
        val current = _state.value
        if (current.error != null) {
            _state.value = current.copy(error = null)
        }
    }

    fun currentInstanceId(): Long = _state.value.dialogInstanceId

    /**
     * Called when pause action completes successfully.
     * Prevents old action callbacks from closing a newer dialog or a dialog owned by another session.
     */
    fun onActionSuccess(dialogInstanceId: Long, initiatingUid: String, initiatingGen: Long): Boolean {
        val current = _state.value
        if (!current.isOpen) return false
        if (current.dialogInstanceId != dialogInstanceId) return false
        if (initiatingUid != activeUserId || initiatingGen != activeGeneration) return false

        _state.value = AdminPauseDialogState(
            isOpen = false,
            reasonCode = AdminPauseReason.CUSTOM.code,
            customReason = "",
            error = null,
            dialogInstanceId = current.dialogInstanceId,
            sessionUserId = activeUserId,
            sessionGeneration = activeGeneration,
        )
        return true
    }

    /**
     * Called when pause action fails.
     * Keeps the modal open, preserves selected preset and draft note for retry,
     * and sets the relevant action error inside the modal.
     */
    fun onActionError(dialogInstanceId: Long, initiatingUid: String, initiatingGen: Long, errorMessage: String): Boolean {
        val current = _state.value
        if (!current.isOpen) return false
        if (current.dialogInstanceId != dialogInstanceId) return false
        if (initiatingUid != activeUserId || initiatingGen != activeGeneration) return false

        _state.value = current.copy(
            error = errorMessage,
        )
        return true
    }
}
