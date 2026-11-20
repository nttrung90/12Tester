package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.R
import com.ntt.crosstest.ui.viewmodels.HomeViewModel
import com.ntt.crosstest.ui.viewmodels.MissionCreateViewModel
import com.ntt.crosstest.ui.viewmodels.MissionReportViewModel
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel
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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionCopyTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        PendingReportStore.clear(context)
        MissionReportDraftStore.clearAll(context)
        SessionCacheManager.clear(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        PendingReportStore.clear(context)
        MissionReportDraftStore.clearAll(context)
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }

    private fun buildRepo(
        userId: String = "user1",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): CampaignRepository {
        val mockEngine = MockEngine { request -> handler(request) }
        val supabase = createSupabaseClient("https://mock.supabase.co", "anon-key") {
            httpEngine = mockEngine
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
        }
        runBlocking {
            supabase.auth.awaitInitialization()
            val sessionJson = """
                {
                    "access_token": "fake-token",
                    "refresh_token": "fake-refresh",
                    "token_type": "bearer",
                    "expires_in": 3600,
                    "user": {
                        "id": "$userId",
                        "aud": "authenticated",
                        "email": "$userId@test.local"
                    }
                }
            """.trimIndent()
            supabase.auth.importSession(json.decodeFromString(UserSession.serializer(), sessionJson))
        }
        return CampaignRepository(supabase, Localizer(context))
    }

    private suspend fun awaitUntil(timeoutMs: Long = 3000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            delay(20)
        }
    }

    private fun jsonOk(body: String, scope: MockRequestHandleScope): HttpResponseData =
        scope.respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun jsonErr(status: HttpStatusCode, message: String, scope: MockRequestHandleScope): HttpResponseData =
        scope.respond("""{"message":"$message"}""", status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun `Mission copy eligibility and unsynced expiration checks`() {
        val now = 1_700_000_000_000L
        val pastIso = "2023-11-14T22:00:00Z" // < 1700000000000 (1700000000s = 2023-11-14T22:13:20Z)
        val futureIso = "2023-11-14T23:00:00Z"

        assertTrue(Mission(id = "m1", status = "expired", endsAt = pastIso).isCopyEligible(now))
        assertTrue(Mission(id = "m2", status = "open", endsAt = pastIso).isCopyEligible(now))
        assertTrue(Mission(id = "m3", status = "full", endsAt = pastIso).isCopyEligible(now))
        assertTrue(Mission(id = "m4", status = "paused_by_creator", endsAt = pastIso).isCopyEligible(now))

        assertFalse(Mission(id = "m5", status = "open", endsAt = futureIso).isCopyEligible(now))
        assertFalse(Mission(id = "m6", status = "full", endsAt = futureIso).isCopyEligible(now))
        assertFalse(Mission(id = "m7", status = "paused_by_creator", endsAt = futureIso).isCopyEligible(now))
        assertFalse(Mission(id = "m8", status = "stopped_no_credits", endsAt = pastIso).isCopyEligible(now))
        assertFalse(Mission(id = "m9", status = "deleted", endsAt = pastIso).isCopyEligible(now))
    }

    @Test
    fun `Mission backward-compatible JSON decoding without new fields`() {
        val legacyJson = """{"id":"m1","title":"Old","status":"open","reward_credits":10,"max_participants":5,"participant_count":1}"""
        val m = json.decodeFromString(Mission.serializer(), legacyJson)
        assertNull(m.sourceMissionId)
        assertFalse(m.blockPreviousParticipants)
        assertFalse(m.isBlockedPreviousParticipant)
    }

    @Test
    fun `MissionCreateViewModel copy mode prefills draft, locks app, defaults switch off, and preserves edits across reload and rotation`() = runBlocking {
        var draftCalls = 0
        val repo = buildRepo { req ->
            val path = req.url.encodedPath
            when {
                path.contains("get_mission_copy_draft") -> {
                    draftCalls++
                    jsonOk(
                        """{
                            "source_mission_id":"m_src",
                            "campaign_id":"c_locked",
                            "app_name":"Locked App",
                            "package_name":"com.locked.app",
                            "campaign_status":"open",
                            "title":"Original Title",
                            "description":"Original Desc",
                            "reward_mode":"once",
                            "max_participants":6,
                            "duration_days":4,
                            "reward_credits":25,
                            "block_previous_participants":false,
                            "user_credits":1200,
                            "source_status":"expired",
                            "source_reward_mode":"legacy"
                        }""",
                        this,
                    )
                }
                path.contains("profiles") -> jsonOk("""[{"id":"user1","credits":1500}]""", this)
                else -> jsonOk("{}", this)
            }
        }

        val savedState = SavedStateHandle()
        val vm = MissionCreateViewModel(
            repository = repo,
            localizer = Localizer(context),
            sourceMissionId = "m_src",
            sourceCampaignId = "c_locked",
            savedStateHandle = savedState,
        )
        awaitUntil { !vm.uiState.value.isLoading }

        val s1 = vm.uiState.value
        assertFalse(s1.isLoading)
        assertTrue(s1.isCopyMode)
        assertEquals("c_locked", s1.selectedCampaignId)
        assertEquals("Original Title", s1.title)
        assertEquals("Original Desc", s1.description)
        assertEquals("once", s1.rewardMode)
        assertEquals(6, s1.participants)
        assertEquals(4, s1.days)
        assertEquals("25", s1.rewardCredits)
        assertFalse(s1.blockPreviousParticipants)
        assertEquals(1500, s1.userCredits)

        // App cannot be switched in copy mode
        vm.selectCampaign("other_camp")
        assertEquals("c_locked", vm.uiState.value.selectedCampaignId)

        // Edit fields and enable blockPreviousParticipants
        vm.updateTitle("Edited Title")
        vm.selectRewardMode("daily")
        vm.toggleBlockPreviousParticipants(true)

        // Reload (retry/refresh) must preserve user edits while updating credits
        vm.retry()
        awaitUntil { draftCalls >= 2 && !vm.uiState.value.isLoading }
        val sAfterReload = vm.uiState.value
        assertEquals(2, draftCalls)
        assertEquals("Edited Title", sAfterReload.title)
        assertEquals("daily", sAfterReload.rewardMode)
        assertTrue(sAfterReload.blockPreviousParticipants)

        // Simulate recreation with same SavedStateHandle (rotation/process restore)
        val vmRestored = MissionCreateViewModel(
            repository = repo,
            localizer = Localizer(context),
            sourceMissionId = "m_src",
            sourceCampaignId = "c_locked",
            savedStateHandle = savedState,
        )
        awaitUntil { !vmRestored.uiState.value.isLoading }
        val sRestored = vmRestored.uiState.value
        assertEquals("Edited Title", sRestored.title)
        assertEquals("daily", sRestored.rewardMode)
        assertTrue(sRestored.blockPreviousParticipants)
    }

    @Test
    fun `MissionCreateViewModel copy retry keeps stable client_operation_id across network failure and rotates on edit`() = runBlocking {
        val capturedOpIds = mutableListOf<String>()
        var failFirstSave = true
        val repo = buildRepo { req ->
            val path = req.url.encodedPath
            when {
                path.contains("get_mission_copy_draft") -> jsonOk(
                    """{
                        "source_mission_id":"m_src","campaign_id":"c1","app_name":"App","package_name":"com.app",
                        "campaign_status":"open","title":"T1","description":"D1","reward_mode":"once",
                        "max_participants":5,"duration_days":2,"reward_credits":20,"block_previous_participants":false,
                        "user_credits":1000
                    }""",
                    this,
                )
                path.contains("profiles") -> jsonOk("""[{"id":"user1","credits":1000}]""", this)
                path.contains("create_mission_from_source") -> {
                    val rawBody = (req.body as? TextContent)?.text.orEmpty()
                    val opId = json.parseToJsonElement(rawBody).jsonObject["p_client_operation_id"]?.jsonPrimitive?.content.orEmpty()
                    capturedOpIds.add(opId)
                    if (failFirstSave) {
                        failFirstSave = false
                        jsonErr(HttpStatusCode.GatewayTimeout, "network timeout", this)
                    } else {
                        jsonOk(
                            """{"success":true,"mission_id":"m_new","fee":100,"escrow":100,"total_cost":200,"balance":800,"reward_mode":"once","source_mission_id":"m_src","block_previous_participants":true,"is_replay":true}""",
                            this,
                        )
                    }
                }
                else -> jsonOk("{}", this)
            }
        }

        val vm = MissionCreateViewModel(
            repository = repo,
            localizer = Localizer(context),
            sourceMissionId = "m_src",
            sourceCampaignId = "c1",
        )
        awaitUntil { !vm.uiState.value.isLoading }
        vm.toggleBlockPreviousParticipants(true)

        // First attempt -> network timeout
        vm.create()
        awaitUntil { !vm.uiState.value.isSaving && vm.uiState.value.error != null }
        assertFalse(vm.uiState.value.created)
        assertNotNull(vm.uiState.value.error)

        // Retry without changing fields -> MUST reuse the exact same p_client_operation_id
        vm.create()
        awaitUntil { !vm.uiState.value.isSaving && vm.uiState.value.created }
        assertTrue(vm.uiState.value.created)
        assertEquals(2, capturedOpIds.size)
        assertTrue(capturedOpIds[0].isNotBlank())
        assertEquals(capturedOpIds[0], capturedOpIds[1])

        // If user edits after an error, operation ID must rotate
        vm.updateTitle("Changed Title")
        failFirstSave = true
        vm.create()
        awaitUntil { !vm.uiState.value.isSaving && capturedOpIds.size >= 3 }
        assertEquals(3, capturedOpIds.size)
        assertNotEquals(capturedOpIds[1], capturedOpIds[2])
    }

    @Test
    fun `MissionCreateViewModel keeps same client_operation_id and surfaces localized message on MISSION_BUSY_RETRY`() = runBlocking {
        val localizer = Localizer(context)
        val capturedOpIds = mutableListOf<String>()
        var returnBusy = true
        val repo = buildRepo { req ->
            val path = req.url.encodedPath
            when {
                path.contains("get_mission_copy_draft") -> jsonOk(
                    """{
                        "source_mission_id":"m_src","campaign_id":"c1","app_name":"App","package_name":"com.app",
                        "campaign_status":"open","title":"T1","description":"D1","reward_mode":"once",
                        "max_participants":5,"duration_days":2,"reward_credits":20,"block_previous_participants":false,
                        "user_credits":1000
                    }""",
                    this,
                )
                path.contains("profiles") -> jsonOk("""[{"id":"user1","credits":1000}]""", this)
                path.contains("create_mission_from_source") -> {
                    val rawBody = (req.body as? TextContent)?.text.orEmpty()
                    val opId = json.parseToJsonElement(rawBody).jsonObject["p_client_operation_id"]?.jsonPrimitive?.content.orEmpty()
                    capturedOpIds.add(opId)
                    if (returnBusy) {
                        returnBusy = false
                        jsonErr(HttpStatusCode.Conflict, "MISSION_BUSY_RETRY", this)
                    } else {
                        jsonOk(
                            """{"success":true,"mission_id":"m_new","fee":100,"escrow":100,"total_cost":200,"balance":800,"reward_mode":"once","source_mission_id":"m_src","block_previous_participants":false,"is_replay":false}""",
                            this,
                        )
                    }
                }
                else -> jsonOk("{}", this)
            }
        }

        val vm = MissionCreateViewModel(
            repository = repo,
            localizer = localizer,
            sourceMissionId = "m_src",
            sourceCampaignId = "c1",
        )
        awaitUntil { !vm.uiState.value.isLoading }

        vm.create()
        awaitUntil { !vm.uiState.value.isSaving && vm.uiState.value.error != null }
        assertFalse(vm.uiState.value.created)
        assertEquals(localizer.get(R.string.mission_copy_err_busy_retry), vm.uiState.value.error)

        vm.create()
        awaitUntil { !vm.uiState.value.isSaving && vm.uiState.value.created }
        assertTrue(vm.uiState.value.created)
        assertEquals(2, capturedOpIds.size)
        assertEquals(capturedOpIds[0], capturedOpIds[1])
    }

    @Test
    fun `MissionCreateViewModel handles credit change between open and save`() = runBlocking {
        var profileCredits = 1000
        val repo = buildRepo { req ->
            val path = req.url.encodedPath
            when {
                path.contains("get_mission_copy_draft") -> jsonOk(
                    """{
                        "source_mission_id":"m_src","campaign_id":"c1","app_name":"App","package_name":"com.app",
                        "campaign_status":"open","title":"T1","description":"D1","reward_mode":"once",
                        "max_participants":5,"duration_days":2,"reward_credits":20,"block_previous_participants":false,
                        "user_credits":1000
                    }""",
                    this,
                )
                path.contains("profiles") -> jsonOk("""[{"id":"user1","credits":$profileCredits}]""", this)
                path.contains("create_mission_from_source") -> {
                    profileCredits = 150
                    jsonErr(HttpStatusCode.BadRequest, "INSUFFICIENT_MIN_CREDITS", this)
                }
                else -> jsonOk("{}", this)
            }
        }

        val vm = MissionCreateViewModel(
            repository = repo,
            localizer = Localizer(context),
            sourceMissionId = "m_src",
            sourceCampaignId = "c1",
        )
        awaitUntil { !vm.uiState.value.isLoading }
        vm.create()
        awaitUntil { !vm.uiState.value.isSaving && vm.uiState.value.error != null }
        val s = vm.uiState.value
        assertFalse(s.created)
        assertEquals(150, s.userCredits)
        assertTrue(s.error?.contains("150") == true)
    }

    @Test
    fun `HomeViewModel joinMission handles MISSION_PREVIOUS_PARTICIPANT_BLOCKED from stale cache and blocks local retry`() = runBlocking {
        val localizer = Localizer(context)
        var joinCalls = 0
        var serverBlockedFlag = false
        val repo = buildRepo { req ->
            val path = req.url.encodedPath
            when {
                path.contains("get_open_missions") -> jsonOk(
                    """{"missions":[{"id":"m_b","title":"Copy B","status":"open","reward_credits":20,"max_participants":5,"participant_count":0,"ends_at":"2026-11-28T00:00:00Z","created_at":"2026-11-19T00:00:00Z","campaign_id":"c1","app_name":"App","creator_id":"owner1","creator_name":"Owner","is_mine":false,"has_joined":false,"has_completed":false,"is_blocked_previous_participant":$serverBlockedFlag}]}""",
                    this,
                )
                path.contains("join_mission") -> {
                    joinCalls++
                    serverBlockedFlag = true
                    jsonErr(HttpStatusCode.BadRequest, "MISSION_PREVIOUS_PARTICIPANT_BLOCKED", this)
                }
                else -> jsonOk("{}", this)
            }
        }

        val homeVm = HomeViewModel(repository = repo, context = context, localizer = localizer)
        homeVm.refreshMissions(forceRefresh = true)
        awaitUntil { !homeVm.uiState.value.isMissionsLoading && homeVm.uiState.value.missions.isNotEmpty() }
        assertEquals(1, homeVm.uiState.value.missions.size)
        assertFalse(homeVm.uiState.value.missions.first().isBlockedPreviousParticipant)

        var firstDone = false
        var firstSuccess = true
        var firstMsg = ""
        homeVm.joinMission("m_b") { ok, msg ->
            firstSuccess = ok
            firstMsg = msg
            firstDone = true
        }
        awaitUntil { firstDone }

        assertFalse(firstSuccess)
        assertEquals(localizer.get(R.string.mission_err_previous_participant_blocked), firstMsg)
        assertEquals(1, joinCalls)
        assertTrue(homeVm.uiState.value.missions.first().isBlockedPreviousParticipant)

        // Second attempt while UI already knows it is blocked must not hit RPC
        var secondDone = false
        var secondSuccess = true
        var secondMsg = ""
        homeVm.joinMission("m_b") { ok, msg ->
            secondSuccess = ok
            secondMsg = msg
            secondDone = true
        }
        awaitUntil { secondDone }
        assertFalse(secondSuccess)
        assertEquals(localizer.get(R.string.mission_err_previous_participant_blocked), secondMsg)
        assertEquals(1, joinCalls)
    }

    @Test
    fun `MissionReportViewModel joinMission handles MISSION_PREVIOUS_PARTICIPANT_BLOCKED and locks button state`() = runBlocking {
        val localizer = Localizer(context)
        var joinCalls = 0
        val repo = buildRepo { req ->
            val path = req.url.encodedPath
            when {
                path.contains("campaigns") -> jsonOk(
                    """[{"id":"c1","owner_id":"owner1","app_name":"App","package_name":"com.app","status":"open"}]""",
                    this,
                )
                path.contains("get_campaign_detail") -> jsonOk(
                    """{"campaign":{"id":"c1","owner_id":"owner1","app_name":"App","package_name":"com.app","status":"open"},"testers":[],"profiles":{}}""",
                    this,
                )
                path.contains("get_open_missions") -> jsonOk(
                    """{"missions":[{"id":"m_b","title":"Copy B","status":"open","reward_credits":20,"max_participants":5,"participant_count":0,"ends_at":"2026-11-28T00:00:00Z","created_at":"2026-11-19T00:00:00Z","campaign_id":"c1","app_name":"App","creator_id":"owner1","creator_name":"Owner","is_mine":false,"has_joined":false,"has_completed":false,"is_blocked_previous_participant":false}]}""",
                    this,
                )
                path.contains("get_campaign_mission") -> jsonOk(
                    """{"mission":{"id":"m_b","title":"Copy B","status":"open","reward_credits":20,"max_participants":5,"participant_count":0,"ends_at":"2026-11-28T00:00:00Z","created_at":"2026-11-19T00:00:00Z","campaign_id":"c1","app_name":"App","creator_id":"owner1","creator_name":"Owner","is_mine":false,"has_joined":false,"has_completed":false,"is_blocked_previous_participant":false}}""",
                    this,
                )
                path.contains("tester_reports") -> jsonOk("[]", this)
                path.contains("join_mission") -> {
                    joinCalls++
                    jsonErr(HttpStatusCode.BadRequest, "MISSION_PREVIOUS_PARTICIPANT_BLOCKED", this)
                }
                else -> jsonOk("{}", this)
            }
        }
        val reportSubmitManager = ReportSubmitManager(context)
        val reportVm = MissionReportViewModel(
            campaignId = "c1",
            missionId = "m_b",
            repository = repo,
            reportSubmitManager = reportSubmitManager,
            appContext = context,
        )
        awaitUntil { !reportVm.uiState.value.isLoading && reportVm.uiState.value.mission != null }

        assertFalse(reportVm.uiState.value.isJoined)
        reportVm.joinMission()
        awaitUntil { !reportVm.uiState.value.isJoining && reportVm.uiState.value.errorMessage != null }

        val stateAfter = reportVm.uiState.value
        assertFalse(stateAfter.isJoined)
        assertEquals(true, stateAfter.mission?.isBlockedPreviousParticipant)
        assertEquals(localizer.get(R.string.mission_err_previous_participant_blocked), stateAfter.errorMessage)
        assertEquals(1, joinCalls)

        // Second tap blocked locally
        reportVm.joinMission()
        assertEquals(1, joinCalls)
    }

    @Test
    fun `OwnerAppDetailViewModel refreshAfterMissionCreated reloads copied mission`() = runBlocking {
        var missionReturnCount = 0
        val repo = buildRepo { req ->
            val path = req.url.encodedPath
            when {
                path.contains("get_campaign_detail") -> jsonOk(
                    """{"campaign":{"id":"c1","owner_id":"user1","app_name":"App","package_name":"com.app","status":"open"},"testers":[],"profiles":{"user1":{"id":"user1","credits":800}}}""",
                    this,
                )
                path.contains("get_campaign_mission") -> {
                    missionReturnCount++
                    val id = if (missionReturnCount == 1) "m_old" else "m_copied"
                    val status = if (missionReturnCount == 1) "expired" else "open"
                    jsonOk(
                        """{"mission":{"id":"$id","title":"Mission $id","status":"$status","reward_credits":20,"max_participants":5,"participant_count":0,"campaign_id":"c1","creator_id":"user1","reward_mode":"once"}}""",
                        this,
                    )
                }
                path.contains("get_daily_task_status") -> jsonOk("""{"completed":0,"target":3,"is_exempt":false}""", this)
                path.contains("get_campaign_tester_bonus_round") -> jsonOk("""{"round":null}""", this)
                else -> jsonOk("{}", this)
            }
        }

        val ownerVm = OwnerAppDetailViewModel(
            campaignId = "c1",
            repository = repo,
            localizer = Localizer(context),
        )
        awaitUntil { ownerVm.uiState.value.mission != null }
        assertEquals("m_old", ownerVm.uiState.value.mission?.id)
        assertEquals("expired", ownerVm.uiState.value.mission?.status)

        ownerVm.refreshAfterMissionCreated()
        awaitUntil { ownerVm.uiState.value.mission?.id == "m_copied" }
        assertEquals("m_copied", ownerVm.uiState.value.mission?.id)
        assertEquals("open", ownerVm.uiState.value.mission?.status)
    }
}
