package com.ntt.crosstest.data

import android.app.Application
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel
import com.ntt.crosstest.ui.viewmodels.MissionCreateUiState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionRewardModesUnitTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        PendingReportStore.clear(context)
        MissionReportDraftStore.clearAll(context)
        SessionCacheManager.clear(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    @After
    fun tearDown() {
        PendingReportStore.clear(context)
        MissionReportDraftStore.clearAll(context)
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }

    @Test
    fun testMissionRewardModeProperties() {
        val onceMission = Mission(
            id = "m1",
            title = "Once Mission",
            rewardCredits = 20,
            maxParticipants = 5,
            durationDays = 3,
            rewardMode = "once",
            escrowTotalCredits = 100,
            escrowRemainingCredits = 80,
            version = 2,
        )
        assertTrue(onceMission.isOnceMode)
        assertFalse(onceMission.isDailyMode)
        assertFalse(onceMission.isLegacyMode)
        assertEquals(20, onceMission.rewardCredits)
        assertEquals(100, onceMission.escrowTotalCredits)
        assertEquals(80, onceMission.escrowRemainingCredits)
        assertEquals(2, onceMission.version)

        val dailyMission = Mission(
            id = "m2",
            title = "Daily Mission",
            rewardCredits = 15,
            maxParticipants = 2,
            durationDays = 3,
            rewardMode = "daily",
            escrowTotalCredits = 90,
            escrowRemainingCredits = 60,
            submittedDaysCount = 2,
            todaySubmitted = true,
        )
        assertFalse(dailyMission.isOnceMode)
        assertTrue(dailyMission.isDailyMode)
        assertFalse(dailyMission.isLegacyMode)
        assertEquals(90, dailyMission.escrowTotalCredits)
        assertEquals(60, dailyMission.escrowRemainingCredits)
        assertEquals(2, dailyMission.submittedDaysCount)
        assertTrue(dailyMission.todaySubmitted)

        val legacyMission = Mission(
            id = "m3",
            title = "Legacy Mission",
            rewardCredits = 10,
            rewardMode = "legacy",
        )
        assertFalse(legacyMission.isOnceMode)
        assertFalse(legacyMission.isDailyMode)
        assertTrue(legacyMission.isLegacyMode)
    }

    @Test
    fun testMissionCreateUiStateBudgetCalculations() {
        // Once mode: 5 participants, 3 days, 20 credits/participant
        // fee = 50 * 3 = 150
        // escrow = 5 * 20 = 100
        // total = 250
        val onceState = MissionCreateUiState(
            rewardMode = "once",
            participants = 5,
            days = 3,
            rewardCredits = "20",
        )
        assertEquals(150, onceState.feeCredits)
        assertEquals(100, onceState.escrowCredits)
        assertEquals(250, onceState.totalCostCredits)
        assertTrue(onceState.estimatedEndsAtText.isNotBlank())

        // Daily mode: 2 participants, 3 days, 15 credits/day
        // fee = 50 * 3 = 150
        // escrow = 2 * 3 * 15 = 90
        // total = 240
        val dailyState = MissionCreateUiState(
            rewardMode = "daily",
            participants = 2,
            days = 3,
            rewardCredits = "15",
        )
        assertEquals(150, dailyState.feeCredits)
        assertEquals(90, dailyState.escrowCredits)
        assertEquals(240, dailyState.totalCostCredits)
        assertTrue(dailyState.estimatedEndsAtText.isNotBlank())
    }

    @Test
    fun testTesterReportRewardModeProperties() {
        val onceReport = TesterReport(
            id = "r1",
            campaignId = "c1",
            userId = "u1",
            submissionContext = "mission",
            missionId = "m1",
            missionRewardMode = "once",
        )
        assertTrue(onceReport.isMissionReport)
        assertTrue(onceReport.isOnceMission)
        assertFalse(onceReport.isDailyMission)

        val dailyReport = TesterReport(
            id = "r2",
            campaignId = "c1",
            userId = "u1",
            submissionContext = "mission",
            missionId = "m2",
            missionRewardMode = "daily",
        )
        assertTrue(dailyReport.isMissionReport)
        assertFalse(dailyReport.isOnceMission)
        assertTrue(dailyReport.isDailyMission)

        val appTestReport = TesterReport(
            id = "r3",
            campaignId = "c1",
            userId = "u1",
            submissionContext = "app_test",
        )
        assertFalse(appTestReport.isMissionReport)
        assertFalse(appTestReport.isOnceMission)
        assertFalse(appTestReport.isDailyMission)
    }

    @Test
    fun testDraftStoreOnceModeDurableDate() {
        val draft = MissionReportDraft(
            userId = "user_test",
            campaignId = "camp_test",
            missionId = "mission_once_test",
            reportDate = "once",
            rating = 5,
            comment = "Durable once draft across days",
        )
        MissionReportDraftStore.saveDraft(context, draft)

        val loaded = MissionReportDraftStore.loadDraft(
            context = context,
            userId = "user_test",
            campaignId = "camp_test",
            missionId = "mission_once_test",
            reportDate = "once",
        )
        assertNotNull(loaded)
        assertEquals("Durable once draft across days", loaded?.comment)
        assertEquals(5, loaded?.rating)

        // Clear draft
        MissionReportDraftStore.clearDraft(
            context = context,
            userId = "user_test",
            campaignId = "camp_test",
            missionId = "mission_once_test",
            reportDate = "once",
        )
        assertNull(MissionReportDraftStore.loadDraft(context, "user_test", "camp_test", "mission_once_test", "once"))
    }

    @Test
    fun testModelSerialization() {
        val json = Json { ignoreUnknownKeys = true }

        val createParams = CreateMissionV2Params(
            campaignId = "c1",
            title = "Test V2",
            description = "Desc",
            maxParticipants = 10,
            durationDays = 4,
            rewardCredits = 15,
            rewardMode = "daily",
        )
        val encodedCreateParams = json.encodeToString(CreateMissionV2Params.serializer(), createParams)
        assertTrue(encodedCreateParams.contains("\"p_reward_mode\":\"daily\""))

        val editResult = EditMissionResult(
            success = true,
            missionId = "m1",
            termsModified = true,
            feeDiff = 50,
            escrowDiff = -30,
            totalDiff = 20,
            version = 2,
        )
        val encodedEdit = json.encodeToString(EditMissionResult.serializer(), editResult)
        val decodedEdit = json.decodeFromString(EditMissionResult.serializer(), encodedEdit)
        assertTrue(decodedEdit.success)
        assertEquals(20, decodedEdit.totalDiff)
        assertEquals(-30, decodedEdit.escrowDiff)
        assertEquals(2, decodedEdit.version)

        val adminDelResult = AdminDeleteMissionResult(
            success = true,
            missionId = "m1",
            status = "deleted",
            reason = "Violation",
            isReplay = false,
        )
        val encodedDel = json.encodeToString(AdminDeleteMissionResult.serializer(), adminDelResult)
        val decodedDel = json.decodeFromString(AdminDeleteMissionResult.serializer(), encodedDel)
        assertTrue(decodedDel.success)
        assertEquals("deleted", decodedDel.status)
        assertEquals("Violation", decodedDel.reason)
    }

    @Test
    fun testAdminNormalRouteLoadsManageableMission() = runBlocking {
        var adminDeleteCalledWithMissionId: String? = null
        var adminDeleteCalledWithReason: String? = null

        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("get_campaign_detail") -> respond(
                    content = """{
                        "campaign": {
                            "id": "c_admin_test",
                            "owner_id": "owner_1",
                            "app_name": "Target App",
                            "status": "open",
                            "slot_number": 1
                        },
                        "is_member": false,
                        "is_owner": false,
                        "can_manage": true,
                        "my_profile": {
                            "id": "admin_user",
                            "role": "admin",
                            "is_locked": false
                        },
                        "profiles": {},
                        "testers": [],
                        "reports": [],
                        "feedbacks": []
                    }""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                path.contains("get_campaign_mission") -> respond(
                    content = """{
                        "mission": {
                            "id": "m_active_target",
                            "campaign_id": "c_admin_test",
                            "title": "Active Mission for App",
                            "status": "open",
                            "reward_mode": "once",
                            "reward_credits": 20,
                            "max_participants": 10,
                            "participant_count": 2
                        }
                    }""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                path.contains("admin_delete_mission") -> {
                    val bodyText = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                    if (bodyText.contains("m_active_target")) {
                        adminDeleteCalledWithMissionId = "m_active_target"
                    }
                    if (bodyText.contains("Violating terms")) {
                        adminDeleteCalledWithReason = "Violating terms"
                    }
                    respond(
                        content = """{
                            "success": true,
                            "mission_id": "m_active_target",
                            "status": "deleted",
                            "reason": "Violating terms"
                        }""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

        val supabase = createSupabaseClient("https://mock.supabase.co", "anon-key") {
            httpEngine = mockEngine
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
        }
        val sessionJson = """{"access_token":"token-admin","refresh_token":"ref-admin","token_type":"bearer","expires_in":3600,"user":{"id":"admin_user","aud":"authenticated"}}"""
        supabase.auth.awaitInitialization()
        supabase.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(sessionJson))

        val repo = CampaignRepository(supabase, Localizer(context))
        val submitManager = ReportSubmitManager(context)

        // Admin opens campaign detail normally from AdminCampaignsScreen without missionId:
        val vm = CampaignDetailViewModel(
            campaignId = "c_admin_test",
            repository = repo,
            reportSubmitManager = submitManager,
            myUserId = "admin_user",
            localizer = Localizer(context),
            appContext = context,
            initialMissionId = null // Normal route!
        )

        withTimeout(10_000) {
            vm.uiState.first { !it.isLoading && it.adminManageableMission != null }
        }

        val state = vm.uiState.value
        assertTrue("Viewer is recognized as admin", state.isAdmin)
        assertFalse("Screen is NOT converted to mission report context", state.isMissionReport)
        assertNotNull("Admin manageable mission is loaded independently", state.adminManageableMission)
        assertEquals("m_active_target", state.adminManageableMission?.id)

        // Admin triggers adminDeleteMission with pinned missionId
        var onDeletedCalled = false
        vm.adminDeleteMission(missionId = "m_active_target", reason = "Violating terms") {
            onDeletedCalled = true
        }

        withTimeout(5_000) {
            while (!onDeletedCalled) delay(50)
        }

        assertEquals("m_active_target", adminDeleteCalledWithMissionId)
        assertEquals("Violating terms", adminDeleteCalledWithReason)
        assertTrue(onDeletedCalled)
    }

    @Test
    fun testOnceModeQueueAndMidnightRolloverStability() = runBlocking {
        val manager = ReportSubmitManager(context)
        val uid = "user_once_rollover"
        val campaignId = "camp_once_rollover"
        val missionId = "mission_once_rollover"

        val keyBefore = manager.submitStateKey(uid, campaignId, "mission", missionId, "once")
        assertEquals("$uid:$campaignId:mission:$missionId:once", keyBefore)

        val request = ReportSubmitManager.SubmitRequest(
            campaignId = campaignId,
            appName = "Rollover App",
            userId = uid,
            rating = 5,
            comment = "Initial once comment",
            needsJoin = false,
            missionId = missionId,
            submissionContext = "mission",
            expectedDate = "2026-11-03",
            isOnceMode = true,
            scopedKey = keyBefore,
        )

        val admission = manager.submit(request)
        assertTrue("Request must be admitted", admission is ReportSubmitManager.SubmitAdmissionResult.Accepted)

        // Check active state
        val state = manager.currentStateForScope(keyBefore)
        assertNotNull(state)
        assertEquals(request.clientSubmitId, state?.requestId)
        assertEquals(keyBefore, state?.scopedKey)

        // Simulate reading pending reports from disk (e.g. after process restart)
        val pendingList = PendingReportStore.list(context)
        val savedReport = pendingList.firstOrNull { it.id == request.clientSubmitId }
        assertNotNull(savedReport)
        assertTrue(savedReport?.isOnceMode == true)
        assertEquals(keyBefore, savedReport?.scopedKey)

        // Effective date on next day:
        val effectiveDate = ReportSubmitManager.effectiveReportDate(savedReport!!)
        assertEquals("once", effectiveDate)

        manager.cancelAllPendingWork()
    }
}
