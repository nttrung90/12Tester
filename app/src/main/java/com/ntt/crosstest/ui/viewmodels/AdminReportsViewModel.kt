package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.ContactReport
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class AdminReportFilter {
    ALL, NEW, REPLIED, RESOLVED
}

data class AdminReportsUiState(
    val isLoading: Boolean = true,
    val isActionLoading: Boolean = false,
    val allReports: List<ContactReport> = emptyList(),
    val filteredReports: List<ContactReport> = emptyList(),
    val filter: AdminReportFilter = AdminReportFilter.ALL,
    val error: String? = null,
    val actionMessage: String? = null,
    val currentAdminId: String? = null,
    val sessionGeneration: Long = 0L,
)

class AdminReportsViewModel(
    private val campaignRepository: CampaignRepository,
    private val localizer: Localizer,
    private val authRepository: AuthRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminReportsUiState())
    val uiState: StateFlow<AdminReportsUiState> = _uiState.asStateFlow()

    private val reportsSeq = AtomicLong(0L)
    private val actionSeq = AtomicLong(0L)

    private fun effectiveUserId(): String? = authRepository?.currentUserId() ?: campaignRepository.currentUserId()
    private val effectiveSessionStatus get() = authRepository?.sessionStatus ?: campaignRepository.sessionStatus

    private var activeUserId: String? = effectiveUserId()
    private var activeGeneration: Long = SessionCacheManager.currentGeneration()

    init {
        _uiState.update { it.copy(currentAdminId = activeUserId, sessionGeneration = activeGeneration) }
        observeSessionStatus()
        refresh(forceRefresh = false)
    }

    private fun reconcileSession(): Pair<String?, Long> {
        val curUid = effectiveUserId()
        val curGen = SessionCacheManager.currentGeneration()
        if (activeUserId != curUid || activeGeneration != curGen) {
            handleSessionReset(curUid, curGen)
        }
        return Pair(curUid, curGen)
    }

    private fun observeSessionStatus() {
        viewModelScope.launch {
            effectiveSessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val newUid = effectiveUserId()
                        val newGen = SessionCacheManager.currentGeneration()
                        if (newUid != activeUserId || newGen != activeGeneration) {
                            handleSessionReset(newUid, newGen)
                            if (newUid != null) {
                                refresh(forceRefresh = true)
                            }
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        val newGen = SessionCacheManager.currentGeneration()
                        if (activeUserId != null || activeGeneration != newGen) {
                            handleSessionReset(null, newGen)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleSessionReset(newUid: String?, newGen: Long) {
        activeUserId = newUid
        activeGeneration = newGen
        reportsSeq.incrementAndGet()
        actionSeq.incrementAndGet()

        _uiState.update {
            AdminReportsUiState(
                currentAdminId = newUid,
                sessionGeneration = newGen,
                isLoading = false,
                isActionLoading = false,
                allReports = emptyList(),
                filteredReports = emptyList(),
                filter = AdminReportFilter.ALL,
                error = null,
                actionMessage = null,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    fun refresh(forceRefresh: Boolean = false) {
        val (reqUid, reqGen) = reconcileSession()
        val shouldShowLoading = forceRefresh || _uiState.value.allReports.isEmpty()
        if (shouldShowLoading) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        val seq = reportsSeq.incrementAndGet()

        viewModelScope.launch {
            if (seq != reportsSeq.get() || reqUid != effectiveUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                val reports = campaignRepository.adminGetContactReports()
                if (seq != reportsSeq.get() || reqUid != effectiveUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        allReports = reports,
                        filteredReports = filterReports(reports, state.filter),
                    )
                }
            } catch (e: CancellationException) {
                if (seq == reportsSeq.get() && reqUid == effectiveUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != reportsSeq.get() || reqUid != effectiveUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = if (forceRefresh || it.allReports.isEmpty()) friendlyError(e, localizer) else null,
                    )
                }
            }
        }
    }

    fun setFilter(filter: AdminReportFilter) {
        _uiState.update { state ->
            state.copy(
                filter = filter,
                filteredReports = filterReports(state.allReports, filter),
            )
        }
    }

    private fun filterReports(reports: List<ContactReport>, filter: AdminReportFilter): List<ContactReport> {
        return when (filter) {
            AdminReportFilter.ALL -> reports
            AdminReportFilter.NEW -> reports.filter { it.isNew }
            AdminReportFilter.REPLIED -> reports.filter { it.isReplied }
            AdminReportFilter.RESOLVED -> reports.filter { it.isResolved }
        }
    }

    fun replyTo(reportId: String, message: String) {
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isActionLoading) return
        _uiState.update { it.copy(isActionLoading = true, error = null) }
        val seq = actionSeq.incrementAndGet()

        viewModelScope.launch {
            if (seq != actionSeq.get() || reqUid != effectiveUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                campaignRepository.adminReplyContactReport(reportId, message.trim())
                if (seq != actionSeq.get() || reqUid != effectiveUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        actionMessage = localizer.get(R.string.admin_report_reply_success),
                    )
                }
                reportsSeq.incrementAndGet()
                refresh(forceRefresh = true)
            } catch (e: CancellationException) {
                if (seq == actionSeq.get() && reqUid == effectiveUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != actionSeq.get() || reqUid != effectiveUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_action_failed_format, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }

    fun setResolved(reportId: String, resolved: Boolean) {
        val (reqUid, reqGen) = reconcileSession()
        if (_uiState.value.isActionLoading) return
        _uiState.update { it.copy(isActionLoading = true, error = null) }
        val seq = actionSeq.incrementAndGet()

        viewModelScope.launch {
            if (seq != actionSeq.get() || reqUid != effectiveUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                return@launch
            }
            try {
                campaignRepository.adminUpdateContactReportStatus(reportId, resolved)
                if (seq != actionSeq.get() || reqUid != effectiveUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        actionMessage = localizer.get(R.string.admin_report_status_updated),
                    )
                }
                reportsSeq.incrementAndGet()
                refresh(forceRefresh = true)
            } catch (e: CancellationException) {
                if (seq == actionSeq.get() && reqUid == effectiveUserId() && reqGen == SessionCacheManager.currentGeneration()) {
                    _uiState.update { it.copy(isActionLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                if (seq != actionSeq.get() || reqUid != effectiveUserId() || reqGen != SessionCacheManager.currentGeneration()) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        error = localizer.get(R.string.admin_action_failed_format, friendlyError(e, localizer)),
                    )
                }
            }
        }
    }
}
