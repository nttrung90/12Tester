package com.ntt.crosstest.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.ntt.crosstest.data.telegram.TelegramAuthUiState
import com.ntt.crosstest.data.telegram.TelegramChatRepository
import com.ntt.crosstest.data.telegram.TelegramChatUiState
import com.ntt.crosstest.data.telegram.TelegramChatViewMode
import com.ntt.crosstest.data.telegram.TelegramConnectionStatus
import com.ntt.crosstest.data.telegram.TelegramMessageUi
import com.ntt.crosstest.data.telegram.TelegramMirrorRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TelegramChatViewModel(
    private val repository: TelegramChatRepository,
    private val mirrorRepository: TelegramMirrorRepository = TelegramMirrorRepository(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val pollIntervalMs: Long = DEFAULT_MIRROR_POLL_INTERVAL_MS,
) : ViewModel() {

    private val vmScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _viewMode = MutableStateFlow(TelegramChatViewMode.READ_ONLY_MIRROR)
    private val _uiState = MutableStateFlow(
        TelegramChatUiState(
            viewMode = TelegramChatViewMode.READ_ONLY_MIRROR,
            isConfigured = repository.isConfigured,
        )
    )
    val uiState: StateFlow<TelegramChatUiState> = _uiState.asStateFlow()

    private var mirrorPollingJob: Job? = null
    private var isScreenForeground = false
    private var hasResolvedInitialMode = false

    init {
        vmScope.launch {
            combine(
                repository.uiState,
                mirrorRepository.uiState,
                _viewMode,
            ) { tdState, mirrorState, mode ->
                Triple(tdState, mirrorState, mode)
            }.collect { (tdState, mirrorState, mode) ->
                var effectiveMode = mode
                if (mode == TelegramChatViewMode.AUTH_FLOW && tdState.authState is TelegramAuthUiState.Ready) {
                    effectiveMode = TelegramChatViewMode.FULL_TDLIB
                    _viewMode.value = effectiveMode
                    stopMirrorPolling()
                } else if (mode == TelegramChatViewMode.FULL_TDLIB && tdState.authState is TelegramAuthUiState.WaitPhoneNumber) {
                    effectiveMode = TelegramChatViewMode.READ_ONLY_MIRROR
                    _viewMode.value = effectiveMode
                    ensureMirrorFeedLoadedAndPoll()
                }

                _uiState.value = when (effectiveMode) {
                    TelegramChatViewMode.READ_ONLY_MIRROR -> TelegramChatUiState(
                        viewMode = TelegramChatViewMode.READ_ONLY_MIRROR,
                        isConfigured = tdState.isConfigured,
                        authState = tdState.authState,
                        connectionStatus = TelegramConnectionStatus.READY,
                        chatInfo = mirrorState.chatInfo,
                        messages = mirrorState.messages,
                        isLoadingHistory = mirrorState.isLoadingInitial,
                        isLoadingMore = mirrorState.isLoadingMore,
                        hasMoreHistory = mirrorState.hasMoreHistory,
                        errorBanner = mirrorState.errorMessage,
                    )
                    TelegramChatViewMode.AUTH_FLOW,
                    TelegramChatViewMode.FULL_TDLIB -> tdState.copy(viewMode = effectiveMode)
                }
            }
        }
    }

    fun onScreenEntered() {
        isScreenForeground = true
        vmScope.launch {
            val loggedIn = repository.hasLoggedInSession()
            hasResolvedInitialMode = true
            if (loggedIn) {
                _viewMode.value = TelegramChatViewMode.FULL_TDLIB
                stopMirrorPolling()
                repository.ensureStarted()
            } else {
                _viewMode.value = TelegramChatViewMode.READ_ONLY_MIRROR
                if (mirrorRepository.uiState.value.messages.isEmpty()) {
                    mirrorRepository.loadInitialFeed()
                } else {
                    mirrorRepository.pollUpdates()
                }
                startMirrorPolling()
            }
        }
    }

    fun onScreenLeft() {
        isScreenForeground = false
        stopMirrorPolling()
        if (_viewMode.value == TelegramChatViewMode.FULL_TDLIB) {
            repository.closeActiveChat()
        }
    }

    fun onAppPause() {
        isScreenForeground = false
        stopMirrorPolling()
    }

    fun onAppResume() {
        isScreenForeground = true
        if (hasResolvedInitialMode && _viewMode.value == TelegramChatViewMode.READ_ONLY_MIRROR) {
            vmScope.launch {
                val mirrorState = mirrorRepository.uiState.value
                if (!mirrorState.isLoadingInitial) {
                    if (mirrorState.messages.isEmpty()) {
                        mirrorRepository.loadInitialFeed()
                    } else {
                        mirrorRepository.pollUpdates()
                    }
                }
                startMirrorPolling()
            }
        }
    }

    fun requestTelegramLogin() {
        hasResolvedInitialMode = true
        stopMirrorPolling()
        _viewMode.value = TelegramChatViewMode.AUTH_FLOW
        if (repository.isConfigured) {
            repository.ensureStarted()
        }
    }

    fun backToReadOnlyMirror() {
        hasResolvedInitialMode = true
        _viewMode.value = TelegramChatViewMode.READ_ONLY_MIRROR
        ensureMirrorFeedLoadedAndPoll()
    }

    fun refreshMirrorFeed() {
        vmScope.launch {
            if (mirrorRepository.uiState.value.messages.isEmpty()) {
                mirrorRepository.loadInitialFeed()
            } else {
                mirrorRepository.pollUpdates()
            }
        }
    }

    fun retryInitialize() {
        repository.retryInitialize()
    }

    fun submitPhoneNumber(countryCode: String, phoneInput: String) {
        repository.sendPhoneNumber(countryCode, phoneInput)
    }

    fun changePhoneNumber() {
        repository.changePhoneNumber()
    }

    fun submitOtpCode(code: String) {
        repository.submitOtpCode(code)
    }

    fun resendOtpCode() {
        repository.resendOtpCode()
    }

    fun submitPassword(password: String) {
        repository.submitPassword(password)
    }

    fun requestPasswordRecovery() {
        repository.requestPasswordRecovery()
    }

    fun backToPasswordEntry() {
        repository.backToPasswordEntry()
    }

    fun submitPasswordRecoveryCode(code: String) {
        repository.submitPasswordRecoveryCode(code)
    }

    fun registerUser(firstName: String, lastName: String) {
        repository.registerUser(firstName, lastName)
    }

    fun onTypingChanged(text: String) {
        if (text.isNotBlank()) {
            repository.sendTypingAction()
        }
    }

    fun sendTextMessage(text: String) {
        repository.sendTextMessage(text)
    }

    fun sendPhotoMessage(localPath: String, caption: String, width: Int = 0, height: Int = 0) {
        repository.sendPhotoMessage(localPath, caption, width, height)
    }

    fun setReplyingTo(message: TelegramMessageUi?) {
        repository.setReplyingTo(message)
    }

    fun setEditingMessage(message: TelegramMessageUi?) {
        repository.setEditingMessage(message)
    }

    fun deleteMessage(messageId: Long, revoke: Boolean = true) {
        repository.deleteMessage(messageId, revoke)
    }

    fun toggleReaction(messageId: Long, emoji: String) {
        repository.toggleReaction(messageId, emoji)
    }

    fun loadMoreHistory() {
        if (_viewMode.value == TelegramChatViewMode.READ_ONLY_MIRROR) {
            val mirrorState = mirrorRepository.uiState.value
            if (mirrorState.isLoadingMore || mirrorState.isLoadingInitial || !mirrorState.hasMoreHistory) {
                return
            }
            vmScope.launch {
                mirrorRepository.loadOlderMessages()
            }
        } else {
            repository.loadMoreHistory()
        }
    }

    fun markMessagesViewed(messageIds: List<Long>) {
        if (_viewMode.value == TelegramChatViewMode.FULL_TDLIB) {
            repository.markMessagesViewed(messageIds)
        }
    }

    fun setSearchActive(active: Boolean) {
        repository.setSearchActive(active)
    }

    fun searchMessages(query: String) {
        repository.searchMessages(query)
    }

    fun downloadFile(fileId: Int) {
        repository.downloadFile(fileId)
    }

    fun logOut() {
        hasResolvedInitialMode = true
        repository.logOut()
        _viewMode.value = TelegramChatViewMode.READ_ONLY_MIRROR
        ensureMirrorFeedLoadedAndPoll()
    }

    fun clearErrorBanner() {
        if (_viewMode.value == TelegramChatViewMode.READ_ONLY_MIRROR) {
            mirrorRepository.clearError()
        } else {
            repository.clearErrorBanner()
        }
    }

    private fun ensureMirrorFeedLoadedAndPoll() {
        vmScope.launch {
            if (mirrorRepository.uiState.value.messages.isEmpty()) {
                mirrorRepository.loadInitialFeed()
            } else {
                mirrorRepository.pollUpdates()
            }
            if (isScreenForeground && _viewMode.value == TelegramChatViewMode.READ_ONLY_MIRROR) {
                startMirrorPolling()
            }
        }
    }

    private fun startMirrorPolling() {
        mirrorPollingJob?.cancel()
        if (!isScreenForeground || _viewMode.value != TelegramChatViewMode.READ_ONLY_MIRROR) return
        mirrorPollingJob = vmScope.launch {
            while (isActive && isScreenForeground && _viewMode.value == TelegramChatViewMode.READ_ONLY_MIRROR) {
                delay(pollIntervalMs)
                if (!isActive || !isScreenForeground || _viewMode.value != TelegramChatViewMode.READ_ONLY_MIRROR) {
                    break
                }
                mirrorRepository.pollUpdates()
            }
        }
    }

    private fun stopMirrorPolling() {
        mirrorPollingJob?.cancel()
        mirrorPollingJob = null
    }

    override fun onCleared() {
        stopMirrorPolling()
        vmScope.cancel()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_MIRROR_POLL_INTERVAL_MS = 15_000L
    }
}
