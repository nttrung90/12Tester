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
import java.util.concurrent.atomic.AtomicInteger

/** Task data must obey request order across full AND partial refreshes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OwnerDailyTaskOrderingReviewTest {
    @Test
    fun olderFullTaskResponseCannotOverwriteNewerPartialResult() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val oldTaskStarted = CompletableDeferred<Unit>()
        val releaseOldTask = CompletableDeferred<Unit>()
        val taskCalls = AtomicInteger(0)
        val day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        val campaign = """{"id":"ordering-campaign","owner_id":"ordering-owner","app_name":"Ordering App","status":"paused","paused_reason":"owner_inactive","slot_number":1}"""
        val client = createSupabaseClient("https://ordering.invalid", "test-key") {
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
                    path.contains("get_campaign_detail") ->
                        """{"campaign":$campaign,"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"ordering-owner","role":"member","credits":1000}}"""
                    path.contains("get_my_daily_task_status") -> {
                        val debt = if (taskCalls.incrementAndGet() == 1) {
                            oldTaskStarted.complete(Unit)
                            releaseOldTask.await()
                            5
                        } else 0
                        """{"status_date":"$day","mode":"owner","met":true,"carryover_remaining_tests":$debt,"carryover_ready":true,"history_ready":true}"""
                    }
                    path.endsWith("/campaigns") -> "[$campaign]"
                    path.contains("get_campaign_mission") -> "null"
                    path.contains("get_business_quote") -> """{"cost":100,"revision":"ordering-revision"}"""
                    else -> "[]"
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        var viewModel: OwnerAppDetailViewModel? = null
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
                """{"access_token":"test-token","refresh_token":"test-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"ordering-owner","aud":"authenticated"}}"""
            ))
            val vm = OwnerAppDetailViewModel("ordering-campaign", CampaignRepository(client, Localizer(context)), Localizer(context))
            viewModel = vm
            withTimeout(10_000) { oldTaskStarted.await() }
            vm.refreshDailyTaskState()
            withTimeout(10_000) { vm.uiState.first { it.dailyTask?.carryoverRemainingTests == 0 } }
            releaseOldTask.complete(Unit)
            withTimeout(10_000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertFalse("Full page loading must finish", vm.uiState.value.isLoading)
            assertEquals("A late old full response must not replace a newer debt-free partial result", 0, vm.uiState.value.dailyTask?.carryoverRemainingTests)
        } finally {
            releaseOldTask.complete(Unit)
            viewModel?.viewModelScope?.cancel()
            client.close()
            SessionCacheManager.clear(context)
            Dispatchers.resetMain()
        }
    }
}
