package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AdminCreditHistoryItem
import com.ntt.crosstest.data.AdminDateCursor
import com.ntt.crosstest.data.AdminDateTimeCursor
import com.ntt.crosstest.data.AdminManagementRepository
import com.ntt.crosstest.data.AdminMemberDailyTaskItem
import com.ntt.crosstest.data.AdminMemberOverview
import com.ntt.crosstest.data.AdminMemberWarningItem
import com.ntt.crosstest.data.AdminMyCapabilities
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
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

data class MemberDetailUiState(
    val userId: String = "",
    val isLoading: Boolean = true,
    val isActionLoading: Boolean = false,
    val overview: AdminMemberOverview? = null,
    val myCapabilities: AdminMyCapabilities? = null,
    val creditHistory: List<AdminCreditHistoryItem> = emptyList(),
    val isCreditsLoading: Boolean = false,
    val creditHasMore: Boolean = false,
    val creditCursor: AdminDateTimeCursor? = null,
    val dailyTasks: List<AdminMemberDailyTaskItem> = emptyList(),
    val isDailyTasksLoading: Boolean = false,
    val dailyTasksHasMore: Boolean = false,
    val dailyTasksCursor: AdminDateCursor? = null,
    val warnings: List<AdminMemberWarningItem> = emptyList(),
    val isWarningsLoading: Boolean = false,
    val warningsHasMore: Boolean = false,
    val warningsCursor: AdminDateTimeCursor? = null,
    val error: String? = null,
    val actionMessage: String? = null,
    val pendingRetryCompensation: CompensationIntent? = null,
    val pendingRetryRequestId: String? = null,
    val currentAdminId: String? = null,
    val sessionGeneration: Long = 0L,
)

data class CompensationIntent(
    val amount: Int,
    val reason: String,
    val originalCreditLogId: String?,
    val contactReportId: String?,
)

data class RoleChangeIntent(
    val newRole: String,
    val reason: String,
)

data class RevokeWarningIntent(
    val warningId: String,
    val reason: String,
)

