package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.SessionCacheManager
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Independent review: dialog identity must follow auth before any network refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminPauseSessionIdentityReviewTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var client: SupabaseClient? = null

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        SessionCacheManager.clear(context)
    }

    @After
    fun cleanup() {
        store.clear()
        runBlocking { client?.close() }
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }

    private fun makeClient(
        missingPresetRpc: Boolean = false,
        presetCalls: AtomicInteger = AtomicInteger(),
        legacyCalls: AtomicInteger = AtomicInteger(),
        denyPresetRpc: Boolean = false,
    ): SupabaseClient = createSupabaseClient("https://fixture.invalid", "dummy-key") {
        install(Auth) {
            enableLifecycleCallbacks = false
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            autoLoadFromStorage = false
            autoSaveToStorage = false
        }
        install(Postgrest)
        httpEngine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/admin_pause_campaign_with_reason")) {
                presetCalls.incrementAndGet()
                if (missingPresetRpc) return@MockEngine respond(
                    """{"code":"PGRST202","message":"Could not find the function public.admin_pause_campaign_with_reason in the schema cache","details":null,"hint":null}""",
                    HttpStatusCode.NotFound,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                if (denyPresetRpc) return@MockEngine respond(
                    """{"code":"42501","message":"permission denied for function admin_pause_campaign_with_reason","details":null,"hint":null}""",
                    HttpStatusCode.Forbidden,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else if (request.url.encodedPath.endsWith("/admin_pause_campaign")) {
                legacyCalls.incrementAndGet()
            }
            val body = when {
                request.url.encodedPath.contains("get_campaign_detail") -> """{
                    "campaign":{"id":"identity-app","owner_id":"owner","app_name":"Review","package_name":"invalid.review.identity","status":"open"},
                    "my_profile":{"id":"staff","role":"admin","credits":100},
                    "is_member":true,"can_manage":true,"is_actual_owner":false,
                    "reports":[],"testers":[],"feedbacks":[],"my_checkin_dates":[],"unread_warnings":[]
                }"""
                request.url.encodedPath.contains("logout") -> "{}"
                else -> "[]"
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }.also { client = it }

    private suspend fun login(authClient: SupabaseClient, uid: String) {
        authClient.auth.awaitInitialization()
        authClient.auth.importSession(Json.decodeFromString<UserSession>(
            """{"access_token":"fixture-$uid","refresh_token":"refresh-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
        ))
    }

    private fun makeVm(authClient: SupabaseClient): CampaignDetailViewModel {
        val localizer = Localizer(context)
        return CampaignDetailViewModel(
            "identity-app", CampaignRepository(authClient, localizer),
            ReportSubmitManager(context), "admin-A", localizer, context
        ).also { store.put("review-identity", it) }
    }

    private suspend fun awaitReady(vm: CampaignDetailViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.uiState.value.isLoading || !vm.uiState.value.isAdmin) {
            dispatcher.scheduler.runCurrent()
            if (System.currentTimeMillis() > deadline) error("Initial fixture did not load")
            withContext(Dispatchers.IO) { Thread.sleep(5) }
        }
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun adminToAdminSwitch_updatesDialogIdentityWithoutWaitingForRefresh() = runBlocking {
        withTimeout(10_000) {
            val authClient = makeClient()
            login(authClient, "admin-A")
            val vm = makeVm(authClient)
            awaitReady(vm)
            assertEquals("admin-A", vm.uiState.value.currentUserId)

            login(authClient, "admin-B")
            dispatcher.scheduler.runCurrent()
            assertEquals("Dialog identity must leave the constructor's account immediately", "admin-B", vm.uiState.value.currentUserId)
        }
    }

    @Test
    fun logout_clearsDialogIdentityAndAdminAccessBeforeAnyRefresh() = runBlocking {
        withTimeout(10_000) {
            val authClient = makeClient()
            login(authClient, "admin-A")
            val vm = makeVm(authClient)
            awaitReady(vm)
            authClient.auth.signOut()
            dispatcher.scheduler.runCurrent()
            assertEquals("An old account must not continue owning the open dialog after logout", null, vm.uiState.value.currentUserId)
            assertFalse("Admin controls must close after logout", vm.uiState.value.isAdmin)
        }
    }

    @Test
    fun missingPresetRpc_explainsBackendUpdateAndNeverFallsBackOrReportsSuccess() = runBlocking {
        withTimeout(10_000) {
            val presetCalls = AtomicInteger()
            val legacyCalls = AtomicInteger()
            val authClient = makeClient(true, presetCalls, legacyCalls)
            login(authClient, "admin-A")
            val vm = makeVm(authClient)
            awaitReady(vm)
            var success = false
            vm.adminPauseCampaignWithReason("missing_google_group", onSuccess = { success = true })
            val deadline = System.currentTimeMillis() + 5_000
            while (vm.uiState.value.error == null && !success) {
                dispatcher.scheduler.runCurrent()
                if (System.currentTimeMillis() > deadline) error("Missing RPC did not publish an outcome")
                withContext(Dispatchers.IO) { Thread.sleep(5) }
            }
            assertFalse(success)
            assertEquals(1, presetCalls.get())
            assertEquals(0, legacyCalls.get())
            val message = vm.uiState.value.error.orEmpty()
            assertTrue("Missing RPC needs an actionable backend update message: $message",
                Regex("backend|máy chủ|server", RegexOption.IGNORE_CASE).containsMatchIn(message) &&
                    Regex("cập nhật|updat|triển khai", RegexOption.IGNORE_CASE).containsMatchIn(message))
        }
    }

    @Test
    fun permissionFailureOnExistingPresetRpc_preservesPermissionFeedback() = runBlocking {
        withTimeout(10_000) {
            val authClient = makeClient(denyPresetRpc = true)
            login(authClient, "admin-A")
            val vm = makeVm(authClient)
            awaitReady(vm)
            var success = false
            vm.adminPauseCampaignWithReason("policy_violation", onSuccess = { success = true })
            val deadline = System.currentTimeMillis() + 5_000
            while (vm.uiState.value.error == null && !success) {
                dispatcher.scheduler.runCurrent()
                if (System.currentTimeMillis() > deadline) error("Permission failure did not publish an outcome")
                withContext(Dispatchers.IO) { Thread.sleep(5) }
            }
            assertFalse(success)
            val message = vm.uiState.value.error.orEmpty()
            assertTrue("An existing RPC's permission failure must not be labeled a missing backend: $message",
                Regex("permission|quyền|authorized|forbidden|phép", RegexOption.IGNORE_CASE).containsMatchIn(message))
        }
    }

    @Test
    fun dialogReattachment_afterGenerationChangedBeforeOpening_preservesCurrentDraft() = runBlocking {
        withTimeout(10_000) {
            val authClient = makeClient()
            login(authClient, "admin-A")
            val vm = makeVm(authClient)
            awaitReady(vm)

            // Cache generation can advance after the auth collector has run
            // (e.g. another screen clears/reloads the shared session cache).
            SessionCacheManager.clear(context)
            vm.openAdminPauseDialog()
            vm.setAdminPauseCustomReason("Giữ bản nháp khi xoay màn hình")
            assertTrue(vm.adminPauseDialogState.value.isOpen)

            // A recreated screen synchronizes the controller using the live
            // identity exposed by its ViewModel, exactly as a new UI observer.
            vm.adminPauseDialogController.syncIdentity(
                vm.uiState.value.currentUserId,
                vm.uiState.value.sessionGeneration,
                vm.uiState.value.isAdmin,
            )
            assertTrue("A newly opened current-session dialog must survive UI reattachment", vm.adminPauseDialogState.value.isOpen)
            assertEquals("Giữ bản nháp khi xoay màn hình", vm.adminPauseDialogState.value.customReason)
        }
    }
}
