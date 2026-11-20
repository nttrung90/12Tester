package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.ViewModelStore
import com.ntt.crosstest.R
import com.ntt.crosstest.data.telegram.TdLibException
import com.ntt.crosstest.data.telegram.TdLibGateway
import com.ntt.crosstest.data.telegram.TelegramAuthUiState
import com.ntt.crosstest.data.telegram.TelegramChatIdCacheStore
import com.ntt.crosstest.data.telegram.TelegramChatRepository
import com.ntt.crosstest.data.telegram.TelegramChatViewMode
import com.ntt.crosstest.data.telegram.TelegramMirrorFeedProvider
import com.ntt.crosstest.data.telegram.TelegramMirrorRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TelegramChatViewModelTest {

    private class FakeGateway : TdLibGateway {
        private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 64)
        override val updates: Flow<TdApi.Object> = _updates
        val queries = mutableListOf<TdApi.Function<*>>()
        var startCount = 0
        val started: Boolean get() = startCount > 0

        override fun start() {
            startCount++
        }

        override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
            queries.add(query)
            val result: TdApi.Object = when (query) {
                is TdApi.GetChat -> TdApi.Chat().apply { id = 88L; title = "12Tester Chat" }
                is TdApi.GetChatPinnedMessage -> TdApi.Error(404, "None")
                is TdApi.GetChatHistory -> TdApi.Messages(0, emptyArray())
                is TdApi.GetMe -> TdApi.User().apply { id = 1L; firstName = "User" }
                is TdApi.SendMessage -> TdApi.Message().apply {
                    id = 50L
                    chatId = 88L
                    senderId = TdApi.MessageSenderUser(1L)
                    isOutgoing = true
                    content = TdApi.MessageText(TdApi.FormattedText("Hi", emptyArray()), null, null)
                }
                else -> TdApi.Ok()
            }
            if (result is TdApi.Error) throw TdLibException(result)
            @Suppress("UNCHECKED_CAST")
            return result as T
        }

        override fun close() {}

        fun emit(obj: TdApi.Object) {
            _updates.tryEmit(obj)
        }
    }

    private class FakeCache(
        private var id: Long = 88L,
        private var loggedIn: Boolean = false,
    ) : TelegramChatIdCacheStore {
        override suspend fun getCachedChatId(): Long = id
        override suspend fun setCachedChatId(chatId: Long) { id = chatId }
        override suspend fun isTelegramLoggedIn(): Boolean = loggedIn
        override suspend fun setTelegramLoggedIn(loggedIn: Boolean) { this.loggedIn = loggedIn }
        override suspend fun clear() {
            id = 0L
            loggedIn = false
        }
    }

    private class FakeMirrorProvider(
        private val handler: (Long?, Long?) -> String = { _, after ->
            if (after == null) {
                """
                {
                  "chat": { "chat_key": "community_main", "telegram_chat_id": -10088, "title": "Mirror Group" },
                  "messages": [
                    { "telegram_message_id": 10, "sender_id": 1, "sender_name": "Alice", "content_type": "text", "text_content": "Mirror Hello", "sent_at": "2026-11-20T00:01:00Z" }
                  ],
                  "has_more": false,
                  "server_time": "2026-11-20T00:01:30Z"
                }
                """.trimIndent()
            } else {
                """
                {
                  "chat": { "chat_key": "community_main", "telegram_chat_id": -10088, "title": "Mirror Group" },
                  "messages": [
                    { "telegram_message_id": 11, "sender_id": 2, "sender_name": "Bob", "content_type": "text", "text_content": "Polled Msg", "sent_at": "2026-11-20T00:02:00Z" }
                  ],
                  "has_more": false,
                  "server_time": "2026-11-20T00:02:10Z"
                }
                """.trimIndent()
            }
        }
    ) : TelegramMirrorFeedProvider {
        var callCount = 0
        override suspend fun fetchFeed(
            chatKey: String,
            beforeMessageId: Long?,
            afterMessageId: Long?,
            sinceEditedAt: String?,
            limit: Int,
        ): String {
            callCount++
            return handler(beforeMessageId, afterMessageId)
        }
    }

    @Test
    fun viewModelCoordinatesLoggedInAuthChatAndCleanupLifecycle() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeGateway()
        val cache = FakeCache(id = 88L, loggedIn = true)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = TelegramChatRepository(
            context = app,
            apiId = 99999,
            apiHash = "abc",
            gateway = gateway,
            chatIdCacheStore = cache,
            ioDispatcher = dispatcher,
            observeForeground = false,
        )
        val mirrorRepo = TelegramMirrorRepository(feedProvider = FakeMirrorProvider())
        val viewModel = TelegramChatViewModel(
            repository = repo,
            mirrorRepository = mirrorRepo,
            dispatcher = dispatcher,
        )
        val store = ViewModelStore().apply { put("vm", viewModel) }

        try {
            viewModel.onScreenEntered()
            assertEquals(TelegramChatViewMode.FULL_TDLIB, viewModel.uiState.value.viewMode)
            assertTrue(gateway.started)

            gateway.emit(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
            assertTrue(viewModel.uiState.value.authState is TelegramAuthUiState.Ready)
            assertEquals(TelegramChatViewMode.FULL_TDLIB, viewModel.uiState.value.viewMode)

            viewModel.onTypingChanged("Hello")
            assertTrue(gateway.queries.any { it is TdApi.SendChatAction })

            viewModel.sendTextMessage("Hi")
            assertEquals(1, viewModel.uiState.value.messages.size)

            viewModel.onScreenLeft()
            assertTrue(gateway.queries.any { it is TdApi.CloseChat && it.chatId == 88L })

            viewModel.logOut()
            assertTrue(gateway.queries.any { it is TdApi.LogOut })
            assertEquals(TelegramChatViewMode.READ_ONLY_MIRROR, viewModel.uiState.value.viewMode)
            assertFalse(cache.isTelegramLoggedIn())
            assertEquals(0, repo.unreadCount.value)
        } finally {
            store.clear()
            repo.dispose()
        }
    }

    @Test
    fun notLoggedInOpensReadOnlyMirrorWithoutStartingTdLibEvenWithMissingConfig() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeGateway()
        val mirrorProvider = FakeMirrorProvider()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = TelegramChatRepository(
            context = app,
            apiId = 0,
            apiHash = "",
            gateway = gateway,
            chatIdCacheStore = FakeCache(0L, loggedIn = false),
            ioDispatcher = dispatcher,
            observeForeground = false,
        )
        val mirrorRepo = TelegramMirrorRepository(feedProvider = mirrorProvider)
        val viewModel = TelegramChatViewModel(
            repository = repo,
            mirrorRepository = mirrorRepo,
            dispatcher = dispatcher,
            pollIntervalMs = 15_000L,
        )
        val store = ViewModelStore().apply { put("vm", viewModel) }

        try {
            viewModel.onScreenEntered()
            assertEquals(TelegramChatViewMode.READ_ONLY_MIRROR, viewModel.uiState.value.viewMode)
            assertFalse(viewModel.uiState.value.isConfigured)
            assertFalse(gateway.started)
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals("Mirror Group", viewModel.uiState.value.chatInfo?.title)

            // Switching to AUTH_FLOW when unconfigured still doesn't start TDLib and can return to mirror
            viewModel.requestTelegramLogin()
            assertEquals(TelegramChatViewMode.AUTH_FLOW, viewModel.uiState.value.viewMode)
            assertFalse(gateway.started)

            viewModel.backToReadOnlyMirror()
            assertEquals(TelegramChatViewMode.READ_ONLY_MIRROR, viewModel.uiState.value.viewMode)

            viewModel.onScreenLeft()
        } finally {
            store.clear()
            repo.dispose()
        }
    }

    @Test
    fun smoothModeTransitionsReadOnlyToAuthToFullTdlibToLogoutToReadOnlyAndPollingLifecycle() = runTest(timeout = 5.seconds) {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeGateway()
        val cache = FakeCache(id = 88L, loggedIn = false)
        val mirrorProvider = FakeMirrorProvider()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = TelegramChatRepository(
            context = app,
            apiId = 99999,
            apiHash = "abc",
            gateway = gateway,
            chatIdCacheStore = cache,
            ioDispatcher = dispatcher,
            observeForeground = false,
        )
        val mirrorRepo = TelegramMirrorRepository(feedProvider = mirrorProvider)
        val viewModel = TelegramChatViewModel(
            repository = repo,
            mirrorRepository = mirrorRepo,
            dispatcher = dispatcher,
            pollIntervalMs = 15_000L,
        )
        val store = ViewModelStore().apply { put("vm", viewModel) }

        try {
            // 1. Enter screen logged out -> READ_ONLY_MIRROR, TDLib not started
            viewModel.onScreenEntered()
            assertEquals(TelegramChatViewMode.READ_ONLY_MIRROR, viewModel.uiState.value.viewMode)
            assertFalse(gateway.started)
            assertEquals(listOf(10L), viewModel.uiState.value.messages.map { it.id })
            assertEquals(1, mirrorProvider.callCount)

            // 2. Delta polling fires after 15s while screen is in foreground
            advanceTimeBy(15_000L)
            runCurrent()
            assertEquals(2, mirrorProvider.callCount)
            assertEquals(listOf(11L, 10L), viewModel.uiState.value.messages.map { it.id })

            // App pause stops polling; resume refreshes and restarts polling
            viewModel.onAppPause()
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(2, mirrorProvider.callCount)

            viewModel.onAppResume()
            assertEquals(3, mirrorProvider.callCount)

            // 3. Request login -> AUTH_FLOW -> starts TDLib, stops mirror polling
            viewModel.requestTelegramLogin()
            assertEquals(TelegramChatViewMode.AUTH_FLOW, viewModel.uiState.value.viewMode)
            assertEquals(1, gateway.startCount)
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(3, mirrorProvider.callCount)

            // 4. User changes mind -> Back to READ_ONLY_MIRROR
            viewModel.backToReadOnlyMirror()
            assertEquals(TelegramChatViewMode.READ_ONLY_MIRROR, viewModel.uiState.value.viewMode)
            val callsBeforeSecondLogin = mirrorProvider.callCount

            // 5. Request login again -> WaitPhoneNumber -> Ready -> transitions to FULL_TDLIB
            viewModel.requestTelegramLogin()
            assertEquals(TelegramChatViewMode.AUTH_FLOW, viewModel.uiState.value.viewMode)
            gateway.emit(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitPhoneNumber()))
            assertTrue(viewModel.uiState.value.authState is TelegramAuthUiState.WaitPhoneNumber)

            viewModel.submitPhoneNumber("+84", "0988777666")
            assertTrue(gateway.queries.any { it is TdApi.SetAuthenticationPhoneNumber && it.phoneNumber == "+84988777666" })

            gateway.emit(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
            assertEquals(TelegramChatViewMode.FULL_TDLIB, viewModel.uiState.value.viewMode)
            assertTrue(viewModel.uiState.value.authState is TelegramAuthUiState.Ready)
            assertTrue(cache.isTelegramLoggedIn())
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(callsBeforeSecondLogin, mirrorProvider.callCount)

            // 6. Log out -> resets unread & flag, returns smoothly to READ_ONLY_MIRROR
            viewModel.logOut()
            assertEquals(TelegramChatViewMode.READ_ONLY_MIRROR, viewModel.uiState.value.viewMode)
            assertFalse(cache.isTelegramLoggedIn())
            assertEquals(0, repo.unreadCount.value)

            // 7. Leaving screen cancels polling cleanly
            val callsBeforeLeave = mirrorProvider.callCount
            viewModel.onScreenLeft()
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(callsBeforeLeave, mirrorProvider.callCount)
        } finally {
            store.clear()
            repo.dispose()
        }
    }

    @Test
    fun expiredSessionInFullTdlibFallsBackToReadOnlyMirror() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val gateway = FakeGateway()
        val cache = FakeCache(id = 88L, loggedIn = true)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = TelegramChatRepository(
            context = app,
            apiId = 99999,
            apiHash = "abc",
            gateway = gateway,
            chatIdCacheStore = cache,
            ioDispatcher = dispatcher,
            observeForeground = false,
        )
        val mirrorRepo = TelegramMirrorRepository(feedProvider = FakeMirrorProvider())
        val viewModel = TelegramChatViewModel(
            repository = repo,
            mirrorRepository = mirrorRepo,
            dispatcher = dispatcher,
        )
        val store = ViewModelStore().apply { put("vm", viewModel) }

        try {
            viewModel.onScreenEntered()
            assertEquals(TelegramChatViewMode.FULL_TDLIB, viewModel.uiState.value.viewMode)

            gateway.emit(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitPhoneNumber()))
            assertEquals(TelegramChatViewMode.READ_ONLY_MIRROR, viewModel.uiState.value.viewMode)
            assertFalse(cache.isTelegramLoggedIn())
            assertEquals(listOf(10L), viewModel.uiState.value.messages.map { it.id })

            viewModel.onScreenLeft()
        } finally {
            store.clear()
            repo.dispose()
        }
    }

    @Test
    fun telegramResourcesExistInEnglishAndVietnamese() {
        val app = RuntimeEnvironment.getApplication()
        val enCfg = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("en")) }
        val viCfg = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("vi")) }
        val enCtx = app.createConfigurationContext(enCfg)
        val viCtx = app.createConfigurationContext(viCfg)

        assertEquals("Telegram Group Chat", enCtx.getString(R.string.telegram_chat_title))
        assertEquals("Nhóm Chat Telegram", viCtx.getString(R.string.telegram_chat_title))
        assertTrue(enCtx.getString(R.string.telegram_missing_config_desc).contains("TELEGRAM_API_ID"))
        assertTrue(viCtx.getString(R.string.telegram_missing_config_desc).contains("TELEGRAM_API_ID"))

        assertEquals("Read-only preview", enCtx.getString(R.string.telegram_read_only_subtitle))
        assertEquals("Chế độ chỉ xem", viCtx.getString(R.string.telegram_read_only_subtitle))
        assertEquals("Photo — Sign in to Telegram to view clearly", enCtx.getString(R.string.telegram_readonly_photo_overlay))
        assertEquals("Hình ảnh — Đăng nhập Telegram để xem rõ nét", viCtx.getString(R.string.telegram_readonly_photo_overlay))
        assertEquals("Sign in to Telegram to join the chat", enCtx.getString(R.string.telegram_readonly_login_prompt))
        assertEquals("Đăng nhập Telegram để tham gia trò chuyện", viCtx.getString(R.string.telegram_readonly_login_prompt))
        assertEquals("Sign in to Telegram", enCtx.getString(R.string.telegram_readonly_login_btn))
        assertEquals("Đăng nhập Telegram", viCtx.getString(R.string.telegram_readonly_login_btn))
        assertEquals("Back to message preview", enCtx.getString(R.string.telegram_back_to_preview_btn))
        assertEquals("Quay lại xem tin nhắn", viCtx.getString(R.string.telegram_back_to_preview_btn))
        assertTrue(enCtx.getString(R.string.telegram_refresh_cd).isNotBlank())
        assertTrue(viCtx.getString(R.string.telegram_refresh_cd).isNotBlank())
        assertTrue(enCtx.getString(R.string.telegram_mirror_load_error).isNotBlank())
        assertTrue(viCtx.getString(R.string.telegram_mirror_load_error).isNotBlank())
    }
}
