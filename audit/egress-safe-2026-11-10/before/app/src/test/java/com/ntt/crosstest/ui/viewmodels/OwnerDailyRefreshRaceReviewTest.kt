package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Supervisor regression: a partial task reload cannot strand a full page load. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OwnerDailyRefreshRaceReviewTest {
    @Test
    fun partialTaskReloadDuringFullRefreshDoesNotLeavePageLoading() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val detailStarted = CompletableDeferred<Unit>()
        val releaseDetail = CompletableDeferred<Unit>()
        val day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        val campaign = """{"id":"review-campaign","owner_id":"review-owner","app_name":"Review App","status":"paused","paused_reason":"owner_inactive","slot_number":1}"""
        val client = createSupabaseClient("https://review.invalid", "test-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                val path = request.url.encodedPath
                val body = when {
                    path.contains("get_campaign_detail") -> {
                        detailStarted.complete(Unit)
                        releaseDetail.await()
                        """{"campaign":$campaign,"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"review-owner","role":"member","credits":1000}}"""
                    }
                    path.contains("get_my_daily_task_status") ->
                        """{"status_date":"$day","mode":"owner","met":true,"carryover_remaining_tests":0,"carryover_ready":true,"history_ready":true}"""
                    path.endsWith("/campaigns") -> "[$campaign]"
                    path.contains("get_campaign_mission") -> "null"
                    path.contains("get_business_quote") -> """{"cost":100,"revision":"review-revision"}"""
                    else -> "[]"
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        var viewModel: OwnerAppDetailViewModel? = null
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
                """{"access_token":"test-token","refresh_token":"test-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"review-owner","aud":"authenticated"}}"""
            ))
            val vm = OwnerAppDetailViewModel("review-campaign", CampaignRepository(client, Localizer(context)), Localizer(context))
            viewModel = vm
            withTimeout(10_000) { detailStarted.await() }
            vm.refreshDailyTaskState()
            withTimeout(10_000) { vm.uiState.first { it.dailyTask != null && it.campaign != null } }
            releaseDetail.complete(Unit)
            withTimeout(10_000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertFalse("A partial task reload must not cancel the full refresh and leave isLoading stuck", vm.uiState.value.isLoading)
            assertFalse("Background synchronization must settle after both refreshes finish", vm.uiState.value.isBackgroundSyncing)
            assertEquals("Full page owner context must still be applied", true, vm.uiState.value.isActualOwner)
        } finally {
            releaseDetail.complete(Unit)
            viewModel?.viewModelScope?.cancel()
            client.close()
            SessionCacheManager.clear(context)
            Dispatchers.resetMain()
        }
    }
}
