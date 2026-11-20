package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.NotificationAckResult
import com.ntt.crosstest.data.NotificationPendingAckStore
import com.ntt.crosstest.data.NotificationPopupClaimResult
import com.ntt.crosstest.data.NotificationStatusResult
import com.ntt.crosstest.data.PendingAckStore
import com.ntt.crosstest.data.UserNotification
import com.ntt.crosstest.util.AppForegroundTracker
import com.ntt.crosstest.util.NotificationPopupBlockerCoordinator
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotificationPopupViewModelTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private val testDispatcher = UnconfinedTestDispatcher()
    private val clientsToClose = mutableListOf<SupabaseClient>()
    private val viewModelStore = ViewModelStore()

    private val userA = "11111111-1111-1111-1111-111111111111"
    private val userB = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        AppForegroundTracker.reset()
        NotificationPopupBlockerCoordinator.reset()
        context.getSharedPreferences("admin_notification_pending_acks", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun cleanup() {
        viewModelStore.clear()
        clientsToClose.forEach { runCatching { runBlocking { it.close() } } }
        clientsToClose.clear()
        AppForegroundTracker.reset()
        NotificationPopupBlockerCoordinator.reset()
        context.getSharedPreferences("admin_notification_pending_acks", Application.MODE_PRIVATE).edit().clear().commit()
        Dispatchers.resetMain()
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun createTestClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient {
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                val url = request.url.encodedPath
                if (url.contains("get_my_notification_status")) {
                    respond(json.encodeToString(NotificationStatusResult(exists = true, isRead = false)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    handler(request)
                }
            }
        }
        clientsToClose.add(client)
        return client
    }

    private fun createPopupViewModel(
        client: SupabaseClient,
        repo: CampaignRepository,
        store: PendingAckStore,
        autoStartPolling: Boolean = false,
    ): NotificationPopupViewModel {
        val vm = NotificationPopupViewModel(client, repo, store, AppForegroundTracker, NotificationPopupBlockerCoordinator, autoStartPolling = autoStartPolling)
        viewModelStore.put(UUID.randomUUID().toString(), vm)
        return vm
    }

    private suspend fun loginUser(client: SupabaseClient, uid: String) {
        client.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"fixture-$uid","refresh_token":"fixture-ref-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )
    }

    @Test
    fun lifecycle_rotation_and_recomposition_preserves_session_and_does_not_double_count() = runTest {
        val claimCount = AtomicInteger(0)
        val notif = UserNotification(id = "notif-1", title = "Admin Note", message = "Hello")

        val client = createTestClient { request ->
            val url = request.url.encodedPath
            if (url.contains("claim_my_notification_popup")) {
                claimCount.incrementAndGet()
                reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
            } else {
                reply("{}")
            }
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        // 1. Initial foreground
        AppForegroundTracker.simulateForeground("session-1")
        vm.tick(userA)

        assertEquals(1, claimCount.get())
        val state1 = vm.uiState.value
        assertTrue(state1 is NotificationPopupUiState.Showing)
        assertEquals("session-1", (state1 as NotificationPopupUiState.Showing).sessionId)

        // 2. Configuration change (rotation / locale / theme)
        AppForegroundTracker.simulateConfigurationChange()
        assertEquals("session-1", AppForegroundTracker.foregroundSessionId.value)

        // Tick again in same session -> must NOT re-claim
        vm.tick(userA)
        assertEquals(1, claimCount.get())
    }

    @Test
    fun popup_showing_persists_across_background_and_resume_without_reclaiming() = runTest {
        val claimCount = AtomicInteger(0)
        val notif = UserNotification(id = "notif-1", title = "Admin Note", message = "Hello")

        val client = createTestClient { request ->
            val url = request.url.encodedPath
            if (url.contains("claim_my_notification_popup")) {
                claimCount.incrementAndGet()
                reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
            } else {
                reply("{}")
            }
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-1")
        vm.tick(userA)

        assertEquals(1, claimCount.get())
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)

        // App goes to background
        AppForegroundTracker.simulateBackground()
        // State remains showing!
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)

        // App resumes
        AppForegroundTracker.simulateForeground("session-2")
        vm.tick(userA)

        // Does NOT dispatch a new claim RPC because popup is already showing!
        assertEquals(1, claimCount.get())
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)
        assertEquals("session-1", (vm.uiState.value as NotificationPopupUiState.Showing).sessionId)
    }

    @Test
    fun dismiss_later_or_back_stops_for_session_and_prompts_in_next_session() = runTest {
        val claimCount = AtomicInteger(0)
        val notif = UserNotification(id = "notif-1", title = "Admin Note", message = "Hello")

        val client = createTestClient { request ->
            val url = request.url.encodedPath
            if (url.contains("claim_my_notification_popup")) {
                val count = claimCount.incrementAndGet()
                reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = count)))
            } else {
                reply("{}")
            }
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        // Session 1
        AppForegroundTracker.simulateForeground("session-1")
        vm.tick(userA)
        assertEquals(1, claimCount.get())

        // User clicks "Để sau"
        vm.onDismissLater()
        assertEquals(NotificationPopupUiState.Idle, vm.uiState.value)

        // Tick in same session -> blocked
        vm.tick(userA)
        assertEquals(1, claimCount.get())

        // Session 2 (fresh foreground)
        AppForegroundTracker.simulateBackground()
        AppForegroundTracker.simulateForeground("session-2")
        vm.tick(userA)

        assertEquals(2, claimCount.get())
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)
        assertEquals(2, (vm.uiState.value as NotificationPopupUiState.Showing).displayCount)
    }

    @Test
    fun mark_as_read_persists_pending_store_before_dismiss_and_syncs_server() = runTest {
        val ackCount = AtomicInteger(0)
        val notif = UserNotification(id = "notif-99", title = "Admin Note", message = "Hello")

        val client = createTestClient { request ->
            val url = request.url.encodedPath
            if (url.contains("claim_my_notification_popup")) {
                reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
            } else if (url.contains("acknowledge_my_notification")) {
                ackCount.incrementAndGet()
                reply(json.encodeToString(NotificationAckResult(success = true, status = "read", notificationId = notif.id)))
            } else {
                reply("{}")
            }
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-1")
        vm.tick(userA)
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)

        // User clicks "Đã đọc"
        val syncJob = vm.onMarkAsRead()
        syncJob?.join()

        // Popup closes immediately
        assertEquals(NotificationPopupUiState.Idle, vm.uiState.value)
        assertEquals(1, ackCount.get())
        // Once synced, removed from pending store
        assertFalse(store.isPendingAck(userA, notif.id))
    }

    @Test
    fun local_save_failure_keeps_popup_open_for_retry() = runTest {
        val notif = UserNotification(id = "notif-fail", title = "Admin Note", message = "Hello")
        val client = createTestClient {
            reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
        }
        loginUser(client, userA)

        val failingStore = object : PendingAckStore {
            override fun recordPendingAck(userId: String, notificationId: String): Boolean = false
            override fun isPendingAck(userId: String, notificationId: String): Boolean = false
            override fun getPendingAcksForUser(userId: String): Set<String> = emptySet()
            override fun removePendingAck(userId: String, notificationId: String): Boolean = false
            override fun clearUser(userId: String) {}
        }

        val repo = CampaignRepository(client, Localizer(context))
        val vm = createPopupViewModel(client, repo, failingStore, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-1")
        vm.tick(userA)
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)

        vm.onMarkAsRead()

        // Popup remains open and shows error!
        val state = vm.uiState.value
        assertTrue(state is NotificationPopupUiState.Showing)
        assertEquals("local_save_failed", (state as NotificationPopupUiState.Showing).errorMessage)
    }

    @Test
    fun pending_ack_offline_restart_prevents_redisplay() = runTest {
        val notif = UserNotification(id = "notif-offline", title = "Admin Note", message = "Hello")
        val ackCount = AtomicInteger(0)

        val client = createTestClient { request ->
            val url = request.url.encodedPath
            if (url.contains("acknowledge_my_notification")) {
                ackCount.incrementAndGet()
                reply(json.encodeToString(NotificationAckResult(success = true, status = "read", notificationId = notif.id)))
            } else if (url.contains("claim_my_notification_popup")) {
                if (ackCount.get() > 0) {
                    reply(json.encodeToString(NotificationPopupClaimResult(status = "none")))
                } else {
                    reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
                }
            } else {
                reply("{}")
            }
        }
        loginUser(client, userA)

        val store = NotificationPendingAckStore(context)
        // Simulate previous offline session recorded pending ack
        store.recordPendingAck(userA, notif.id)

        val repo = CampaignRepository(client, Localizer(context))
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-restart")
        vm.tick(userA)

        // Must sync ack and NOT display popup
        assertEquals(1, ackCount.get())
        assertEquals(NotificationPopupUiState.Idle, vm.uiState.value)
    }

    @Test
    fun external_notification_read_or_delete_dismisses_popup() = runTest {
        val notif = UserNotification(id = "notif-ext", title = "Admin Note", message = "Hello")
        val client = createTestClient {
            reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-1")
        vm.tick(userA)
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)

        // User marks read via list/bottom sheet
        repo.markNotificationAsRead(notif.id)

        // Popup must be dismissed immediately!
        assertEquals(NotificationPopupUiState.Idle, vm.uiState.value)
    }

    @Test
    fun blocker_coordinator_prevents_claim_until_unblocked() = runTest {
        val claimCount = AtomicInteger(0)
        val notif = UserNotification(id = "notif-blocker", title = "Admin Note", message = "Hello")

        val client = createTestClient { request ->
            claimCount.incrementAndGet()
            reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-1")

        // 1. Blocked by reward overlay
        NotificationPopupBlockerCoordinator.setRewardOverlayShowing(true)
        vm.tick(userA)
        assertEquals(0, claimCount.get())
        assertEquals(NotificationPopupUiState.Idle, vm.uiState.value)

        // 2. Blocked by system warning
        NotificationPopupBlockerCoordinator.setRewardOverlayShowing(false)
        NotificationPopupBlockerCoordinator.setSystemWarningShowing(true)
        vm.tick(userA)
        assertEquals(0, claimCount.get())
        assertEquals(NotificationPopupUiState.Idle, vm.uiState.value)

        // 3. Unblocked
        NotificationPopupBlockerCoordinator.setSystemWarningShowing(false)
        vm.tick(userA)
        assertEquals(1, claimCount.get())
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)
    }

    @Test
    fun account_switch_cancels_jobs_and_closes_popup() = runTest {
        val notif = UserNotification(id = "notif-userA", title = "Admin Note", message = "Hello A")
        val client = createTestClient { request ->
            reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-1")
        vm.tick(userA)
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)

        // Switch to user B
        loginUser(client, userB)
        // State resets to Idle, user A popup is closed!
        assertEquals(NotificationPopupUiState.Idle, vm.uiState.value)
    }

    @Test
    fun multiple_blockers_and_app_loading_prevents_claim_until_all_cleared() = runTest {
        val claimCount = AtomicInteger(0)
        val notif = UserNotification(id = "notif-multi-blocker", title = "Admin Note", message = "Multi blocker")

        val client = createTestClient {
            claimCount.incrementAndGet()
            reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-1")

        // 3 blockers active simultaneously
        NotificationPopupBlockerCoordinator.setAppLoading(true)
        NotificationPopupBlockerCoordinator.setRewardOverlayShowing(true)
        NotificationPopupBlockerCoordinator.setModalDialogShowing(true)

        vm.tick(userA)
        assertEquals(0, claimCount.get())

        NotificationPopupBlockerCoordinator.setAppLoading(false)
        vm.tick(userA)
        assertEquals(0, claimCount.get())

        NotificationPopupBlockerCoordinator.setRewardOverlayShowing(false)
        vm.tick(userA)
        assertEquals(0, claimCount.get())

        // Final blocker cleared
        NotificationPopupBlockerCoordinator.setModalDialogShowing(false)
        vm.tick(userA)
        assertEquals(1, claimCount.get())
        assertTrue(vm.uiState.value is NotificationPopupUiState.Showing)
    }

    @Test
    fun held_popup_transitions_to_showing_when_blocker_cleared() = runTest {
        val notif = UserNotification(id = "notif-held", title = "Admin Note", message = "Held test")
        val client = createTestClient {
            // Blocker turns on right during RPC response
            NotificationPopupBlockerCoordinator.setSystemWarningShowing(true)
            reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-1")
        vm.tick(userA)

        // State is Held
        assertTrue("State must be Held while system warning is showing", vm.uiState.value is NotificationPopupUiState.Held)

        // Clear blocker -> automatically resumes to Showing
        NotificationPopupBlockerCoordinator.setSystemWarningShowing(false)
        testScheduler.runCurrent()

        assertTrue("State must become Showing after blocker is cleared", vm.uiState.value is NotificationPopupUiState.Showing)
        assertEquals("notif-held", (vm.uiState.value as NotificationPopupUiState.Showing).notification.id)
    }

    @Test
    fun carried_popup_dismissed_by_external_delete_consumes_resumed_session() = runTest {
        val notif = UserNotification(id = "notif-del", title = "Admin Note", message = "Delete carried")
        var claimCount = 0
        val client = createTestClient { request ->
            if (request.url.encodedPath.contains("claim_my_notification_popup")) {
                claimCount++
                reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = claimCount)))
            } else if (request.url.encodedPath.contains("delete")) {
                reply("[]")
            } else {
                reply("{}")
            }
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-1")
        vm.tick(userA)
        assertEquals(1, claimCount)

        // Resume in session-2
        AppForegroundTracker.simulateBackground()
        AppForegroundTracker.simulateForeground("session-2")
        vm.tick(userA)

        // Remote deletion from list
        repo.deleteNotification(notif.id)
        assertEquals(NotificationPopupUiState.Idle, vm.uiState.value)

        // Same session-2 tick again -> must NOT claim
        vm.tick(userA)
        assertEquals(1, claimCount)
    }

    @Test
    fun fetch_stale_response_cannot_restore_acknowledged_notification_as_unread() = runTest {
        val notifId = "stale-check-notif"
        val client = createTestClient { request ->
            when {
                request.url.encodedPath.contains("acknowledge_my_notification") ->
                    reply("""{"success":true,"status":"read","notification_id":"$notifId"}""")
                request.url.encodedPath.contains("notifications") ->
                    reply("""[{"id":"$notifId","user_id":"$userA","title":"Stale","message":"Msg","type":"admin","is_read":false}]""")
                else -> reply("{}")
            }
        }
        loginUser(client, userA)

        val repo = CampaignRepository(client, Localizer(context))

        // Acknowledge notification
        repo.acknowledgeNotification(notifId)

        // Stale fetch from server still returns is_read = false
        val list = repo.getMyNotifications(limit = 10, forceRefresh = true)
        val notif = list.first { it.id == notifId }

        assertTrue("Local read overlay must keep acknowledged notification as read even if stale fetch returned false", notif.isRead)
    }

    @Test
    fun manual_test_mode_autoStartPolling_false_does_not_start_polling_on_auth_change() = runTest {
        val claimCount = AtomicInteger(0)
        val notif = UserNotification(id = "notif-poll", title = "Admin Note", message = "Polling test")
        val client = createTestClient { request ->
            if (request.url.encodedPath.contains("claim_my_notification_popup")) {
                claimCount.incrementAndGet()
                reply(json.encodeToString(NotificationPopupClaimResult(status = "claimed", notification = notif, displayCount = 1)))
            } else {
                reply("{}")
            }
        }

        val repo = CampaignRepository(client, Localizer(context))
        val store = NotificationPendingAckStore(context)
        val vm = createPopupViewModel(client, repo, store, autoStartPolling = false)

        AppForegroundTracker.simulateForeground("session-poll")

        // Authenticate user -> observeAuthStatus must NOT launch restartPollingLoop when autoStartPolling=false
        loginUser(client, userA)
        testScheduler.advanceTimeBy(120_000L)
        testScheduler.runCurrent()

        assertEquals("No automatic claim calls must occur when autoStartPolling is false", 0, claimCount.get())

        // Explicit manual tick works as expected
        vm.tick(userA)
        assertEquals("Manual tick should claim exactly once", 1, claimCount.get())
    }
}
