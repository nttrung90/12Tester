package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.AdminCampaignItem
import com.ntt.crosstest.data.AdminDateTimeCursor
import com.ntt.crosstest.data.AdminManagementRepository
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class AdminCampaignsUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val campaigns: List<AdminCampaignItem> = emptyList(),
    val query: String = "",
    val statusFilter: String? = null,
    val hasMore: Boolean = false,
    val nextCursor: AdminDateTimeCursor? = null,
    val error: String? = null,
    val currentAdminId: String? = null,
    val sessionGeneration: Long = 0L,
)

class AdminCampaignsViewModel(
    private val adminManagementRepository: AdminManagementRepository,
    private val authRepository: AuthRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminCampaignsUiState())
    val uiState: StateFlow<AdminCampaignsUiState> = _uiState.asStateFlow()

    private val reqSeq = AtomicLong(0L)
    private val loadMoreSeq = AtomicLong(0L)
    private var searchJob: Job? = null

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
        searchJob?.cancel()

        _uiState.update {
            AdminCampaignsUiState(
                currentAdminId = newUid,
                sessionGeneration = newGen,
                isLoading = false,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setQuery(q: String) {
        _uiState.update { it.copy(query = q, hasMore = false, nextCursor = null) }
        loadMoreSeq.incrementAndGet()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            refresh()
        }
    }

    fun setStatusFilter(status: String?) {
        _uiState.update { it.copy(statusFilter = status, hasMore = false, nextCursor = null) }
        loadMoreSeq.incrementAndGet()
        searchJob?.cancel()
        refresh()
    }

    fun refresh() {
        val (curUid, curGen) = reconcileSession()
        val seq = reqSeq.incrementAndGet()
        loadMoreSeq.incrementAndGet()
        val queryAtStart = _uiState.value.query
        val statusAtStart = _uiState.value.statusFilter

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != reqSeq.get()) return@launch
            try {
                val res = adminManagementRepository.searchCampaigns(
                    query = queryAtStart,
                    status = statusAtStart,
                    limit = 50,
                )
                if (isStale(curUid, curGen) || seq != reqSeq.get() ||
                    queryAtStart != _uiState.value.query || statusAtStart != _uiState.value.statusFilter
                ) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        campaigns = res.items,
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
        val queryAtStart = state.query
        val statusAtStart = state.statusFilter

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != loadMoreSeq.get()) return@launch
            try {
                val res = adminManagementRepository.searchCampaigns(
                    query = queryAtStart,
                    status = statusAtStart,
                    limit = 50,
                    cursorCreatedAt = cursor.createdAt,
                    cursorId = cursor.id,
                )
                if (isStale(curUid, curGen) || seq != loadMoreSeq.get() ||
                    queryAtStart != _uiState.value.query || statusAtStart != _uiState.value.statusFilter
                ) {
                    return@launch
                }
                _uiState.update { s ->
                    val existingIds = s.campaigns.map { it.id }.toSet()
                    val newItems = res.items.filter { it.id !in existingIds }
                    s.copy(
                        isLoadingMore = false,
                        campaigns = s.campaigns + newItems,
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
}
