package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.ntt.crosstest.data.AdminActionEventsEnvelope
import com.ntt.crosstest.data.AdminCampaignItem
import com.ntt.crosstest.data.AdminCarryoverPoliciesResult
import com.ntt.crosstest.data.AdminCompensateCreditsResult
import com.ntt.crosstest.data.AdminCreditHistoryEnvelope
import com.ntt.crosstest.data.AdminDateTimeCursor
import com.ntt.crosstest.data.AdminJobRunsQueueEnvelope
import com.ntt.crosstest.data.AdminManagementRepository
import com.ntt.crosstest.data.AdminMemberDailyTasksEnvelope
import com.ntt.crosstest.data.AdminMemberItem
import com.ntt.crosstest.data.AdminMemberOverview
import com.ntt.crosstest.data.AdminMemberWarningsEnvelope
import com.ntt.crosstest.data.AdminMyCapabilities
import com.ntt.crosstest.data.AdminSearchCampaignsEnvelope
import com.ntt.crosstest.data.AdminSearchMembersEnvelope
import com.ntt.crosstest.data.AdminSetUserRoleResult
import com.ntt.crosstest.data.AdminSystemMonitorSummary
import com.ntt.crosstest.data.AdminTesterReportsQueueEnvelope
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
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminManagementCoreTest {

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

    private fun createClient(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): SupabaseClient {
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

    @Test
    fun memberSearch_staleLoadMoreAfterQueryChange_isIgnored() = runBlocking {
        kotlinx.coroutines.withTimeout(10_000) {
            val page1Started = CompletableDeferred<Unit>()
            val releasePage1 = CompletableDeferred<Unit>()
            val requestCount = AtomicInteger(0)

            val client = createClient { req ->
                val path = req.url.encodedPath
                if (path.contains("admin_search_members")) {
                    val idx = requestCount.incrementAndGet()
                    if (idx == 1) {
                        // Initial load
                        respond(
                            """{"items":[{"id":"u1","display_name":"Alice","credits":10,"role":"member","is_locked":false}],"has_more":true,"next_cursor":{"created_at":"2026-11-01T00:00:00Z","id":"u1"}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else if (idx == 2) {
                        // Load more for query "" (delayed)
                        page1Started.complete(Unit)
                        releasePage1.await()
                        respond(
                            """{"items":[{"id":"u2","display_name":"Bob","credits":20,"role":"member","is_locked":false}],"has_more":false,"next_cursor":null}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        // Search for "charlie"
                        respond(
                            """{"items":[{"id":"u3","display_name":"Charlie","credits":30,"role":"member","is_locked":false}],"has_more":false,"next_cursor":null}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                } else {
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            login(client, adminA)

            val adminMgmtRepo = AdminManagementRepository(client)
            val vm = AdminUsersViewModel(
                CampaignRepository(client, localizer),
                AuthRepository(client, context),
                AppConfigRepository(client, context),
                localizer,
                adminMgmtRepo
            )
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.filteredUsers.isNotEmpty() }

            assertEquals(1, vm.uiState.value.filteredUsers.size)
            assertEquals("u1", vm.uiState.value.filteredUsers.first().id)
            assertTrue(vm.uiState.value.hasMoreMembers)

            // Start load more (delayed)
            vm.loadMoreMembers()
            waitUntil { vm.uiState.value.isLoadingMore }
            page1Started.await()

            // User changes query to "charlie"
            vm.setQuery("charlie")
            testDispatcher.scheduler.advanceTimeBy(350)
            waitUntil { vm.uiState.value.filteredUsers.any { it.id == "u3" } }

            // Verify "Charlie" loaded and replaced the list
            assertEquals(1, vm.uiState.value.filteredUsers.size)
            assertEquals("u3", vm.uiState.value.filteredUsers.first().id)

            // Now release the stale loadMore for empty query
            releasePage1.complete(Unit)
            waitUntil { !vm.uiState.value.isLoadingMore }

            // Stale page 1 must NOT append u2 to query "charlie"
            assertEquals("Stale loadMore must not append to new query results", 1, vm.uiState.value.filteredUsers.size)
            assertEquals("u3", vm.uiState.value.filteredUsers.first().id)
            assertFalse(vm.uiState.value.isLoadingMore)
        }
    }

    @Test
    fun memberDetail_staleActionPreflightAfterSessionReset_doesNotReleaseNewSessionGuard() = runBlocking {
        kotlinx.coroutines.withTimeout(10_000) {
            val action1Entered = CompletableDeferred<Unit>()
            val releaseAction1 = CompletableDeferred<Unit>()
            val action2Entered = CompletableDeferred<Unit>()
            val releaseAction2 = CompletableDeferred<Unit>()

            val client = createClient { req ->
                val path = req.url.encodedPath
                if (path.contains("admin_compensate_credits")) {
                    action1Entered.complete(Unit)
                    releaseAction1.await()
                    respond(
                        """{"success":true,"request_id":"req-1","user_id":"$userTarget","previous_credits":100,"new_credits":120,"amount":20,"reason":"test","is_replay":false}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else if (path.contains("admin_set_user_role")) {
                    action2Entered.complete(Unit)
                    releaseAction2.await()
                    respond(
                        """{"success":true,"user_id":"$userTarget","previous_role":"member","new_role":"moderator","reason":"Promotion"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            login(client, adminA)

            val adminMgmtRepo = AdminManagementRepository(client)
            val authRepo = AuthRepository(client, context)
            val vm = MemberDetailViewModel(userTarget, adminMgmtRepo, authRepo, localizer)
            waitUntil { !vm.uiState.value.isLoading }

            // Admin A triggers compensation (enters MockEngine, hanging on releaseAction1)
            vm.compensateCredits(amount = 20, reason = "Fix")
            testDispatcher.scheduler.runCurrent()
            action1Entered.await()
            assertTrue(vm.uiState.value.isActionLoading)

            // Session reset occurs to Admin B
            login(client, adminB)
            waitUntil { vm.uiState.value.currentAdminId == adminB }
            assertFalse(vm.uiState.value.isActionLoading)

            // Admin B initiates a role change action (hangs on releaseAction2)
            vm.changeRole(newRole = "moderator", reason = "Promotion")
            testDispatcher.scheduler.runCurrent()
            action2Entered.await()
            assertTrue("Admin B acquired action guard", vm.uiState.value.isActionLoading)

            // Release stale Action 1 from Admin A
            releaseAction1.complete(Unit)
            testDispatcher.scheduler.runCurrent()

            // Stale Action 1 must NOT have prematurely released Admin B's guard
            assertTrue("Admin B guard must stay active despite stale Action 1 completing", vm.uiState.value.isActionLoading)

            // Release Action 2
            releaseAction2.complete(Unit)
            waitUntil { !vm.uiState.value.isActionLoading }
        }
    }

    @Test
    fun sameUID_newGeneration_rejectsStalePage_andPreservesFreshState() = runBlocking {
        kotlinx.coroutines.withTimeout(10_000) {
            val loadMoreStarted = CompletableDeferred<Unit>()
            val releaseLoadMore = CompletableDeferred<Unit>()
            var creditCalls = 0

            val client = createClient { req ->
                val path = req.url.encodedPath
                if (path.contains("admin_get_member_credit_history")) {
                    creditCalls++
                    if (creditCalls == 1) {
                        // Initial load
                        respond(
                            """{"items":[{"id":"cred-page1","user_id":"$userTarget","amount":10,"balance":110,"created_at":"2026-11-01T00:00:00Z"}],"has_more":true,"next_cursor":{"created_at":"2026-11-01T00:00:00Z","id":"cred-page1"}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else if (creditCalls == 2) {
                        // Stale loadMore from generation 1
                        loadMoreStarted.complete(Unit)
                        releaseLoadMore.await()
                        respond(
                            """{"items":[{"id":"stale-cred-page2","user_id":"$userTarget","amount":20,"balance":130,"created_at":"2026-11-01T00:30:00Z"}],"has_more":false,"next_cursor":null}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        // Fresh load under generation 2
                        respond(
                            """{"items":[{"id":"fresh-cred-gen2","user_id":"$userTarget","amount":50,"balance":150,"created_at":"2026-11-01T01:00:00Z"}],"has_more":false,"next_cursor":null}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                } else if (path.contains("admin_get_member_overview")) {
                    respond(
                        """{"id":"$userTarget","credits":100}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else if (path.contains("admin_get_my_capabilities")) {
                    respond(
                        """{"caller_id":"$adminA","role":"admin"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            login(client, adminA)

            val adminMgmtRepo = AdminManagementRepository(client)
            val vm = MemberDetailViewModel(userTarget, adminMgmtRepo, AuthRepository(client, context), localizer)
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.creditHistory.isNotEmpty() }
            assertEquals(listOf("cred-page1"), vm.uiState.value.creditHistory.map { it.id })

            // Start loadMore under generation 1
            vm.loadMoreCreditHistory()
            testDispatcher.scheduler.runCurrent()
            loadMoreStarted.await()
            assertTrue(vm.uiState.value.isCreditsLoading)

            // Generation clears and explicit refresh starts fresh generation 2 request
            SessionCacheManager.clear(context)
            val genAfter = SessionCacheManager.currentGeneration()
            vm.refreshAll()
            waitUntil { vm.uiState.value.sessionGeneration == genAfter && !vm.uiState.value.isLoading && vm.uiState.value.creditHistory.any { it.id == "fresh-cred-gen2" } }

            // Verify generation 2 refreshed cleanly
            assertTrue(vm.uiState.value.creditHistory.any { it.id == "fresh-cred-gen2" })

            // Release stale loadMore from generation 1
            releaseLoadMore.complete(Unit)
            testDispatcher.scheduler.runCurrent()

            // Stale page 2 must NOT be appended to generation 2 state
            assertTrue("Stale page from old generation must be rejected", vm.uiState.value.creditHistory.none { it.id == "stale-cred-page2" })
            assertEquals(listOf("fresh-cred-gen2"), vm.uiState.value.creditHistory.map { it.id })
            assertFalse(vm.uiState.value.isCreditsLoading)
        }
    }

    @Test
    fun compensation_requestIdReusedOnFailureRetry_andNewIdOnPayloadChange() = runBlocking {
        var failCompensate = true
        val observedRequestIds = mutableListOf<String>()

        val client = createClient { req ->
            val path = req.url.encodedPath
            if (path.contains("admin_compensate_credits")) {
                val bodyStr = String(req.body.toByteArray())
                val parsed = json.decodeFromString<com.ntt.crosstest.data.AdminCompensateCreditsParams>(bodyStr)
                observedRequestIds.add(parsed.requestId)
                if (failCompensate) {
                    respond("Server error", HttpStatusCode.InternalServerError)
                } else {
                    respond(
                        """{"success":true,"request_id":"${parsed.requestId}","user_id":"$userTarget","previous_credits":100,"new_credits":150,"amount":50,"reason":"OK","is_replay":false}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            } else {
                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        login(client, adminA)

        val adminMgmtRepo = AdminManagementRepository(client)
        val vm = MemberDetailViewModel(userTarget, adminMgmtRepo, AuthRepository(client, context), localizer)
        waitUntil { !vm.uiState.value.isLoading }

        // Attempt 1: fails
        vm.compensateCredits(amount = 50, reason = "Bonus")
        waitUntil { observedRequestIds.size == 1 && !vm.uiState.value.isActionLoading }
        val id1 = observedRequestIds[0]
        assertNotNull(vm.uiState.value.error)

        // Attempt 2: retry with SAME intent (amount=50, reason="Bonus")
        vm.compensateCredits(amount = 50, reason = "Bonus")
        waitUntil { observedRequestIds.size == 2 && !vm.uiState.value.isActionLoading }
        assertEquals("Request ID must persist across retry of unchanged intent", id1, observedRequestIds[1])

        // Attempt 3: user changes payload (amount=60)
        failCompensate = false
        vm.compensateCredits(amount = 60, reason = "Bonus")
        waitUntil { observedRequestIds.size == 3 && !vm.uiState.value.isActionLoading }
        assertNotEquals("New payload must generate a new Request ID", id1, observedRequestIds[2])
        assertNull(vm.uiState.value.error)
        assertNotNull(vm.uiState.value.actionMessage)
    }

    @Test
    fun compensation_doubleSubmitBeforeCoroutineStarts_isGuarded() = runBlocking {
        val compensateCalls = AtomicInteger(0)
        val client = createClient { req ->
            val path = req.url.encodedPath
            if (path.contains("admin_compensate_credits")) {
                compensateCalls.incrementAndGet()
                respond(
                    """{"success":true,"request_id":"req-1","user_id":"$userTarget","previous_credits":100,"new_credits":150,"amount":50,"reason":"OK","is_replay":false}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        login(client, adminA)

        val vm = MemberDetailViewModel(userTarget, AdminManagementRepository(client), AuthRepository(client, context), localizer)
        waitUntil { !vm.uiState.value.isLoading }

        // Trigger two compensation submissions synchronously before dispatcher executes
        vm.compensateCredits(amount = 50, reason = "Bonus")
        vm.compensateCredits(amount = 50, reason = "Bonus")
        waitUntil { !vm.uiState.value.isActionLoading }

        assertEquals("Double submit before coroutine starts must execute only 1 network call", 1, compensateCalls.get())
    }

    @Test
    fun compensation_savedStateHandleRestoration_persistsIntentAndRequestId_noAutoSubmit() = runBlocking {
        var failCompensate = true
        val observedRequestIds = mutableListOf<String>()
        val handle = androidx.lifecycle.SavedStateHandle()

        val client = createClient { req ->
            val path = req.url.encodedPath
            if (path.contains("admin_compensate_credits")) {
                val bodyStr = String(req.body.toByteArray())
                val parsed = json.decodeFromString<com.ntt.crosstest.data.AdminCompensateCreditsParams>(bodyStr)
                observedRequestIds.add(parsed.requestId)
                if (failCompensate) {
                    respond("Server error", HttpStatusCode.InternalServerError)
                } else {
                    respond(
                        """{"success":true,"request_id":"${parsed.requestId}","user_id":"$userTarget","previous_credits":100,"new_credits":200,"amount":100,"reason":"Reimbursement","is_replay":false}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            } else {
                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        login(client, adminA)

        // VM 1: submits compensation, fails with ambiguous server error
        val vm1 = MemberDetailViewModel(userTarget, AdminManagementRepository(client), AuthRepository(client, context), localizer, handle)
        waitUntil { !vm1.uiState.value.isLoading }

        vm1.compensateCredits(amount = 100, reason = "Reimbursement", confirmingAdminId = adminA, confirmingSessionGen = vm1.uiState.value.sessionGeneration)
        waitUntil { observedRequestIds.size == 1 && !vm1.uiState.value.isActionLoading }
        val originalReqId = observedRequestIds[0]
        assertNotNull(vm1.uiState.value.error)
        assertNotNull(vm1.uiState.value.pendingRetryCompensation)
        assertEquals(originalReqId, vm1.uiState.value.pendingRetryRequestId)

        // Process recreation: VM 2 initialized with the same SavedStateHandle
        val vm2 = MemberDetailViewModel(userTarget, AdminManagementRepository(client), AuthRepository(client, context), localizer, handle)
        waitUntil { !vm2.uiState.value.isLoading }

        // Must restore pending intent without auto-submitting
        assertEquals("No network call should be automatically dispatched upon init", 1, observedRequestIds.size)
        assertNotNull("Pending retry compensation must be restored from SavedStateHandle", vm2.uiState.value.pendingRetryCompensation)
        assertEquals(100, vm2.uiState.value.pendingRetryCompensation?.amount)
        assertEquals("Reimbursement", vm2.uiState.value.pendingRetryCompensation?.reason)
        assertEquals("Restored request ID must match original", originalReqId, vm2.uiState.value.pendingRetryRequestId)

        // Explicit retry uses original request ID
        failCompensate = false
        val retryComp = vm2.uiState.value.pendingRetryCompensation!!
        vm2.compensateCredits(
            amount = retryComp.amount,
            reason = retryComp.reason,
            originalCreditLogId = retryComp.originalCreditLogId,
            contactReportId = retryComp.contactReportId,
            confirmingAdminId = adminA,
            confirmingSessionGen = vm2.uiState.value.sessionGeneration
        )
        waitUntil { observedRequestIds.size == 2 && !vm2.uiState.value.isActionLoading }
        assertEquals("Retry must reuse exact original request ID", originalReqId, observedRequestIds[1])
        assertNull("Confirmed compensation must clear pending retry intent", vm2.uiState.value.pendingRetryCompensation)
    }

    @Test
    fun compensation_heldRpcProcessRecreate_noCatchRan_restoresOriginalRequestForReplay() = runBlocking {
        val req1Entered = CompletableDeferred<Unit>()
        val releaseReq1 = CompletableDeferred<Unit>()
        val observedRequestIds = mutableListOf<String>()
        val handle = androidx.lifecycle.SavedStateHandle()

        val client = createClient { req ->
            val path = req.url.encodedPath
            if (path.contains("admin_compensate_credits")) {
                val bodyStr = String(req.body.toByteArray())
                val parsed = json.decodeFromString<com.ntt.crosstest.data.AdminCompensateCreditsParams>(bodyStr)
                observedRequestIds.add(parsed.requestId)
                if (observedRequestIds.size == 1) {
                    req1Entered.complete(Unit)
                    releaseReq1.await()
                    respond(
                        """{"success":true,"request_id":"${parsed.requestId}","user_id":"$userTarget","previous_credits":100,"new_credits":150,"amount":50,"reason":"Lost Reply","is_replay":false}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        """{"success":true,"request_id":"${parsed.requestId}","user_id":"$userTarget","previous_credits":100,"new_credits":150,"amount":50,"reason":"Lost Reply","is_replay":true}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            } else {
                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        login(client, adminA)

        // VM 1: Initiates compensation. It enters MockEngine and is held (no catch ran!)
        val vm1 = MemberDetailViewModel(userTarget, AdminManagementRepository(client), AuthRepository(client, context), localizer, handle)
        waitUntil { !vm1.uiState.value.isLoading }

        vm1.compensateCredits(amount = 50, reason = "Lost Reply", confirmingAdminId = adminA, confirmingSessionGen = vm1.uiState.value.sessionGeneration)
        testDispatcher.scheduler.runCurrent()
        req1Entered.await()
        assertEquals(1, observedRequestIds.size)
        val initialReqId = observedRequestIds[0]

        // Process dies while RPC in-flight. VM 2 initialized from the SavedStateHandle.
        // SessionCacheManager counter is different on new process!
        SessionCacheManager.clear(context)
        val vm2 = MemberDetailViewModel(userTarget, AdminManagementRepository(client), AuthRepository(client, context), localizer, handle)
        waitUntil { !vm2.uiState.value.isLoading }

        // Must restore the pending unresolved compensation without auto-submit
        assertEquals("Process restart must not auto-dispatch RPC", 1, observedRequestIds.size)
        assertNotNull("Pending intent must be restored even if no catch ran", vm2.uiState.value.pendingRetryCompensation)
        assertEquals(initialReqId, vm2.uiState.value.pendingRetryRequestId)

        // When user explicitly retries, it replays with original request ID
        val pending = vm2.uiState.value.pendingRetryCompensation!!
        vm2.compensateCredits(
            amount = pending.amount,
            reason = pending.reason,
            originalCreditLogId = pending.originalCreditLogId,
            contactReportId = pending.contactReportId,
            confirmingAdminId = adminA,
            confirmingSessionGen = vm2.uiState.value.sessionGeneration
        )
        waitUntil { observedRequestIds.size == 2 && !vm2.uiState.value.isActionLoading }
        assertEquals("Replay retry must use exact same initial request ID", initialReqId, observedRequestIds[1])
        assertNull("Confirmed receipt clears pending retry intent", vm2.uiState.value.pendingRetryCompensation)

        // Complete the initial hanging request 1
        releaseReq1.complete(Unit)
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun carryoverPolicy_futurePagination125Items_noDuplicatesOrOmissions() = runBlocking {
        kotlinx.coroutines.withTimeout(10_000) {
            // 125 policies with valid ISO future dates starting tomorrow
            val baseDate = java.time.LocalDate.parse("2026-11-01")
            val allItems = (1..125).map { idx ->
                val dateStr = baseDate.plusDays(idx.toLong()).toString()
                com.ntt.crosstest.data.AdminCarryoverPolicyItem(
                    id = idx.toString(),
                    effectiveFrom = dateStr,
                    isEnabled = idx % 2 == 0,
                    operatorNote = "Policy $idx"
                )
            }

            val client = createClient { req ->
                val path = req.url.encodedPath
                if (path.contains("admin_list_future_carryover_policies")) {
                    val bodyStr = String(req.body.toByteArray())
                    val parsed = json.decodeFromString<com.ntt.crosstest.data.AdminListFutureCarryoverPoliciesParams>(bodyStr)
                    assertEquals(50, parsed.limit)
                    val cursorDate = parsed.cursorDate
                    val filtered = if (cursorDate == null) {
                        allItems.take(50)
                    } else {
                        allItems.filter { (it.effectiveFrom ?: "") > cursorDate }.take(50)
                    }
                    val hasMore = filtered.size == 50 && allItems.any { (it.effectiveFrom ?: "") > (filtered.lastOrNull()?.effectiveFrom ?: "") }
                    val nextCursor = if (hasMore) """{"date":"${filtered.last().effectiveFrom}"}""" else "null"
                    val itemsJson = json.encodeToString(filtered)
                    respond(
                        """{"items":$itemsJson,"has_more":$hasMore,"next_cursor":$nextCursor}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else if (path.contains("admin_get_carryover_policies")) {
                    respond(
                        """{"active":{"id":"1","effective_from":"2026-10-01","is_enabled":true},"future":[],"history":[],"revision":"1"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            login(client, adminA)

            val vm = CarryoverPolicyViewModel(AdminManagementRepository(client), AuthRepository(client, context), localizer)
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.futurePolicies.isNotEmpty() }

            // Page 1: 50 items
            assertEquals(50, vm.uiState.value.futurePolicies.size)
            assertTrue(vm.uiState.value.futureHasMore)

            // Load Page 2: 50 items -> 100 total
            vm.loadMoreFuturePolicies()
            waitUntil { vm.uiState.value.futurePolicies.size == 100 }
            assertTrue(vm.uiState.value.futureHasMore)

            // Load Page 3: 25 items -> 125 total
            vm.loadMoreFuturePolicies()
            waitUntil { vm.uiState.value.futurePolicies.size == 125 }
            assertFalse(vm.uiState.value.futureHasMore)

            // Verify all 125 items present with no duplicates or omissions
            val ids = vm.uiState.value.futurePolicies.mapNotNull { it.id }
            assertEquals(125, ids.size)
            assertEquals(125, ids.distinct().size)
            assertEquals("1", ids.first())
            assertEquals("125", ids.last())
        }
    }

    @Test
    fun action_ownerChangedDuringConfirmation_abortsAndRequiresFreshConfirm() = runBlocking {
        val compensateCalls = AtomicInteger(0)
        val client = createClient { req ->
            val path = req.url.encodedPath
            if (path.contains("admin_compensate_credits")) {
                compensateCalls.incrementAndGet()
                respond(
                    """{"success":true,"request_id":"req-1","user_id":"$userTarget","previous_credits":100,"new_credits":150,"amount":50,"reason":"OK","is_replay":false}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        login(client, adminA)

        val vm = MemberDetailViewModel(userTarget, AdminManagementRepository(client), AuthRepository(client, context), localizer)
        waitUntil { !vm.uiState.value.isLoading }

        // Confirmation started under Admin A
        val staleAdminId = adminA
        val staleGen = vm.uiState.value.sessionGeneration

        // Session switches to Admin B before confirmation action executes
        login(client, adminB)
        waitUntil { vm.uiState.value.currentAdminId == adminB }

        // Action executes with stale confirmation arguments from Admin A
        vm.compensateCredits(amount = 50, reason = "Bonus", confirmingAdminId = staleAdminId, confirmingSessionGen = staleGen)
        testDispatcher.scheduler.runCurrent()

        // Must abort without making network calls under Admin B's session
        assertEquals("Stale confirmation must not execute network call under new session", 0, compensateCalls.get())
        assertNotNull("Aborted action must surface friendly session changed error", vm.uiState.value.error)
    }

    @Test
    fun carryoverPolicy_revisionConflict_reloadsWithoutLosingDraft() = runBlocking {
        var conflict = true
        val client = createClient { req ->
            val path = req.url.encodedPath
            when {
                path.contains("admin_get_carryover_policies") -> {
                    respond(
                        """{"active":{"id":"1","effective_from":"2026-10-01","is_enabled":true,"operator_note":"Init"},"future":[],"history":[],"revision":"10"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                path.contains("admin_schedule_carryover_policy") -> {
                    if (conflict) {
                        conflict = false
                        respond("""{"code":"REVISION_CONFLICT","message":"Revision changed"}""", HttpStatusCode.BadRequest)
                    } else {
                        respond(
                            """{"success":true,"effective_from":"2026-11-05","is_enabled":true,"operator_note":"Draft Note","revision":"11"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
                else -> respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        login(client, adminA)

        val vm = CarryoverPolicyViewModel(AdminManagementRepository(client), AuthRepository(client, context), localizer)
        waitUntil {
            if (vm.uiState.value.error != null) println("VM ERROR: ${vm.uiState.value.error}")
            !vm.uiState.value.isLoading && vm.uiState.value.revision != null
        }

        assertEquals("10", vm.uiState.value.revision)

        // User edits draft
        vm.setDraft(effectiveFrom = "2026-11-05", isEnabled = true, note = "Draft Note")
        assertEquals("Draft Note", vm.uiState.value.draftNote)
        assertEquals("2026-11-05", vm.uiState.value.draftEffectiveFrom)

        // Try save -> gets REVISION_CONFLICT -> automatically reloads policies
        vm.schedulePolicy(effectiveFrom = "2026-11-05", isEnabled = true, note = "Draft Note")
        waitUntil { !vm.uiState.value.isSaving && !vm.uiState.value.isLoading }

        // Draft must NOT be lost on conflict reload
        assertEquals("Draft note must be preserved on conflict reload", "Draft Note", vm.uiState.value.draftNote)
        assertEquals("Draft effective date must be preserved on conflict reload", "2026-11-05", vm.uiState.value.draftEffectiveFrom)
    }

    @Test
    fun adminManagementDto_exactJsonParsingAgainstRealBackendContract() {
        // 1. AdminActionEventsEnvelope
        val auditJson = """{"items":[{"id":"c1f7a0b3-1111-1111-1111-111111111111","actor_id":"11111111-1111-1111-1111-111111111111","actor_email":"admin@crosstest.local","actor_name":"Admin 1","action":"compensate_credits","target_type":"user","target_id":"22222222-2222-2222-2222-222222222222","reason":"Bồi hoàn","old_state":{"credits":50},"new_state":{"credits":60,"amount":10},"request_id":"99999999-9999-9999-9999-999999999999","created_at":"2026-11-01T08:30:00Z"}],"has_more":false,"next_cursor":null}"""
        val auditDto = json.decodeFromString<AdminActionEventsEnvelope>(auditJson)
        assertEquals(1, auditDto.items.size)
        assertEquals("compensate_credits", auditDto.items[0].action)
        assertFalse(auditDto.hasMore)

        // 2. AdminMyCapabilities
        val capsJson = """{"caller_id":"11111111-1111-1111-1111-111111111111","role":"admin","is_locked":false,"capabilities":["admin_management"],"can_manage_admins":true,"can_manage_moderators":true}"""
        val capsDto = json.decodeFromString<AdminMyCapabilities>(capsJson)
        assertTrue(capsDto.canManageAdmins)
        assertTrue(capsDto.canManageModerators)

        // 3. AdminSearchMembersEnvelope
        val membersJson = """{"items":[{"id":"22222222-2222-2222-2222-222222222222","email":"user@example.com","display_name":"Nguyễn Văn A","avatar_url":"https://example.com/avatar.png","credits":150,"role":"member","is_locked":false,"created_at":"2026-10-15T02:00:00Z","last_login_date":"2026-11-01","daily_missed_streak":0}],"has_more":true,"next_cursor":{"created_at":"2026-10-15T02:00:00Z","id":"22222222-2222-2222-2222-222222222222"}}"""
        val membersDto = json.decodeFromString<AdminSearchMembersEnvelope>(membersJson)
        assertEquals(1, membersDto.items.size)
        assertTrue(membersDto.hasMore)
        assertEquals("22222222-2222-2222-2222-222222222222", membersDto.nextCursor?.id)

        // 4. AdminMemberOverview
        val overviewJson = """{"id":"22222222-2222-2222-2222-222222222222","email":"user@example.com","display_name":"Nguyễn Văn A","avatar_url":"https://example.com/avatar.png","credits":150,"role":"member","is_locked":false,"created_at":"2026-10-15T02:00:00Z","last_login_date":"2026-11-01","daily_missed_streak":0,"campaigns_total":3,"campaigns_open":1,"slots_total":2,"slots_active":1,"warnings_active":1,"warnings_revoked":0,"tester_reports_count":14,"contact_reports_count":2}"""
        val overviewDto = json.decodeFromString<AdminMemberOverview>(overviewJson)
        assertEquals(3, overviewDto.campaignsTotal)
        assertEquals(14, overviewDto.testerReportsCount)

        // 5. AdminCreditHistoryEnvelope
        val creditJson = """{"items":[{"id":"f5a2b1","user_id":"22222222-2222-2222-2222-222222222222","amount":2,"action":"report_provisional_reward","note":"Tạm cấp 2 Credit","balance":152,"created_at":"2026-11-01T07:15:00Z","metadata":null}],"has_more":false,"next_cursor":null}"""
        val creditDto = json.decodeFromString<AdminCreditHistoryEnvelope>(creditJson)
        assertEquals(2, creditDto.items[0].amount)

        // 6. AdminMemberDailyTasksEnvelope
        val dailyTaskJson = """{"items":[{"task_date":"2026-11-01","is_finalized":false,"finalized_at":null,"penalty_credits":0,"incomplete_history":false,"status":{"status_date":"2026-11-01","mode":"member","active_app_count":2,"required_tests":6,"effective_required_tests":6,"completed_tests":4,"available_apps":5,"logged_in_today":true,"is_penalty_exempt":false,"missed_streak":0,"met":false,"carry_in_tests":0,"carryover_remaining_tests":2,"carryover_ready":true,"history_ready":true}}],"has_more":true,"next_cursor":{"date":"2026-10-18"}}"""
        val dailyTaskDto = json.decodeFromString<AdminMemberDailyTasksEnvelope>(dailyTaskJson)
        assertEquals("2026-10-18", dailyTaskDto.nextCursor?.date)
        assertEquals(4, dailyTaskDto.items[0].status?.completedTests)

        // 7. AdminCarryoverPoliciesResult (handles null active and populated future)
        val carryoverJson = """{"active":{"id":"1","effective_from":"2026-10-01","is_enabled":true,"operator_note":"Chính sách ban đầu","created_at":"2026-10-01T00:00:00Z"},"future":[{"id":"2","effective_from":"2026-11-05","is_enabled":false,"operator_note":"Tạm tắt","created_at":"2026-11-01T09:00:00Z"}],"history":[],"revision":"4"}"""
        val carryoverDto = json.decodeFromString<AdminCarryoverPoliciesResult>(carryoverJson)
        assertEquals("4", carryoverDto.revision)
        assertEquals(1, carryoverDto.future.size)

        // 8. AdminSearchCampaignsEnvelope
        val campaignJson = """{"items":[{"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","owner_id":"22222222-2222-2222-2222-222222222222","owner_name":"Nguyễn Văn A","owner_email":"user@example.com","app_name":"Game Đua Xe","package_name":"com.example.racing","status":"open","target_testers":12,"current_testers":8,"paused_reason":null,"created_at":"2026-10-20T05:00:00Z","pending_reports_count":3}],"has_more":false,"next_cursor":null}"""
        val campaignDto = json.decodeFromString<AdminSearchCampaignsEnvelope>(campaignJson)
        assertEquals("Game Đua Xe", campaignDto.items[0].appName)

        // 9. AdminTesterReportsQueueEnvelope
        val queueJson = """{"items":[{"id":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","campaign_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","campaign_name":"Game Đua Xe","package_name":"com.example.racing","owner_id":"22222222-2222-2222-2222-222222222222","owner_name":"Nguyễn Văn A","tester_id":"44444444-4444-4444-4444-444444444444","tester_name":"Tester B","tester_email":"tester@example.com","report_type":"feedback","content":"Mượt mà","screenshot_url":"https://example.com/shot.png","thumbnail_url":null,"rating":5,"status":"pending","submission_context":"app_test","mission_title":null,"created_at":"2026-11-01T06:00:00Z","reviewed_at":null,"rejection_reason":null}],"has_more":true,"next_cursor":{"created_at":"2026-11-01T06:00:00Z","id":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}}"""
        val queueDto = json.decodeFromString<AdminTesterReportsQueueEnvelope>(queueJson)
        assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", queueDto.items[0].id)
        assertEquals(5, queueDto.items[0].rating)

        // 10. AdminCompensateCreditsResult
        val compJson = """{"success":true,"request_id":"99999999-9999-9999-9999-999999999999","user_id":"22222222-2222-2222-2222-222222222222","previous_credits":100,"new_credits":120,"amount":20,"reason":"Bồi hoàn","original_credit_log_id":null,"contact_report_id":null,"is_replay":false}"""
        val compDto = json.decodeFromString<AdminCompensateCreditsResult>(compJson)
        assertEquals(120, compDto.newCredits)
        assertFalse(compDto.isReplay)

        // 11. AdminSetUserRoleResult
        val roleJson = """{"success":true,"user_id":"33333333-3333-3333-3333-333333333333","previous_role":"member","new_role":"moderator","reason":"Bổ nhiệm"}"""
        val roleDto = json.decodeFromString<AdminSetUserRoleResult>(roleJson)
        assertEquals("moderator", roleDto.newRole)

        // 12. AdminMemberWarningsEnvelope
        val warnJson = """{"items":[{"id":"e4d3c2","user_id":"22222222-2222-2222-2222-222222222222","warning_type":"tester_rejection_streak","message":"3 báo cáo bị từ chối","warning_date":"2026-10-31","created_at":"2026-10-31T14:00:00Z","acknowledged":false,"is_revoked":true,"revocation":{"revoked_at":"2026-11-01T08:00:00Z","revoked_by":"11111111-1111-1111-1111-111111111111","reason":"Lỗi app"}}],"has_more":false,"next_cursor":null}"""
        val warnDto = json.decodeFromString<AdminMemberWarningsEnvelope>(warnJson)
        assertTrue(warnDto.items[0].isRevoked)
        assertEquals("Lỗi app", warnDto.items[0].revocation?.reason)

        // 13. AdminSystemMonitorSummary
        val monitorJson = """{"members":{"total":1250,"locked":12,"admins":3,"moderators":5},"campaigns":{"open":45,"paused":6,"completed":120,"total":171},"tester_reports":{"pending":28,"approved":3400,"rejected":112},"contact_reports":{"pending":4,"resolved":89},"mission_settlement_jobs":{"pending":2,"failed":1,"completed":450,"skipped":5},"cleanup_runs":{"last_run":{"id":"11223344","job_name":"storage_cleanup","status":"success","started_at":"2026-11-01T03:00:00Z","finished_at":"2026-11-01T03:05:00Z","scanned":120,"deleted":15,"error":null}},"timestamp":"2026-11-01T10:00:00Z"}"""
        val monitorDto = json.decodeFromString<AdminSystemMonitorSummary>(monitorJson)
        assertEquals(1250, monitorDto.members.total)
        assertEquals(1, monitorDto.missionSettlementJobs.failed)
        assertEquals("storage_cleanup", monitorDto.cleanupRuns.lastRun?.jobName)

        // 14. AdminJobRunsQueueEnvelope
        val jobJson = """{"items":[{"id":"77889900","report_id":"bbbbbbbb","mission_id":"55667788","campaign_id":"aaaaaaaa","tester_id":"44444444","reward_credits":10,"status":"failed","attempt_count":3,"next_attempt_at":"2026-11-01T10:30:00Z","last_error":"CREDIT_OVERFLOW","created_at":"2026-11-01T08:00:00Z"}],"has_more":false,"next_cursor":null}"""
        val jobDto = json.decodeFromString<AdminJobRunsQueueEnvelope>(jobJson)
        assertEquals("CREDIT_OVERFLOW", jobDto.items[0].lastError)

        // 15. AdminMemberOverview with slots array and AdminMemberSlotsResult
        val slotsJson = """{"user_id":"22222222-2222-2222-2222-222222222222","slots":[{"slot_number":1,"is_active":true,"unlocked_at":"2026-10-15T02:00:00Z","expires_at":null,"current_campaign":{"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","app_name":"Game Đua Xe","package_name":"com.example.racing","status":"open"}},{"slot_number":2,"is_active":false,"unlocked_at":null,"expires_at":null,"current_campaign":null}],"slots_total":2,"slots_active":1}"""
        val slotsDto = json.decodeFromString<com.ntt.crosstest.data.AdminMemberSlotsResult>(slotsJson)
        assertEquals(2, slotsDto.slots.size)
        assertTrue(slotsDto.slots[0].isActive)
        assertEquals("Game Đua Xe", slotsDto.slots[0].currentCampaign?.appName)
        assertFalse(slotsDto.slots[1].isActive)
        assertNull(slotsDto.slots[1].currentCampaign)

        // 16. AdminCarryoverAuditHistoryEnvelope with cancellation events
        val carryoverAuditJson = """{"items":[{"id":5,"policy_id":2,"effective_from":"2026-11-05","old_is_enabled":false,"new_is_enabled":false,"old_operator_note":"Tạm tắt","new_operator_note":"CANCELLED: Không bảo trì nữa","is_cancelled":true,"clean_note":"Không bảo trì nữa","changed_by":"admin:11111111-1111-1111-1111-111111111111","changed_at":"2026-11-01T10:00:00Z"}],"has_more":false,"next_cursor":null}"""
        val carryoverAuditDto = json.decodeFromString<com.ntt.crosstest.data.AdminCarryoverAuditHistoryEnvelope>(carryoverAuditJson)
        assertEquals(1, carryoverAuditDto.items.size)
        assertTrue(carryoverAuditDto.items[0].isCancelled)
        assertEquals("Không bảo trì nữa", carryoverAuditDto.items[0].cleanNote)
    }

    @Test
    fun navigation_adminRoutes_contractAndConstruction() {
        assertEquals("admin_users", com.ntt.crosstest.ui.navigation.Routes.ADMIN_USERS)
        assertEquals("admin_member/{userId}", com.ntt.crosstest.ui.navigation.Routes.ADMIN_MEMBER_DETAIL)
        assertEquals("admin_member/user123", com.ntt.crosstest.ui.navigation.Routes.adminMemberDetail("user123"))
        assertEquals("admin_campaigns", com.ntt.crosstest.ui.navigation.Routes.ADMIN_CAMPAIGNS)
        assertEquals("admin_tester_reports", com.ntt.crosstest.ui.navigation.Routes.ADMIN_TESTER_REPORTS)
        assertEquals("admin_audit", com.ntt.crosstest.ui.navigation.Routes.ADMIN_AUDIT)
        assertEquals("admin_monitor", com.ntt.crosstest.ui.navigation.Routes.ADMIN_MONITOR)
        assertEquals("admin_carryover", com.ntt.crosstest.ui.navigation.Routes.ADMIN_CARRYOVER)
        assertEquals("admin_notification_campaigns", com.ntt.crosstest.ui.navigation.Routes.ADMIN_NOTIFICATION_CAMPAIGNS)
        assertEquals("admin_business_policies", com.ntt.crosstest.ui.navigation.Routes.ADMIN_BUSINESS_POLICIES)
    }
}
