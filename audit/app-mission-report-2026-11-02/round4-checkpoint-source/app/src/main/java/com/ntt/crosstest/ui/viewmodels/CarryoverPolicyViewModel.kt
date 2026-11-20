package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AdminCarryoverPoliciesResult
import com.ntt.crosstest.data.AdminCarryoverPolicyItem
import com.ntt.crosstest.data.AdminManagementRepository
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.util.VnTime
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class CarryoverPolicyUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isCancelling: Boolean = false,
    val activePolicy: AdminCarryoverPolicyItem? = null,
    val futurePolicies: List<AdminCarryoverPolicyItem> = emptyList(),
    val isFutureLoadingMore: Boolean = false,
    val futureHasMore: Boolean = false,
    val futureCursorDate: String? = null,
    val history: List<AdminCarryoverPolicyItem> = emptyList(),
    val auditHistory: List<com.ntt.crosstest.data.AdminCarryoverAuditHistoryItem> = emptyList(),
    val isAuditHistoryLoading: Boolean = false,
    val auditHistoryHasMore: Boolean = false,
    val auditHistoryCursorId: Long? = null,
    val revision: String? = null,
    val draftEffectiveFrom: String = VnTime.today().plusDays(1).toString(),
    val draftIsEnabled: Boolean = true,
    val draftNote: String = "",
    val error: String? = null,
    val actionMessage: String? = null,
    val currentAdminId: String? = null,
    val sessionGeneration: Long = 0L,
)

