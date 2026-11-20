package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.AdminActionEvent
import com.ntt.crosstest.data.AdminDateTimeCursor
import com.ntt.crosstest.data.AdminManagementRepository
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
import java.util.concurrent.atomic.AtomicLong

data class AdminAuditUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val events: List<AdminActionEvent> = emptyList(),
    val actionFilter: String? = null,
    val targetTypeFilter: String? = null,
    val hasMore: Boolean = false,
    val nextCursor: AdminDateTimeCursor? = null,
    val error: String? = null,
    val currentAdminId: String? = null,
    val sessionGeneration: Long = 0L,
)

class AdminAuditViewModel(
    private val adminManagementRepository: AdminManagementRepository,
    private val authRepository: AuthRepository,
    private val localizer: Localizer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminAuditUiState())
    val uiState: StateFlow<AdminAuditUiState> = _uiState.asStateFlow()

    private val reqSeq = AtomicLong(0L)
    private val loadMoreSeq = AtomicLong(0L)

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

        _uiState.update {
            AdminAuditUiState(
                currentAdminId = newUid,
                sessionGeneration = newGen,
                isLoading = false,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setActionFilter(action: String?) {
        _uiState.update { it.copy(actionFilter = action, hasMore = false, nextCursor = null) }
        loadMoreSeq.incrementAndGet()
        refresh()
    }

    fun setTargetTypeFilter(targetType: String?) {
        _uiState.update { it.copy(targetTypeFilter = targetType, hasMore = false, nextCursor = null) }
        loadMoreSeq.incrementAndGet()
        refresh()
    }

    fun refresh() {
        val (curUid, curGen) = reconcileSession()
        val seq = reqSeq.incrementAndGet()
        loadMoreSeq.incrementAndGet()
        val actionAtStart = _uiState.value.actionFilter
        val targetAtStart = _uiState.value.targetTypeFilter

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != reqSeq.get()) return@launch
            try {
                val res = adminManagementRepository.getActionEvents(
                    limit = 50,
                    action = actionAtStart,
                    targetType = targetAtStart,
                )
                if (isStale(curUid, curGen) || seq != reqSeq.get() ||
                    actionAtStart != _uiState.value.actionFilter || targetAtStart != _uiState.value.targetTypeFilter
                ) {
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        events = res.items,
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
        val actionAtStart = state.actionFilter
        val targetAtStart = state.targetTypeFilter

        viewModelScope.launch {
            if (isStale(curUid, curGen) || seq != loadMoreSeq.get()) return@launch
            try {
                val res = adminManagementRepository.getActionEvents(
                    limit = 50,
                    cursorCreatedAt = cursor.createdAt,
                    cursorId = cursor.id,
                    action = actionAtStart,
                    targetType = targetAtStart,
                )
                if (isStale(curUid, curGen) || seq != loadMoreSeq.get() ||
                    actionAtStart != _uiState.value.actionFilter || targetAtStart != _uiState.value.targetTypeFilter
                ) {
                    return@launch
                }
                _uiState.update { s ->
                    val existingIds = s.events.map { it.id }.toSet()
                    val newItems = res.items.filter { it.id !in existingIds }
                    s.copy(
                        isLoadingMore = false,
                        events = s.events + newItems,
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
