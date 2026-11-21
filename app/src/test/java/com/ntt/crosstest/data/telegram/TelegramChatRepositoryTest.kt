package com.ntt.crosstest.data.telegram

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.util.CommunityLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TelegramChatRepositoryTest {

    private class InMemoryChatIdStore(
        var cachedId: Long = 0L,
        var loggedIn: Boolean = false,
    ) : TelegramChatIdCacheStore {
        override suspend fun getCachedChatId(): Long = cachedId
        override suspend fun setCachedChatId(chatId: Long) {
            cachedId = chatId
        }
        override suspend fun isTelegramLoggedIn(): Boolean = loggedIn
        override suspend fun setTelegramLoggedIn(loggedIn: Boolean) {
            this.loggedIn = loggedIn
        }
        override suspend fun clear() {
            cachedId = 0L
            loggedIn = false
        }
    }

    private class FakeTdLibGateway : TdLibGateway {
        private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 64)
        override val updates: Flow<TdApi.Object> = _updates

        var startCount = 0
        var closeCount = 0
        val sentQueries = mutableListOf<TdApi.Function<*>>()
        var handler: (TdApi.Function<*>) -> TdApi.Object = { TdApi.Ok() }

        override fun start() {
            startCount++
        }

        override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
            sentQueries.add(query)
            val result = handler(query)
            if (result is TdApi.Error) {
                throw TdLibException(result)
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }

        override fun close() {
            closeCount++
        }

        fun emitUpdate(obj: TdApi.Object) {
            assertTrue(_updates.tryEmit(obj))
        }
    }

    private fun createLocalizedContext(base: Context, tag: String): Context {
        val cfg = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return base.createConfigurationContext(cfg)
    }

    @Test
    fun missingApiConfigDisablesStartupWithoutCallingGateway() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        val repo = TelegramChatRepository(
            context = app,
            apiId = 0,
            apiHash = "",
            gateway = gateway,
            chatIdCacheStore = InMemoryChatIdStore(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )

        assertFalse(repo.isConfigured)
        assertFalse(repo.uiState.value.isConfigured)
        repo.ensureStarted()
        assertEquals(0, gateway.startCount)
        repo.dispose()
    }

    @Test
    fun phoneNumberNormalizationHandlesVietnamAndInternationalFormats() {
        assertEquals("+84912345678", TelegramChatRepository.normalizePhoneNumber("+84", "0912345678"))
        assertEquals("+84912345678", TelegramChatRepository.normalizePhoneNumber("+84", "912 345-678"))
        assertEquals("+16505550100", TelegramChatRepository.normalizePhoneNumber("+84", "+1 650 555 0100"))
        assertNull(TelegramChatRepository.normalizePhoneNumber("+84", ""))
        assertNull(TelegramChatRepository.normalizePhoneNumber("+84", "123"))
    }

    @Test
    fun errorTranslationSupportsVietnameseAndEnglish() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("testcheo_settings_sync", Context.MODE_PRIVATE)
        val localizer = Localizer(app)

        prefs.edit().putString("app_language", "vi").commit()
        val viCtx = createLocalizedContext(app, "vi")
        assertEquals(
            viCtx.getString(R.string.telegram_err_phone_invalid),
            TelegramChatRepository.translateTdErrorMessage("PHONE_NUMBER_INVALID", localizer),
        )
        assertEquals(
            viCtx.getString(R.string.telegram_err_code_invalid),
            TelegramChatRepository.translateTdErrorMessage("PHONE_CODE_INVALID", localizer),
        )
        assertEquals(
            viCtx.getString(R.string.telegram_err_password_invalid),
            TelegramChatRepository.translateTdErrorMessage("PASSWORD_HASH_INVALID", localizer),
        )
        assertEquals(
            viCtx.getString(R.string.telegram_err_flood_wait_minutes, 2, 5),
            TelegramChatRepository.translateTdErrorMessage("FLOOD_WAIT_125", localizer),
        )

        prefs.edit().putString("app_language", "en").commit()
        val enCtx = createLocalizedContext(app, "en")
        assertEquals(
            enCtx.getString(R.string.telegram_err_phone_invalid),
            TelegramChatRepository.translateTdErrorMessage("PHONE_NUMBER_INVALID", localizer),
        )
        assertEquals(
            enCtx.getString(R.string.telegram_err_code_expired),
            TelegramChatRepository.translateTdErrorMessage("PHONE_CODE_EXPIRED", localizer),
        )
        assertEquals(
            enCtx.getString(R.string.telegram_err_flood_wait_seconds, 30),
            TelegramChatRepository.translateTdErrorMessage("FLOOD_WAIT_30", localizer),
        )
    }

    @Test
    fun fullAuthFlowWaitParamsToPhoneToOtpTo2FaToReadyAutoJoinAndBackfill() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        val cache = InMemoryChatIdStore(0L)
        var historyCallCount = 0

        gateway.handler = { query ->
            when (query) {
                is TdApi.SetTdlibParameters -> TdApi.Ok()
                is TdApi.SetAuthenticationPhoneNumber -> TdApi.Ok()
                is TdApi.CheckAuthenticationCode -> TdApi.Ok()
                is TdApi.CheckAuthenticationPassword -> TdApi.Ok()
                is TdApi.SetOption -> TdApi.Ok()
                is TdApi.GetMe -> TdApi.User().apply { id = 100L; firstName = "Tester"; lastName = "One" }
                is TdApi.SearchPublicChat -> TdApi.Chat().apply {
                    id = 777L
                    title = "12 Cross Testers"
                    type = TdApi.ChatTypeSupergroup(555L, false)
                }
                is TdApi.JoinChat -> TdApi.ChatJoinResultSuccess(query.chatId)
                is TdApi.GetChat -> TdApi.Chat().apply {
                    id = query.chatId
                    title = "12 Cross Testers"
                    type = TdApi.ChatTypeSupergroup(555L, false)
                }
                is TdApi.OpenChat -> TdApi.Ok()
                is TdApi.GetSupergroup -> TdApi.Supergroup().apply {
                    id = 555L
                    memberCount = 42
                    isForum = false
                    status = TdApi.ChatMemberStatusLeft()
                }
                is TdApi.GetChatPinnedMessage -> TdApi.Error(404, "Not found")
                is TdApi.GetChatHistory -> {
                    historyCallCount++
                    when (historyCallCount) {
                        1 -> TdApi.Messages(
                            3,
                            arrayOf(createTextMessage(id = 30L, chatId = 777L, senderUserId = 100L, text = "Newest")),
                        )
                        2 -> TdApi.Messages(
                            3,
                            arrayOf(
                                createTextMessage(id = 20L, chatId = 777L, senderUserId = 200L, text = "Middle"),
                                createTextMessage(id = 10L, chatId = 777L, senderUserId = 200L, text = "Oldest"),
                            ),
                        )
                        else -> TdApi.Messages(0, emptyArray())
                    }
                }
                is TdApi.GetUser -> TdApi.User().apply {
                    id = query.userId
                    firstName = "Member"
                    lastName = "${query.userId}"
                }
                else -> TdApi.Ok()
            }
        }

        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = gateway,
            chatIdCacheStore = cache,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )

        repo.ensureStarted()
        assertEquals(1, gateway.startCount)

        // 1. WaitTdlibParameters
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()))
        val paramsSent = gateway.sentQueries.filterIsInstance<TdApi.SetTdlibParameters>().firstOrNull()
        assertNotNull(paramsSent)
        assertEquals(12345, paramsSent!!.apiId)
        assertEquals("test_hash", paramsSent.apiHash)

        // 2. WaitPhoneNumber
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitPhoneNumber()))
        assertTrue(repo.uiState.value.authState is TelegramAuthUiState.WaitPhoneNumber)
        repo.sendPhoneNumber("+84", "0912345678")
        val phoneQuery = gateway.sentQueries.filterIsInstance<TdApi.SetAuthenticationPhoneNumber>().first()
        assertEquals("+84912345678", phoneQuery.phoneNumber)
        assertFalse(phoneQuery.settings.allowFlashCall)
        assertFalse(phoneQuery.settings.allowMissedCall)
        assertTrue(phoneQuery.settings.allowSmsRetrieverApi)

        // 3. WaitCode
        gateway.emitUpdate(
            TdApi.UpdateAuthorizationState(
                TdApi.AuthorizationStateWaitCode(
                    TdApi.AuthenticationCodeInfo(
                        "+84912345678",
                        TdApi.AuthenticationCodeTypeSms(5),
                        TdApi.AuthenticationCodeTypeCall(5),
                        0,
                    )
                )
            )
        )
        val waitCode = repo.uiState.value.authState as TelegramAuthUiState.WaitCode
        assertEquals(OtpDeliveryType.SMS, waitCode.codeType)
        assertEquals(5, waitCode.codeLength)
        repo.submitOtpCode("12345")
        assertTrue(gateway.sentQueries.any { it is TdApi.CheckAuthenticationCode && it.code == "12345" })

        // 4. WaitPassword (2FA)
        gateway.emitUpdate(
            TdApi.UpdateAuthorizationState(
                TdApi.AuthorizationStateWaitPassword("pet name", true, false, "a***@gmail.com")
            )
        )
        val waitPass = repo.uiState.value.authState as TelegramAuthUiState.WaitPassword
        assertEquals("pet name", waitPass.passwordHint)
        assertTrue(waitPass.hasRecoveryEmail)
        repo.submitPassword("my_secret")
        assertTrue(gateway.sentQueries.any { it is TdApi.CheckAuthenticationPassword && it.password == "my_secret" })

        // 5. Ready -> SearchPublicChat("Crosstesters") -> JoinChat -> OpenChat -> GetChatHistory backfill
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))

        assertTrue(repo.uiState.value.authState is TelegramAuthUiState.Ready)
        assertEquals(777L, cache.cachedId)
        assertTrue(gateway.sentQueries.any { it is TdApi.SearchPublicChat && it.username == "Crosstesters" })
        assertTrue(gateway.sentQueries.any { it is TdApi.JoinChat && it.chatId == 777L })
        assertTrue(gateway.sentQueries.any { it is TdApi.OpenChat && it.chatId == 777L })
        assertEquals(42, repo.uiState.value.chatInfo?.memberCount)

        val msgs = repo.uiState.value.messages
        assertEquals(listOf(30L, 20L, 10L), msgs.map { it.id })
        assertFalse(repo.uiState.value.hasMoreHistory)
        repo.dispose()
    }

    @Test
    fun cachedChatIdSkipsInviteCheckAndOpensDirectly() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        val cache = InMemoryChatIdStore(999L)

        gateway.handler = { query ->
            when (query) {
                is TdApi.GetChat -> TdApi.Chat().apply {
                    id = 999L
                    title = "Cached Group"
                    type = TdApi.ChatTypeBasicGroup(11L)
                }
                is TdApi.GetBasicGroup -> TdApi.BasicGroup().apply { id = 11L; memberCount = 15 }
                is TdApi.GetChatPinnedMessage -> createTextMessage(id = 5L, chatId = 999L, senderUserId = 1L, text = "Welcome pinned")
                is TdApi.GetChatHistory -> TdApi.Messages(0, emptyArray())
                is TdApi.GetMe -> TdApi.User().apply { id = 1L; firstName = "Admin" }
                else -> TdApi.Ok()
            }
        }

        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = gateway,
            chatIdCacheStore = cache,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )
        repo.ensureStarted()
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))

        assertTrue(repo.uiState.value.authState is TelegramAuthUiState.Ready)
        assertEquals("Cached Group", repo.uiState.value.chatInfo?.title)
        assertEquals("Welcome pinned", repo.uiState.value.chatInfo?.pinnedMessage?.previewText)
        assertFalse(gateway.sentQueries.any { it is TdApi.CheckChatInviteLink })
        assertFalse(gateway.sentQueries.any { it is TdApi.JoinChatByInviteLink })
        assertTrue(gateway.sentQueries.any { it is TdApi.GetChat && it.chatId == 999L })
        repo.dispose()
    }

    @Test
    fun waitRegistrationAndJoinRequestSentHandledCleanly() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        gateway.handler = { query ->
            when (query) {
                is TdApi.CheckChatInviteLink -> TdApi.ChatInviteLinkInfo().apply { chatId = 0L }
                is TdApi.JoinChatByInviteLink -> TdApi.ChatJoinResultRequestSent()
                else -> TdApi.Ok()
            }
        }
        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = gateway,
            chatIdCacheStore = InMemoryChatIdStore(),
            groupInviteUrl = "https://t.me/+PrivateInviteHash",
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            suggestedDisplayNameProvider = { "Nguyen An" },
            observeForeground = false,
        )
        repo.ensureStarted()
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitRegistration()))
        val regState = repo.uiState.value.authState as TelegramAuthUiState.WaitRegistration
        assertEquals("Nguyen", regState.suggestedFirstName)
        assertEquals("An", regState.suggestedLastName)

        repo.registerUser("Nguyen", "An")
        assertTrue(gateway.sentQueries.any { it is TdApi.RegisterUser && it.firstName == "Nguyen" && it.lastName == "An" })

        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        assertTrue(repo.uiState.value.authState is TelegramAuthUiState.JoinRequestSent)
        repo.dispose()
    }

    @Test
    fun realtimeUpdatesAndMessagingActionsWorkCorrectly() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        gateway.handler = { query ->
            when (query) {
                is TdApi.GetChat -> TdApi.Chat().apply { id = 500L; title = "Test Group" }
                is TdApi.GetChatPinnedMessage -> TdApi.Error(404, "None")
                is TdApi.GetChatHistory -> TdApi.Messages(0, emptyArray())
                is TdApi.GetMe -> TdApi.User().apply { id = 10L; firstName = "Me" }
                is TdApi.SendMessage -> createTextMessage(
                    id = 101L,
                    chatId = 500L,
                    senderUserId = 10L,
                    text = (query.inputMessageContent as? TdApi.InputMessageText)?.text?.text ?: "photo",
                    isOutgoing = true,
                )
                is TdApi.EditMessageText -> createTextMessage(
                    id = query.messageId,
                    chatId = 500L,
                    senderUserId = 10L,
                    text = (query.inputMessageContent as TdApi.InputMessageText).text.text,
                    isOutgoing = true,
                    editDate = 1700000100,
                )
                is TdApi.SearchChatMessages -> TdApi.FoundChatMessages(
                    1,
                    arrayOf(createTextMessage(id = 101L, chatId = 500L, senderUserId = 10L, text = "Edited hello")),
                    0L,
                )
                else -> TdApi.Ok()
            }
        }

        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = gateway,
            chatIdCacheStore = InMemoryChatIdStore(500L),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )
        repo.ensureStarted()
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))

        // New message arrives
        gateway.emitUpdate(
            TdApi.UpdateNewMessage(
                createTextMessage(id = 100L, chatId = 500L, senderUserId = 20L, text = "Hello")
            )
        )
        assertEquals(1, repo.uiState.value.messages.size)

        // Reply to message 100
        repo.setReplyingTo(repo.uiState.value.messages.first())
        repo.sendTextMessage("Reply text")
        val sendQuery = gateway.sentQueries.filterIsInstance<TdApi.SendMessage>().last()
        assertEquals(100L, (sendQuery.replyTo as TdApi.InputMessageReplyToMessage).messageId)
        assertEquals(2, repo.uiState.value.messages.size)

        // Edit message 101
        val outgoing = repo.uiState.value.messages.first { it.id == 101L }
        repo.setEditingMessage(outgoing)
        repo.sendTextMessage("Edited hello")
        assertTrue(gateway.sentQueries.any { it is TdApi.EditMessageText && it.messageId == 101L })
        assertTrue(repo.uiState.value.messages.first { it.id == 101L }.isEdited)

        // Toggle reaction
        repo.toggleReaction(101L, "👍")
        assertTrue(gateway.sentQueries.any { it is TdApi.AddMessageReaction && it.messageId == 101L })
        gateway.emitUpdate(
            TdApi.UpdateMessageInteractionInfo(
                500L,
                101L,
                TdApi.MessageInteractionInfo(
                    1,
                    0,
                    null,
                    TdApi.MessageReactions(
                        arrayOf(TdApi.MessageReaction(TdApi.ReactionTypeEmoji("👍"), 1, true, null, emptyArray())),
                        false,
                        emptyArray(),
                        false,
                    ),
                ),
            )
        )
        assertEquals(1, repo.uiState.value.messages.first { it.id == 101L }.reactions.first().count)
        repo.toggleReaction(101L, "👍")
        assertTrue(gateway.sentQueries.any { it is TdApi.RemoveMessageReaction && it.messageId == 101L })

        // Photo send
        repo.sendPhotoMessage("/tmp/pic.jpg", "Screenshot")
        assertTrue(
            gateway.sentQueries.any {
                it is TdApi.SendMessage && it.inputMessageContent is TdApi.InputMessagePhoto
            }
        )

        // Search & delete removes from both messages and searchResults
        repo.searchMessages("Edited")
        assertEquals(1, repo.uiState.value.searchResults.size)

        // Mark viewed & delete
        repo.markMessagesViewed(listOf(100L, 101L))
        assertTrue(gateway.sentQueries.any { it is TdApi.ViewMessages && it.messageIds.size == 2 && it.forceRead })
        repo.deleteMessage(101L, revoke = true)
        assertFalse(repo.uiState.value.messages.any { it.id == 101L })
        assertFalse(repo.uiState.value.searchResults.any { it.id == 101L })

        repo.dispose()
    }

    @Test
    fun retryInitializeRecoversAfterCheckChatInviteLinkError() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        var failInvite = true
        gateway.handler = { query ->
            when (query) {
                is TdApi.GetAuthorizationState -> TdApi.AuthorizationStateReady()
                is TdApi.GetMe -> TdApi.User().apply { id = 1L; firstName = "Tester" }
                is TdApi.CheckChatInviteLink -> {
                    if (failInvite) TdApi.Error(500, "TEMP_NETWORK_ERROR")
                    else TdApi.ChatInviteLinkInfo().apply { chatId = 888L }
                }
                is TdApi.GetChat -> TdApi.Chat().apply { id = 888L; title = "Recovered Group" }
                is TdApi.GetChatPinnedMessage -> TdApi.Error(404, "None")
                is TdApi.GetChatHistory -> TdApi.Messages(0, emptyArray())
                else -> TdApi.Ok()
            }
        }

        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = gateway,
            chatIdCacheStore = InMemoryChatIdStore(0L),
            groupInviteUrl = "https://t.me/+PrivateInviteHash",
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )
        repo.ensureStarted()
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        assertTrue(repo.uiState.value.authState is TelegramAuthUiState.Error)

        // Retry should query GetAuthorizationState and re-run resolveAndOpenGroupChat
        failInvite = false
        repo.retryInitialize()
        assertTrue(repo.uiState.value.authState is TelegramAuthUiState.Ready)
        assertEquals("Recovered Group", repo.uiState.value.chatInfo?.title)
        assertEquals(1, gateway.startCount)
        repo.dispose()
    }

    @Test
    fun sendMessageRaceWithEarlyUpdateMessageSendSucceededDoesNotDuplicate() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val tempId = 9001L
        val serverId = 999001L

        val suspGateway = object : TdLibGateway {
            private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 64)
            override val updates: Flow<TdApi.Object> = _updates
            override fun start() {}
            override fun close() {}
            override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
                val res: TdApi.Object = when (query) {
                    is TdApi.GetChat -> TdApi.Chat().apply { id = 500L; title = "Race Group" }
                    is TdApi.GetChatPinnedMessage -> TdApi.Error(404, "None")
                    is TdApi.GetChatHistory -> TdApi.Messages(0, emptyArray())
                    is TdApi.GetMe -> TdApi.User().apply { id = 10L; firstName = "Me" }
                    is TdApi.SendMessage -> {
                        // Simulate UpdateMessageSendSucceeded arriving BEFORE SendMessage returns
                        val confirmed = createTextMessage(
                            id = serverId,
                            chatId = 500L,
                            senderUserId = 10L,
                            text = "Race test",
                            isOutgoing = true,
                        )
                        _updates.tryEmit(TdApi.UpdateMessageSendSucceeded(confirmed, tempId))
                        createTextMessage(
                            id = tempId,
                            chatId = 500L,
                            senderUserId = 10L,
                            text = "Race test",
                            isOutgoing = true,
                        ).apply {
                            sendingState = TdApi.MessageSendingStatePending(1)
                        }
                    }
                    else -> TdApi.Ok()
                }
                if (res is TdApi.Error) throw TdLibException(res)
                @Suppress("UNCHECKED_CAST")
                return res as T
            }

            fun emit(obj: TdApi.Object) {
                assertTrue(_updates.tryEmit(obj))
            }
        }

        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = suspGateway,
            chatIdCacheStore = InMemoryChatIdStore(500L),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )
        repo.ensureStarted()
        suspGateway.emit(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))

        repo.sendTextMessage("Race test")
        val msgs = repo.uiState.value.messages
        assertEquals(listOf(serverId), msgs.map { it.id })
        assertFalse(msgs.first().isSending)
        repo.dispose()
    }

    @Test
    fun logOutAndReInitCleansStateAndRestartsAfterClosed() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        val cache = InMemoryChatIdStore(500L)
        gateway.handler = { query ->
            when (query) {
                is TdApi.GetChat -> TdApi.Chat().apply { id = 500L; title = "Group" }
                is TdApi.GetChatPinnedMessage -> TdApi.Error(404, "None")
                is TdApi.GetChatHistory -> TdApi.Messages(
                    1,
                    arrayOf(createTextMessage(id = 10L, chatId = 500L, senderUserId = 1L, text = "Old")),
                )
                is TdApi.GetMe -> TdApi.User().apply { id = 1L; firstName = "Me" }
                else -> TdApi.Ok()
            }
        }

        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = gateway,
            chatIdCacheStore = cache,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )
        repo.ensureStarted()
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        assertEquals(1, repo.uiState.value.messages.size)
        assertEquals(1, gateway.startCount)

        repo.logOut()
        assertEquals(0L, cache.cachedId)
        assertTrue(repo.uiState.value.messages.isEmpty())
        assertNull(repo.uiState.value.chatInfo)

        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
        assertEquals(2, gateway.startCount)

        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitPhoneNumber()))
        assertTrue(repo.uiState.value.authState is TelegramAuthUiState.WaitPhoneNumber)
        assertFalse(cache.loggedIn)
        assertEquals(0, repo.unreadCount.value)
        repo.dispose()
    }

    @Test
    fun startUnreadWatcherDoesNotStartTdLibWhenNotLoggedIn() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        val cache = InMemoryChatIdStore(cachedId = 0L, loggedIn = false)

        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = gateway,
            chatIdCacheStore = cache,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )

        repo.startUnreadWatcherIfLoggedIn()
        assertEquals(0, gateway.startCount)
        assertEquals(0, repo.unreadCount.value)
        assertFalse(repo.hasLoggedInSession())
        repo.dispose()
    }

    @Test
    fun unreadWatcherTracksGetChatAndUpdateChatReadInboxAndClearsOnOpenWithForceRead() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        val cache = InMemoryChatIdStore(cachedId = 500L, loggedIn = true)
        var serverUnreadCount = 4

        gateway.handler = { query ->
            when (query) {
                is TdApi.GetChat -> TdApi.Chat().apply {
                    id = 500L
                    title = "12 Cross Testers"
                    unreadCount = serverUnreadCount
                    lastMessage = createTextMessage(id = 200L, chatId = 500L, senderUserId = 2L, text = "Latest")
                }
                is TdApi.GetChatPinnedMessage -> TdApi.Error(404, "None")
                is TdApi.GetChatHistory -> TdApi.Messages(
                    1,
                    arrayOf(createTextMessage(id = 200L, chatId = 500L, senderUserId = 2L, text = "Latest")),
                )
                is TdApi.GetMe -> TdApi.User().apply { id = 1L; firstName = "Me" }
                else -> TdApi.Ok()
            }
        }

        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = gateway,
            chatIdCacheStore = cache,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )

        // Watcher starts TDLib without opening the chat
        repo.startUnreadWatcherIfLoggedIn()
        assertEquals(1, gateway.startCount)
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))

        assertFalse(gateway.sentQueries.any { it is TdApi.OpenChat })
        assertEquals(4, repo.unreadCount.value)

        // Realtime UpdateChatReadInbox updates unreadCount while on HomeScreen
        gateway.emitUpdate(TdApi.UpdateChatReadInbox(500L, 190L, 7))
        assertEquals(7, repo.unreadCount.value)

        // New incoming message while chat is closed syncs via GetChat
        serverUnreadCount = 8
        gateway.emitUpdate(
            TdApi.UpdateNewMessage(
                createTextMessage(id = 201L, chatId = 500L, senderUserId = 3L, text = "Ping")
            )
        )
        assertEquals(8, repo.unreadCount.value)

        // User opens TelegramChatScreen -> OpenChat + ViewMessages(forceRead = true) -> unreadCount becomes 0
        repo.ensureStarted()
        assertTrue(gateway.sentQueries.any { it is TdApi.OpenChat && it.chatId == 500L })
        assertTrue(gateway.sentQueries.any { it is TdApi.ViewMessages && it.forceRead && it.chatId == 500L })
        assertEquals(0, repo.unreadCount.value)

        // User leaves TelegramChatScreen -> CloseChat called, TDLib stays running to receive updates
        repo.closeActiveChat()
        assertTrue(gateway.sentQueries.any { it is TdApi.CloseChat && it.chatId == 500L })
        gateway.emitUpdate(TdApi.UpdateChatReadInbox(500L, 201L, 3))
        assertEquals(3, repo.unreadCount.value)

        // Log out -> immediately clears unreadCount & loggedIn flag
        repo.logOut()
        assertEquals(0, repo.unreadCount.value)
        assertFalse(cache.loggedIn)
        repo.dispose()
    }

    @Test
    fun extractPublicUsernameDistinguishesPublicAndPrivateLinks() {
        assertEquals("Crosstesters", TelegramChatRepository.extractPublicUsername("https://t.me/Crosstesters"))
        assertEquals("Crosstesters", TelegramChatRepository.extractPublicUsername("https://t.me/Crosstesters/"))
        assertEquals("Crosstesters", TelegramChatRepository.extractPublicUsername("@Crosstesters"))
        assertNull(TelegramChatRepository.extractPublicUsername("https://t.me/+KcJBT02zr1ZiOGNl"))
        assertNull(TelegramChatRepository.extractPublicUsername("https://t.me/joinchat/KcJBT02zr1ZiOGNl"))
        assertNull(TelegramChatRepository.extractPublicUsername("https://t.me/s/Crosstesters"))
    }

    @Test
    fun publicGroupSkipsJoinChatWhenAlreadyMemberAndPrivateInviteStillWorks() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeTdLibGateway()
        val cache = InMemoryChatIdStore(0L, loggedIn = true)

        gateway.handler = { query ->
            when (query) {
                is TdApi.SearchPublicChat -> TdApi.Chat().apply {
                    id = 808L
                    title = "Crosstesters Public"
                    unreadCount = 4
                    type = TdApi.ChatTypeSupergroup(909L, false)
                }
                is TdApi.GetSupergroup -> TdApi.Supergroup().apply {
                    id = 909L
                    status = TdApi.ChatMemberStatusMember()
                }
                is TdApi.GetChat -> TdApi.Chat().apply {
                    id = 808L
                    title = "Crosstesters Public"
                    unreadCount = 4
                    type = TdApi.ChatTypeSupergroup(909L, false)
                }
                is TdApi.GetChatPinnedMessage -> TdApi.Error(404, "None")
                is TdApi.GetChatHistory -> TdApi.Messages(0, emptyArray())
                else -> TdApi.Ok()
            }
        }

        val repo = TelegramChatRepository(
            context = app,
            apiId = 12345,
            apiHash = "test_hash",
            gateway = gateway,
            chatIdCacheStore = cache,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            observeForeground = false,
        )
        repo.startUnreadWatcherIfLoggedIn()
        gateway.emitUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))

        assertEquals(4, repo.unreadCount.value)
        assertEquals(808L, cache.cachedId)
        assertTrue(gateway.sentQueries.any { it is TdApi.SearchPublicChat && it.username == "Crosstesters" })
        assertFalse(gateway.sentQueries.any { it is TdApi.JoinChat })
        repo.dispose()
    }

    @Test
    fun dataStoreCacheInvalidatesOldChatIdWhenGroupUrlChanges() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val store = DataStoreTelegramChatIdCacheStore(app)
        store.clear()

        store.setCachedChatId(111L, "https://t.me/+KcJBT02zr1ZiOGNl")
        assertEquals(111L, store.getCachedChatId("https://t.me/+KcJBT02zr1ZiOGNl"))
        assertEquals(0L, store.getCachedChatId("https://t.me/Crosstesters"))

        store.setCachedChatId(222L, "https://t.me/Crosstesters")
        assertEquals(222L, store.getCachedChatId("https://t.me/Crosstesters"))
        store.clear()
    }

    @Test
    fun tdlibNativeLibrariesHave16KbPageAlignmentAndValidElfHeaders() {
        val jniLibsDir = listOf(
            File("tdlib/src/main/jniLibs"),
            File("../tdlib/src/main/jniLibs"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected tdlib/src/main/jniLibs directory to exist", jniLibsDir)

        for (abi in listOf("arm64-v8a", "x86_64")) {
            val soFile = File(jniLibsDir, "$abi/libtdjni.so")
            assertTrue("Missing $soFile", soFile.exists())
            assertTrue("Expected real prebuilt binary (>10 MB) for $abi", soFile.length() > 10_000_000L)
            val alignments = readElf64LoadAlignments(soFile)
            assertTrue("Expected at least one PT_LOAD segment in $abi", alignments.isNotEmpty())
            for (align in alignments) {
                assertTrue(
                    "Expected ELF PT_LOAD alignment >= 16384 (0x4000) for $abi, got $align",
                    align >= 16384L,
                )
            }
        }

        val armv7 = File(jniLibsDir, "armeabi-v7a/libtdjni.so")
        assertTrue("Missing $armv7", armv7.exists())
        assertTrue("Expected real prebuilt binary (>10 MB) for armeabi-v7a", armv7.length() > 10_000_000L)
        val header = ByteArray(5)
        RandomAccessFile(armv7, "r").use { it.readFully(header) }
        assertEquals(0x7F.toByte(), header[0])
        assertEquals('E'.code.toByte(), header[1])
        assertEquals('L'.code.toByte(), header[2])
        assertEquals('F'.code.toByte(), header[3])
        assertEquals(1.toByte(), header[4]) // ELFCLASS32
    }

    private fun readElf64LoadAlignments(file: File): List<Long> {
        RandomAccessFile(file, "r").use { raf ->
            val ehdr = ByteArray(64)
            raf.readFully(ehdr)
            val buf = ByteBuffer.wrap(ehdr).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x7F.toByte(), buf.get(0))
            assertEquals('E'.code.toByte(), buf.get(1))
            assertEquals('L'.code.toByte(), buf.get(2))
            assertEquals('F'.code.toByte(), buf.get(3))
            assertEquals(2.toByte(), buf.get(4)) // ELFCLASS64

            val ePhoff = buf.getLong(32)
            val ePhentsize = buf.getShort(54).toInt() and 0xFFFF
            val ePhnum = buf.getShort(56).toInt() and 0xFFFF
            assertEquals(56, ePhentsize)

            val result = mutableListOf<Long>()
            val phdr = ByteArray(ePhentsize)
            for (i in 0 until ePhnum) {
                raf.seek(ePhoff + i.toLong() * ePhentsize)
                raf.readFully(phdr)
                val pBuf = ByteBuffer.wrap(phdr).order(ByteOrder.LITTLE_ENDIAN)
                val pType = pBuf.getInt(0)
                if (pType == 1) { // PT_LOAD
                    result.add(pBuf.getLong(48)) // p_align
                }
            }
            return result
        }
    }

    private fun createTextMessage(
        id: Long,
        chatId: Long,
        senderUserId: Long,
        text: String,
        isOutgoing: Boolean = false,
        editDate: Int = 0,
    ): TdApi.Message = TdApi.Message().apply {
        this.id = id
        this.chatId = chatId
        this.senderId = TdApi.MessageSenderUser(senderUserId)
        this.isOutgoing = isOutgoing
        this.date = 1700000000
        this.editDate = editDate
        this.content = TdApi.MessageText(TdApi.FormattedText(text, emptyArray()), null, null)
    }
}
