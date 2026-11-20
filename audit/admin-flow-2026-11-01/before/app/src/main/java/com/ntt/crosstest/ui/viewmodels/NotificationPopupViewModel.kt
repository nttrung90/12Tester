package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.NotificationEvent
import com.ntt.crosstest.data.PendingAckStore
import com.ntt.crosstest.data.UserNotification
import com.ntt.crosstest.util.AppForegroundTracker
import com.ntt.crosstest.util.NotificationPopupBlockerCoordinator
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

sealed interface NotificationPopupUiState {
    object Idle : NotificationPopupUiState

    sealed interface Active : NotificationPopupUiState {
        val notification: UserNotification
        val displayCount: Int
        val sessionId: String
        val isAwaitingAck: Boolean
        val errorMessage: String?
    }

    data class Showing(
        override val notification: UserNotification,
        override val displayCount: Int,
        override val sessionId: String,
        override val isAwaitingAck: Boolean = false,
        override val errorMessage: String? = null,
    ) : Active

    data class Held(
        override val notification: UserNotification,
        override val displayCount: Int,
        override val sessionId: String,
        override val isAwaitingAck: Boolean = false,
        override val errorMessage: String? = null,
    ) : Active
}

class NotificationPopupViewModel(
    private val supabase: SupabaseClient,
    private val campaignRepository: CampaignRepository,
    private val pendingAckStore: PendingAckStore,
    private val foregroundTracker: AppForegroundTracker = AppForegroundTracker,
    private val blockerCoordinator: NotificationPopupBlockerCoordinator = NotificationPopupBlockerCoordinator,
    private val pollIntervalMs: Long = 60_000L,
    private val autoStartPolling: Boolean = true,
) : ViewModel() {

    private val _uiState = MutableStateFlow<NotificationPopupUiState>(NotificationPopupUiState.Idle)
    val uiState: StateFlow<NotificationPopupUiState> = _uiState.asStateFlow()

    private var activeUserId: String? = supabase.auth.currentUserOrNull()?.id
    private val consumedSessions = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var inFlightClaimSession: String? = null
    private val claimMutex = Mutex()
    private val syncMutex = Mutex()
    private var pollingJob: Job? = null
    private var syncJob: Job? = null

    init {
        observeAuthStatus()
        observeExternalNotificationEvents()
        observeBlockers()
        if (autoStartPolling) {
            observeForegroundLifecycle()
        }
    }

    private fun observeAuthStatus() {
        viewModelScope.launch {
            supabase.auth.sessionStatus.collectLatest { status ->
                when (status) {
                    is SessionStatus.Initializing -> {
                        // Intermediate initialization state, do not treat as logout
                    }
                    is SessionStatus.NotAuthenticated -> {
                        cancelAllJobs()
                        activeUserId = null
                        consumedSessions.clear()
                        _uiState.value = NotificationPopupUiState.Idle
                    }
                    is SessionStatus.Authenticated -> {
                        val newUid = status.session.user?.id ?: supabase.auth.currentUserOrNull()?.id
                        if (newUid != null && newUid != activeUserId) {
                            cancelAllJobs()
                            activeUserId = newUid
                            consumedSessions.clear()
                            _uiState.value = NotificationPopupUiState.Idle
                            restartPollingLoop()
                        } else if (newUid != null) {
                            activeUserId = newUid
                            if (pollingJob?.isActive != true) {
                                restartPollingLoop()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeExternalNotificationEvents() {
        viewModelScope.launch {
            campaignRepository.notificationEvents.collect { event ->
                val currentUid = activeUserId ?: supabase.auth.currentUserOrNull()?.id
                if (event.ownerId != currentUid) return@collect

                val current = _uiState.value as? NotificationPopupUiState.Active ?: return@collect
                val notif = current.notification
                when (event) {
                    is NotificationEvent.MarkedRead -> {
                        if (notif.id == event.id) {
                            consumedSessions.add(current.sessionId)
                            foregroundTracker.foregroundSessionId.value?.let { consumedSessions.add(it) }
                            _uiState.value = NotificationPopupUiState.Idle
                        }
                    }
                    is NotificationEvent.MarkedAllRead -> {
                        consumedSessions.add(current.sessionId)
                        foregroundTracker.foregroundSessionId.value?.let { consumedSessions.add(it) }
                        _uiState.value = NotificationPopupUiState.Idle
                    }
                    is NotificationEvent.Deleted -> {
                        if (notif.id == event.id) {
                            consumedSessions.add(current.sessionId)
                            foregroundTracker.foregroundSessionId.value?.let { consumedSessions.add(it) }
                            _uiState.value = NotificationPopupUiState.Idle
                        }
                    }
                }
            }
        }
    }

    private fun observeForegroundLifecycle() {
        viewModelScope.launch {
            foregroundTracker.isForeground.collectLatest { isFg ->
                if (isFg) {
                    val uid = activeUserId
                    if (uid != null) {
                        viewModelScope.launch { syncPendingAcks(uid) }
                    }
                    restartPollingLoop()
                } else {
                    // App went to background: pause polling, but keep current Showing/Held popup
                    pollingJob?.cancel()
                    pollingJob = null
                }
            }
        }
    }

    private fun observeBlockers() {
        viewModelScope.launch {
            blockerCoordinator.isBlocked.collectLatest { isBlocked ->
                if (isBlocked) {
                    // Blocker appeared: if currently showing, pause into Held state
                    val current = _uiState.value as? NotificationPopupUiState.Showing
                    if (current != null) {
                        _uiState.value = NotificationPopupUiState.Held(
                            notification = current.notification,
                            displayCount = current.displayCount,
                            sessionId = current.sessionId,
                            isAwaitingAck = current.isAwaitingAck,
                            errorMessage = current.errorMessage
                        )
                    }
                } else {
                    // Blocker cleared: if previously Held, resume into Showing state
                    val held = _uiState.value as? NotificationPopupUiState.Held
                    if (held != null) {
                        _uiState.value = NotificationPopupUiState.Showing(
                            notification = held.notification,
                            displayCount = held.displayCount,
                            sessionId = held.sessionId,
                            isAwaitingAck = held.isAwaitingAck,
                            errorMessage = held.errorMessage
                        )
                    } else if (autoStartPolling && foregroundTracker.isForeground.value && activeUserId != null) {
                        restartPollingLoop()
                    }
                }
            }
        }
    }

    fun restartPollingLoop() {
        pollingJob?.cancel()
        val uid = activeUserId ?: return
        if (!foregroundTracker.isForeground.value) return

        pollingJob = viewModelScope.launch {
            while (true) {
                try {
                    tick(uid)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Suppress network / transient errors and retry after pollIntervalMs
                }
                delay(pollIntervalMs)
            }
        }
    }

    /**
     * Dedicated outbox synchronization for pending acknowledgments.
     * Decoupled from popup quotas and blocker state (F1).
     */
    suspend fun syncPendingAcks(expectedUid: String): Boolean {
        if (!syncMutex.tryLock()) return false
        try {
            val currentUid = supabase.auth.currentUserOrNull()?.id ?: return false
            if (currentUid != expectedUid) return false

            val pending = pendingAckStore.getPendingAcksForUser(currentUid)
            if (pending.isEmpty()) return true

            var allSuccess = true
            for (id in pending) {
                if (supabase.auth.currentUserOrNull()?.id != currentUid) return false
                try {
                    val result = campaignRepository.acknowledgeNotification(id)
                    if (result.success || result.status in setOf("read", "already_read", "deleted")) {
                        pendingAckStore.removePendingAck(currentUid, id)
                    } else {
                        allSuccess = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    allSuccess = false
                    break
                }
            }
            return allSuccess && pendingAckStore.getPendingAcksForUser(currentUid).isEmpty()
        } finally {
            syncMutex.unlock()
        }
    }

    suspend fun tick(expectedUid: String) {
        // F1: Always retry pending acknowledgments regardless of session consumption or UI blocker
        syncPendingAcks(expectedUid)

        if (!claimMutex.tryLock()) return
        try {
            val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
            if (currentUid != expectedUid) return
            if (!foregroundTracker.isForeground.value) return

            // F2: Never claim while pending acknowledgments remain unsynced for current user
            if (pendingAckStore.getPendingAcksForUser(currentUid).isNotEmpty()) {
                return
            }

            val sessionId = foregroundTracker.foregroundSessionId.value ?: return

            // 1. If currently showing or held, perform lightweight remote status check (cross-device sync)
            val currentPopup = _uiState.value as? NotificationPopupUiState.Active

            if (currentPopup != null) {
                // F3: Mark current session consumed as it is actively presenting / carrying this popup
                consumedSessions.add(currentPopup.sessionId)
                consumedSessions.add(sessionId)

                try {
                    val status = campaignRepository.getNotificationStatus(currentPopup.notification.id)
                    if (!status.exists || status.isRead) {
                        _uiState.value = NotificationPopupUiState.Idle
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
                return
            }

            // Blocker check for new claim
            if (blockerCoordinator.isBlocked.value) return

            // 2. If this foreground session has already claimed or dismissed its popup, do nothing
            if (consumedSessions.contains(sessionId)) {
                return
            }
            if (inFlightClaimSession == sessionId) {
                return
            }
            inFlightClaimSession = sessionId
            try {
                // Verify conditions right before network call
                if (pendingAckStore.getPendingAcksForUser(currentUid).isNotEmpty()) return
                if (_uiState.value !is NotificationPopupUiState.Idle) return
                if (consumedSessions.contains(sessionId)) return

                // 3. Claim from server
                val result = campaignRepository.claimNotificationPopup(sessionId)

                // Verify UID and session boundary before publishing response
                if (supabase.auth.currentUserOrNull()?.id != currentUid) return
                if (foregroundTracker.foregroundSessionId.value != sessionId) return

                when (result.status) {
                    "claimed" -> {
                        val notif = result.notification
                        if (notif != null) {
                            // G4: If notification was already read or pending locally, do not show stale popup!
                            if (campaignRepository.isNotificationLocallyRead(currentUid, notif.id) ||
                                pendingAckStore.isPendingAck(currentUid, notif.id)
                            ) {
                                consumedSessions.add(sessionId)
                                syncPendingAcks(currentUid)
                                _uiState.value = NotificationPopupUiState.Idle
                                return
                            }

                            consumedSessions.add(sessionId)
                            // F4: If a blocker appeared during the network roundtrip, hold instead of showing over the blocker
                            if (blockerCoordinator.isBlocked.value) {
                                _uiState.value = NotificationPopupUiState.Held(
                                    notification = notif,
                                    displayCount = result.displayCount ?: 1,
                                    sessionId = sessionId,
                                )
                            } else {
                                _uiState.value = NotificationPopupUiState.Showing(
                                    notification = notif,
                                    displayCount = result.displayCount ?: 1,
                                    sessionId = sessionId,
                                )
                            }
                        }
                    }
                    "stopped", "blocked" -> {
                        consumedSessions.add(sessionId)
                    }
                    "none" -> {
                        // No candidate currently, session NOT consumed so later notification in this session can be detected
                    }
                }
            } finally {
                inFlightClaimSession = null
            }
        } finally {
            claimMutex.unlock()
        }
    }

    fun onDismissLater() {
        val current = _uiState.value as? NotificationPopupUiState.Active ?: return
        consumedSessions.add(current.sessionId)
        foregroundTracker.foregroundSessionId.value?.let { consumedSessions.add(it) }
        _uiState.value = NotificationPopupUiState.Idle
    }

    fun onMarkAsRead(): Job? {
        val current = _uiState.value as? NotificationPopupUiState.Active ?: return null
        if (current.isAwaitingAck) return null // Double-tap guard

        val uid = activeUserId ?: supabase.auth.currentUserOrNull()?.id ?: return null
        val notifId = current.notification.id

        _uiState.value = when (current) {
            is NotificationPopupUiState.Showing -> current.copy(isAwaitingAck = true, errorMessage = null)
            is NotificationPopupUiState.Held -> current.copy(isAwaitingAck = true, errorMessage = null)
        }

        // 1. Save durable local pending acknowledgment
        val saved = pendingAckStore.recordPendingAck(uid, notifId)
        if (!saved) {
            // Local persistence failure -> keep popup open and surface error
            _uiState.value = when (current) {
                is NotificationPopupUiState.Showing -> current.copy(isAwaitingAck = false, errorMessage = "local_save_failed")
                is NotificationPopupUiState.Held -> current.copy(isAwaitingAck = false, errorMessage = "local_save_failed")
            }
            return null
        }

        // G1: Immediately notify repository of local read intent before network sync!
        campaignRepository.notifyNotificationLocallyRead(uid, notifId)

        // 2. Dismiss popup immediately & mark both sessions consumed (F3)
        consumedSessions.add(current.sessionId)
        foregroundTracker.foregroundSessionId.value?.let { consumedSessions.add(it) }
        _uiState.value = NotificationPopupUiState.Idle

        // 3. Launch background sync to server
        val job = viewModelScope.launch {
            syncPendingAcks(uid)
        }
        syncJob = job
        return job
    }

    private fun cancelAllJobs() {
        pollingJob?.cancel()
        pollingJob = null
        syncJob?.cancel()
        syncJob = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelAllJobs()
    }
}
