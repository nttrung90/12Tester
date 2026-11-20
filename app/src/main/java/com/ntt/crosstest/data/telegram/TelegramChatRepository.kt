package com.ntt.crosstest.data.telegram

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.util.AppForegroundTracker
import com.ntt.crosstest.util.CommunityLinks
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val Context.telegramPrefsDataStore by preferencesDataStore(name = "telegram_chat_prefs")

interface TelegramChatIdCacheStore {
    suspend fun getCachedChatId(): Long
    suspend fun setCachedChatId(chatId: Long)
    suspend fun isTelegramLoggedIn(): Boolean = false
    suspend fun setTelegramLoggedIn(loggedIn: Boolean) {}
    suspend fun clear()
}

class DataStoreTelegramChatIdCacheStore(
    private val context: Context,
) : TelegramChatIdCacheStore {
    private val cachedChatIdKey = longPreferencesKey("cached_telegram_chat_id")
    private val hasLoggedInKey = booleanPreferencesKey("telegram_has_logged_in")

    override suspend fun getCachedChatId(): Long =
        context.telegramPrefsDataStore.data.map { prefs -> prefs[cachedChatIdKey] ?: 0L }.first()

    override suspend fun setCachedChatId(chatId: Long) {
        context.telegramPrefsDataStore.edit { prefs ->
            prefs[cachedChatIdKey] = chatId
        }
    }

    override suspend fun isTelegramLoggedIn(): Boolean =
        context.telegramPrefsDataStore.data.map { prefs -> prefs[hasLoggedInKey] ?: false }.first()

    override suspend fun setTelegramLoggedIn(loggedIn: Boolean) {
        context.telegramPrefsDataStore.edit { prefs ->
            prefs[hasLoggedInKey] = loggedIn
        }
    }

    override suspend fun clear() {
        context.telegramPrefsDataStore.edit { prefs ->
            prefs.remove(cachedChatIdKey)
            prefs[hasLoggedInKey] = false
        }
    }
}