class CarryoverPolicyViewModel(
    private val adminManagementRepository: AdminManagementRepository,
    private val authRepository: AuthRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CarryoverPolicyUiState())
    val uiState: StateFlow<CarryoverPolicyUiState> = _uiState.asStateFlow()

    private val configSeq = AtomicLong(0L)
    private val futureSeq = AtomicLong(0L)
    private val auditHistorySeq = AtomicLong(0L)
    private val actionSeq = AtomicLong(0L)
    private val isActionInProgress = AtomicBoolean(false)
    private val activeActionToken = AtomicLong(0L)

    private var activeUserId: String? = authRepository.currentUserId()
    private var activeGeneration: Long = SessionCacheManager.currentGeneration()

    private var lastSchedulePayload: String? = null
    private var scheduleRequestId: String = UUID.randomUUID().toString()

    private var lastCancelPayload: String? = null
    private var cancelRequestId: String = UUID.randomUUID().toString()

    init {
        _uiState.update { it.copy(currentAdminId = activeUserId, sessionGeneration = activeGeneration) }
        observeSession()
        loadPolicies()
    }

    private fun checkSessionChanged(reqUid: String?, reqGen: Long): Boolean {
        val curUid = authRepository.currentUserId()
        val curGen = SessionCacheManager.currentGeneration()
        if (activeUserId != curUid || activeGeneration != curGen) {
            handleSessionReset(curUid, curGen)
            return true
        }
        if (reqUid != curUid || reqGen != curGen) {
            return true
        }
        return false
    }

    private fun isStale(reqUid: String?, reqGen: Long): Boolean {
        return checkSessionChanged(reqUid, reqGen)
    }

    private fun reconcileSession(): Pair<String?, Long> {
        val curUid = authRepository.currentUserId()
        val curGen = SessionCacheManager.currentGeneration()
        if (activeUserId != curUid || activeGeneration != curGen) {
            handleSessionReset(curUid, curGen)
        }
        return Pair(curUid, curGen)
    }

    private fun observeSession() {
        viewModelScope.launch {
            authRepository.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val newUid = authRepository.currentUserId()
                        val newGen = SessionCacheManager.currentGeneration()
                        if (newUid != activeUserId || newGen != activeGeneration) {
                            handleSessionReset(newUid, newGen)
                            loadPolicies()
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        val newGen = SessionCacheManager.currentGeneration()
                        handleSessionReset(null, newGen)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleSessionReset(newUid: String?, newGen: Long) {
        if (activeUserId == newUid && activeGeneration == newGen) return
        activeUserId = newUid
        activeGeneration = newGen
        configSeq.incrementAndGet()
        futureSeq.incrementAndGet()
        auditHistorySeq.incrementAndGet()
        actionSeq.incrementAndGet()
        activeActionToken.set(0L)
        isActionInProgress.set(false)
        lastSchedulePayload = null
        scheduleRequestId = UUID.randomUUID().toString()
        lastCancelPayload = null
        cancelRequestId = UUID.randomUUID().toString()

        _uiState.update {
            CarryoverPolicyUiState(
                currentAdminId = newUid,
                sessionGeneration = newGen,
                isLoading = false,
                isFutureLoadingMore = false,
                isAuditHistoryLoading = false,
                futureHasMore = false,
                futureCursorDate = null,
            )
        }
    }

    private fun releaseActionGuard(token: Long) {
        if (activeActionToken.compareAndSet(token, 0L)) {
            isActionInProgress.set(false)
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, actionMessage = null) }
    }

    fun setDraft(effectiveFrom: String? = null, isEnabled: Boolean? = null, note: String? = null) {
        _uiState.update {
            it.copy(
                draftEffectiveFrom = effectiveFrom ?: it.draftEffectiveFrom,
                draftIsEnabled = isEnabled ?: it.draftIsEnabled,
                draftNote = note ?: it.draftNote,
            )
        }
    }

    fun loadPolicies(preserveDraft: Boolean = true) {
        val (curUid, curGen) = reconcileSession()
        val seq = configSeq.incrementAndGet()
        futureSeq.incrementAndGet()
        _uiState.update { it.copy(isLoading = true, isFutureLoadingMore = false, error = null) }

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != configSeq.get()) return@launch
            try {
                val res = adminManagementRepository.getCarryoverPolicies()
                var futureError: Throwable? = null
                val futureRes = try {
                    adminManagementRepository.listFutureCarryoverPolicies(limit = 50)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    futureError = e
                    null
                }
                if (isStale(curUid, curGen) || seq != configSeq.get()) return@launch

                _uiState.update { state ->
                    val defaultTomorrow = VnTime.today().plusDays(1).toString()
                    val futureItems = futureRes?.items ?: res.future
                    val hasMore = futureRes?.hasMore ?: false
                    val nextCursorDate = futureRes?.nextCursor?.date
                    state.copy(
                        isLoading = false,
                        isFutureLoadingMore = false,
                        activePolicy = res.active,
                        futurePolicies = futureItems,
                        futureHasMore = hasMore,
                        futureCursorDate = nextCursorDate,
                        history = res.history,
                        revision = res.revision,
                        draftEffectiveFrom = if (preserveDraft) state.draftEffectiveFrom else defaultTomorrow,
                        draftIsEnabled = if (preserveDraft) state.draftIsEnabled else true,
                        draftNote = if (preserveDraft) state.draftNote else "",
                        error = if (futureError != null) friendlyError(futureError, localizer) else null,
                    )
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && seq == configSeq.get()) {
                    _uiState.update { it.copy(isLoading = false, isFutureLoadingMore = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || seq != configSeq.get()) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isFutureLoadingMore = false,
                        error = friendlyError(e, localizer),
                    )
                }
            }
        }
    }

    fun loadMoreFuturePolicies() {
        val (curUid, curGen) = reconcileSession()
        val state = _uiState.value
        if (state.isLoading || state.isFutureLoadingMore || !state.futureHasMore) return
        val cursorDate = state.futureCursorDate ?: return
        _uiState.update { it.copy(isFutureLoadingMore = true) }
        val seq = futureSeq.incrementAndGet()

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != futureSeq.get()) return@launch
            try {
                val res = adminManagementRepository.listFutureCarryoverPolicies(
                    limit = 50,
                    cursorDate = cursorDate,
                )
                if (isStale(curUid, curGen) || seq != futureSeq.get()) return@launch
                _uiState.update { s ->
                    val existingIds = s.futurePolicies.mapNotNull { it.id }.toSet()
                    val newItems = res.items.filter { it.id !in existingIds }
                    s.copy(
                        isFutureLoadingMore = false,
                        futurePolicies = s.futurePolicies + newItems,
                        futureHasMore = res.hasMore,
                        futureCursorDate = res.nextCursor?.date,
                    )
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && seq == futureSeq.get()) {
                    _uiState.update { it.copy(isFutureLoadingMore = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || seq != futureSeq.get()) return@launch
                _uiState.update { it.copy(isFutureLoadingMore = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    fun loadAuditHistory(reset: Boolean = false) {
        val (curUid, curGen) = reconcileSession()
        if (_uiState.value.isAuditHistoryLoading) return
        _uiState.update { it.copy(isAuditHistoryLoading = true) }
        val seq = auditHistorySeq.incrementAndGet()
        val cursorId = if (reset) null else _uiState.value.auditHistoryCursorId

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != auditHistorySeq.get()) return@launch
            try {
                val res = adminManagementRepository.getCarryoverPolicyHistory(
                    limit = 50,
                    cursorId = cursorId,
                    includeCancelled = true,
                )
                if (isStale(curUid, curGen) || seq != auditHistorySeq.get()) return@launch
                _uiState.update { s ->
                    val combined = if (reset) res.items else (s.auditHistory + res.items).distinctBy { it.id }
                    s.copy(
                        isAuditHistoryLoading = false,
                        auditHistory = combined,
                        auditHistoryHasMore = res.hasMore,
                        auditHistoryCursorId = res.nextCursor,
                    )
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && seq == auditHistorySeq.get()) {
                    _uiState.update { it.copy(isAuditHistoryLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || seq != auditHistorySeq.get()) return@launch
                _uiState.update { it.copy(isAuditHistoryLoading = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    fun schedulePolicy(
        effectiveFrom: String,
        isEnabled: Boolean,
        note: String,
        confirmingAdminId: String? = null,
        confirmingSessionGen: Long = 0L,
    ) {
        val (curUid, curGen) = reconcileSession()
        if (confirmingAdminId != null && (confirmingAdminId != curUid || confirmingSessionGen != curGen)) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_action_aborted_session_changed)) }
            return
        }

        val reqId = scheduleRequestId
        val expectedRev = _uiState.value.revision?.toLongOrNull()
        if (expectedRev == null) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_daily_task_err_revision_required)) }
            return
        }

        val token = actionSeq.incrementAndGet()
        if (!isActionInProgress.compareAndSet(false, true)) return
        activeActionToken.set(token)
        _uiState.update { it.copy(isSaving = true, error = null, actionMessage = null) }

        viewModelScope.launch {
            if (isStale(curUid, curGen) || token != activeActionToken.get()) {
                releaseActionGuard(token)
                return@launch
            }
            try {
                val res = adminManagementRepository.scheduleCarryoverPolicy(
                    effectiveFrom = effectiveFrom,
                    isEnabled = isEnabled,
                    operatorNote = note.trim(),
                    expectedRevision = expectedRev,
                    requestId = reqId,
                )
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch

                lastSchedulePayload = null
                scheduleRequestId = UUID.randomUUID().toString()

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        actionMessage = localizer.get(R.string.admin_carryover_schedule_success),
                        draftNote = "",
                        draftEffectiveFrom = VnTime.today().plusDays(1).toString(),
                    )
                }
                loadPolicies(preserveDraft = false)
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && token == activeActionToken.get()) {
                    _uiState.update { it.copy(isSaving = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch
                val errMsg = friendlyError(e, localizer)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = localizer.get(R.string.admin_carryover_schedule_failed, errMsg),
                    )
                }
                if ("REVISION_CONFLICT" in e.message.orEmpty()) {
                    loadPolicies(preserveDraft = true)
                }
            } finally {
                releaseActionGuard(token)
            }
        }
    }

    fun cancelFuturePolicy(
        effectiveFrom: String,
        reason: String,
        confirmingAdminId: String? = null,
        confirmingSessionGen: Long = 0L,
    ) {
        val (curUid, curGen) = reconcileSession()
        if (confirmingAdminId != null && (confirmingAdminId != curUid || confirmingSessionGen != curGen)) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_action_aborted_session_changed)) }
            return
        }

        val reqId = cancelRequestId
        val expectedRev = _uiState.value.revision?.toLongOrNull()
        if (expectedRev == null) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_daily_task_err_revision_required)) }
            return
        }

        val token = actionSeq.incrementAndGet()
        if (!isActionInProgress.compareAndSet(false, true)) return
        activeActionToken.set(token)
        _uiState.update { it.copy(isCancelling = true, error = null, actionMessage = null) }

        viewModelScope.launch {
            if (isStale(curUid, curGen) || token != activeActionToken.get()) {
                releaseActionGuard(token)
                return@launch
            }
            try {
                adminManagementRepository.cancelFutureCarryoverPolicy(
                    effectiveFrom = effectiveFrom,
                    reason = reason.trim(),
                    expectedRevision = expectedRev,
                    requestId = reqId,
                )
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch

                lastCancelPayload = null
                cancelRequestId = UUID.randomUUID().toString()

                _uiState.update {
                    it.copy(
                        isCancelling = false,
                        actionMessage = localizer.get(R.string.admin_carryover_cancel_success),
                    )
                }
                loadPolicies(preserveDraft = true)
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && token == activeActionToken.get()) {
                    _uiState.update { it.copy(isCancelling = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch
                val errMsg = friendlyError(e, localizer)
                _uiState.update {
                    it.copy(
                        isCancelling = false,
                        error = localizer.get(R.string.admin_carryover_cancel_failed, errMsg),
                    )
                }
                if ("REVISION_CONFLICT" in e.message.orEmpty()) {
                    loadPolicies(preserveDraft = true)
                }
            } finally {
                releaseActionGuard(token)
            }
        }
    }
}
