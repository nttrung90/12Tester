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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import io.ktor.http.HttpMethod

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotificationPopupRound3RegressionTest {

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
    fun offline_popup_read_must_update_the_existing_home_list_before_server_sync() = runBlocking {
        val notifId = "offline-home-read"
        val notif = UserNotification(id = notifId, userId = userA)
        val client = createTestClient { request ->
            when {
                request.url.encodedPath.contains("get_my_dashboard") -> reply("""{"profile":{"id":"$userA","credits":100},"notifications":[{"id":"$notifId","user_id":"$userA","is_read":false}],"quest_summary":{}}""")
                request.url.encodedPath.contains("claim_my_notification_popup") -> reply(json.encodeToString(NotificationPopupClaimResult("claimed", notif, 1)))
                request.url.encodedPath.contains("acknowledge_my_notification") -> reply("""{"message":"offline"}""", HttpStatusCode.ServiceUnavailable)
                request.url.encodedPath.contains("/rpc/") -> reply("{}")
                else -> reply("[]")
            }
        }
        loginUser(client, userA)
        val repo = CampaignRepository(client, Localizer(context))
        val home = HomeViewModel(repo, localizer = Localizer(context))
        val store = NotificationPendingAckStore(context)
        val popup = NotificationPopupViewModel(client, repo, store, autoStartPolling = false)
        try {
            withTimeout(5000) { home.uiState.first { s -> s.notifications.any { it.id == notifId } && !s.isBackgroundSyncing } }
            AppForegroundTracker.simulateForeground("session-1")
            popup.tick(userA)
            assertTrue(popup.uiState.value is NotificationPopupUiState.Showing)
            popup.onMarkAsRead()?.join()
            assertTrue("The durable pending ack must still be present while offline", store.isPendingAck(userA, notifId))
            assertEquals(NotificationPopupUiState.Idle, popup.uiState.value)
            assertTrue("Local read intent must update Home even when the server ack fails", home.uiState.value.notifications.first { it.id == notifId }.isRead)
            assertEquals(0, home.uiState.value.unreadNotificationsCount)
        } finally { popup.viewModelScope.cancel(); home.viewModelScope.cancel(); client.close() }
    }

    @Test
    fun a_dashboard_started_before_ack_must_not_return_unread_after_ack_succeeds() = runTest {
        val notifId = "stale-dashboard-read"
        val dashboardStarted = CompletableDeferred<Unit>()
        val releaseDashboard = CompletableDeferred<Unit>()
        val client = createTestClient { request ->
            when {
                request.url.encodedPath.contains("get_my_dashboard") -> {
                    dashboardStarted.complete(Unit)
                    releaseDashboard.await()
                    reply("""{"profile":{"id":"$userA"},"notifications":[{"id":"$notifId","user_id":"$userA","is_read":false}]}""")
                }
                request.url.encodedPath.contains("acknowledge_my_notification") -> reply("""{"success":true,"status":"read","notification_id":"$notifId"}""")
                else -> reply("{}")
            }
        }
        loginUser(client, userA)
        val repo = CampaignRepository(client, Localizer(context))
        try {
            val oldDashboard = async { repo.getMyDashboard(forceRefresh = true) }
            dashboardStarted.await()
            repo.acknowledgeNotification(notifId)
            releaseDashboard.complete(Unit)
            val result = oldDashboard.await()!!
            assertTrue("The main Home dashboard path must apply the same read overlay as getMyNotifications", result.notifications.first { it.id == notifId }.isRead)
        } finally { releaseDashboard.complete(Unit); client.close() }
    }

    @Test
    fun a_late_mark_all_read_from_user_a_must_not_mark_user_b_home_list_as_read() = runBlocking {
        val oldUpdateStarted = CompletableDeferred<Unit>()
        val releaseOldUpdate = CompletableDeferred<Unit>()
        var dashboardOwner = userA
        val client = createTestClient { request ->
            when {
                request.method == HttpMethod.Patch && request.url.encodedPath.endsWith("/notifications") -> {
                    oldUpdateStarted.complete(Unit)
                    releaseOldUpdate.await()
                    reply("[]")
                }
                request.url.encodedPath.contains("get_my_dashboard") -> {
                    val owner = dashboardOwner
                    reply("""{"profile":{"id":"$owner","credits":100},"notifications":[{"id":"notif-$owner","user_id":"$owner","is_read":false}],"quest_summary":{}}""")
                }
                request.url.encodedPath.contains("/rpc/") -> reply("{}")
                else -> reply("[]")
            }
        }
        loginUser(client, userA)
        val repo = CampaignRepository(client, Localizer(context))
        val home = HomeViewModel(repo, localizer = Localizer(context))
        var oldAction: kotlinx.coroutines.Job? = null
        try {
            withTimeout(5000) { home.uiState.first { it.userProfile?.id == userA && !it.isBackgroundSyncing } }
            oldAction = launch { repo.markAllNotificationsAsRead() }
            withTimeout(5000) { oldUpdateStarted.await() }
            dashboardOwner = userB
            repo.clearMemoryCache()
            loginUser(client, userB)
            withTimeout(5000) { home.uiState.first { s -> s.userProfile?.id == userB && s.notifications.any { it.userId == userB } && !s.isBackgroundSyncing } }
            assertFalse(home.uiState.value.notifications.first { it.userId == userB }.isRead)
            releaseOldUpdate.complete(Unit)
            oldAction.join()
            assertFalse("Late A MarkedAllRead must not mark B's notifications as read", home.uiState.value.notifications.first { it.userId == userB }.isRead)
            assertEquals(1, home.uiState.value.unreadNotificationsCount)
        } finally { releaseOldUpdate.complete(Unit); oldAction?.cancel(); home.viewModelScope.cancel(); client.close() }
    }

    @Test
    fun a_late_claim_must_not_show_a_notification_already_read_from_the_list() = runTest {
        val notif = UserNotification(id = "read-during-claim", userId = userA)
        val claimStarted = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        val client = createTestClient { request ->
            if (request.url.encodedPath.contains("claim_my_notification_popup")) {
                claimStarted.complete(Unit)
                releaseClaim.await()
                reply(json.encodeToString(NotificationPopupClaimResult("claimed", notif, 1)))
            } else reply("[]")
        }
        loginUser(client, userA)
        val repo = CampaignRepository(client, Localizer(context))
        val popup = NotificationPopupViewModel(client, repo, NotificationPendingAckStore(context), autoStartPolling = false)
        try {
            AppForegroundTracker.simulateForeground("session-1")
            val oldClaim = async { popup.tick(userA) }
            claimStarted.await()
            repo.markNotificationAsRead(notif.id)
            releaseClaim.complete(Unit)
            oldClaim.await()
            val active = popup.uiState.value as? NotificationPopupUiState.Active
            assertFalse("A list read completed during the claim must invalidate its stale popup result", active?.notification?.id == notif.id)
        } finally { releaseClaim.complete(Unit); popup.viewModelScope.cancel(); client.close() }
    }
}
