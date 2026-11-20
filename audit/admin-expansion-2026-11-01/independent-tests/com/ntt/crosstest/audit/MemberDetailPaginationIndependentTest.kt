package com.ntt.crosstest.audit

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.ntt.crosstest.data.*
import com.ntt.crosstest.ui.viewmodels.MemberDetailViewModel
import io.github.jan.supabase.SupabaseClient
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MemberDetailPaginationIndependentTest {
    private val admin = "11111111-1111-1111-1111-111111111111"
    private val target = "22222222-2222-2222-2222-222222222222"
    private val time = "2026-11-01T08:00:00Z"

    private inner class FixtureRepository(client: SupabaseClient) : AdminManagementRepository(client) {
        val creditGate = CompletableDeferred<Unit>()
        var creditPageStarted = false
        var displayName = "Initial A"
        override suspend fun getMemberOverview(userId: String) = AdminMemberOverview(id = target, displayName = displayName)
        override suspend fun getMyCapabilities() = AdminMyCapabilities(callerId = admin, role = "admin")
        override suspend fun getMemberCreditHistory(userId: String, limit: Int, cursorCreatedAt: String?, cursorId: String?): AdminCreditHistoryEnvelope {
            if (cursorId == null) return AdminCreditHistoryEnvelope(
                items = listOf(AdminCreditHistoryItem("credit1", target, 1, createdAt = time)),
                hasMore = true, nextCursor = AdminDateTimeCursor(time, "credit1"),
            )
            creditPageStarted = true
            creditGate.await()
            return AdminCreditHistoryEnvelope(items = listOf(AdminCreditHistoryItem("credit2", target, 2, createdAt = time)))
        }
        override suspend fun getMemberDailyTasks(userId: String, limit: Int, cursorDate: String?) = AdminMemberDailyTasksEnvelope()
        override suspend fun getMemberWarnings(userId: String, limit: Int, cursorCreatedAt: String?, cursorId: String?) =
            if (cursorId == null) AdminMemberWarningsEnvelope(
                items = listOf(AdminMemberWarningItem("warning1", target, message = "one", createdAt = time)),
                hasMore = true, nextCursor = AdminDateTimeCursor(time, "warning1"),
            ) else AdminMemberWarningsEnvelope(items = listOf(AdminMemberWarningItem("warning2", target, message = "two", createdAt = time)))
    }

    @Test fun concurrentCreditAndWarningPaginationDoesNotDiscardCreditPageOrLeaveSpinner() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val app: Application = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(app)
        val store = ViewModelStore()
        val client = createSupabaseClient("https://fixture.invalid", "dummy") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        }
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
                """{"access_token":"fixture-token","refresh_token":"fixture-ref","expires_in":3600,"token_type":"bearer","user":{"id":"$admin","aud":"authenticated"}}"""
            ))
            val repository = FixtureRepository(client)
            val vm = MemberDetailViewModel(target, repository, AuthRepository(client, app), Localizer(app))
            store.put("member", vm)
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.uiState.value.isLoading)
            assertTrue(vm.uiState.value.creditHasMore)
            assertTrue(vm.uiState.value.warningsHasMore)

            vm.loadMoreCreditHistory()
            dispatcher.scheduler.runCurrent()
            assertTrue(repository.creditPageStarted)
            assertTrue(vm.uiState.value.isCreditsLoading)
            vm.loadMoreWarnings()
            dispatcher.scheduler.advanceUntilIdle()
            repository.creditGate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse("Starting warning pagination must not strand the credit spinner", vm.uiState.value.isCreditsLoading)
            assertEquals(listOf("credit1", "credit2"), vm.uiState.value.creditHistory.map { it.id })
            assertEquals(listOf("warning1", "warning2"), vm.uiState.value.warnings.map { it.id })
        } finally {
            store.clear()
            client.close()
            SessionCacheManager.clear(app)
            Dispatchers.resetMain()
        }
    }

    @Test fun staleAccountAPageCannotResetFreshAccountBState() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val app: Application = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(app)
        val store = ViewModelStore()
        val client = createSupabaseClient("https://fixture.invalid", "dummy") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        }
        val json = Json { ignoreUnknownKeys = true }
        suspend fun login(uid: String) {
            client.auth.awaitInitialization()
            client.auth.importSession(json.decodeFromString<UserSession>(
                """{"access_token":"fixture-token-$uid","refresh_token":"fixture-ref-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
            ))
        }
        try {
            login(admin)
            val repository = FixtureRepository(client)
            val vm = MemberDetailViewModel(target, repository, AuthRepository(client, app), Localizer(app))
            store.put("member", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.loadMoreCreditHistory()
            dispatcher.scheduler.runCurrent()
            assertTrue(repository.creditPageStarted)

            val adminB = "99999999-9999-9999-9999-999999999999"
            repository.displayName = "Fresh B"
            login(adminB)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(adminB, vm.uiState.value.currentAdminId)
            assertEquals("Fresh B", vm.uiState.value.overview?.displayName)

            repository.creditGate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("Old A response must not clear B overview", "Fresh B", vm.uiState.value.overview?.displayName)
            assertEquals(adminB, vm.uiState.value.currentAdminId)
            assertEquals(listOf("credit1"), vm.uiState.value.creditHistory.map { it.id })
        } finally {
            store.clear()
            client.close()
            SessionCacheManager.clear(app)
            Dispatchers.resetMain()
        }
    }
}