class TelegramChatRepository(
    private val context: Context,
    private val apiId: Int = BuildConfig.TELEGRAM_API_ID,
    private val apiHash: String = BuildConfig.TELEGRAM_API_HASH,
    private val gateway: TdLibGateway = TdLibClientWrapper(),
    private val chatIdCacheStore: TelegramChatIdCacheStore = DataStoreTelegramChatIdCacheStore(context),
    private val localizer: Localizer = Localizer(context),
    private val groupInviteUrl: String = CommunityLinks.TELEGRAM_GROUP_URL,
    private val appVersionName: String = BuildConfig.VERSION_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val suggestedDisplayNameProvider: () -> String? = {
        runCatching { SessionCacheManager.loadSync(context)?.userProfile?.displayName }.getOrNull()
    },
    private val observeForeground: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    val isConfigured: Boolean = apiId > 0 && apiHash.isNotBlank()

    private val _uiState = MutableStateFlow(
        TelegramChatUiState(
            isConfigured = isConfigured,
            authState = if (isConfigured) {
                TelegramAuthUiState.Initializing
            } else {
                TelegramAuthUiState.Error(localizer.get(R.string.telegram_missing_config_title))
            },
        )
    )
    val uiState: StateFlow<TelegramChatUiState> = _uiState.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val userCache = ConcurrentHashMap<Long, TdApi.User>()
    private val rawMessages = LinkedHashMap<Long, TdApi.Message>()
    private val filePaths = ConcurrentHashMap<Int, String>()
    private val downloadingFileIds = ConcurrentHashMap.newKeySet<Int>()
    private val pendingUserFetches = ConcurrentHashMap.newKeySet<Long>()
    private val viewedMessageIds = ConcurrentHashMap.newKeySet<Long>()
    private val completedTempMessageIds = HashSet<Long>()

    private var updatesJob: Job? = null
    private var foregroundJob: Job? = null
    private var countdownJob: Job? = null
    private var resolveGroupJob: Job? = null

    @Volatile
    private var started = false

    @Volatile
    private var activeChatId: Long = 0L

    @Volatile
    private var watchedChatId: Long = 0L

    @Volatile
    private var isChatScreenActive = false

    @Volatile
    private var lastTypingSentMs: Long = 0L

    @Volatile
    private var isLoggingOut = false

    suspend fun hasLoggedInSession(): Boolean {
        if (!isConfigured) return false
        if (_uiState.value.authState is TelegramAuthUiState.Ready) return true
        return runCatching { chatIdCacheStore.isTelegramLoggedIn() }.getOrDefault(false)
    }

    fun startUnreadWatcherIfLoggedIn() {
        if (!isConfigured) return
        scope.launch {
            val loggedIn = runCatching { chatIdCacheStore.isTelegramLoggedIn() }.getOrDefault(false)
            if (!loggedIn) return@launch
            val alreadyStarted = synchronized(this@TelegramChatRepository) {
                if (started) {
                    true
                } else {
                    isChatScreenActive = false
                    startGatewayInternalLocked()
                    false
                }
            }
            if (alreadyStarted) {
                val targetId = activeChatId.takeIf { it != 0L }
                    ?: watchedChatId.takeIf { it != 0L }
                    ?: runCatching { chatIdCacheStore.getCachedChatId() }.getOrDefault(0L)
                if (targetId != 0L && _uiState.value.authState is TelegramAuthUiState.Ready) {
                    runCatching { gateway.send(TdApi.GetChat(targetId)) }.onSuccess { chat ->
                        watchedChatId = chat.id
                        if (!isChatScreenActive) {
                            _unreadCount.value = chat.unreadCount.coerceAtLeast(0)
                        }
                    }
                }
            }
        }
    }

    fun ensureStarted() {
        if (!isConfigured) {
            _uiState.update { it.copy(isConfigured = false) }
            return
        }
        synchronized(this) {
            isChatScreenActive = true
            if (started) {
                if (activeChatId != 0L) {
                    scope.launch {
                        runCatching { gateway.send(TdApi.OpenChat(activeChatId)) }
                        runCatching {
                            gateway.send(
                                TdApi.SetOption(
                                    "online",
                                    TdApi.OptionValueBoolean(AppForegroundTracker.isForeground.value),
                                )
                            )
                        }
                        val latestMsgId = synchronized(rawMessages) { rawMessages.keys.maxOrNull() }
                        if (latestMsgId != null && latestMsgId > 0L) {
                            markChatReadOnOpen(activeChatId, listOf(latestMsgId))
                        } else {
                            _unreadCount.value = 0
                        }
                    }
                } else if (watchedChatId != 0L) {
                    resolveGroupJob?.cancel()
                    resolveGroupJob = scope.launch {
                        resolveAndOpenGroupChat()
                    }
                } else {
                    val currentAuth = _uiState.value.authState
                    if (currentAuth is TelegramAuthUiState.Error || currentAuth is TelegramAuthUiState.JoinRequestSent) {
                        recoverFromCurrentAuthorizationState()
                    }
                }
                return
            }
            startGatewayInternalLocked()
        }
    }

    private fun startGatewayInternalLocked() {
        started = true
        if (updatesJob == null) {
            updatesJob = scope.launch {
                gateway.updates.collect { update ->
                    handleUpdate(update)
                }
            }
        }
        if (observeForeground && foregroundJob == null) {
            foregroundJob = scope.launch {
                AppForegroundTracker.isForeground.drop(1).collect { isFg ->
                    if (_uiState.value.authState is TelegramAuthUiState.Ready) {
                        runCatching {
                            gateway.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(isFg)))
                        }
                    }
                }
            }
        }
        gateway.start()
    }

    fun retryInitialize() {
        _uiState.update {
            it.copy(
                authState = TelegramAuthUiState.Initializing,
                errorBanner = null,
            )
        }
        val wasStarted = synchronized(this) { started }
        if (wasStarted) {
            recoverFromCurrentAuthorizationState()
        } else {
            synchronized(this) { activeChatId = 0L }
            ensureStarted()
        }
    }

    private fun recoverFromCurrentAuthorizationState() {
        scope.launch {
            val stateResult = runCatching { gateway.send(TdApi.GetAuthorizationState()) }
            stateResult.onSuccess { authState ->
                handleAuthorizationState(authState)
            }.onFailure {
                synchronized(this@TelegramChatRepository) {
                    started = false
                    activeChatId = 0L
                }
                gateway.close()
                ensureStarted()
            }
        }
    }

    fun sendPhoneNumber(countryCode: String, rawPhoneInput: String) {
        val normalized = normalizePhoneNumber(countryCode, rawPhoneInput)
        if (normalized == null) {
            _uiState.update { state ->
                val current = state.authState as? TelegramAuthUiState.WaitPhoneNumber
                    ?: TelegramAuthUiState.WaitPhoneNumber(countryCode, rawPhoneInput)
                state.copy(
                    authState = current.copy(
                        isLoading = false,
                        error = localizer.get(R.string.telegram_err_empty_phone),
                    )
                )
            }
            return
        }
        _uiState.update { state ->
            val current = state.authState as? TelegramAuthUiState.WaitPhoneNumber
                ?: TelegramAuthUiState.WaitPhoneNumber(countryCode, rawPhoneInput)
            state.copy(authState = current.copy(isLoading = true, error = null))
        }
        scope.launch {
            val settings = TdApi.PhoneNumberAuthenticationSettings(
                false, // allowFlashCall
                false, // allowMissedCall
                false, // isCurrentPhoneNumber
                false, // hasUnknownPhoneNumber
                true,  // allowSmsRetrieverApi
                null,  // firebaseAuthenticationSettings
                emptyArray(), // authenticationTokens
            )
            runCatching {
                gateway.send(TdApi.SetAuthenticationPhoneNumber(normalized, settings))
            }.onFailure { err ->
                val message = translateError(err)
                _uiState.update { state ->
                    val current = state.authState as? TelegramAuthUiState.WaitPhoneNumber
                        ?: TelegramAuthUiState.WaitPhoneNumber(countryCode, rawPhoneInput)
                    state.copy(authState = current.copy(isLoading = false, error = message))
                }
            }
        }
    }

    fun changePhoneNumber() {
        countdownJob?.cancel()
        _uiState.update { state ->
            state.copy(
                authState = TelegramAuthUiState.WaitPhoneNumber(),
            )
        }
    }

    fun submitOtpCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { state ->
            val current = state.authState as? TelegramAuthUiState.WaitCode ?: return@update state
            state.copy(authState = current.copy(isLoading = true, error = null))
        }
        scope.launch {
            runCatching {
                gateway.send(TdApi.CheckAuthenticationCode(trimmed))
            }.onFailure { err ->
                val message = translateError(err)
                _uiState.update { state ->
                    val current = state.authState as? TelegramAuthUiState.WaitCode ?: return@update state
                    state.copy(authState = current.copy(isLoading = false, error = message))
                }
            }
        }
    }

    fun resendOtpCode() {
        _uiState.update { state ->
            val current = state.authState as? TelegramAuthUiState.WaitCode ?: return@update state
            state.copy(authState = current.copy(isLoading = true, error = null))
        }
        scope.launch {
            runCatching {
                gateway.send(TdApi.ResendAuthenticationCode(null))
            }.onFailure { err ->
                val message = translateError(err)
                _uiState.update { state ->
                    val current = state.authState as? TelegramAuthUiState.WaitCode ?: return@update state
                    state.copy(authState = current.copy(isLoading = false, error = message))
                }
            }
        }
    }

    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        _uiState.update { state ->
            val current = state.authState as? TelegramAuthUiState.WaitPassword ?: return@update state
            state.copy(authState = current.copy(isLoading = true, error = null))
        }
        scope.launch {
            runCatching {
                gateway.send(TdApi.CheckAuthenticationPassword(password))
            }.onFailure { err ->
                val message = translateError(err)
                _uiState.update { state ->
                    val current = state.authState as? TelegramAuthUiState.WaitPassword ?: return@update state
                    state.copy(authState = current.copy(isLoading = false, error = message))
                }
            }
        }
    }

    fun requestPasswordRecovery() {
        _uiState.update { state ->
            val current = state.authState as? TelegramAuthUiState.WaitPassword ?: return@update state
            state.copy(authState = current.copy(isLoading = true, error = null))
        }
        scope.launch {
            runCatching {
                gateway.send(TdApi.RequestAuthenticationPasswordRecovery())
            }.onSuccess {
                _uiState.update { state ->
                    val current = state.authState as? TelegramAuthUiState.WaitPassword ?: return@update state
                    state.copy(
                        authState = current.copy(
                            isLoading = false,
                            isRecoveryMode = true,
                            error = null,
                        )
                    )
                }
            }.onFailure { err ->
                val message = translateError(err)
                _uiState.update { state ->
                    val current = state.authState as? TelegramAuthUiState.WaitPassword ?: return@update state
                    state.copy(authState = current.copy(isLoading = false, error = message))
                }
            }
        }
    }

    fun backToPasswordEntry() {
        _uiState.update { state ->
            val current = state.authState as? TelegramAuthUiState.WaitPassword ?: return@update state
            state.copy(authState = current.copy(isRecoveryMode = false, error = null))
        }
    }

    fun submitPasswordRecoveryCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { state ->
            val current = state.authState as? TelegramAuthUiState.WaitPassword ?: return@update state
            state.copy(authState = current.copy(isLoading = true, error = null))
        }
        scope.launch {
            runCatching {
                gateway.send(TdApi.CheckAuthenticationPasswordRecoveryCode(trimmed))
            }.onFailure { err ->
                val message = translateError(err)
                _uiState.update { state ->
                    val current = state.authState as? TelegramAuthUiState.WaitPassword ?: return@update state
                    state.copy(authState = current.copy(isLoading = false, error = message))
                }
            }
        }
    }

    fun registerUser(firstName: String, lastName: String) {
        val trimmedFirst = firstName.trim()
        if (trimmedFirst.isEmpty()) {
            _uiState.update { state ->
                val current = state.authState as? TelegramAuthUiState.WaitRegistration ?: return@update state
                state.copy(
                    authState = current.copy(
                        isLoading = false,
                        error = localizer.get(R.string.telegram_err_empty_name),
                    )
                )
            }
            return
        }
        _uiState.update { state ->
            val current = state.authState as? TelegramAuthUiState.WaitRegistration ?: return@update state
            state.copy(authState = current.copy(isLoading = true, error = null))
        }
        scope.launch {
            runCatching {
                gateway.send(TdApi.RegisterUser(trimmedFirst, lastName.trim(), false))
            }.onFailure { err ->
                val message = translateError(err)
                _uiState.update { state ->
                    val current = state.authState as? TelegramAuthUiState.WaitRegistration ?: return@update state
                    state.copy(authState = current.copy(isLoading = false, error = message))
                }
            }
        }
    }

    fun closeActiveChat() {
        isChatScreenActive = false
        val chatId = activeChatId
        if (chatId == 0L) return
        scope.launch {
            runCatching { gateway.send(TdApi.CloseChat(chatId)) }
            runCatching { gateway.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(false))) }
        }
    }

    fun logOut() {
        isLoggingOut = true
        isChatScreenActive = false
        countdownJob?.cancel()
        resolveGroupJob?.cancel()
        _unreadCount.value = 0
        watchedChatId = 0L
        scope.launch {
            runCatching { chatIdCacheStore.setTelegramLoggedIn(false) }
            runCatching { chatIdCacheStore.clear() }
            activeChatId = 0L
            synchronized(rawMessages) {
                rawMessages.clear()
                completedTempMessageIds.clear()
            }
            userCache.clear()
            viewedMessageIds.clear()
            filePaths.clear()
            downloadingFileIds.clear()
            pendingUserFetches.clear()
            _uiState.update {
                it.copy(
                    authState = TelegramAuthUiState.Initializing,
                    chatInfo = null,
                    messages = emptyList(),
                    replyingTo = null,
                    editingMessage = null,
                    isSearchActive = false,
                    searchQuery = "",
                    searchResults = emptyList(),
                    isSearching = false,
                    errorBanner = null,
                )
            }
            runCatching { gateway.send(TdApi.LogOut()) }
                .onFailure { err ->
                    isLoggingOut = false
                    _uiState.update { it.copy(errorBanner = translateError(err)) }
                }
        }
    }

    fun clearErrorBanner() {
        _uiState.update { it.copy(errorBanner = null) }
    }

    fun setReplyingTo(message: TelegramMessageUi?) {
        _uiState.update { it.copy(replyingTo = message, editingMessage = null) }
    }

    fun setEditingMessage(message: TelegramMessageUi?) {
        _uiState.update { it.copy(editingMessage = message, replyingTo = null) }
    }

    fun sendTypingAction() {
        val chatId = activeChatId
        if (chatId == 0L) return
        val now = System.currentTimeMillis()
        if (now - lastTypingSentMs < 4000L) return
        lastTypingSentMs = now
        scope.launch {
            runCatching {
                gateway.send(TdApi.SendChatAction(chatId, null, "", TdApi.ChatActionTyping()))
            }
        }
    }

    fun sendTextMessage(text: String) {
        val chatId = activeChatId
        val trimmed = text.trim()
        if (chatId == 0L || trimmed.isEmpty()) return

        val editing = _uiState.value.editingMessage
        if (editing != null) {
            _uiState.update { it.copy(editingMessage = null) }
            scope.launch {
                runCatching {
                    gateway.send(
                        TdApi.EditMessageText(
                            chatId,
                            editing.id,
                            null,
                            TdApi.InputMessageText(
                                TdApi.FormattedText(trimmed, emptyArray()),
                                null,
                                false,
                            ),
                        )
                    )
                }.onSuccess { updatedMsg ->
                    mergeMessages(listOf(updatedMsg))
                }.onFailure { err ->
                    _uiState.update { it.copy(errorBanner = translateError(err)) }
                }
            }
            return
        }

        val replyId = _uiState.value.replyingTo?.id
        _uiState.update { it.copy(replyingTo = null) }
        val replyTo = replyId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
        scope.launch {
            runCatching {
                gateway.send(
                    TdApi.SendMessage(
                        chatId,
                        null,
                        replyTo,
                        null,
                        null,
                        TdApi.InputMessageText(
                            TdApi.FormattedText(trimmed, emptyArray()),
                            null,
                            true,
                        ),
                    )
                )
            }.onSuccess { sentMsg ->
                mergePendingSentMessage(sentMsg)
            }.onFailure { err ->
                _uiState.update { it.copy(errorBanner = translateError(err)) }
            }
        }
    }

    fun sendPhotoMessage(localPath: String, caption: String, width: Int = 0, height: Int = 0) {
        val chatId = activeChatId
        if (chatId == 0L || localPath.isBlank()) return
        val replyId = _uiState.value.replyingTo?.id
        _uiState.update { it.copy(replyingTo = null) }
        val replyTo = replyId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
        val inputPhoto = TdApi.InputPhoto(
            TdApi.InputFileLocal(localPath),
            null,
            null,
            intArrayOf(),
            width,
            height,
        )
        scope.launch {
            runCatching {
                gateway.send(
                    TdApi.SendMessage(
                        chatId,
                        null,
                        replyTo,
                        null,
                        null,
                        TdApi.InputMessagePhoto(
                            inputPhoto,
                            TdApi.FormattedText(caption.trim(), emptyArray()),
                            false,
                            null,
                            false,
                        ),
                    )
                )
            }.onSuccess { sentMsg ->
                mergePendingSentMessage(sentMsg)
            }.onFailure { err ->
                _uiState.update { it.copy(errorBanner = translateError(err)) }
            }
        }
    }

    fun deleteMessage(messageId: Long, revoke: Boolean = true) {
        val chatId = activeChatId
        if (chatId == 0L) return
        scope.launch {
            runCatching {
                gateway.send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), revoke))
            }.onSuccess {
                synchronized(rawMessages) {
                    rawMessages.remove(messageId)
                }
                _uiState.update { state ->
                    if (state.searchResults.any { it.id == messageId }) {
                        state.copy(searchResults = state.searchResults.filterNot { it.id == messageId })
                    } else {
                        state
                    }
                }
                publishMappedMessages()
            }.onFailure { err ->
                _uiState.update { it.copy(errorBanner = translateError(err)) }
            }
        }
    }

    fun toggleReaction(messageId: Long, emoji: String) {
        val chatId = activeChatId
        if (chatId == 0L) return
        val currentMsg = _uiState.value.messages.firstOrNull { it.id == messageId }
        val alreadyChosen = currentMsg?.reactions?.any { it.emoji == emoji && it.isChosen } == true
        scope.launch {
            val result = runCatching {
                if (alreadyChosen) {
                    gateway.send(
                        TdApi.RemoveMessageReaction(
                            chatId,
                            messageId,
                            TdApi.ReactionTypeEmoji(emoji),
                        )
                    )
                } else {
                    gateway.send(
                        TdApi.AddMessageReaction(
                            chatId,
                            messageId,
                            TdApi.ReactionTypeEmoji(emoji),
                            false,
                            true,
                        )
                    )
                }
            }
            result.onFailure { err ->
                _uiState.update { it.copy(errorBanner = translateError(err)) }
            }
        }
    }

    fun markMessagesViewed(messageIds: List<Long>) {
        val chatId = activeChatId
        if (chatId == 0L || messageIds.isEmpty()) return
        val unseen = messageIds.filter { it > 0L && viewedMessageIds.add(it) }
        if (unseen.isEmpty()) return
        scope.launch {
            runCatching {
                gateway.send(
                    TdApi.ViewMessages(
                        chatId,
                        unseen.toLongArray(),
                        TdApi.MessageSourceChatHistory(),
                        true,
                    )
                )
            }.onSuccess {
                if (isChatScreenActive) {
                    _unreadCount.value = 0
                }
            }
        }
    }

    private suspend fun markChatReadOnOpen(chatId: Long, messageIds: List<Long>) {
        val validIds = messageIds.filter { it > 0L }.distinct()
        if (validIds.isNotEmpty()) {
            runCatching {
                gateway.send(
                    TdApi.ViewMessages(
                        chatId,
                        validIds.toLongArray(),
                        TdApi.MessageSourceChatHistory(),
                        true,
                    )
                )
            }
        }
        _unreadCount.value = 0
    }

    fun loadMoreHistory() {
        val chatId = activeChatId
        val state = _uiState.value
        if (chatId == 0L || state.isLoadingMore || state.isLoadingHistory || !state.hasMoreHistory) return
        val oldestId = synchronized(rawMessages) {
            rawMessages.values.minOfOrNull { it.id } ?: 0L
        }
        if (oldestId == 0L) return

        _uiState.update { it.copy(isLoadingMore = true) }
        scope.launch {
            runCatching {
                fetchHistoryBatchWithBackfill(chatId = chatId, initialFromMessageId = oldestId, targetCount = 30)
            }.onSuccess { (fetched, reachedEnd) ->
                mergeMessages(fetched)
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        hasMoreHistory = !reachedEnd,
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        errorBanner = translateError(err),
                    )
                }
            }
        }
    }

    fun setSearchActive(active: Boolean) {
        _uiState.update {
            if (!active) {
                it.copy(
                    isSearchActive = false,
                    searchQuery = "",
                    searchResults = emptyList(),
                    isSearching = false,
                )
            } else {
                it.copy(isSearchActive = true)
            }
        }
    }

    fun searchMessages(query: String) {
        val chatId = activeChatId
        val trimmed = query.trim()
        _uiState.update { it.copy(searchQuery = query) }
        if (chatId == 0L || trimmed.isEmpty()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        _uiState.update { it.copy(isSearching = true) }
        scope.launch {
            runCatching {
                gateway.send(
                    TdApi.SearchChatMessages(
                        chatId,
                        null,
                        trimmed,
                        null,
                        0L,
                        0,
                        40,
                        TdApi.SearchMessagesFilterEmpty(),
                    )
                )
            }.onSuccess { result ->
                val list = result.messages?.toList().orEmpty()
                list.forEach { resolveSendersAndFiles(it) }
                _uiState.update { state ->
                    if (state.searchQuery.trim() != trimmed) return@update state
                    state.copy(
                        isSearching = false,
                        searchResults = list.map { mapMessage(it) },
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        errorBanner = translateError(err),
                    )
                }
            }
        }
    }

    fun downloadFile(fileId: Int, priority: Int = 16) {
        if (fileId <= 0) return
        if (filePaths.containsKey(fileId)) return
        if (!downloadingFileIds.add(fileId)) return
        publishMappedMessages()
        scope.launch {
            runCatching {
                gateway.send(TdApi.DownloadFile(fileId, priority, 0L, 0L, false))
            }.onSuccess { file ->
                handleFileUpdated(file)
            }.onFailure {
                downloadingFileIds.remove(fileId)
                publishMappedMessages()
            }
        }
    }

    fun dispose() {
        countdownJob?.cancel()
        resolveGroupJob?.cancel()
        updatesJob?.cancel()
        foregroundJob?.cancel()
        scope.cancel()
    }

    private fun handleUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuthorizationState(obj.authorizationState)
            is TdApi.UpdateConnectionState -> handleConnectionState(obj.state)
            is TdApi.UpdateNewMessage -> {
                val targetId = activeChatId.takeIf { it != 0L } ?: watchedChatId
                if (obj.message.chatId == activeChatId && activeChatId != 0L) {
                    mergeMessages(listOf(obj.message))
                }
                if (targetId != 0L &&
                    obj.message.chatId == targetId &&
                    !isChatScreenActive &&
                    !obj.message.isOutgoing
                ) {
                    scope.launch {
                        runCatching { gateway.send(TdApi.GetChat(targetId)) }.onSuccess { chat ->
                            if (!isChatScreenActive) {
                                _unreadCount.value = chat.unreadCount.coerceAtLeast(0)
                            }
                        }
                    }
                }
            }
            is TdApi.UpdateChatReadInbox -> {
                val targetId = activeChatId.takeIf { it != 0L } ?: watchedChatId
                if (targetId != 0L && obj.chatId == targetId) {
                    _unreadCount.value = if (isChatScreenActive) 0 else obj.unreadCount.coerceAtLeast(0)
                }
            }
            is TdApi.UpdateMessageContent -> {
                if (obj.chatId == activeChatId) {
                    val updated = synchronized(rawMessages) {
                        val existing = rawMessages[obj.messageId] ?: return@synchronized false
                        existing.content = obj.newContent
                        true
                    }
                    if (updated) publishMappedMessages()
                }
            }
            is TdApi.UpdateDeleteMessages -> {
                if (obj.chatId == activeChatId && !obj.fromCache) {
                    val deletedSet = obj.messageIds.toSet()
                    val removed = synchronized(rawMessages) {
                        var any = false
                        for (id in obj.messageIds) {
                            if (rawMessages.remove(id) != null) any = true
                        }
                        any
                    }
                    if (deletedSet.isNotEmpty()) {
                        _uiState.update { state ->
                            if (state.searchResults.any { it.id in deletedSet }) {
                                state.copy(searchResults = state.searchResults.filterNot { it.id in deletedSet })
                            } else {
                                state
                            }
                        }
                    }
                    if (removed) publishMappedMessages()
                }
            }
            is TdApi.UpdateMessageInteractionInfo -> {
                if (obj.chatId == activeChatId) {
                    val updated = synchronized(rawMessages) {
                        val existing = rawMessages[obj.messageId] ?: return@synchronized false
                        existing.interactionInfo = obj.interactionInfo
                        true
                    }
                    if (updated) publishMappedMessages()
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                if (obj.message.chatId == activeChatId) {
                    synchronized(rawMessages) {
                        val existed = rawMessages.remove(obj.oldMessageId) != null
                        if (!existed) {
                            completedTempMessageIds.add(obj.oldMessageId)
                        }
                        rawMessages[obj.message.id] = obj.message
                    }
                    resolveSendersAndFiles(obj.message)
                    publishMappedMessages()
                }
            }
            is TdApi.UpdateMessageSendFailed -> {
                if (obj.message.chatId == activeChatId) {
                    synchronized(rawMessages) {
                        val existed = rawMessages.remove(obj.oldMessageId) != null
                        if (!existed) {
                            completedTempMessageIds.add(obj.oldMessageId)
                        }
                        rawMessages[obj.message.id] = obj.message
                    }
                    publishMappedMessages()
                }
            }
            is TdApi.UpdateMessageIsPinned -> {
                if (obj.chatId == activeChatId) {
                    synchronized(rawMessages) {
                        rawMessages[obj.messageId]?.isPinned = obj.isPinned
                    }
                    scope.launch { refreshPinnedMessage(obj.chatId) }
                    publishMappedMessages()
                }
            }
            is TdApi.UpdateUser -> {
                userCache[obj.user.id] = obj.user
                trackFile(obj.user.profilePhoto?.small)
                publishMappedMessages()
            }
            is TdApi.UpdateFile -> handleFileUpdated(obj.file)
            is TdApi.UpdateChatOnlineMemberCount -> {
                if (obj.chatId == activeChatId) {
                    _uiState.update { state ->
                        val info = state.chatInfo ?: return@update state
                        state.copy(chatInfo = info.copy(onlineCount = obj.onlineMemberCount))
                    }
                }
            }
            is TdApi.UpdateChatTitle -> {
                if (obj.chatId == activeChatId) {
                    _uiState.update { state ->
                        val info = state.chatInfo ?: return@update state
                        state.copy(chatInfo = info.copy(title = obj.title))
                    }
                }
            }
            is TdApi.UpdateChatPhoto -> {
                if (obj.chatId == activeChatId) {
                    val small = obj.photo?.small
                    trackFile(small)
                    _uiState.update { state ->
                        val info = state.chatInfo ?: return@update state
                        state.copy(
                            chatInfo = info.copy(
                                avatarFileId = small?.id ?: 0,
                                avatarLocalPath = small?.id?.let { filePaths[it] },
                            )
                        )
                    }
                }
            }
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                _uiState.update { it.copy(authState = TelegramAuthUiState.Initializing) }
                scope.launch {
                    val dbDir = File(context.filesDir, "tdlib/db").apply { mkdirs() }.absolutePath
                    val filesDir = File(context.filesDir, "tdlib/files").apply { mkdirs() }.absolutePath
                    val params = TdApi.SetTdlibParameters(
                        false,
                        dbDir,
                        filesDir,
                        byteArrayOf(),
                        true,
                        true,
                        true,
                        false,
                        apiId,
                        apiHash,
                        Locale.getDefault().language.ifBlank { "en" },
                        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" },
                        "Android ${Build.VERSION.RELEASE ?: "14"}",
                        appVersionName.ifBlank { "1.0" },
                    )
                    runCatching { gateway.send(params) }
                        .onFailure { err ->
                            _uiState.update {
                                it.copy(authState = TelegramAuthUiState.Error(translateError(err)))
                            }
                        }
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                countdownJob?.cancel()
                _unreadCount.value = 0
                watchedChatId = 0L
                scope.launch {
                    runCatching { chatIdCacheStore.setTelegramLoggedIn(false) }
                }
                _uiState.update { ui ->
                    val prev = ui.authState as? TelegramAuthUiState.WaitPhoneNumber
                    ui.copy(
                        authState = TelegramAuthUiState.WaitPhoneNumber(
                            countryCode = prev?.countryCode ?: "+84",
                            phoneNumberInput = prev?.phoneNumberInput ?: "",
                        )
                    )
                }
            }
            is TdApi.AuthorizationStateWaitCode -> {
                val info = state.codeInfo
                val codeType = mapCodeType(info.type)
                val codeLength = extractCodeLength(info.type)
                val nextType = info.nextType?.let { mapCodeType(it) }
                val timeout = info.timeout.coerceAtLeast(0)
                _uiState.update { ui ->
                    ui.copy(
                        authState = TelegramAuthUiState.WaitCode(
                            phoneNumber = info.phoneNumber.orEmpty(),
                            codeType = codeType,
                            codeLength = codeLength,
                            nextType = nextType,
                            timeoutSeconds = timeout,
                        )
                    )
                }
                startOtpCountdown(timeout)
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                countdownJob?.cancel()
                _uiState.update { ui ->
                    ui.copy(
                        authState = TelegramAuthUiState.WaitPassword(
                            passwordHint = state.passwordHint.orEmpty(),
                            hasRecoveryEmail = state.hasRecoveryEmailAddress,
                            recoveryEmailPattern = state.recoveryEmailAddressPattern.orEmpty(),
                        )
                    )
                }
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                countdownJob?.cancel()
                val suggested = suggestedDisplayNameProvider()?.trim().orEmpty()
                val parts = suggested.split(Regex("\\s+"), limit = 2)
                val first = parts.getOrNull(0).orEmpty()
                val last = parts.getOrNull(1).orEmpty()
                _uiState.update { ui ->
                    ui.copy(
                        authState = TelegramAuthUiState.WaitRegistration(
                            suggestedFirstName = first,
                            suggestedLastName = last,
                        )
                    )
                }
            }
            is TdApi.AuthorizationStateReady -> {
                countdownJob?.cancel()
                resolveGroupJob?.cancel()
                resolveGroupJob = scope.launch {
                    runCatching { chatIdCacheStore.setTelegramLoggedIn(true) }
                    runCatching {
                        gateway.send(
                            TdApi.SetOption(
                                "online",
                                TdApi.OptionValueBoolean(AppForegroundTracker.isForeground.value),
                            )
                        )
                    }
                    runCatching {
                        val me = gateway.send(TdApi.GetMe())
                        userCache[me.id] = me
                    }
                    if (isChatScreenActive) {
                        resolveAndOpenGroupChat()
                    } else {
                        resolveWatchedChatUnreadCount()
                    }
                }
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                countdownJob?.cancel()
                _unreadCount.value = 0
                watchedChatId = 0L
                scope.launch {
                    runCatching { chatIdCacheStore.setTelegramLoggedIn(false) }
                }
                _uiState.update {
                    it.copy(
                        authState = TelegramAuthUiState.Initializing,
                        chatInfo = null,
                        messages = emptyList(),
                    )
                }
            }
            is TdApi.AuthorizationStateClosing -> {
                countdownJob?.cancel()
                _uiState.update {
                    it.copy(
                        authState = TelegramAuthUiState.Initializing,
                        chatInfo = null,
                        messages = emptyList(),
                    )
                }
            }
            is TdApi.AuthorizationStateClosed -> {
                countdownJob?.cancel()
                _unreadCount.value = 0
                watchedChatId = 0L
                scope.launch {
                    runCatching { chatIdCacheStore.setTelegramLoggedIn(false) }
                }
                synchronized(this) {
                    started = false
                    activeChatId = 0L
                }
                synchronized(rawMessages) {
                    rawMessages.clear()
                    completedTempMessageIds.clear()
                }
                filePaths.clear()
                downloadingFileIds.clear()
                pendingUserFetches.clear()
                if (isLoggingOut) {
                    isLoggingOut = false
                    ensureStarted()
                }
            }
        }
    }

    private suspend fun resolveWatchedChatUnreadCount() {
        val cachedId = runCatching { chatIdCacheStore.getCachedChatId() }.getOrDefault(0L)
        var targetChat: TdApi.Chat? = null
        if (cachedId != 0L) {
            targetChat = runCatching { gateway.send(TdApi.GetChat(cachedId)) }.getOrNull()
        }
        if (targetChat == null) {
            val info = runCatching { gateway.send(TdApi.CheckChatInviteLink(groupInviteUrl)) }.getOrNull()
            if (info != null && info.chatId != 0L) {
                runCatching { chatIdCacheStore.setCachedChatId(info.chatId) }
                targetChat = runCatching { gateway.send(TdApi.GetChat(info.chatId)) }.getOrNull()
            }
        }
        if (targetChat != null) {
            watchedChatId = targetChat.id
            if (!isChatScreenActive) {
                _unreadCount.value = targetChat.unreadCount.coerceAtLeast(0)
            }
        }
        if (isChatScreenActive) {
            resolveAndOpenGroupChat()
        }
    }

    private suspend fun resolveAndOpenGroupChat() {
        _uiState.update {
            it.copy(
                authState = TelegramAuthUiState.ResolvingGroup(
                    localizer.get(R.string.telegram_joining_group_label)
                )
            )
        }

        val cachedId = runCatching { chatIdCacheStore.getCachedChatId() }.getOrDefault(0L)
        var resolvedChat: TdApi.Chat? = null
        if (cachedId != 0L) {
            resolvedChat = runCatching { gateway.send(TdApi.GetChat(cachedId)) }.getOrNull()
            if (resolvedChat == null) {
                runCatching { chatIdCacheStore.setCachedChatId(0L) }
            }
        }

        if (resolvedChat == null) {
            val inviteResult = runCatching {
                gateway.send(TdApi.CheckChatInviteLink(groupInviteUrl))
            }
            val info = inviteResult.getOrElse { err ->
                _uiState.update {
                    it.copy(authState = TelegramAuthUiState.Error(translateError(err)))
                }
                return
            }

            if (info.chatId != 0L) {
                runCatching { chatIdCacheStore.setCachedChatId(info.chatId) }
                resolvedChat = runCatching { gateway.send(TdApi.GetChat(info.chatId)) }
                    .getOrElse { err ->
                        _uiState.update {
                            it.copy(authState = TelegramAuthUiState.Error(translateError(err)))
                        }
                        return
                    }
            } else {
                val joinResult = runCatching {
                    gateway.send(TdApi.JoinChatByInviteLink(groupInviteUrl))
                }.getOrElse { err ->
                    if (err is TdLibException && err.tdMessage.contains("INVITE_REQUEST_SENT", ignoreCase = true)) {
                        _uiState.update { it.copy(authState = TelegramAuthUiState.JoinRequestSent) }
                    } else {
                        _uiState.update {
                            it.copy(authState = TelegramAuthUiState.Error(translateError(err)))
                        }
                    }
                    return
                }
                val joinedChatId = when (joinResult) {
                    is TdApi.ChatJoinResultSuccess -> joinResult.chatId
                    is TdApi.ChatJoinResultRequestSent,
                    is TdApi.ChatJoinResultGuardBotApprovalRequired -> {
                        _uiState.update { it.copy(authState = TelegramAuthUiState.JoinRequestSent) }
                        return
                    }
                    is TdApi.ChatJoinResultDeclined -> {
                        _uiState.update {
                            it.copy(
                                authState = TelegramAuthUiState.Error(
                                    localizer.get(R.string.telegram_err_invite_invalid)
                                )
                            )
                        }
                        return
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                authState = TelegramAuthUiState.Error(
                                    localizer.get(R.string.telegram_err_invite_invalid)
                                )
                            )
                        }
                        return
                    }
                }
                runCatching { chatIdCacheStore.setCachedChatId(joinedChatId) }
                resolvedChat = runCatching { gateway.send(TdApi.GetChat(joinedChatId)) }
                    .getOrElse { err ->
                        _uiState.update {
                            it.copy(authState = TelegramAuthUiState.Error(translateError(err)))
                        }
                        return
                    }
            }
        }

        val chat = resolvedChat
        activeChatId = chat.id
        watchedChatId = chat.id
        runCatching { gateway.send(TdApi.OpenChat(chat.id)) }
        val lastMsgId = chat.lastMessage?.id ?: 0L
        if (lastMsgId > 0L) {
            markChatReadOnOpen(chat.id, listOf(lastMsgId))
        } else if (chat.unreadCount == 0) {
            _unreadCount.value = 0
        }

        val avatarFile = chat.photo?.small
        trackFile(avatarFile)

        var memberCount = 0
        var isForum = false
        when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> {
                runCatching { gateway.send(TdApi.GetSupergroup(type.supergroupId)) }
                    .onSuccess { sg ->
                        memberCount = sg.memberCount
                        isForum = sg.isForum
                    }
            }
            is TdApi.ChatTypeBasicGroup -> {
                runCatching { gateway.send(TdApi.GetBasicGroup(type.basicGroupId)) }
                    .onSuccess { bg ->
                        memberCount = bg.memberCount
                    }
            }
        }

        _uiState.update { state ->
            state.copy(
                authState = TelegramAuthUiState.Ready,
                chatInfo = TelegramChatInfo(
                    chatId = chat.id,
                    title = chat.title.ifBlank { localizer.get(R.string.telegram_chat_title) },
                    memberCount = memberCount,
                    onlineCount = state.chatInfo?.onlineCount ?: 0,
                    avatarFileId = avatarFile?.id ?: 0,
                    avatarLocalPath = avatarFile?.id?.let { filePaths[it] },
                    isForum = isForum,
                ),
                isLoadingHistory = true,
                hasMoreHistory = true,
            )
        }

        refreshPinnedMessage(chat.id)
        loadInitialHistory(chat.id)
    }

    private suspend fun refreshPinnedMessage(chatId: Long) {
        val pinned = runCatching { gateway.send(TdApi.GetChatPinnedMessage(chatId)) }.getOrNull()
        val pinnedUi = pinned?.let { msg ->
            resolveSenderName(msg.senderId)
            PinnedMessageUi(
                messageId = msg.id,
                previewText = extractPlainPreview(msg.content),
                senderName = getCachedSenderName(msg.senderId),
            )
        }
        _uiState.update { state ->
            val info = state.chatInfo ?: return@update state
            if (info.chatId != chatId) return@update state
            state.copy(chatInfo = info.copy(pinnedMessage = pinnedUi))
        }
    }

    private suspend fun loadInitialHistory(chatId: Long) {
        val result = runCatching {
            fetchHistoryBatchWithBackfill(chatId = chatId, initialFromMessageId = 0L, targetCount = 40)
        }
        result.onSuccess { (fetched, reachedEnd) ->
            mergeMessages(fetched)
            val latestFetchedId = fetched.maxOfOrNull { it.id } ?: 0L
            if (latestFetchedId > 0L) {
                markChatReadOnOpen(chatId, listOf(latestFetchedId))
            } else {
                _unreadCount.value = 0
            }
            _uiState.update {
                it.copy(
                    isLoadingHistory = false,
                    hasMoreHistory = !reachedEnd,
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    isLoadingHistory = false,
                    errorBanner = translateError(err),
                )
            }
        }
    }

    private suspend fun fetchHistoryBatchWithBackfill(
        chatId: Long,
        initialFromMessageId: Long,
        targetCount: Int,
        maxIterations: Int = 6,
    ): Pair<List<TdApi.Message>, Boolean> {
        val collected = LinkedHashMap<Long, TdApi.Message>()
        var cursorId = initialFromMessageId
        var reachedEnd = false

        for (i in 0 until maxIterations) {
            val res = gateway.send(TdApi.GetChatHistory(chatId, cursorId, 0, 50, false))
            val batch = res.messages?.toList().orEmpty()
            if (batch.isEmpty()) {
                reachedEnd = true
                break
            }
            var addedNew = false
            for (msg in batch) {
                if (collected.putIfAbsent(msg.id, msg) == null) {
                    addedNew = true
                }
            }
            val nextCursor = batch.minOf { it.id }
            if (!addedNew || nextCursor == cursorId) {
                break
            }
            cursorId = nextCursor
            if (collected.size >= targetCount) {
                break
            }
        }
        return collected.values.toList() to reachedEnd
    }

    private fun mergePendingSentMessage(msg: TdApi.Message) {
        val inserted = synchronized(rawMessages) {
            if (completedTempMessageIds.remove(msg.id)) {
                false
            } else {
                rawMessages[msg.id] = msg
                true
            }
        }
        if (inserted) {
            resolveSendersAndFiles(msg)
            publishMappedMessages()
        }
    }

    private fun mergeMessages(messages: List<TdApi.Message>) {
        if (messages.isEmpty()) return
        synchronized(rawMessages) {
            for (msg in messages) {
                rawMessages[msg.id] = msg
            }
        }
        for (msg in messages) {
            resolveSendersAndFiles(msg)
        }
        publishMappedMessages()
    }

    private fun resolveSendersAndFiles(msg: TdApi.Message) {
        resolveSenderName(msg.senderId)
        when (val content = msg.content) {
            is TdApi.MessagePhoto -> {
                val bestSize = selectBestPhotoSize(content.photo.sizes)
                trackFile(bestSize?.photo)
            }
            is TdApi.MessageSticker -> {
                if (content.sticker.format is TdApi.StickerFormatWebp) {
                    trackFile(content.sticker.sticker)
                } else {
                    trackFile(content.sticker.thumbnail?.file)
                }
            }
            is TdApi.MessageDocument -> {
                val docFile = content.document.document
                if (docFile.local?.isDownloadingCompleted == true && !docFile.local.path.isNullOrBlank()) {
                    filePaths[docFile.id] = docFile.local.path
                }
            }
            else -> {}
        }
    }

    private fun resolveSenderName(sender: TdApi.MessageSender?) {
        if (sender is TdApi.MessageSenderUser) {
            val uid = sender.userId
            if (!userCache.containsKey(uid) && pendingUserFetches.add(uid)) {
                scope.launch {
                    runCatching { gateway.send(TdApi.GetUser(uid)) }
                        .onSuccess { user ->
                            userCache[user.id] = user
                            trackFile(user.profilePhoto?.small)
                            pendingUserFetches.remove(uid)
                            publishMappedMessages()
                        }
                        .onFailure {
                            pendingUserFetches.remove(uid)
                        }
                }
            }
        }
    }

    private fun trackFile(file: TdApi.File?) {
        if (file == null || file.id <= 0) return
        val local = file.local
        if (local != null && local.isDownloadingCompleted && !local.path.isNullOrBlank()) {
            filePaths[file.id] = local.path
            downloadingFileIds.remove(file.id)
        } else if (local != null && local.canBeDownloaded && !filePaths.containsKey(file.id)) {
            downloadFile(file.id, priority = 8)
        }
    }

    private fun handleFileUpdated(file: TdApi.File) {
        val local = file.local
        if (local != null && local.isDownloadingCompleted && !local.path.isNullOrBlank()) {
            filePaths[file.id] = local.path
            downloadingFileIds.remove(file.id)
            _uiState.update { state ->
                val info = state.chatInfo
                val updatedInfo = if (info != null && info.avatarFileId == file.id) {
                    info.copy(avatarLocalPath = local.path)
                } else {
                    info
                }
                state.copy(chatInfo = updatedInfo)
            }
            publishMappedMessages()
        } else if (local != null && !local.isDownloadingActive && !local.isDownloadingCompleted) {
            downloadingFileIds.remove(file.id)
        }
    }

    private fun publishMappedMessages() {
        val snapshot = synchronized(rawMessages) {
            rawMessages.values.sortedByDescending { it.id }
        }
        val mapped = snapshot.map { mapMessage(it) }
        _uiState.update { it.copy(messages = mapped) }
    }

    private fun mapMessage(msg: TdApi.Message): TelegramMessageUi {
        val senderId = when (val s = msg.senderId) {
            is TdApi.MessageSenderUser -> s.userId
            is TdApi.MessageSenderChat -> s.chatId
            else -> 0L
        }
        val user = if (msg.senderId is TdApi.MessageSenderUser) userCache[senderId] else null
        val senderName = getCachedSenderName(msg.senderId)
        val avatarFileId = user?.profilePhoto?.small?.id ?: 0
        val avatarPath = if (avatarFileId > 0) filePaths[avatarFileId] else null

        val replyPreview = (msg.replyTo as? TdApi.MessageReplyToMessage)?.let { reply ->
            val targetMsg = synchronized(rawMessages) { rawMessages[reply.messageId] }
            val replySender = targetMsg?.let { getCachedSenderName(it.senderId) } ?: "Message"
            val preview = targetMsg?.let { extractPlainPreview(it.content) }
                ?: reply.quote?.text?.text?.takeIf { it.isNotBlank() }
                ?: reply.content?.let { extractPlainPreview(it) }
                ?: "..."
            TelegramReplyPreviewUi(
                messageId = reply.messageId,
                senderName = replySender,
                previewText = preview,
            )
        }

        val reactions = msg.interactionInfo?.reactions?.reactions?.mapNotNull { r ->
            val emoji = (r.type as? TdApi.ReactionTypeEmoji)?.emoji ?: return@mapNotNull null
            TelegramReactionUi(emoji = emoji, count = r.totalCount, isChosen = r.isChosen)
        }.orEmpty()

        val isSending = msg.sendingState is TdApi.MessageSendingStatePending
        val failedState = msg.sendingState as? TdApi.MessageSendingStateFailed

        return TelegramMessageUi(
            id = msg.id,
            chatId = msg.chatId,
            senderId = senderId,
            senderName = senderName,
            senderAvatarFileId = avatarFileId,
            senderAvatarPath = avatarPath,
            isOutgoing = msg.isOutgoing,
            isSending = isSending,
            sendFailed = failedState != null,
            sendError = failedState?.error?.message,
            dateSeconds = msg.date,
            isEdited = msg.editDate > 0,
            isPinned = msg.isPinned,
            replyPreview = replyPreview,
            content = mapContent(msg.content),
            reactions = reactions,
        )
    }

    private fun mapContent(content: TdApi.MessageContent?): TelegramMessageContentUi {
        return when (content) {
            is TdApi.MessageText -> TelegramMessageContentUi.Text(mapFormattedText(content.text))
            is TdApi.MessagePhoto -> {
                val best = selectBestPhotoSize(content.photo.sizes)
                val fileId = best?.photo?.id ?: 0
                val localPath = filePaths[fileId]
                    ?: best?.photo?.local?.takeIf { it.isDownloadingCompleted && !it.path.isNullOrBlank() }?.path
                TelegramMessageContentUi.Photo(
                    formattedCaption = mapFormattedText(content.caption),
                    fileId = fileId,
                    localPath = localPath,
                    isDownloading = fileId in downloadingFileIds,
                    width = best?.width ?: 0,
                    height = best?.height ?: 0,
                    minithumbnailBytes = content.photo.minithumbnail?.data,
                )
            }
            is TdApi.MessageSticker -> {
                val isWebp = content.sticker.format is TdApi.StickerFormatWebp
                val targetFile = if (isWebp) content.sticker.sticker else content.sticker.thumbnail?.file
                val fileId = targetFile?.id ?: 0
                val localPath = filePaths[fileId]
                    ?: targetFile?.local?.takeIf { it.isDownloadingCompleted && !it.path.isNullOrBlank() }?.path
                TelegramMessageContentUi.Sticker(
                    emoji = content.sticker.emoji.orEmpty(),
                    fileId = fileId,
                    localPath = localPath,
                    isWebp = isWebp,
                    width = content.sticker.width,
                    height = content.sticker.height,
                )
            }
            is TdApi.MessageDocument -> {
                val file = content.document.document
                val localPath = filePaths[file.id]
                    ?: file.local?.takeIf { it.isDownloadingCompleted && !it.path.isNullOrBlank() }?.path
                TelegramMessageContentUi.Document(
                    fileName = content.document.fileName.ifBlank { "document" },
                    mimeType = content.document.mimeType.orEmpty(),
                    sizeBytes = file.size,
                    fileId = file.id,
                    localPath = localPath,
                    isDownloading = file.id in downloadingFileIds,
                    formattedCaption = mapFormattedText(content.caption),
                )
            }
            is TdApi.MessageVideo -> TelegramMessageContentUi.MediaPlaceholder(
                typeLabel = localizer.get(R.string.telegram_media_video_label),
                formattedCaption = mapFormattedText(content.caption),
            )
            is TdApi.MessageAnimation -> TelegramMessageContentUi.MediaPlaceholder(
                typeLabel = localizer.get(R.string.telegram_media_animation_label),
                formattedCaption = mapFormattedText(content.caption),
            )
            is TdApi.MessageVoiceNote -> TelegramMessageContentUi.MediaPlaceholder(
                typeLabel = localizer.get(R.string.telegram_media_voice_label),
                formattedCaption = mapFormattedText(content.caption),
            )
            is TdApi.MessageAudio -> TelegramMessageContentUi.MediaPlaceholder(
                typeLabel = localizer.get(R.string.telegram_media_audio_label),
                formattedCaption = mapFormattedText(content.caption),
            )
            is TdApi.MessagePinMessage -> TelegramMessageContentUi.SystemMessage(
                localizer.get(R.string.telegram_system_pinned_message)
            )
            is TdApi.MessageChatJoinByLink -> TelegramMessageContentUi.SystemMessage(
                localizer.get(R.string.telegram_system_joined_by_link)
            )
            is TdApi.MessageChatAddMembers -> TelegramMessageContentUi.SystemMessage(
                localizer.get(R.string.telegram_system_added_members)
            )
            is TdApi.MessageChatDeleteMember -> TelegramMessageContentUi.SystemMessage(
                localizer.get(R.string.telegram_system_member_left)
            )
            else -> TelegramMessageContentUi.Unsupported(
                content?.javaClass?.simpleName ?: "Unknown"
            )
        }
    }

    private fun mapFormattedText(ft: TdApi.FormattedText?): TelegramFormattedText {
        if (ft == null || ft.text.isNullOrEmpty()) {
            return TelegramFormattedText("")
        }
        val entities = ft.entities?.mapNotNull { entity ->
            val mappedType = when (val t = entity.type) {
                is TdApi.TextEntityTypeUrl -> TelegramEntityType.Url
                is TdApi.TextEntityTypeTextUrl -> TelegramEntityType.TextUrl(t.url)
                is TdApi.TextEntityTypeBold -> TelegramEntityType.Bold
                is TdApi.TextEntityTypeItalic -> TelegramEntityType.Italic
                is TdApi.TextEntityTypeCode -> TelegramEntityType.Code
                is TdApi.TextEntityTypePre, is TdApi.TextEntityTypePreCode -> TelegramEntityType.Pre
                is TdApi.TextEntityTypeUnderline -> TelegramEntityType.Underline
                is TdApi.TextEntityTypeStrikethrough -> TelegramEntityType.Strikethrough
                is TdApi.TextEntityTypeSpoiler -> TelegramEntityType.Spoiler
                is TdApi.TextEntityTypeMention, is TdApi.TextEntityTypeMentionName -> TelegramEntityType.Mention
                is TdApi.TextEntityTypeBotCommand -> TelegramEntityType.BotCommand
                is TdApi.TextEntityTypeHashtag -> TelegramEntityType.Hashtag
                else -> null
            } ?: return@mapNotNull null
            TelegramTextEntity(entity.offset, entity.length, mappedType)
        }.orEmpty()
        return TelegramFormattedText(ft.text, entities)
    }

    private fun extractPlainPreview(content: TdApi.MessageContent?): String {
        return when (content) {
            is TdApi.MessageText -> content.text?.text.orEmpty()
            is TdApi.MessagePhoto -> content.caption?.text?.takeIf { it.isNotBlank() }
                ?: "📷 ${localizer.get(R.string.telegram_media_photo_label)}"
            is TdApi.MessageSticker -> "${content.sticker.emoji.orEmpty()} ${localizer.get(R.string.telegram_media_sticker_label)}".trim()
            is TdApi.MessageDocument -> content.document.fileName.ifBlank {
                localizer.get(R.string.telegram_media_document_label)
            }
            is TdApi.MessageVideo -> localizer.get(R.string.telegram_media_video_label)
            is TdApi.MessageAnimation -> localizer.get(R.string.telegram_media_animation_label)
            is TdApi.MessageVoiceNote -> localizer.get(R.string.telegram_media_voice_label)
            is TdApi.MessageAudio -> localizer.get(R.string.telegram_media_audio_label)
            else -> "..."
        }
    }

    private fun getCachedSenderName(sender: TdApi.MessageSender?): String {
        return when (sender) {
            is TdApi.MessageSenderUser -> {
                val u = userCache[sender.userId]
                if (u != null) {
                    listOfNotNull(
                        u.firstName?.takeIf { it.isNotBlank() },
                        u.lastName?.takeIf { it.isNotBlank() },
                    ).joinToString(" ").ifBlank {
                        u.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "User #${u.id}"
                    }
                } else {
                    "User #${sender.userId}"
                }
            }
            is TdApi.MessageSenderChat -> _uiState.value.chatInfo?.title ?: "Group"
            else -> "Telegram"
        }
    }

    private fun selectBestPhotoSize(sizes: Array<TdApi.PhotoSize>?): TdApi.PhotoSize? {
        if (sizes.isNullOrEmpty()) return null
        // Prefer ~1280px ("x" / "y") or largest available
        return sizes.maxByOrNull { it.width * it.height }
    }

    private fun handleConnectionState(state: TdApi.ConnectionState) {
        val status = when (state) {
            is TdApi.ConnectionStateReady -> TelegramConnectionStatus.READY
            is TdApi.ConnectionStateWaitingForNetwork -> TelegramConnectionStatus.WAITING_FOR_NETWORK
            is TdApi.ConnectionStateUpdating -> TelegramConnectionStatus.UPDATING
            is TdApi.ConnectionStateConnectingToProxy -> TelegramConnectionStatus.CONNECTING_TO_PROXY
            else -> TelegramConnectionStatus.CONNECTING
        }
        _uiState.update { it.copy(connectionStatus = status) }
    }

    private fun startOtpCountdown(initialSeconds: Int) {
        countdownJob?.cancel()
        if (initialSeconds <= 0) return
        countdownJob = scope.launch {
            var remaining = initialSeconds
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _uiState.update { state ->
                    val current = state.authState as? TelegramAuthUiState.WaitCode ?: return@update state
                    state.copy(authState = current.copy(timeoutSeconds = remaining))
                }
            }
        }
    }

    private fun mapCodeType(type: TdApi.AuthenticationCodeType?): OtpDeliveryType = when (type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> OtpDeliveryType.TELEGRAM_APP
        is TdApi.AuthenticationCodeTypeSms -> OtpDeliveryType.SMS
        is TdApi.AuthenticationCodeTypeCall -> OtpDeliveryType.CALL
        is TdApi.AuthenticationCodeTypeMissedCall -> OtpDeliveryType.MISSED_CALL
        is TdApi.AuthenticationCodeTypeFragment -> OtpDeliveryType.FRAGMENT
        else -> OtpDeliveryType.UNKNOWN
    }

    private fun extractCodeLength(type: TdApi.AuthenticationCodeType?): Int = when (type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
        is TdApi.AuthenticationCodeTypeSms -> type.length
        is TdApi.AuthenticationCodeTypeCall -> type.length
        is TdApi.AuthenticationCodeTypeMissedCall -> type.length
        is TdApi.AuthenticationCodeTypeFragment -> type.length
        else -> 5
    }.coerceIn(4, 8)

    internal fun translateError(error: Throwable): String {
        val raw = (error as? TdLibException)?.tdMessage ?: error.message.orEmpty()
        return translateTdErrorMessage(raw, localizer)
    }

    companion object {
        private val FLOOD_WAIT_REGEX = Regex("FLOOD_WAIT_(\\d+)", RegexOption.IGNORE_CASE)

        fun normalizePhoneNumber(countryCode: String, rawInput: String): String? {
            val trimmed = rawInput.trim().replace(Regex("[\\s\\-().]"), "")
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("+")) {
                val digits = trimmed.drop(1).filter { it.isDigit() }
                return if (digits.length >= 6) "+$digits" else null
            }
            val cleanCode = countryCode.trim().let { c ->
                val digits = c.filter { it.isDigit() }
                if (digits.isEmpty()) "+84" else "+$digits"
            }
            val localDigits = trimmed.filter { it.isDigit() }.trimStart('0')
            if (localDigits.length < 5) return null
            return "$cleanCode$localDigits"
        }

        fun translateTdErrorMessage(rawMessage: String, localizer: Localizer): String {
            val upper = rawMessage.uppercase(Locale.US)
            val floodMatch = FLOOD_WAIT_REGEX.find(upper)
            if (floodMatch != null) {
                val seconds = floodMatch.groupValues[1].toIntOrNull() ?: 60
                return if (seconds >= 60) {
                    val mins = seconds / 60
                    val rem = seconds % 60
                    localizer.get(R.string.telegram_err_flood_wait_minutes, mins, rem)
                } else {
                    localizer.get(R.string.telegram_err_flood_wait_seconds, seconds)
                }
            }
            return when {
                upper.contains("PHONE_NUMBER_INVALID") -> localizer.get(R.string.telegram_err_phone_invalid)
                upper.contains("PHONE_CODE_INVALID") -> localizer.get(R.string.telegram_err_code_invalid)
                upper.contains("PHONE_CODE_EXPIRED") -> localizer.get(R.string.telegram_err_code_expired)
                upper.contains("PASSWORD_HASH_INVALID") -> localizer.get(R.string.telegram_err_password_invalid)
                upper.contains("INVITE_HASH_EXPIRED") || upper.contains("INVITE_HASH_INVALID") ->
                    localizer.get(R.string.telegram_err_invite_invalid)
                else -> localizer.get(R.string.telegram_err_generic_format, rawMessage.ifBlank { "Unknown" })
            }
        }
    }
}
