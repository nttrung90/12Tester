package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.DailyTaskStatus
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
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OwnerAutoPauseResumeHardeningTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var context: Context
    private lateinit var localizer: Localizer

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        localizer = Localizer(context)
        SessionCacheManager.clear(context)
    }

    @After
    fun tearDown() {
        SessionCacheManager.clear(context)
    }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://dummy.supabase.co", "anon-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }

    private fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun login(c: SupabaseClient, uid: String) {
        c.auth.awaitInitialization()
        c.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"token-$uid","refresh_token":"ref-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )
    }

    @Test
    fun uiState_flags_reflect_carryover_debt_and_pending() {
        val stateClean = OwnerAppDetailUiState(
            dailyTask = DailyTaskStatus(mode = "owner", met = true, carryoverRemainingTests = 0, carryoverReady = true, historyReady = true)
        )
        assertFalse("Clean task has no carryover block", stateClean.isResumeBlockedByCarryover)
        assertFalse("Clean task has no pending block", stateClean.isResumeBlockedByPending)

        val stateDebt = OwnerAppDetailUiState(
            dailyTask = DailyTaskStatus(mode = "owner", met = true, carryoverRemainingTests = 2, carryoverReady = true, historyReady = true)
        )
        assertTrue("Task with debt blocks resume", stateDebt.isResumeBlockedByCarryover)
        assertFalse("Task with debt is not pending", stateDebt.isResumeBlockedByPending)

        val statePendingOwner = OwnerAppDetailUiState(
            dailyTask = DailyTaskStatus(mode = "owner", met = false, carryoverRemainingTests = 0, carryoverReady = false, historyReady = true)
        )
        assertFalse("Pending task carryover debt is 0", statePendingOwner.isResumeBlockedByCarryover)
        assertTrue("Unready carryover blocks as pending for owner", statePendingOwner.isResumeBlockedByPending)

        // Review Point 7: member mode with unready carryover or history must also be blocked
        val statePendingMemberCarryover = OwnerAppDetailUiState(
            dailyTask = DailyTaskStatus(mode = "member", met = true, carryoverRemainingTests = 0, carryoverReady = false, historyReady = true)
        )
        assertTrue("Unready carryover blocks member mode with paused apps", statePendingMemberCarryover.isResumeBlockedByPending)

        val statePendingMemberHistory = OwnerAppDetailUiState(
            dailyTask = DailyTaskStatus(mode = "member", met = true, carryoverRemainingTests = 0, carryoverReady = true, historyReady = false)
        )
        assertTrue("Unready history blocks member mode with paused apps", statePendingMemberHistory.isResumeBlockedByPending)
    }

    @Test
    fun precheck_blocks_resume_when_mode_is_member_but_history_is_pending() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val resumeRpcCalls = AtomicInteger(0)
        val c = client { req ->
            val path = req.url.toString()
            when {
                path.contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"c1","owner_id":"userA","app_name":"App 1","status":"paused","paused_reason":"owner_manual","slot_number":1,"created_at":"2026-10-01T00:00:00Z"},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500,"role":"member"}}"""
                )
                path.contains("get_my_daily_task_status") -> jsonResponse(
                    """{
                    "status_date":"2026-11-05","mode":"member","active_app_count":0,"required_tests":0,
                    "available_apps":1,"effective_required_tests":0,"completed_tests":0,"met":true,
                    "carry_in_tests":0,"carryover_remaining_tests":0,"carryover_ready":false,"history_ready":true}"""
                )
                path.contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                path.contains("get_business_quote") -> jsonResponse("{\"quote_type\":\"push\",\"cost\":100,\"revision\":\"rev1\"}")
                path.contains("resume_campaign") -> {
                    resumeRpcCalls.incrementAndGet()
                    jsonResponse("{\"success\":true,\"status\":\"open\"}")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val repo = CampaignRepository(c, localizer)
        val vm = OwnerAppDetailViewModel("c1", repo, localizer)
        try {
            val state = withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isLoading && it.dailyTask != null } }
            assertTrue("State must indicate blocked by pending", state.isResumeBlockedByPending)

            vm.togglePauseResume()
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals("Resume RPC must not be called when member mode history is pending", 0, resumeRpcCalls.get())
            val err = vm.uiState.value.error
            assertEquals(localizer.get(R.string.oad_resume_error_pending), err)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun precheck_blocks_resume_when_carryover_debt_exists() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val resumeRpcCalls = AtomicInteger(0)
        val c = client { req ->
            val path = req.url.toString()
            when {
                path.contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"c1","owner_id":"userA","app_name":"App 1","status":"paused","paused_reason":"owner_inactive","slot_number":1,"created_at":"2026-10-01T00:00:00Z"},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500,"role":"member"}}"""
                )
                path.contains("get_my_daily_task_status") -> jsonResponse(
                    """{
                    "status_date":"2026-11-05","mode":"owner","active_app_count":1,"required_tests":3,
                    "available_apps":1,"effective_required_tests":1,"completed_tests":1,"met":true,
                    "carry_in_tests":2,"carryover_remaining_tests":2,"carryover_ready":true,"history_ready":true}"""
                )
                path.contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                path.contains("get_business_quote") -> jsonResponse("{\"quote_type\":\"push\",\"cost\":100,\"revision\":\"rev1\"}")
                path.contains("resume_campaign") -> {
                    resumeRpcCalls.incrementAndGet()
                    jsonResponse("{\"success\":true,\"status\":\"open\"}")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val repo = CampaignRepository(c, localizer)
        val vm = OwnerAppDetailViewModel("c1", repo, localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isLoading && it.dailyTask != null } }
            assertTrue(vm.uiState.value.isResumeBlockedByCarryover)

            vm.togglePauseResume()
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals("RPC must not be called when pre-check detects carryover debt", 0, resumeRpcCalls.get())
            val err = vm.uiState.value.error
            assertNotNull(err)
            assertTrue("Error must mention 2 remaining carryover tasks", err!!.contains("2"))
            assertEquals("paused", vm.uiState.value.campaign?.status)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun precheck_blocks_resume_when_carryover_pending() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val resumeRpcCalls = AtomicInteger(0)
        val c = client { req ->
            val path = req.url.toString()
            when {
                path.contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"c1","owner_id":"userA","app_name":"App 1","status":"paused","paused_reason":"owner_inactive","slot_number":1,"created_at":"2026-10-01T00:00:00Z"},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500,"role":"member"}}"""
                )
                path.contains("get_my_daily_task_status") -> jsonResponse(
                    """{
                    "status_date":"2026-11-05","mode":"owner","active_app_count":1,"required_tests":3,
                    "available_apps":1,"effective_required_tests":1,"completed_tests":0,"met":false,
                    "carry_in_tests":0,"carryover_remaining_tests":0,"carryover_ready":false,"history_ready":true}"""
                )
                path.contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                path.contains("get_business_quote") -> jsonResponse("{\"quote_type\":\"push\",\"cost\":100,\"revision\":\"rev1\"}")
                path.contains("resume_campaign") -> {
                    resumeRpcCalls.incrementAndGet()
                    jsonResponse("{\"success\":true,\"status\":\"open\"}")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val repo = CampaignRepository(c, localizer)
        val vm = OwnerAppDetailViewModel("c1", repo, localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isLoading && it.dailyTask != null } }
            assertTrue(vm.uiState.value.isResumeBlockedByPending)

            vm.togglePauseResume()
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals("RPC must not be called when pre-check detects pending history", 0, resumeRpcCalls.get())
            val err = vm.uiState.value.error
            assertNotNull(err)
            assertTrue("Error must show pending reconciliation message", err == localizer.get(R.string.oad_resume_error_pending))
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun server_refusal_with_owner_carryover_incomplete_displays_count_and_reloads_daily_task() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var dailyTaskFetchCount = 0
        val c = client { req ->
            val path = req.url.toString()
            when {
                path.contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"c1","owner_id":"userA","app_name":"App 1","status":"paused","paused_reason":"owner_inactive","slot_number":1,"created_at":"2026-10-01T00:00:00Z"},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500,"role":"member"}}"""
                )
                path.contains("get_my_daily_task_status") -> {
                    dailyTaskFetchCount++
                    jsonResponse(
                        """{
                        "status_date":"2026-11-05","mode":"owner","active_app_count":1,"required_tests":3,
                        "available_apps":1,"effective_required_tests":1,"completed_tests":1,"met":true,
                        "carry_in_tests":0,"carryover_remaining_tests":0,"carryover_ready":true,"history_ready":true}"""
                    )
                }
                path.contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                path.contains("get_business_quote") -> jsonResponse("{\"quote_type\":\"push\",\"cost\":100,\"revision\":\"rev1\"}")
                path.contains("resume_campaign") -> {
                    respond(
                        """{"code":"P0001","message":"OWNER_CARRYOVER_INCOMPLETE:3"}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val repo = CampaignRepository(c, localizer)
        val vm = OwnerAppDetailViewModel("c1", repo, localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isLoading } }
            val initialFetches = dailyTaskFetchCount

            vm.togglePauseResume()
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            val err = vm.uiState.value.error
            assertNotNull("Error message must be present", err)
            assertTrue("Error message must contain debt count 3", err!!.contains("3"))
            assertTrue("Daily task was reloaded on server refusal", dailyTaskFetchCount > initialFetches)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun server_refusal_with_daily_task_pending_displays_pending_error() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val c = client { req ->
            val path = req.url.toString()
            when {
                path.contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"c1","owner_id":"userA","app_name":"App 1","status":"paused","paused_reason":"owner_inactive","slot_number":1,"created_at":"2026-10-01T00:00:00Z"},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500,"role":"member"}}"""
                )
                path.contains("get_my_daily_task_status") -> jsonResponse(
                    """{
                    "status_date":"2026-11-05","mode":"owner","active_app_count":1,"required_tests":3,
                    "available_apps":1,"effective_required_tests":1,"completed_tests":1,"met":true,
                    "carry_in_tests":0,"carryover_remaining_tests":0,"carryover_ready":true,"history_ready":true}"""
                )
                path.contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                path.contains("get_business_quote") -> jsonResponse("{\"quote_type\":\"push\",\"cost\":100,\"revision\":\"rev1\"}")
                path.contains("resume_campaign") -> {
                    respond(
                        """{"code":"P0001","message":"DAILY_TASK_PENDING"}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val repo = CampaignRepository(c, localizer)
        val vm = OwnerAppDetailViewModel("c1", repo, localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isLoading } }

            vm.togglePauseResume()
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            val err = vm.uiState.value.error
            assertEquals(localizer.get(R.string.oad_resume_error_pending), err)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun admin_resume_bypasses_owner_carryover_precheck() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val adminResumeCalls = AtomicInteger(0)
        val c = client { req ->
            val path = req.url.toString()
            when {
                path.contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"c1","owner_id":"otherUser","app_name":"Other App","status":"paused","paused_reason":"owner_inactive","slot_number":1,"created_at":"2026-10-01T00:00:00Z"},
                    "can_manage":true,"is_actual_owner":false,"my_profile":{"id":"adminA","credits":500,"role":"admin"}}"""
                )
                path.contains("get_my_daily_task_status") -> jsonResponse(
                    """{
                    "status_date":"2026-11-05","mode":"owner","active_app_count":1,"required_tests":3,
                    "available_apps":1,"effective_required_tests":1,"completed_tests":0,"met":false,
                    "carry_in_tests":5,"carryover_remaining_tests":5,"carryover_ready":true,"history_ready":true}"""
                )
                path.contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                path.contains("get_business_quote") -> jsonResponse("{\"quote_type\":\"push\",\"cost\":100,\"revision\":\"rev1\"}")
                path.contains("admin_resume_campaign") -> {
                    adminResumeCalls.incrementAndGet()
                    jsonResponse("{\"success\":true,\"status\":\"open\"}")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "adminA")
        val repo = CampaignRepository(c, localizer)
        val vm = OwnerAppDetailViewModel("c1", repo, localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isLoading && it.isAdmin } }

            vm.togglePauseResume()
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals("Admin resume must be called even if carryover debt is present on profile", 1, adminResumeCalls.get())
            assertNull("Admin resume should have no error", vm.uiState.value.error)
            assertEquals(localizer.get(R.string.owner_app_reactivated), vm.uiState.value.message)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun owner_resume_succeeds_when_quota_met_and_debt_zero() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val resumeCalls = AtomicInteger(0)
        val c = client { req ->
            val path = req.url.toString()
            when {
                path.contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"c1","owner_id":"userA","app_name":"App 1","status":"paused","paused_reason":"owner_inactive","slot_number":1,"created_at":"2026-10-01T00:00:00Z"},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500,"role":"member"}}"""
                )
                path.contains("get_my_daily_task_status") -> jsonResponse(
                    """{
                    "status_date":"2026-11-05","mode":"owner","active_app_count":1,"required_tests":3,
                    "available_apps":3,"effective_required_tests":3,"completed_tests":3,"met":true,
                    "carry_in_tests":0,"carryover_remaining_tests":0,"carryover_ready":true,"history_ready":true}"""
                )
                path.contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                path.contains("get_business_quote") -> jsonResponse("{\"quote_type\":\"push\",\"cost\":100,\"revision\":\"rev1\"}")
                path.contains("resume_campaign") -> {
                    resumeCalls.incrementAndGet()
                    jsonResponse("{\"success\":true,\"status\":\"open\"}")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val repo = CampaignRepository(c, localizer)
        val vm = OwnerAppDetailViewModel("c1", repo, localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isLoading && it.dailyTask != null } }
            assertFalse(vm.uiState.value.isResumeBlockedByCarryover)
            assertFalse(vm.uiState.value.isResumeBlockedByPending)

            vm.togglePauseResume()
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals("Resume RPC called when quota is met and debt is zero", 1, resumeCalls.get())
            assertNull(vm.uiState.value.error)
            assertEquals(localizer.get(R.string.owner_app_reactivated), vm.uiState.value.message)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun precheck_blocks_resume_when_manual_pause_and_daily_task_unmet() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val resumeRpcCalls = AtomicInteger(0)
        val c = client { req ->
            val path = req.url.toString()
            when {
                path.contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"c1","owner_id":"userA","app_name":"App 1","status":"paused","paused_reason":"owner_manual","slot_number":1,"created_at":"2026-10-01T00:00:00Z"},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500,"role":"member"}}"""
                )
                path.contains("get_my_daily_task_status") -> jsonResponse(
                    """{
                    "status_date":"2026-11-05","mode":"owner","active_app_count":1,"required_tests":3,
                    "available_apps":3,"effective_required_tests":3,"completed_tests":1,"met":false,
                    "carry_in_tests":0,"carryover_remaining_tests":0,"carryover_ready":true,"history_ready":true}"""
                )
                path.contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                path.contains("get_business_quote") -> jsonResponse("{\"quote_type\":\"push\",\"cost\":100,\"revision\":\"rev1\"}")
                path.contains("resume_campaign") -> {
                    resumeRpcCalls.incrementAndGet()
                    jsonResponse("{\"success\":true,\"status\":\"open\"}")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val repo = CampaignRepository(c, localizer)
        val vm = OwnerAppDetailViewModel("c1", repo, localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isLoading && it.dailyTask != null } }
            assertTrue("State must indicate blocked by unmet daily task", vm.uiState.value.isResumeBlockedByUnmetDailyTask)

            vm.togglePauseResume()
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals("Resume RPC must not be called when daily task is unmet", 0, resumeRpcCalls.get())
            assertEquals(localizer.get(R.string.owner_app_inactive_3days), vm.uiState.value.error)
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
