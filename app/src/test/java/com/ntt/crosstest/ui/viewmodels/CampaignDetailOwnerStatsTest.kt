package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignDetailResult
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.EgressOptimizationConfig
import com.ntt.crosstest.data.EgressSyncProtocol
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.MergeResult
import com.ntt.crosstest.data.OwnerActivityStats
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.ScopedSyncSnapshot
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
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CampaignDetailOwnerStatsTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clientsToClose = mutableListOf<SupabaseClient>()
    private val vmsToCancel = mutableListOf<CampaignDetailViewModel>()

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var localizer: Localizer
    private val viewerId = "viewer-tester-1"
    private val ownerId = "owner-user-99"
    private val campaignId = "camp-owner-stats-1"
    private var originalDetailSyncV2Flag = false

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        originalDetailSyncV2Flag = EgressOptimizationConfig.isCampaignDetailSyncV2Enabled
        EgressOptimizationConfig.isCampaignDetailSyncV2Enabled = false
        EgressOptimizationConfig.resetCapabilityCache()
        SessionCacheManager.clear(context)
        localizer = Localizer(context)
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        } catch (_: Exception) {}
    }

    @After
    fun tearDown() = runBlocking {
        for (vm in vmsToCancel) {
            try { vm.viewModelScope.cancel() } catch (_: Exception) {}
        }
        vmsToCancel.clear()

        for (c in clientsToClose) {
            try { c.close() } catch (_: Exception) {}
        }
        clientsToClose.clear()

        EgressOptimizationConfig.isCampaignDetailSyncV2Enabled = originalDetailSyncV2Flag
        EgressOptimizationConfig.resetCapabilityCache()
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
        try {
            WorkManagerTestInitHelper.closeWorkDatabase()
        } catch (_: Exception) {}
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun createTestClient(
        uid: String = viewerId,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
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
            delay(20)
        }
    }

    @Test
    fun `get_campaign_detail with owner_stats populates uiState ownerStats`() = runBlocking {
        val client = createTestClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/get_campaign_detail") -> {
                    reply(
                        """{
                            "campaign":{"id":"$campaignId","owner_id":"$ownerId","app_name":"Owner Stats App","package_name":"com.owner.stats","status":"open","target_testers":12,"target_days":14},
                            "is_member":true,
                            "is_actual_owner":false,
                            "can_manage":false,
                            "my_profile":{"id":"$viewerId","role":"member","credits":100},
                            "reports":[],
                            "testers":[],
                            "feedbacks":[],
                            "checked_in_confirmed_today":false,
                            "owner_stats":{"tested_count":15,"installed_count":6}
                        }"""
                    )
                }
                path.endsWith("/get_campaign_tester_bonus") -> reply("""{"has_round":false}""")
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val submitManager = ReportSubmitManager(context)
        val vm = CampaignDetailViewModel(
            campaignId = campaignId,
            repository = repo,
            reportSubmitManager = submitManager,
            myUserId = viewerId,
            localizer = localizer,
            appContext = context,
        )
        vmsToCancel += vm

        waitUntil { !vm.uiState.value.isLoading }

        assertNotNull(vm.uiState.value.ownerStats)
        assertEquals(15, vm.uiState.value.ownerStats?.testedCount)
        assertEquals(6, vm.uiState.value.ownerStats?.installedCount)
    }

    @Test
    fun `legacy get_campaign_detail without owner_stats falls back to checkins and campaign_members excluding own campaigns`() = runBlocking {
        val ownCampaignId2 = "camp-owner-own-2"
        val otherCampaignA = "camp-other-a"
        val otherCampaignB = "camp-other-b"

        val client = createTestClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/get_campaign_detail") -> {
                    reply(
                        """{
                            "campaign":{"id":"$campaignId","owner_id":"$ownerId","app_name":"Legacy App","package_name":"com.legacy.app","status":"open","target_testers":12,"target_days":14},
                            "is_member":true,
                            "is_actual_owner":false,
                            "can_manage":false,
                            "my_profile":{"id":"$viewerId","role":"member","credits":100},
                            "reports":[],
                            "testers":[],
                            "feedbacks":[],
                            "checked_in_confirmed_today":false
                        }"""
                    )
                }
                path.endsWith("/checkins") -> {
                    reply(
                        """[
                            {"id":"ck-1","campaign_id":"$otherCampaignA","user_id":"$ownerId","checkin_date":"2026-11-15"},
                            {"id":"ck-2","campaign_id":"$otherCampaignA","user_id":"$ownerId","checkin_date":"2026-11-16"},
                            {"id":"ck-3","campaign_id":"$otherCampaignB","user_id":"$ownerId","checkin_date":"2026-11-16"},
                            {"id":"ck-own","campaign_id":"$campaignId","user_id":"$ownerId","checkin_date":"2026-11-16"}
                        ]"""
                    )
                }
                path.endsWith("/campaign_members") -> {
                    reply(
                        """[
                            {"campaign_id":"$otherCampaignA","user_id":"$ownerId"},
                            {"campaign_id":"$otherCampaignA","user_id":"$ownerId"},
                            {"campaign_id":"$otherCampaignB","user_id":"$ownerId"},
                            {"campaign_id":"$ownCampaignId2","user_id":"$ownerId"}
                        ]"""
                    )
                }
                path.endsWith("/campaigns") -> {
                    reply(
                        """[
                            {"id":"$campaignId","owner_id":"$ownerId","app_name":"Legacy App","package_name":"com.legacy.app"},
                            {"id":"$ownCampaignId2","owner_id":"$ownerId","app_name":"Own App 2","package_name":"com.own.two"}
                        ]"""
                    )
                }
                path.endsWith("/get_campaign_tester_bonus") -> reply("""{"has_round":false}""")
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val submitManager = ReportSubmitManager(context)
        val vm = CampaignDetailViewModel(
            campaignId = campaignId,
            repository = repo,
            reportSubmitManager = submitManager,
            myUserId = viewerId,
            localizer = localizer,
            appContext = context,
        )
        vmsToCancel += vm

        waitUntil { !vm.uiState.value.isLoading }

        assertNotNull(vm.uiState.value.ownerStats)
        assertEquals(3, vm.uiState.value.ownerStats?.testedCount)
        assertEquals(2, vm.uiState.value.ownerStats?.installedCount)
    }

    @Test
    fun `validateAndMergeCampaignDetail supports both present and absent owner_stats block`() {
        val json = Json { encodeDefaults = true }
        val day = LocalDate.of(2026, 11, 17)
        val tokenA = "a".repeat(64)
        val tokenB = "b".repeat(64)

        val basePayload = CampaignDetailResult(
            campaign = Campaign(id = campaignId, ownerId = ownerId, appName = "Test App", packageName = "com.test.app"),
            ownerStats = OwnerActivityStats(testedCount = 15, installedCount = 6),
        )
        val rawBlocksWithStats = json.encodeToJsonElement(basePayload).jsonObject
        val envelopeWithStats = JsonObject(
            mapOf(
                "schema_version" to JsonPrimitive(2),
                "business_date" to JsonPrimitive(day.toString()),
                "blocks" to JsonObject(
                    rawBlocksWithStats.mapValues { (_, value) ->
                        JsonObject(
                            mapOf(
                                "token" to JsonPrimitive(tokenA),
                                "changed" to JsonPrimitive(true),
                                "value" to value,
                            )
                        )
                    }
                ),
            )
        )

        // 1. Present owner_stats with changed = true
        val mergeWithStats = EgressSyncProtocol.validateAndMergeCampaignDetail(
            rawJson = envelopeWithStats.toString(),
            baseSnapshot = null,
            expectedBusinessDate = day,
        )
        assertTrue(mergeWithStats is MergeResult.Success)
        val successWithStats = mergeWithStats as MergeResult.Success
        assertEquals(OwnerActivityStats(testedCount = 15, installedCount = 6), successWithStats.payload.ownerStats)
        assertEquals(tokenA, successWithStats.tokens["owner_stats"])

        // 2. Present owner_stats with changed = false reuses baseSnapshot ownerStats
        val baseSnapshot = ScopedSyncSnapshot(
            payload = successWithStats.payload,
            tokens = successWithStats.tokens,
            scopeUid = viewerId,
            sessionGeneration = 1L,
            mutationVersion = 1L,
            businessDate = day,
            monotonicCreatedAtMs = 0L,
        )
        val unchangedBlocks = envelopeWithStats["blocks"]!!.jsonObject.mapValues { (key, _) ->
            JsonObject(
                mapOf(
                    "token" to JsonPrimitive(tokenA),
                    "changed" to JsonPrimitive(false),
                )
            )
        }
        val unchangedEnvelope = JsonObject(envelopeWithStats + ("blocks" to JsonObject(unchangedBlocks)))
        val mergeUnchanged = EgressSyncProtocol.validateAndMergeCampaignDetail(
            rawJson = unchangedEnvelope.toString(),
            baseSnapshot = baseSnapshot,
            expectedBusinessDate = day,
        )
        assertTrue(mergeUnchanged is MergeResult.Success)
        val successUnchanged = mergeUnchanged as MergeResult.Success
        assertTrue(successUnchanged.isAllUnchanged)
        assertEquals(OwnerActivityStats(testedCount = 15, installedCount = 6), successUnchanged.payload.ownerStats)

        // 3. Present owner_stats with null value
        val nullStatsBlocks = envelopeWithStats["blocks"]!!.jsonObject + (
            "owner_stats" to JsonObject(
                mapOf(
                    "token" to JsonPrimitive(tokenB),
                    "changed" to JsonPrimitive(true),
                    "value" to JsonNull,
                )
            )
        )
        val mergeNullStats = EgressSyncProtocol.validateAndMergeCampaignDetail(
            rawJson = JsonObject(envelopeWithStats + ("blocks" to JsonObject(nullStatsBlocks))).toString(),
            baseSnapshot = null,
            expectedBusinessDate = day,
        )
        assertTrue(mergeNullStats is MergeResult.Success)
        assertNull((mergeNullStats as MergeResult.Success).payload.ownerStats)

        // 4. Absent owner_stats block (pre-096 server) still succeeds and returns null ownerStats without base
        val legacyBlocks = envelopeWithStats["blocks"]!!.jsonObject - "owner_stats"
        val legacyEnvelope = JsonObject(envelopeWithStats + ("blocks" to JsonObject(legacyBlocks)))
        val mergeLegacy = EgressSyncProtocol.validateAndMergeCampaignDetail(
            rawJson = legacyEnvelope.toString(),
            baseSnapshot = null,
            expectedBusinessDate = day,
        )
        assertTrue(mergeLegacy is MergeResult.Success)
        assertNull((mergeLegacy as MergeResult.Success).payload.ownerStats)
    }
}
