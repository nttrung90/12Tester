package com.ntt.crosstest.audit

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.ntt.crosstest.data.*
import com.ntt.crosstest.ui.viewmodels.CarryoverPolicyViewModel
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
class CarryoverStreamsIndependentTest {
    @Test fun futurePageFailureMustExposeErrorAndRecoverOnExplicitRefresh() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val app: Application = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(app)
        val store = ViewModelStore()
        val client = createSupabaseClient("https://fixture.invalid", "dummy") {
            install(Auth) {
                sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false; autoSaveToStorage = false; enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        }
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
                """{"access_token":"fixture","refresh_token":"fixture","expires_in":3600,"token_type":"bearer","user":{"id":"11111111-1111-1111-1111-111111111111","aud":"authenticated"}}"""
            ))
            val items = (1..51).map { n -> AdminCarryoverPolicyItem(
                id = n.toString(), effectiveFrom = java.time.LocalDate.now().plusDays(n.toLong()).toString(),
            ) }
            var failPage = true
            val repo = object : AdminManagementRepository(client) {
                override suspend fun getCarryoverPolicies() = AdminCarryoverPoliciesResult(future = items.take(50), revision = "1")
                override suspend fun listFutureCarryoverPolicies(limit: Int, cursorDate: String?): AdminListFutureCarryoverPoliciesEnvelope {
                    if (failPage) throw java.io.IOException("Fixture page response unavailable")
                    return if (cursorDate == null) AdminListFutureCarryoverPoliciesEnvelope(
                        items = items.take(50), hasMore = true, nextCursor = AdminDateCursor(items[49].effectiveFrom!!),
                    ) else {
                        assertEquals(items[49].effectiveFrom, cursorDate)
                        AdminListFutureCarryoverPoliciesEnvelope(items = items.drop(50))
                    }
                }
            }
            val vm = CarryoverPolicyViewModel(repo, AuthRepository(client, app), Localizer(app))
            store.put("carryover-error", vm)
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.uiState.value.isLoading)
            assertNotNull("A failed future page must expose an error for explicit retry", vm.uiState.value.error)
            assertFalse("A snapshot of50 rows cannot manufacture a valid next-page cursor", vm.uiState.value.futureHasMore)
            failPage = false
            vm.loadPolicies()
            dispatcher.scheduler.advanceUntilIdle()
            assertNull(vm.uiState.value.error)
            assertTrue(vm.uiState.value.futureHasMore)
            vm.loadMoreFuturePolicies()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals((1..51).map(Int::toString), vm.uiState.value.futurePolicies.map { it.id })
            assertFalse(vm.uiState.value.futureHasMore)
        } finally {
            store.clear(); client.close(); SessionCacheManager.clear(app); Dispatchers.resetMain()
        }
    }

    @Test fun auditHistoryRequestCannotInvalidatePolicyRefreshOrStrandSpinner() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val app: Application = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(app)
        val store = ViewModelStore()
        val client = createSupabaseClient("https://fixture.invalid", "dummy") {
            install(Auth) {
                sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false; autoSaveToStorage = false; enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        }
        val releasePolicy = CompletableDeferred<Unit>()
        var policyStarted = false
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
                """{"access_token":"fixture","refresh_token":"fixture","expires_in":3600,"token_type":"bearer","user":{"id":"11111111-1111-1111-1111-111111111111","aud":"authenticated"}}"""
            ))
            val repo = object : AdminManagementRepository(client) {
                override suspend fun getCarryoverPolicies(): AdminCarryoverPoliciesResult {
                    policyStarted = true; releasePolicy.await()
                    return AdminCarryoverPoliciesResult(active = AdminCarryoverPolicyItem(id = "1", operatorNote = "Fresh policy"), revision = "1")
                }
                override suspend fun listFutureCarryoverPolicies(limit: Int, cursorDate: String?) = AdminListFutureCarryoverPoliciesEnvelope()
                override suspend fun getCarryoverPolicyHistory(limit: Int, cursorId: Long?, includeCancelled: Boolean) = AdminCarryoverAuditHistoryEnvelope()
            }
            val vm = CarryoverPolicyViewModel(repo, AuthRepository(client, app), Localizer(app))
            store.put("carryover", vm)
            dispatcher.scheduler.runCurrent()
            assertTrue(policyStarted)
            vm.loadAuditHistory(reset = true)
            dispatcher.scheduler.runCurrent()
            releasePolicy.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse("Audit pagination must not strand policy refresh spinner", vm.uiState.value.isLoading)
            assertEquals("Fresh policy", vm.uiState.value.activePolicy?.operatorNote)
        } finally {
            releasePolicy.complete(Unit); store.clear(); client.close()
            SessionCacheManager.clear(app); Dispatchers.resetMain()
        }
    }
}
