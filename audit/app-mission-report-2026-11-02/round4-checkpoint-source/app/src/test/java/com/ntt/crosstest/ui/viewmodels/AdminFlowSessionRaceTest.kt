package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.data.AdminAdjustCreditsResult
import com.ntt.crosstest.data.AdminSetUserLockResult
import com.ntt.crosstest.data.AppConfig
import com.ntt.crosstest.data.AppConfigRepository
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.ContactReport
import com.ntt.crosstest.data.HomeStatsPolicyConfig
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Profile
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
class AdminFlowSessionRaceTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val localizer by lazy { Localizer(context) }
    private val json = Json { ignoreUnknownKeys = true }
    private val testDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clientsToClose = mutableListOf<SupabaseClient>()

    private val adminA = "11111111-1111-1111-1111-111111111111"
    private val adminB = "22222222-2222-2222-2222-222222222222"
    private val userTarget = "33333333-3333-3333-3333-333333333333"

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

    private fun createAdminReportsVm(client: SupabaseClient): AdminReportsViewModel {
        val authRepo = AuthRepository(client, context)
        val campaignRepo = CampaignRepository(client, localizer)
        val vm = AdminReportsViewModel(campaignRepo, localizer, authRepo)
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
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 5000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceUntilIdle()
            if (condition()) break
            if (System.currentTimeMillis() > deadline) {
                fail("Condition timed out after ${timeoutMs}ms")
            }
            withContext(Dispatchers.IO) {
                Thread.sleep(5)
            }
        }
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Stale vs Newer Refresh in AdminReportsViewModel
    // ---------------------------------------------------------------------------------------------

    @Test
    fun delayed_stale_refresh_does_not_overwrite_newer_refresh_in_admin_reports() = runBlocking {
        val report1Started = CompletableDeferred<Unit>()
        val releaseReport1 = CompletableDeferred<Unit>()
        val requestIndex = AtomicInteger(0)

        val client = createClient { request ->
            val path = request.url.encodedPath
            if (path.contains("admin_get_contact_reports")) {
                val idx = requestIndex.incrementAndGet()
                if (idx == 1) {
                    report1Started.complete(Unit)
                    releaseReport1.await()
                    reply(json.encodeToString(listOf(ContactReport(id = "rep-old", userId = "u1", message = "Old report"))))
                } else {
                    reply(json.encodeToString(listOf(ContactReport(id = "rep-new", userId = "u1", message = "New report"))))
                }
            } else {
                reply("[]")
            }
        }
        login(client, adminA)

        val vm = createAdminReportsVm(client)
        testDispatcher.scheduler.runCurrent()

        // Wait until Request 1 is actively inside MockEngine waiting on releaseReport1
        report1Started.await()
        assertTrue("Request 1 is in-flight", vm.uiState.value.isLoading)

        // Launch Request 2 (newer refresh)
        vm.refresh(forceRefresh = true)
        testDispatcher.scheduler.runCurrent()

        // Wait until Request 2 completes and publishes rep-new
        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.allReports.any { it.id == "rep-new" } }
        assertEquals("rep-new", vm.uiState.value.allReports.first().id)

        // Release stale Request 1
        releaseReport1.complete(Unit)
        awaitJob(latestJob(vm))

        // Stale completion must NOT overwrite newer report list
        assertEquals("Stale refresh must not overwrite newer reports list", "rep-new", vm.uiState.value.allReports.first().id)
        assertFalse(vm.uiState.value.isLoading)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Refresh vs Mutation Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun refresh_initiated_before_lock_mutation_does_not_overwrite_mutation_result() = runBlocking {
        val refresh1Started = CompletableDeferred<Unit>()
        val releaseRefresh1 = CompletableDeferred<Unit>()
        var getReportsCount = 0

        val client = createClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("admin_update_report_status") -> {
                    reply(json.encodeToString(ContactReport(id = "rep-1", userId = "u1", status = "resolved")))
                }
                path.contains("admin_get_contact_reports") -> {
                    getReportsCount++
                    if (getReportsCount == 1) {
                        // Request 1 returns report as unresolved
                        reply(json.encodeToString(listOf(ContactReport(id = "rep-1", userId = "u1", status = "new"))))
                    } else if (getReportsCount == 2) {
                        // Request 2 (refresh initiated before mutation) is delayed and would return report as "new"
                        refresh1Started.complete(Unit)
                        releaseRefresh1.await()
                        reply(json.encodeToString(listOf(ContactReport(id = "rep-1", userId = "u1", status = "new"))))
                    } else {
                        // Refresh triggered by mutation returns report as "resolved"
                        reply(json.encodeToString(listOf(ContactReport(id = "rep-1", userId = "u1", status = "resolved"))))
                    }
                }
                else -> reply("[]")
            }
        }
        login(client, adminA)

        val vm = createAdminReportsVm(client)
        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.allReports.isNotEmpty() }
        assertEquals("new", vm.uiState.value.allReports.first().status)

        // Start refresh 1 (delayed)
        vm.refresh(forceRefresh = true)
        testDispatcher.scheduler.runCurrent()
        refresh1Started.await()

        // Admin mutates status to resolved
        vm.setResolved("rep-1", true)
        testDispatcher.scheduler.runCurrent()

        // Wait until mutation and its fresh refresh complete
        waitUntil { vm.uiState.value.allReports.any { it.status == "resolved" } }
        assertEquals("resolved", vm.uiState.value.allReports.first().status)

        // Release stale refresh 1
        releaseRefresh1.complete(Unit)
        awaitJob(latestJob(vm))

        // Mutation result must NOT be overwritten by stale refresh
        assertEquals("Mutation state must not be rolled back by stale refresh", "resolved", vm.uiState.value.allReports.first().status)
    }

    @Test
    fun refresh_initiated_before_report_reply_mutation_does_not_overwrite_replied_status() = runBlocking {
        val refresh1Started = CompletableDeferred<Unit>()
        val releaseRefresh1 = CompletableDeferred<Unit>()
        var getReportsCount = 0

        val client = createClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("admin_reply_contact_report") -> {
                    reply(json.encodeToString(ContactReport(id = "rep-1", userId = "u1", status = "replied", adminReply = "Handled")))
                }
                path.contains("admin_get_contact_reports") -> {
                    getReportsCount++
                    if (getReportsCount == 1) {
                        reply(json.encodeToString(listOf(ContactReport(id = "rep-1", userId = "u1", status = "new"))))
                    } else if (getReportsCount == 2) {
                        refresh1Started.complete(Unit)
                        releaseRefresh1.await()
                        reply(json.encodeToString(listOf(ContactReport(id = "rep-1", userId = "u1", status = "new"))))
                    } else {
                        reply(json.encodeToString(listOf(ContactReport(id = "rep-1", userId = "u1", status = "replied", adminReply = "Handled"))))
                    }
                }
                else -> reply("[]")
            }
        }
        login(client, adminA)

        val vm = createAdminReportsVm(client)
        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.allReports.isNotEmpty() }
        assertEquals("new", vm.uiState.value.allReports.first().status)

        // Start refresh 1 (delayed)
        vm.refresh(forceRefresh = true)
        testDispatcher.scheduler.runCurrent()
        refresh1Started.await()

        // Admin replies to report
        vm.replyTo("rep-1", "Handled")
        testDispatcher.scheduler.runCurrent()

        // Wait until mutation and its triggered refresh complete
        waitUntil { vm.uiState.value.allReports.any { it.status == "replied" } }
        assertEquals("replied", vm.uiState.value.allReports.first().status)

        // Release stale refresh
        releaseRefresh1.complete(Unit)
        awaitJob(latestJob(vm))

        assertEquals("Stale refresh must not roll back replied report status", "replied", vm.uiState.value.allReports.first().status)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Account Switch during in-flight operations
    // ---------------------------------------------------------------------------------------------

    @Test
    fun account_switch_during_in_flight_request_and_action_drops_old_response_and_clears_sensitive_state() = runBlocking {
        val configStarted = CompletableDeferred<Unit>()
        val releaseConfigA = CompletableDeferred<Unit>()

        val client = createClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("app_configs") -> {
                    val authHeader = request.headers[HttpHeaders.Authorization].orEmpty()
                    if (authHeader.contains(adminA)) {
                        configStarted.complete(Unit)
                        releaseConfigA.await()
                        reply(json.encodeToString(listOf(AppConfig(title = "Secret Config A"))))
                    } else {
                        reply(json.encodeToString(listOf(AppConfig(title = "Public Config B"))))
                    }
                }
                path.contains("profiles") -> reply("[]")
                else -> reply("[]")
            }
        }
        login(client, adminA)

        val vm = createAdminUsersVm(client)
        waitUntil { !vm.uiState.value.isLoading }
        assertEquals(adminA, vm.uiState.value.currentAdminId)

        // Admin A loads app config (delayed)
        vm.loadAppConfig()
        testDispatcher.scheduler.runCurrent()
        configStarted.await()
        assertTrue(vm.uiState.value.isConfigLoading)

        // Account switches to Admin B
        login(client, adminB)
        waitUntil { vm.uiState.value.currentAdminId == adminB }

        // Release Admin A's stale response
        releaseConfigA.complete(Unit)
        awaitJob(latestJob(vm))

        // Verify Admin A's stale config was NOT published into Admin B's state
        assertNotEquals("Secret Config A", vm.uiState.value.appConfig?.title)
        assertFalse(vm.uiState.value.isConfigLoading)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Generation change for same UID
    // ---------------------------------------------------------------------------------------------

    @Test
    fun generation_change_for_same_uid_rejects_stale_response() = runBlocking {
        val statsStarted = CompletableDeferred<Unit>()
        val releaseStats = CompletableDeferred<Unit>()

        val client = createClient { request ->
            val path = request.url.encodedPath
            if (path.contains("admin_get_home_stats_config")) {
                statsStarted.complete(Unit)
                releaseStats.await()
                reply(json.encodeToString(HomeStatsPolicyConfig(activeCycleDays = 7, revision = "gen1-rev")))
            } else {
                reply("[]")
            }
        }
        login(client, adminA)

        val vm = createAdminUsersVm(client)
        waitUntil { !vm.uiState.value.isLoading }

        // Admin A starts loading home stats config under generation 1
        vm.loadHomeStatsConfig()
        testDispatcher.scheduler.runCurrent()
        statsStarted.await()
        assertTrue(vm.uiState.value.isHomeStatsLoading)

        // Generation increments for same UID (e.g. logout / clear session cache)
        val genBefore = SessionCacheManager.currentGeneration()
        SessionCacheManager.clear(context)
        val genAfter = SessionCacheManager.currentGeneration()
        assertTrue("Generation must increment on session clear", genAfter > genBefore)

        // Release stale response from generation 1
        releaseStats.complete(Unit)
        awaitJob(latestJob(vm))

        // Must reject stale generation response
        assertNull("Generation 1 response must be dropped", vm.uiState.value.homeStatsConfig)
        assertFalse(vm.uiState.value.isHomeStatsLoading)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Double Action Submission on StandardTestDispatcher
    // ---------------------------------------------------------------------------------------------

    @Test
    fun double_action_submission_with_standard_test_dispatcher_admits_only_first_call() = runBlocking {
        val lockCallCount = AtomicInteger(0)
        val creditCallCount = AtomicInteger(0)
        val notifCallCount = AtomicInteger(0)
        val replyCallCount = AtomicInteger(0)
        val statusCallCount = AtomicInteger(0)

        val client = createClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("admin_set_user_lock") -> {
                    lockCallCount.incrementAndGet()
                    reply(json.encodeToString(AdminSetUserLockResult(success = true, userId = userTarget, isLocked = true)))
                }
                path.contains("admin_adjust_credits") -> {
                    creditCallCount.incrementAndGet()
                    reply(json.encodeToString(AdminAdjustCreditsResult(success = true, newCredits = 200, amount = 50)))
                }
                path.contains("admin_send_notification") -> {
                    notifCallCount.incrementAndGet()
                    reply("""{"sent_count":1}""")
                }
                path.contains("admin_reply_contact_report") -> {
                    replyCallCount.incrementAndGet()
                    reply(json.encodeToString(ContactReport(id = "rep-1", userId = "u1", status = "replied")))
                }
                path.contains("admin_update_report_status") -> {
                    statusCallCount.incrementAndGet()
                    reply(json.encodeToString(ContactReport(id = "rep-1", userId = "u1", status = "resolved")))
                }
                else -> reply("[]")
            }
        }
        login(client, adminA)

        val usersVm = createAdminUsersVm(client)
        val reportsVm = createAdminReportsVm(client)
        waitUntil { !usersVm.uiState.value.isLoading && !reportsVm.uiState.value.isLoading }

        // Double submit setUserLock synchronously before dispatcher runs
        usersVm.setUserLock(userTarget, true)
        usersVm.setUserLock(userTarget, true)
        waitUntil { lockCallCount.get() > 0 }
        assertEquals("Double setUserLock must dispatch exactly 1 network call", 1, lockCallCount.get())
        waitUntil { !usersVm.uiState.value.isActionLoading }

        // Double submit adjustCredits synchronously
        usersVm.adjustCredits(userTarget, 50)
        usersVm.adjustCredits(userTarget, 50)
        waitUntil { creditCallCount.get() > 0 }
        assertEquals("Double adjustCredits must dispatch exactly 1 network call", 1, creditCallCount.get())
        waitUntil { !usersVm.uiState.value.isActionLoading }

        // Double submit sendNotification synchronously
        usersVm.sendNotification(userTarget, "T", "M")
        usersVm.sendNotification(userTarget, "T", "M")
        waitUntil { notifCallCount.get() > 0 }
        assertEquals("Double sendNotification must dispatch exactly 1 network call", 1, notifCallCount.get())
        waitUntil { !usersVm.uiState.value.isActionLoading }

        // Double submit replyTo synchronously
        reportsVm.replyTo("rep-1", "Msg")
        reportsVm.replyTo("rep-1", "Msg")
        waitUntil { replyCallCount.get() > 0 }
        assertEquals("Double replyTo must dispatch exactly 1 network call", 1, replyCallCount.get())
        waitUntil { !reportsVm.uiState.value.isActionLoading }

        // Double submit setResolved synchronously
        reportsVm.setResolved("rep-1", true)
        reportsVm.setResolved("rep-1", true)
        waitUntil { statusCallCount.get() > 0 }
        assertEquals("Double setResolved must dispatch exactly 1 network call", 1, statusCallCount.get())
        waitUntil { !reportsVm.uiState.value.isActionLoading }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Stale homeStats response does not clear current config/flags
    // ---------------------------------------------------------------------------------------------

    @Test
    fun stale_home_stats_response_does_not_clear_current_config_or_flags() = runBlocking {
        val load1Started = CompletableDeferred<Unit>()
        val releaseLoad1 = CompletableDeferred<Unit>()
        val statsCount = AtomicInteger(0)

        val client = createClient { request ->
            val path = request.url.encodedPath
            if (path.contains("admin_set_home_stats_cycle")) {
                reply(json.encodeToString(HomeStatsPolicyConfig(activeCycleDays = 14, revision = "save-rev")))
            } else if (path.contains("admin_get_home_stats_config")) {
                statsCount.incrementAndGet()
                load1Started.complete(Unit)
                releaseLoad1.await()
                reply(json.encodeToString(HomeStatsPolicyConfig(activeCycleDays = 3, revision = "stale-rev")))
            } else {
                reply("[]")
            }
        }
        login(client, adminA)

        val vm = createAdminUsersVm(client)
        waitUntil { !vm.uiState.value.isLoading }

        // Start loadHomeStatsConfig (hanging on releaseLoad1)
        vm.loadHomeStatsConfig()
        testDispatcher.scheduler.runCurrent()
        load1Started.await()
        assertTrue(vm.uiState.value.isHomeStatsLoading)

        // Save a newer cycle of 14 days
        vm.saveHomeStatsCycle(14)
        testDispatcher.scheduler.runCurrent()
        waitUntil { vm.uiState.value.homeStatsConfig?.activeCycleDays == 14 }
        assertEquals(14, vm.uiState.value.homeStatsConfig?.activeCycleDays)

        // Now release stale load 1
        releaseLoad1.complete(Unit)
        awaitJob(latestJob(vm))

        // Must retain config 14 and must NOT have cleared homeStatsConfig to null or rolled back to 3
        assertEquals("Stale completion must not overwrite or null newer config", 14, vm.uiState.value.homeStatsConfig?.activeCycleDays)
        assertFalse(vm.uiState.value.isHomeStatsLoading)
    }

    // ---------------------------------------------------------------------------------------------
    // 7. Forced Refresh Error with Existing Data is not swallowed
    // ---------------------------------------------------------------------------------------------

    @Test
    fun forced_refresh_error_with_existing_data_is_surfaced_to_ui_state() = runBlocking {
        var failReports = false

        val client = createClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("admin_get_contact_reports") -> {
                    if (failReports) {
                        respond("Server error", HttpStatusCode.InternalServerError)
                    } else {
                        reply(json.encodeToString(listOf(ContactReport(id = "rep-1", userId = "u1", message = "Hello"))))
                    }
                }
                else -> reply("[]")
            }
        }
        login(client, adminA)

        val reportsVm = createAdminReportsVm(client)
        waitUntil { !reportsVm.uiState.value.isLoading && reportsVm.uiState.value.allReports.isNotEmpty() }

        // Existing data loaded successfully
        assertEquals(1, reportsVm.uiState.value.allReports.size)
        assertNull(reportsVm.uiState.value.error)

        // Forced refresh with server error
        failReports = true
        reportsVm.refresh(forceRefresh = true)
        testDispatcher.scheduler.runCurrent()
        waitUntil { !reportsVm.uiState.value.isLoading }

        // Error must NOT be swallowed even though existing data is present
        assertNotNull("Forced refresh error in AdminReports must be surfaced", reportsVm.uiState.value.error)
        assertEquals("Existing reports data must be preserved on refresh error", 1, reportsVm.uiState.value.allReports.size)
    }

    @Test
    fun home_stats_reload_during_save_keeps_mutation_as_the_only_request_owner() = runBlocking {
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val readCount = AtomicInteger(0)
        val client = createClient { request ->
            when {
                request.url.encodedPath.contains("admin_set_home_stats_cycle") -> {
                    saveStarted.complete(Unit)
                    releaseSave.await()
                    reply(json.encodeToString(HomeStatsPolicyConfig(activeCycleDays = 2, revision = "saved")))
                }
                request.url.encodedPath.contains("admin_get_home_stats_config") -> {
                    readCount.incrementAndGet()
                    reply(json.encodeToString(HomeStatsPolicyConfig(activeCycleDays = 1, revision = "before-save")))
                }
                else -> reply("[]")
            }
        }
        login(client, adminA)
        val vm = createAdminUsersVm(client)
        waitUntil { !vm.uiState.value.isLoading }
        try {
            vm.saveHomeStatsCycle(2)
            val saveJob = latestJob(vm)
            testDispatcher.scheduler.runCurrent()
            kotlinx.coroutines.withTimeout(5000) { saveStarted.await() }
            assertTrue(vm.uiState.value.isHomeStatsSaving)

            // Recreating the open editor triggers its load effect during a save.
            vm.loadHomeStatsConfig()
            testDispatcher.scheduler.runCurrent()
            assertFalse("Reload must wait for the pending save", vm.uiState.value.isHomeStatsLoading)

            releaseSave.complete(Unit)
            awaitJob(saveJob)
            assertEquals(0, readCount.get())
            assertEquals(2, vm.uiState.value.homeStatsConfig?.activeCycleDays)
            assertFalse(vm.uiState.value.isHomeStatsSaving)
        } finally {
            releaseSave.complete(Unit)
        }
    }
}
