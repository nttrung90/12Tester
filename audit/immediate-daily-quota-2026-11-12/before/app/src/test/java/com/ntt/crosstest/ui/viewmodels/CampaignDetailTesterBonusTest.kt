package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.ReportSubmitManager
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CampaignDetailTesterBonusTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clientsToClose = mutableListOf<SupabaseClient>()
    private val vmsToCancel = mutableListOf<CampaignDetailViewModel>()

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var localizer: Localizer
    private val userId = "user-tester-44"
    private val campaignId = "camp-bonus-55"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        localizer = Localizer(context)
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        } catch (_: Exception) {}
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        for (vm in vmsToCancel) {
            try { vm.viewModelScope.cancel() } catch (_: Exception) {}
        }
        vmsToCancel.clear()

        for (c in clientsToClose) {
            try { c.close() } catch (_: Exception) {}
        }
        clientsToClose.clear()
        Dispatchers.resetMain()
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun createTestClient(
        uid: String = userId,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): SupabaseClient {
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
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
        val json = Json { ignoreUnknownKeys = true }
        client.auth.awaitInitialization()
        client.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"token-$uid","refresh_token":"refresh-$uid","token_type":"bearer","expires_in":3600,"user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )
        clientsToClose += client
        return client
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 3000,
        condition: () -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (condition()) break
            if (System.currentTimeMillis() - start > timeoutMs) {
                break
            }
            kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun `CampaignDetailViewModel loads bonus info and does not preserve previous user my_awarded across identity change`() = runBlocking {
        var callCount = 0
        val client = createTestClient { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_campaign_detail") -> {
                    reply(
                        """{
                            "campaign":{"id":"$campaignId","owner_id":"owner-1","app_name":"Detail App","package_name":"com.detail.app","status":"open","target_testers":20,"target_days":14},
                            "is_member":true,
                            "is_actual_owner":false,
                            "can_manage":false,
                            "my_profile":{"id":"$userId","role":"member","credits":100},
                            "reports":[],
                            "testers":[],
                            "feedbacks":[],
                            "checked_in_confirmed_today":false
                        }"""
                    )
                }
                path.contains("campaigns") -> {
                    reply(
                        """
                        [
                            {
                                "id": "$campaignId",
                                "owner_id": "owner-1",
                                "app_name": "Detail App",
                                "package_name": "com.detail.app",
                                "status": "open",
                                "target_testers": 20,
                                "target_days": 14
                            }
                        ]
                        """
                    )
                }
                path.contains("profiles") -> {
                    reply("""[{"id": "$userId", "credits": 100, "role": "member"}]""")
                }
                path.contains("campaign_members") -> {
                    reply("""[{"id": "mem-1", "campaign_id": "$campaignId", "user_id": "$userId", "status": "approved"}]""")
                }
                path.contains("tester_reports") -> reply("[]")
                path.contains("warnings") -> reply("[]")
                path.contains("feedbacks") -> reply("[]")
                path.contains("get_campaign_tester_bonus") -> {
                    callCount++
                    if (callCount == 1) {
                        // User 1 has been awarded bonus
                        reply(
                            """
                            {
                                "has_round": true,
                                "is_owner": false,
                                "round_id": "round-detail-1",
                                "campaign_id": "$campaignId",
                                "reward_credits": 5,
                                "max_recipients": 10,
                                "paid_count": 8,
                                "remaining_slots": 2,
                                "status": "active",
                                "my_awarded": true,
                                "my_award_amount": 5,
                                "my_pending_eligible": false
                            }
                            """
                        )
                    } else {
                        // User 2 has NOT been awarded bonus
                        reply(
                            """
                            {
                                "has_round": true,
                                "is_owner": false,
                                "round_id": "round-detail-1",
                                "campaign_id": "$campaignId",
                                "reward_credits": 5,
                                "max_recipients": 10,
                                "paid_count": 8,
                                "remaining_slots": 2,
                                "status": "active",
                                "my_awarded": false,
                                "my_award_amount": 0,
                                "my_pending_eligible": true
                            }
                            """
                        )
                    }
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val submitManager = ReportSubmitManager(context)
        val vm = CampaignDetailViewModel(
            campaignId = campaignId,
            repository = repo,
            reportSubmitManager = submitManager,
            myUserId = userId,
            localizer = localizer,
            appContext = context,
        )
        vmsToCancel += vm

        waitUntil { !vm.uiState.value.isLoading }

        // First user has myAwarded = true
        assertEquals(true, vm.uiState.value.testerBonusInfo?.myAwarded)
        assertEquals(5, vm.uiState.value.testerBonusInfo?.myAwardAmount)

        // Refresh occurs for next user/session
        vm.refresh(forceRefresh = true)
        waitUntil { callCount >= 2 && !vm.uiState.value.isLoading }

        // Second user must NOT retain first user's myAwarded
        assertEquals(false, vm.uiState.value.testerBonusInfo?.myAwarded)
        assertEquals(true, vm.uiState.value.testerBonusInfo?.myPendingEligible)
    }
}
