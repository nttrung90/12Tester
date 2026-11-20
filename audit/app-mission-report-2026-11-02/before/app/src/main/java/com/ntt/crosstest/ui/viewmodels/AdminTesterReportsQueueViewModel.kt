package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AdminDateTimeCursor
import com.ntt.crosstest.data.AdminManagementRepository
import com.ntt.crosstest.data.AdminTesterReportQueueItem
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AdminTesterReportsQueueUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isActionLoading: Boolean = false,
    val reports: List<AdminTesterReportQueueItem> = emptyList(),
    val statusFilter: String? = "pending",
    val contextFilter: String? = null,
    val hasMore: Boolean = false,
    val nextCursor: AdminDateTimeCursor? = null,
    val error: String? = null,
    val actionMessage: String? = null,
    val currentAdminId: String? = null,
    val sessionGeneration: Long = 0L,
)

class AdminTesterReportsQueueViewModel(
    private val adminManagementRepository: AdminManagementRepository,
    private val authRepository: AuthRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminTesterReportsQueueUiState())
    val uiState: StateFlow<AdminTesterReportsQueueUiState> = _uiState.asStateFlow()

    private val reqSeq = AtomicLong(0L)
    private val loadMoreSeq = AtomicLong(0L)
    private val actionSeq = AtomicLong(0L)
    private val isActionInProgress = AtomicBoolean(false)
    private val activeActionToken = AtomicLong(0L)

    private var activeUserId: String? = authRepository.currentUserId()
    private var activeGeneration: Long = SessionCacheManager.currentGeneration()

    init {
        _uiState.update { it.copy(currentAdminId = activeUserId, sessionGeneration = activeGeneration) }
        observeSession()
        refresh()
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
                            refresh()
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
        reqSeq.incrementAndGet()
        loadMoreSeq.incrementAndGet()
        actionSeq.incrementAndGet()
        activeActionToken.set(0L)
        isActionInProgress.set(false)

        _uiState.update {
            AdminTesterReportsQueueUiState(
                currentAdminId = newUid,
                sessionGeneration = newGen,
                isLoading = false,
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

    fun setStatusFilter(status: String?) {
        _uiState.update { it.copy(statusFilter = status, hasMore = false, nextCursor = null) }
        loadMoreSeq.incrementAndGet()
        refresh()
    }

    fun setContextFilter(context: String?) {
        _uiState.update { it.copy(contextFilter = context, hasMore = false, nextCursor = null) }
        loadMoreSeq.incrementAndGet()
        refresh()
    }

    fun refresh() {
        val (curUid, curGen) = reconcileSession()
        val seq = reqSeq.incrementAndGet()
        loadMoreSeq.incrementAndGet()
        val statusAtStart = _uiState.value.statusFilter
        val contextAtStart = _uiState.value.contextFilter

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != reqSeq.get()) return@launch
            try {
                val res = adminManagementRepository.getTesterReportsQueue(
                    status = statusAtStart,
                    submissionContext = contextAtStart,
                    limit = 50,
                )
                if (isStale(curUid, curGen) || seq != reqSeq.get() ||
                    statusAtStart != _uiState.value.statusFilter || contextAtStart != _uiState.value.contextFilter
                ) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        reports = res.items,
                        hasMore = res.hasMore,
                        nextCursor = res.nextCursor,
                    )
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && seq == reqSeq.get()) {
                    _uiState.update { it.copy(isLoading = false, isLoadingMore = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || seq != reqSeq.get()) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = friendlyError(e, localizer),
                    )
                }
            }
        }
    }

    fun loadMore() {
        val (curUid, curGen) = reconcileSession()
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore) return
        val cursor = state.nextCursor ?: return
        _uiState.update { it.copy(isLoadingMore = true) }
        val seq = loadMoreSeq.incrementAndGet()
        val statusAtStart = state.statusFilter
        val contextAtStart = state.contextFilter

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != loadMoreSeq.get()) return@launch
            try {
                val res = adminManagementRepository.getTesterReportsQueue(
                    status = statusAtStart,
                    submissionContext = contextAtStart,
                    limit = 50,
                    cursorCreatedAt = cursor.createdAt,
                    cursorId = cursor.id,
                )
                if (isStale(curUid, curGen) || seq != loadMoreSeq.get() ||
                    statusAtStart != _uiState.value.statusFilter || contextAtStart != _uiState.value.contextFilter
                ) {
                    return@launch
                }
                _uiState.update { s ->
                    val existingIds = s.reports.map { it.id }.toSet()
                    val newItems = res.items.filter { it.id !in existingIds }
                    s.copy(
                        isLoadingMore = false,
                        reports = s.reports + newItems,
                        hasMore = res.hasMore,
                        nextCursor = res.nextCursor,
                    )
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && seq == loadMoreSeq.get()) {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || seq != loadMoreSeq.get()) return@launch
                _uiState.update { it.copy(isLoadingMore = false, error = friendlyError(e, localizer)) }
            }
        }
    }

    fun approveReport(
        reportId: String,
        bonusCredits: Int = 0,
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

        viewModelScope.launch {
            if (isStale(curUid, curGen) || token != activeActionToken.get()) {
                releaseActionGuard(token)
                return@launch
            }
            try {
                val res = adminManagementRepository.approveTesterReport(reportId, bonusCredits)
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch
                if (res.success) {
                    _uiState.update { s ->
                        s.copy(
                            isActionLoading = false,
                            actionMessage = localizer.get(R.string.admin_report_approved_success),
                            reports = s.reports.map {
                                if (it.id == reportId) it.copy(status = "approved") else it
                            },
                        )
                    }
                    refresh()
                } else {
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            error = localizer.get(R.string.admin_report_approved_failed, "Conflict or expired"),
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && token == activeActionToken.get()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_report_approved_failed, friendlyError(e, localizer)),
                    )
                }
            } finally {
                releaseActionGuard(token)
            }
        }
    }

    fun rejectReport(
        reportId: String,
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

        viewModelScope.launch {
            if (isStale(curUid, curGen) || token != activeActionToken.get()) {
                releaseActionGuard(token)
                return@launch
            }
            try {
                val res = adminManagementRepository.rejectTesterReport(reportId, reason)
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch
                if (res.success) {
                    _uiState.update { s ->
                        s.copy(
                            isActionLoading = false,
                            actionMessage = localizer.get(R.string.admin_report_rejected_success),
                            reports = s.reports.map {
                                if (it.id == reportId) it.copy(status = "rejected", rejectionReason = reason) else it
                            },
                        )
                    }
                    refresh()
                } else {
                    _uiState.update {
                        it.copy(
                            isActionLoading = false,
                            error = localizer.get(R.string.admin_report_rejected_failed, "Conflict or expired"),
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (!isStale(curUid, curGen) && token == activeActionToken.get()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (isStale(curUid, curGen) || token != activeActionToken.get()) return@launch
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_report_rejected_failed, friendlyError(e, localizer)),
                    )
                }
            } finally {
                releaseActionGuard(token)
            }
        }
    }
}
