package com.ntt.crosstest.ui.viewmodels

import android.app.Application
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
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric
import android.app.Activity

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotificationPopupReviewRegressionTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private val testDispatcher = UnconfinedTestDispatcher()

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
        AppForegroundTracker.reset()
        NotificationPopupBlockerCoordinator.reset()
        context.getSharedPreferences("admin_notification_pending_acks", Application.MODE_PRIVATE).edit().clear().commit()
        Dispatchers.resetMain()
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun createTestClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://fixture.invalid", "dummy-key") {
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

    private suspend fun loginUser(client: SupabaseClient, uid: String) {
        client.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"fixture-$uid","refresh_token":"fixture-ref-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )
    }

    @Test
    fun pending_read_must_retry_even_after_this_foreground_session_is_consumed() = runTest {
        val ackCalls = AtomicInteger()
        var ackSucceeds = false
        val notif = UserNotification(id = "retry-read", userId = userA)
        val client = createTestClient { request ->
            when {
                request.url.encodedPath.contains("claim_my_notification_popup") ->
                    reply(json.encodeToString(NotificationPopupClaimResult("claimed", notif, 1)))
                request.url.encodedPath.contains("acknowledge_my_notification") -> {
                    ackCalls.incrementAndGet()
                    if (ackSucceeds) reply("""{"success":true,"status":"read"}""")
                    else reply("""{"message":"offline"}""", HttpStatusCode.ServiceUnavailable)
                }
                else -> reply("{}")
            }
        }
        loginUser(client, userA)
        val store = NotificationPendingAckStore(context)
        val vm = NotificationPopupViewModel(client, CampaignRepository(client, Localizer(context)), store, autoStartPolling = false)
        try {
            AppForegroundTracker.simulateForeground("session-1")
            vm.tick(userA)
            vm.onMarkAsRead()?.join()
            assertTrue(store.isPendingAck(userA, notif.id))
            val callsBeforeReconnect = ackCalls.get()
            ackSucceeds = true
            vm.tick(userA)
            assertTrue("Reconnect tick must retry ack even though popup session was consumed", ackCalls.get() > callsBeforeReconnect)
            assertFalse(store.isPendingAck(userA, notif.id))
        } finally { vm.viewModelScope.cancel(); client.close() }
    }

    @Test
    fun unsynced_pending_read_must_prevent_new_claim_from_consuming_an_invisible_view() = runTest {
        val claimCalls = AtomicInteger()
        val notif = UserNotification(id = "pending-read", userId = userA)
        val client = createTestClient { request ->
            when {
                request.url.encodedPath.contains("acknowledge_my_notification") ->
                    reply("""{"message":"ack temporarily unavailable"}""", HttpStatusCode.ServiceUnavailable)
                request.url.encodedPath.contains("claim_my_notification_popup") -> {
                    claimCalls.incrementAndGet()
                    reply(json.encodeToString(NotificationPopupClaimResult("claimed", notif, 1)))
                }
                else -> reply("{}")
            }
        }
        loginUser(client, userA)
        val store = NotificationPendingAckStore(context)
        store.recordPendingAck(userA, notif.id)
        val vm = NotificationPopupViewModel(client, CampaignRepository(client, Localizer(context)), store, autoStartPolling = false)
        try {
            AppForegroundTracker.simulateForeground("restart-session")
            vm.tick(userA)
            assertEquals("Do not claim while pending ack remains unsynced", 0, claimCalls.get())
        } finally { vm.viewModelScope.cancel(); client.close() }
    }

    @Test
    fun closing_a_popup_carried_across_resume_must_consume_current_session_too() = runTest {
        val claimCalls = AtomicInteger()
        val notif = UserNotification(id = "carried-popup", userId = userA)
        val client = createTestClient { request ->
            if (request.url.encodedPath.contains("claim_my_notification_popup")) {
                val count = claimCalls.incrementAndGet()
                reply(json.encodeToString(NotificationPopupClaimResult("claimed", notif, count)))
            } else reply("{}")
        }
        loginUser(client, userA)
        val vm = NotificationPopupViewModel(client, CampaignRepository(client, Localizer(context)), NotificationPendingAckStore(context), autoStartPolling = false)
        try {
            AppForegroundTracker.simulateForeground("session-1")
            vm.tick(userA)
            AppForegroundTracker.simulateBackground()
            AppForegroundTracker.simulateForeground("session-2")
            vm.tick(userA)
            vm.onDismissLater()
            vm.tick(userA)
            assertEquals("Dismissing the resumed popup must not reopen it in session-2", 1, claimCalls.get())
            assertEquals(NotificationPopupUiState.Idle, vm.uiState.value)
        } finally { vm.viewModelScope.cancel(); client.close() }
    }

    @Test
    fun a_blocker_appearing_during_claim_must_prevent_showing_the_returned_popup() = runTest {
        val notif = UserNotification(id = "late-claim", userId = userA)
        val client = createTestClient { request ->
            if (request.url.encodedPath.contains("claim_my_notification_popup")) {
                NotificationPopupBlockerCoordinator.setSystemWarningShowing(true)
                reply(json.encodeToString(NotificationPopupClaimResult("claimed", notif, 1)))
            } else reply("{}")
        }
        loginUser(client, userA)
        val vm = NotificationPopupViewModel(client, CampaignRepository(client, Localizer(context)), NotificationPendingAckStore(context), autoStartPolling = false)
        try {
            AppForegroundTracker.simulateForeground("session-1")
            vm.tick(userA)
            assertTrue(NotificationPopupBlockerCoordinator.isBlocked.value)
            // The current host renders every Showing state without observing blockers.
            assertFalse("Do not publish a visible Showing state over an active system warning", vm.uiState.value is NotificationPopupUiState.Showing)
        } finally { vm.viewModelScope.cancel(); client.close() }
    }

    @Test
    fun real_activity_start_must_publish_new_session_before_foreground_event() = runTest {
        var sessionSeenAtForeground: String? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            AppForegroundTracker.isForeground.collect { active ->
                if (active) sessionSeenAtForeground = AppForegroundTracker.foregroundSessionId.value
            }
        }
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        AppForegroundTracker.onActivityStarted(activity)
        assertNotNull(AppForegroundTracker.foregroundSessionId.value)
        assertEquals("A foreground observer must immediately see the new session ID", AppForegroundTracker.foregroundSessionId.value, sessionSeenAtForeground)
    }

    @Test
    fun successful_popup_ack_must_update_the_already_loaded_home_notification_list() = runBlocking {
        val notifId = "home-list-notif"
        val client = createTestClient { request ->
            when {
                request.url.encodedPath.contains("get_my_dashboard") -> reply("""{"profile":{"id":"$userA","credits":100},"notifications":[{"id":"$notifId","user_id":"$userA","is_read":false}],"quest_summary":{}}""")
                request.url.encodedPath.contains("acknowledge_my_notification") -> reply("""{"success":true,"status":"read","notification_id":"$notifId"}""")
                request.url.encodedPath.contains("/rpc/") -> reply("{}")
                else -> reply("[]")
            }
        }
        loginUser(client, userA)
        val repo = CampaignRepository(client, Localizer(context))
        val home = HomeViewModel(repo, localizer = Localizer(context))
        try {
            withTimeout(5000) { home.uiState.first { state -> state.notifications.any { it.id == notifId } && !state.isBackgroundSyncing } }
            assertFalse(home.uiState.value.notifications.first { it.id == notifId }.isRead)
            repo.acknowledgeNotification(notifId)
            assertTrue("A successful popup acknowledgment must update Home without manual refresh", home.uiState.value.notifications.first { it.id == notifId }.isRead)
            assertEquals(0, home.uiState.value.unreadNotificationsCount)
        } finally { home.viewModelScope.cancel(); client.close() }
    }
}
