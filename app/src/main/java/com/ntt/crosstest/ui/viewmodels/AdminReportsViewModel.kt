package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.ContactReport
import com.ntt.crosstest.data.Localizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

class AdminReportsViewModel(
    private val campaignRepository: CampaignRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminReportsUiState())
    val uiState: StateFlow<AdminReportsUiState> = _uiState.asStateFlow()

    init {
        refresh(forceRefresh = false)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearActionMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }

    fun refresh(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            if (forceRefresh || _uiState.value.allReports.isEmpty()) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val reports = campaignRepository.adminGetContactReports()
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        allReports = reports,
                        filteredReports = filterReports(reports, state.filter),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = if (_uiState.value.allReports.isEmpty()) friendlyError(e, localizer) else null,
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
        if (_uiState.value.isActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                campaignRepository.adminReplyContactReport(reportId, message.trim())
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        actionMessage = localizer.get(R.string.admin_report_reply_success),
                    )
                }
                refresh()
            } catch (e: Exception) {
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
        if (_uiState.value.isActionLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true, error = null) }
            try {
                campaignRepository.adminUpdateContactReportStatus(reportId, resolved)
                _uiState.update {
                    it.copy(
                        isActionLoading = false,
                        actionMessage = localizer.get(R.string.admin_report_status_updated),
                    )
                }
                refresh()
            } catch (e: Exception) {
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
