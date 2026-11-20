package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.AppConfigRepository
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
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
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminOwnerAutoPauseViewModelTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val localizer by lazy { Localizer(context) }
    private val json = Json { ignoreUnknownKeys = true }
    private val testDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clientsToClose = mutableListOf<SupabaseClient>()

    private val adminId = "11111111-1111-1111-1111-111111111111"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        SessionCacheManager.clear(context)
    }

    @After
    fun cleanup() {
        store.clear()
        clientsToClose.forEach { runCatching { runBlocking { it.close() } } }
        clientsToClose.clear()
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun createClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient {
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
        clientsToClose.add(client)
        return client
    }

    private suspend fun login(c: SupabaseClient, uid: String) {
        c.auth.awaitInitialization()
        c.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"token-$uid","refresh_token":"ref-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )
    }

    private fun createAdminUsersVm(client: SupabaseClient): AdminUsersViewModel {
        val authRepo = AuthRepository(client, context)
        val appConfigRepo = AppConfigRepository(client, context)
        val campaignRepo = CampaignRepository(client, localizer)
        val vm = AdminUsersViewModel(campaignRepo, authRepo, appConfigRepo, localizer)
        store.put(UUID.randomUUID().toString(), vm)
        return vm
    }

    private fun latestJob(vm: ViewModel): Job? =
        vm.viewModelScope.coroutineContext[Job]?.children?.lastOrNull()

    private suspend fun awaitJob(job: Job?, timeoutMs: Long = 5000) {
        if (job == null) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!job.isCompleted) {
            testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceUntilIdle()
            if (job.isCompleted) break
            if (System.currentTimeMillis() > deadline) {
                fail("Job timed out after ${timeoutMs}ms")
            }
            withContext(Dispatchers.IO) {
                Thread.sleep(5)
            }
        }
        testDispatcher.scheduler.runCurrent()
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            testDispatcher.scheduler.runCurrent()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("Condition timed out")
            withContext(Dispatchers.IO) { Thread.sleep(5) }
        }
    }

    @Test
    fun testLoadOwnerAutoPauseConfig_success() = runBlocking {
        val client = createClient { req ->
            val path = req.url.encodedPath
            when {
                path.contains("admin_get_owner_auto_pause_config") ->
                    reply("""{"missed_days":7,"revision":"rev-init-123","updated_at":"2026-11-03T10:00:00Z"}""")
                path.contains("admin_get_daily_task_config") ->
                    reply("""{"today":"2026-11-03","timezone":"Asia/Ho_Chi_Minh","revision":"d1","has_more":false,"active_policy":{"id":1,"tests_per_active_app":3,"effective_from":null,"is_baseline":true,"operator_note":"b","updated_at":null},"future_policies":[]}""")
                path.contains("profiles") ->
                    reply("[]")
                else ->
                    reply("{}", HttpStatusCode.NotFound)
            }
        }
        login(client, adminId)
        val vm = createAdminUsersVm(client)

        vm.loadOwnerAutoPauseConfig()
        awaitJob(latestJob(vm))

        val state = vm.uiState.value
        assertFalse(state.isOwnerAutoPauseLoading)
        assertNotNull(state.ownerAutoPauseConfig)
        assertEquals(7, state.ownerAutoPauseConfig?.missedDays)
        assertEquals("rev-init-123", state.ownerAutoPauseConfig?.revision)
        assertNull(state.ownerAutoPauseError)
    }

    @Test
    fun testSaveOwnerAutoPauseConfig_success() = runBlocking {
        var currentDays = 7
        var currentRev = "rev-init"
        val client = createClient { req ->
            val path = req.url.encodedPath
            when {
                path.contains("admin_get_owner_auto_pause_config") ->
                    reply("""{"missed_days":$currentDays,"revision":"$currentRev","updated_at":"2026-11-03T10:00:00Z"}""")
                path.contains("admin_set_owner_auto_pause_config") -> {
                    assertEquals(
                        Json.parseToJsonElement("""{"p_missed_days":3,"p_expected_revision":"rev-init"}"""),
                        Json.parseToJsonElement(req.body.toByteArray().decodeToString()),
                    )
                    currentDays = 3
                    currentRev = "rev-new-456"
                    reply("""{"success":true,"missed_days":3,"revision":"rev-new-456","previous_missed_days":7}""")
                }
                path.contains("admin_get_daily_task_config") ->
                    reply("""{"today":"2026-11-03","timezone":"Asia/Ho_Chi_Minh","revision":"d1","has_more":false,"active_policy":{"id":1,"tests_per_active_app":3,"effective_from":null,"is_baseline":true,"operator_note":"b","updated_at":null},"future_policies":[]}""")
                path.contains("profiles") ->
                    reply("[]")
                else ->
                    reply("{}", HttpStatusCode.NotFound)
            }
        }
        login(client, adminId)
        val vm = createAdminUsersVm(client)

        vm.loadOwnerAutoPauseConfig()
        awaitJob(latestJob(vm))

        val initialSignal = vm.uiState.value.ownerAutoPauseSaveSignal

        vm.saveOwnerAutoPauseConfig(3)
        awaitJob(latestJob(vm))

        val state = vm.uiState.value
        assertFalse(state.isOwnerAutoPauseSaving)
        assertEquals(3, state.ownerAutoPauseConfig?.missedDays)
        assertEquals("rev-new-456", state.ownerAutoPauseConfig?.revision)
        assertEquals(initialSignal + 1, state.ownerAutoPauseSaveSignal)
        assertNotNull(state.actionMessage)
        assertNull(state.ownerAutoPauseError)
    }

    @Test
    fun testSaveOwnerAutoPauseConfig_concurrencyConflictReloads() = runBlocking {
        var loadCount = 0
        val client = createClient { req ->
            val path = req.url.encodedPath
            when {
                path.contains("admin_get_owner_auto_pause_config") -> {
                    loadCount++
                    if (loadCount == 1) {
                        reply("""{"missed_days":7,"revision":"rev-stale","updated_at":"2026-11-03T10:00:00Z"}""")
                    } else {
                        reply("""{"missed_days":4,"revision":"rev-fresh","updated_at":"2026-11-03T10:05:00Z"}""")
                    }
                }
                path.contains("admin_set_owner_auto_pause_config") -> {
                    reply("""{"message":"CONCURRENCY_CONFLICT: Config has been modified by another administrator"}""", HttpStatusCode.BadRequest)
                }
                path.contains("admin_get_daily_task_config") ->
                    reply("""{"today":"2026-11-03","timezone":"Asia/Ho_Chi_Minh","revision":"d1","has_more":false,"active_policy":{"id":1,"tests_per_active_app":3,"effective_from":null,"is_baseline":true,"operator_note":"b","updated_at":null},"future_policies":[]}""")
                path.contains("profiles") ->
                    reply("[]")
                else ->
                    reply("{}", HttpStatusCode.NotFound)
            }
        }
        login(client, adminId)
        val vm = createAdminUsersVm(client)

        vm.loadOwnerAutoPauseConfig()
        awaitJob(latestJob(vm))
        assertEquals("rev-stale", vm.uiState.value.ownerAutoPauseConfig?.revision)

        // Attempt save -> conflict
        vm.saveOwnerAutoPauseConfig(2)
        awaitJob(latestJob(vm))
        waitUntil { vm.uiState.value.ownerAutoPauseConfig?.revision == "rev-fresh" }

        val state = vm.uiState.value
        assertFalse(state.isOwnerAutoPauseSaving)
        assertNotNull(state.ownerAutoPauseError)
        // Verify it triggered auto reload and fetched fresh revision
        assertEquals("rev-fresh", state.ownerAutoPauseConfig?.revision)
        assertEquals(4, state.ownerAutoPauseConfig?.missedDays)
    }

    @Test
    fun testSaveOwnerAutoPauseConfig_missingRevisionError() = runBlocking {
        val client = createClient {
            reply("[]")
        }
        login(client, adminId)
        val vm = createAdminUsersVm(client)

        // Attempt save before config is loaded (revision is empty/null)
        vm.saveOwnerAutoPauseConfig(5)
        val state = vm.uiState.value
        assertNotNull(state.ownerAutoPauseError)
        assertFalse(state.isOwnerAutoPauseSaving)
    }

    @Test
    fun missingRpcLeavesConfigUnavailable() = runBlocking {
        val client = createClient { req ->
            if (req.url.encodedPath.contains("admin_get_owner_auto_pause_config")) {
                reply("""{"message":"Could not find the function"}""", HttpStatusCode.NotFound)
            } else {
                reply("[]")
            }
        }
        login(client, adminId)
        val vm = createAdminUsersVm(client)
        vm.loadOwnerAutoPauseConfig()
        awaitJob(latestJob(vm))

        assertNull(vm.uiState.value.ownerAutoPauseConfig)
        assertNotNull(vm.uiState.value.ownerAutoPauseError)
        assertFalse(vm.uiState.value.isOwnerAutoPauseLoading)
    }

    @Test
    fun saveResponseFromOldSessionGenerationIsDiscarded() = runBlocking {
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val client = createClient { req ->
            when {
                req.url.encodedPath.contains("admin_get_owner_auto_pause_config") ->
                    reply("""{"missed_days":7,"revision":"rev-current"}""")
                req.url.encodedPath.contains("admin_set_owner_auto_pause_config") -> {
                    saveStarted.complete(Unit)
                    releaseSave.await()
                    reply("""{"success":true,"missed_days":2,"revision":"rev-stale","previous_missed_days":7}""")
                }
                else -> reply("[]")
            }
        }
        login(client, adminId)
        val vm = createAdminUsersVm(client)
        vm.loadOwnerAutoPauseConfig()
        awaitJob(latestJob(vm))

        vm.saveOwnerAutoPauseConfig(2)
        val saveJob = latestJob(vm)
        testDispatcher.scheduler.runCurrent()
        withTimeout(5000) { saveStarted.await() }
        SessionCacheManager.clear(context)
        releaseSave.complete(Unit)
        awaitJob(saveJob)

        val state = vm.uiState.value
        assertNull(state.ownerAutoPauseConfig)
        assertFalse(state.isOwnerAutoPauseSaving)
        assertEquals(0, state.ownerAutoPauseSaveSignal)
    }
}