class MemberDetailViewModel(
    val targetUserId: String,
    private val adminManagementRepository: AdminManagementRepository,
    private val authRepository: AuthRepository,
    private val localizer: Localizer,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle? = null,
) : ViewModel() {

    companion object {
        private const val KEY_PENDING_COMP_TARGET = "pending_comp_target"
        private const val KEY_PENDING_COMP_ADMIN = "pending_comp_admin"
        private const val KEY_PENDING_COMP_REQ_ID = "pending_comp_req_id"
        private const val KEY_PENDING_COMP_AMOUNT = "pending_comp_amount"
        private const val KEY_PENDING_COMP_REASON = "pending_comp_reason"
        private const val KEY_PENDING_COMP_ORIG_LOG = "pending_comp_orig_log"
        private const val KEY_PENDING_COMP_CONTACT = "pending_comp_contact"
        private const val KEY_PENDING_COMP_UNRESOLVED = "pending_comp_unresolved"
        private const val KEY_PENDING_COMP_DISMISSED = "pending_comp_dismissed"
    }

    private val _uiState = MutableStateFlow(MemberDetailUiState(userId = targetUserId))
    val uiState: StateFlow<MemberDetailUiState> = _uiState.asStateFlow()

    private val refreshSeq = AtomicLong(0L)
    private val creditsSeq = AtomicLong(0L)
    private val dailyTasksSeq = AtomicLong(0L)
    private val warningsSeq = AtomicLong(0L)

    private val actionSeq = AtomicLong(0L)
    private val isActionInProgress = AtomicBoolean(false)
    private val activeActionToken = AtomicLong(0L)

    private var activeUserId: String? = authRepository.currentUserId()
    private var activeGeneration: Long = SessionCacheManager.currentGeneration()

    private var lastCompensationIntent: CompensationIntent? = null
    private var compensationRequestId: String = UUID.randomUUID().toString()

    private var lastRoleIntent: RoleChangeIntent? = null
    private var roleRequestId: String = UUID.randomUUID().toString()

    private var lastRevokeIntent: RevokeWarningIntent? = null
    private var revokeRequestId: String = UUID.randomUUID().toString()

    init {
        _uiState.update { it.copy(currentAdminId = activeUserId, sessionGeneration = activeGeneration) }
        tryRestorePendingCompensation(activeUserId)
        observeSession()
        refreshAll()
    }

    private fun tryRestorePendingCompensation(authenticatedAdminId: String?) {
        if (authenticatedAdminId.isNullOrBlank()) return
        val savedTarget = savedStateHandle?.get<String>(KEY_PENDING_COMP_TARGET)
        val savedAdmin = savedStateHandle?.get<String>(KEY_PENDING_COMP_ADMIN)
        val savedReqId = savedStateHandle?.get<String>(KEY_PENDING_COMP_REQ_ID)
        val savedUnresolved = savedStateHandle?.get<Boolean>(KEY_PENDING_COMP_UNRESOLVED) ?: false
        val savedDismissed = savedStateHandle?.get<Boolean>(KEY_PENDING_COMP_DISMISSED) ?: false

        if (savedTarget == targetUserId && savedAdmin == authenticatedAdminId && !savedReqId.isNullOrBlank() && savedUnresolved) {
            val amount = savedStateHandle.get<Int>(KEY_PENDING_COMP_AMOUNT) ?: 0
            val reason = savedStateHandle.get<String>(KEY_PENDING_COMP_REASON).orEmpty()
            val origLog = savedStateHandle.get<String>(KEY_PENDING_COMP_ORIG_LOG)
            val contact = savedStateHandle.get<String>(KEY_PENDING_COMP_CONTACT)
            val intent = CompensationIntent(amount, reason, origLog, contact)
            lastCompensationIntent = intent
            compensationRequestId = savedReqId
            if (!savedDismissed) {
                _uiState.update { it.copy(pendingRetryCompensation = intent, pendingRetryRequestId = savedReqId) }
            }
        }
    }

    private fun savePendingCompensation(intent: CompensationIntent, reqId: String, adminUid: String?) {
        savedStateHandle?.set(KEY_PENDING_COMP_TARGET, targetUserId)
        savedStateHandle?.set(KEY_PENDING_COMP_ADMIN, adminUid)
        savedStateHandle?.set(KEY_PENDING_COMP_REQ_ID, reqId)
        savedStateHandle?.set(KEY_PENDING_COMP_AMOUNT, intent.amount)
        savedStateHandle?.set(KEY_PENDING_COMP_REASON, intent.reason)
        savedStateHandle?.set(KEY_PENDING_COMP_ORIG_LOG, intent.originalCreditLogId)
        savedStateHandle?.set(KEY_PENDING_COMP_CONTACT, intent.contactReportId)
        savedStateHandle?.set(KEY_PENDING_COMP_UNRESOLVED, true)
        savedStateHandle?.set(KEY_PENDING_COMP_DISMISSED, false)
    }

    private fun clearSavedPendingCompensation() {
        savedStateHandle?.remove<String>(KEY_PENDING_COMP_TARGET)
        savedStateHandle?.remove<String>(KEY_PENDING_COMP_ADMIN)
        savedStateHandle?.remove<String>(KEY_PENDING_COMP_REQ_ID)
        savedStateHandle?.remove<Int>(KEY_PENDING_COMP_AMOUNT)
        savedStateHandle?.remove<String>(KEY_PENDING_COMP_REASON)
        savedStateHandle?.remove<String>(KEY_PENDING_COMP_ORIG_LOG)
        savedStateHandle?.remove<String>(KEY_PENDING_COMP_CONTACT)
        savedStateHandle?.remove<Boolean>(KEY_PENDING_COMP_UNRESOLVED)
        savedStateHandle?.remove<Boolean>(KEY_PENDING_COMP_DISMISSED)
    }

    fun dismissPendingCompensation() {
        // Hide the banner from UI but retain unresolved payload & UUID on disk/handle
        // so next attempt with same intent will reuse the UUID, preventing double-credit.
        savedStateHandle?.set(KEY_PENDING_COMP_DISMISSED, true)
        _uiState.update { it.copy(pendingRetryCompensation = null, pendingRetryRequestId = null) }
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
                            refreshAll()
                        } else {
                            tryRestorePendingCompensation(newUid)
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
        refreshSeq.incrementAndGet()
        creditsSeq.incrementAndGet()
        dailyTasksSeq.incrementAndGet()
        warningsSeq.incrementAndGet()

        actionSeq.incrementAndGet()
        activeActionToken.set(0L)
        isActionInProgress.set(false)

        clearSavedPendingCompensation()

        lastCompensationIntent = null
        compensationRequestId = UUID.randomUUID().toString()
        lastRoleIntent = null
        roleRequestId = UUID.randomUUID().toString()
        lastRevokeIntent = null
        revokeRequestId = UUID.randomUUID().toString()

        _uiState.update {
            MemberDetailUiState(
                userId = targetUserId,
                currentAdminId = newUid,
                sessionGeneration = newGen,
                isLoading = false,
                isActionLoading = false,
                isCreditsLoading = false,
                isDailyTasksLoading = false,
                isWarningsLoading = false,
                pendingRetryCompensation = null,
                pendingRetryRequestId = null,
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

    fun refreshAll() {
        val (curUid, curGen) = reconcileSession()
        val epoch = refreshSeq.incrementAndGet()
        creditsSeq.incrementAndGet()
        dailyTasksSeq.incrementAndGet()
        warningsSeq.incrementAndGet()

        _uiState.update {
            it.copy(
                isLoading = true,
                isCreditsLoading = false,
                isDailyTasksLoading = false,
                isWarningsLoading = false,
                error = null,
            )
        }

        viewModelScope.launch {
            if (isStale(curUid, curGen) || epoch != refreshSeq.get()) return@launch
            try {
                val overview = adminManagementRepository.getMemberOverview(targetUserId)
                val caps = try {
                    adminManagementRepository.getMyCapabilities()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val credits = adminManagementRepository.getMemberCreditHistory(targetUserId, limit = 20)
                val dailyTasks = adminManagementRepository.getMemberDailyTasks(targetUserId, limit = 14)
                val warnings = adminManagementRepository.getMemberWarnings(targetUserId, limit = 20)

                if (isStale(curUid, curGen) || epoch != refreshSeq.get()) return@launch

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        overview = overview,
                        myCapabilities = caps,
                        creditHistory = credits.items,
                        creditHasMore = credits.hasMore,
                        creditCursor = credits.nextCursor,
                        dailyTasks = dailyTasks.items,
                        dailyTasksHasMore = dailyTasks.hasMore,
                        dailyTasksCursor = dailyTasks.nextCursor,
                        warnings = warnings.items,
                        warningsHasMore = warnings.hasMore,
                        warningsCursor = warnings.nextCursor,
                    )
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && epoch == refreshSeq.get()) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || epoch != refreshSeq.get()) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = friendlyError(e, localizer),
                    )
                }
            }
        }
    }

    fun loadMoreCreditHistory() {
        val (curUid, curGen) = reconcileSession()
        val state = _uiState.value
        if (state.isCreditsLoading || !state.creditHasMore) return
        val cursor = state.creditCursor ?: return
        _uiState.update { it.copy(isCreditsLoading = true) }
        val seq = creditsSeq.incrementAndGet()
        val epoch = refreshSeq.get()

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != creditsSeq.get() || epoch != refreshSeq.get()) return@launch
            try {
                val res = adminManagementRepository.getMemberCreditHistory(
                    userId = targetUserId,
                    limit = 20,
                    cursorCreatedAt = cursor.createdAt,
                    cursorId = cursor.id,
                )
                if (isStale(curUid, curGen) || seq != creditsSeq.get() || epoch != refreshSeq.get()) return@launch
                _uiState.update { s ->
                    val existingIds = s.creditHistory.map { it.id }.toSet()
                    val newItems = res.items.filter { it.id !in existingIds }
                    s.copy(
                        isCreditsLoading = false,
                        creditHistory = s.creditHistory + newItems,
                        creditHasMore = res.hasMore,
                        creditCursor = res.nextCursor,
                    )
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && seq == creditsSeq.get() && epoch == refreshSeq.get()) {
                    _uiState.update { it.copy(isCreditsLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || seq != creditsSeq.get() || epoch != refreshSeq.get()) return@launch
                _uiState.update { it.copy(isCreditsLoading = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    fun loadMoreDailyTasks() {
        val (curUid, curGen) = reconcileSession()
        val state = _uiState.value
        if (state.isDailyTasksLoading || !state.dailyTasksHasMore) return
        val cursor = state.dailyTasksCursor ?: return
        _uiState.update { it.copy(isDailyTasksLoading = true) }
        val seq = dailyTasksSeq.incrementAndGet()
        val epoch = refreshSeq.get()

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != dailyTasksSeq.get() || epoch != refreshSeq.get()) return@launch
            try {
                val res = adminManagementRepository.getMemberDailyTasks(
                    userId = targetUserId,
                    limit = 14,
                    cursorDate = cursor.date,
                )
                if (isStale(curUid, curGen) || seq != dailyTasksSeq.get() || epoch != refreshSeq.get()) return@launch
                _uiState.update { s ->
                    val existingDates = s.dailyTasks.map { it.taskDate }.toSet()
                    val newItems = res.items.filter { it.taskDate !in existingDates }
                    s.copy(
                        isDailyTasksLoading = false,
                        dailyTasks = s.dailyTasks + newItems,
                        dailyTasksHasMore = res.hasMore,
                        dailyTasksCursor = res.nextCursor,
                    )
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && seq == dailyTasksSeq.get() && epoch == refreshSeq.get()) {
                    _uiState.update { it.copy(isDailyTasksLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || seq != dailyTasksSeq.get() || epoch != refreshSeq.get()) return@launch
                _uiState.update { it.copy(isDailyTasksLoading = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    fun loadMoreWarnings() {
        val (curUid, curGen) = reconcileSession()
        val state = _uiState.value
        if (state.isWarningsLoading || !state.warningsHasMore) return
        val cursor = state.warningsCursor ?: return
        _uiState.update { it.copy(isWarningsLoading = true) }
        val seq = warningsSeq.incrementAndGet()
        val epoch = refreshSeq.get()

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != warningsSeq.get() || epoch != refreshSeq.get()) return@launch
            try {
                val res = adminManagementRepository.getMemberWarnings(
                    userId = targetUserId,
                    limit = 20,
                    cursorCreatedAt = cursor.createdAt,
                    cursorId = cursor.id,
                )
                if (isStale(curUid, curGen) || seq != warningsSeq.get() || epoch != refreshSeq.get()) return@launch
                _uiState.update { s ->
                    val existingIds = s.warnings.map { it.id }.toSet()
                    val newItems = res.items.filter { it.id !in existingIds }
                    s.copy(
                        isWarningsLoading = false,
                        warnings = s.warnings + newItems,
                        warningsHasMore = res.hasMore,
                        warningsCursor = res.nextCursor,
                    )
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && seq == warningsSeq.get() && epoch == refreshSeq.get()) {
                    _uiState.update { it.copy(isWarningsLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || seq != warningsSeq.get() || epoch != refreshSeq.get()) return@launch
                _uiState.update { it.copy(isWarningsLoading = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    fun changeRole(
        newRole: String,
        reason: String,
        confirmingAdminId: String? = null,
        confirmingSessionGen: Long = 0L,
    ) {
        val (curUid, curGen) = reconcileSession()
        if (confirmingAdminId != null && (confirmingAdminId != curUid || confirmingSessionGen != curGen)) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_action_aborted_session_changed)) }
            return
        }

        val token = actionSeq.incrementAndGet()
        if (!isActionInProgress.compareAndSet(false, true)) return
        activeActionToken.set(token)
        _uiState.update { it.copy(isActionLoading = true, error = null) }

        val intent = RoleChangeIntent(newRole = newRole, reason = reason)
        if (intent != lastRoleIntent) {
            roleRequestId = UUID.randomUUID().toString()
            lastRoleIntent = intent
        }
        val reqId = roleRequestId

        viewModelScope.launch {
            if (isStale(curUid, curGen) || token != activeActionToken.get()) {
                releaseActionGuard(token)
                return@launch
            }
            try {
                val res = adminManagementRepository.setUserRole(
                    targetUserId = targetUserId,
                    newRole = newRole,
                    reason = reason,
                    requestId = reqId,
                )
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch

                lastRoleIntent = null
                roleRequestId = UUID.randomUUID().toString()

                _uiState.update { state ->
                    state.copy(
                        isActionLoading = false,
                        overview = state.overview?.copy(role = res.newRole),
                        actionMessage = localizer.get(R.string.admin_role_change_success),
                    )
                }
                refreshAll()
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && token == activeActionToken.get()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch
                val errMsg = parseBackendError(e)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_role_change_failed, errMsg),
                    )
                }
            } finally {
                releaseActionGuard(token)
            }
        }
    }

    fun compensateCredits(
        amount: Int,
        reason: String,
        originalCreditLogId: String? = null,
        contactReportId: String? = null,
        confirmingAdminId: String? = null,
        confirmingSessionGen: Long = 0L,
    ) {
        val (curUid, curGen) = reconcileSession()
        if (confirmingAdminId != null && (confirmingAdminId != curUid || confirmingSessionGen != curGen)) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_action_aborted_session_changed)) }
            return
        }

        // Double-click guard before coroutine starts
        val token = actionSeq.incrementAndGet()
        if (!isActionInProgress.compareAndSet(false, true)) return
        activeActionToken.set(token)
        _uiState.update { it.copy(isActionLoading = true, error = null) }

        val intent = CompensationIntent(
            amount = amount,
            reason = reason,
            originalCreditLogId = originalCreditLogId,
            contactReportId = contactReportId,
        )
        if (intent != lastCompensationIntent) {
            compensationRequestId = UUID.randomUUID().toString()
            lastCompensationIntent = intent
        }
        val reqId = compensationRequestId
        savePendingCompensation(intent, reqId, curUid)

        viewModelScope.launch {
            if (isStale(curUid, curGen) || token != activeActionToken.get()) {
                releaseActionGuard(token)
                return@launch
            }
            try {
                val res = adminManagementRepository.compensateCredits(
                    requestId = reqId,
                    targetUserId = targetUserId,
                    amount = amount,
                    reason = reason,
                    originalCreditLogId = originalCreditLogId,
                    contactReportId = contactReportId,
                )
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch

                clearSavedPendingCompensation()
                lastCompensationIntent = null
                compensationRequestId = UUID.randomUUID().toString()

                val msg = if (res.isReplay) {
                    localizer.get(R.string.admin_compensate_success, res.newCredits) + " " + localizer.get(R.string.admin_compensate_replay_note)
                } else {
                    localizer.get(R.string.admin_compensate_success, res.newCredits)
                }

                _uiState.update { state ->
                    state.copy(
                        isActionLoading = false,
                        pendingRetryCompensation = null,
                        pendingRetryRequestId = null,
                        overview = state.overview?.copy(credits = res.newCredits),
                        actionMessage = msg,
                    )
                }
                refreshAll()
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && token == activeActionToken.get()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch
                // Do NOT auto retry financial operations
                val errMsg = parseBackendError(e)
                val msg = e.message.orEmpty()
                val isDefinitiveNonCommit = "USER_NOT_FOUND" in msg ||
                        "INSUFFICIENT_PRIVILEGE" in msg ||
                        "CANNOT_CHANGE_OWN_ROLE" in msg ||
                        "CANNOT_REMOVE_LAST_ADMIN" in msg
                if (isDefinitiveNonCommit) {
                    clearSavedPendingCompensation()
                    lastCompensationIntent = null
                    compensationRequestId = UUID.randomUUID().toString()
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            pendingRetryCompensation = null,
                            pendingRetryRequestId = null,
                            error = localizer.get(R.string.admin_compensate_failed, errMsg),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            pendingRetryCompensation = intent,
                            pendingRetryRequestId = reqId,
                            error = localizer.get(R.string.admin_compensate_failed, errMsg),
                        )
                    }
                }
            } finally {
                releaseActionGuard(token)
            }
        }
    }

    fun revokeWarning(
        warningId: String,
        reason: String,
        confirmingAdminId: String? = null,
        confirmingSessionGen: Long = 0L,
    ) {
        val (curUid, curGen) = reconcileSession()
        if (confirmingAdminId != null && (confirmingAdminId != curUid || confirmingSessionGen != curGen)) {
            _uiState.update { it.copy(error = localizer.get(R.string.admin_action_aborted_session_changed)) }
            return
        }

        val token = actionSeq.incrementAndGet()
        if (!isActionInProgress.compareAndSet(false, true)) return
        activeActionToken.set(token)
        _uiState.update { it.copy(isActionLoading = true, error = null) }

        val intent = RevokeWarningIntent(warningId = warningId, reason = reason)
        if (intent != lastRevokeIntent) {
            revokeRequestId = UUID.randomUUID().toString()
            lastRevokeIntent = intent
        }
        val reqId = revokeRequestId

        viewModelScope.launch {
            if (isStale(curUid, curGen) || token != activeActionToken.get()) {
                releaseActionGuard(token)
                return@launch
            }
            try {
                adminManagementRepository.revokeWarning(
                    warningId = warningId,
                    reason = reason,
                    requestId = reqId,
                )
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch

                lastRevokeIntent = null
                revokeRequestId = UUID.randomUUID().toString()

                _uiState.update { state ->
                    state.copy(
                        isActionLoading = false,
                        actionMessage = localizer.get(R.string.admin_revoke_warning_success),
                    )
                }
                refreshAll()
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && token == activeActionToken.get()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch
                val errMsg = parseBackendError(e)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_revoke_warning_failed, errMsg),
                    )
                }
            } finally {
                releaseActionGuard(token)
            }
        }
    }

    private fun parseBackendError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            "INSUFFICIENT_PRIVILEGE" in msg -> localizer.get(R.string.admin_role_insufficient_privilege)
            "CANNOT_CHANGE_OWN_ROLE" in msg -> localizer.get(R.string.admin_role_cannot_change_self)
            "CANNOT_REMOVE_LAST_ADMIN" in msg -> localizer.get(R.string.admin_role_cannot_remove_last_admin)
            else -> friendlyError(e, localizer)
        }
    }
}
