package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.ntt.crosstest.data.AdminCampaignRecipientItem
import com.ntt.crosstest.data.AdminMemberItem
import com.ntt.crosstest.data.AdminNotificationCampaign
import com.ntt.crosstest.data.AdminNotificationCampaignDetailResponse
import com.ntt.crosstest.data.AdminNotificationCampaignMutationResponse
import com.ntt.crosstest.data.AdminNotificationCampaignPreviewResponse
import com.ntt.crosstest.data.AdminNotificationCampaignRepository
import com.ntt.crosstest.data.AdminNotificationSchedulerHealth
import com.ntt.crosstest.data.AdminListNotificationCampaignsEnvelope
import com.ntt.crosstest.data.AdminListNotificationCampaignRecipientsEnvelope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

private fun createDummyCampaign(
    id: String = "c1111111-1111-1111-1111-111111111111",
    status: String = "draft",
    revision: Int = 1,
    titleVi: String = "Tiêu đề",
    titleEn: String = "Title",
) = AdminNotificationCampaign(
    id = id,
    titleVi = titleVi,
    titleEn = titleEn,
    bodyVi = "Nội dung",
    bodyEn = "Body",
    defaultLocale = "vi",
    audienceType = "all",
    status = status,
    revision = revision,
    totalRecipients = 10,
    sentCount = 0,
    readCount = 0,
    failedCount = 0,
    createdBy = "admin-1",
    createdAt = "2026-11-01T12:00:00.000000Z",
    updatedAt = "2026-11-01T12:00:00.000000Z",
)

private fun createTestClient(): SupabaseClient =
    createSupabaseClient("https://fixture.invalid", "dummy-key") {
        install(Postgrest)
        httpEngine = MockEngine {
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminNotificationCampaignViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val fixedClockInstant = Instant.parse("2026-11-01T00:00:00Z")

    open class TestableRepository(client: SupabaseClient) : AdminNotificationCampaignRepository(client) {
        var createCallCount = 0
        var updateCallCount = 0
        var scheduleCallCount = 0
        var sendNowCallCount = 0
        var cancelCallCount = 0
        var listCallCount = 0
        var detailCallCount = 0
        var healthCallCount = 0

        val sentIds = mutableListOf<String>()
        var lastRequestIdPassed: String? = null
        var lastScheduledForPassed: String? = null
        var shouldThrowCasConflict = false
        var shouldThrowNetworkError = false

        var createDeferred: CompletableDeferred<AdminNotificationCampaignMutationResponse>? = null
        var slowListDeferred: CompletableDeferred<AdminListNotificationCampaignsEnvelope>? = null
        var slowDetailDeferred: CompletableDeferred<AdminNotificationCampaignDetailResponse>? = null
        var slowHealthDeferred: CompletableDeferred<AdminNotificationSchedulerHealth>? = null
        var sendGate: CompletableDeferred<Unit>? = null
        var scheduleGate: CompletableDeferred<Unit>? = null
        var cancelGate: CompletableDeferred<Unit>? = null

        override suspend fun createCampaign(
            requestId: String,
            titleVi: String,
            titleEn: String,
            bodyVi: String,
            bodyEn: String,
            defaultLocale: String,
            audienceType: String,
            targetRole: String?,
            selectedUserIds: List<String>?,
            reason: String?,
        ): AdminNotificationCampaignMutationResponse {
            createCallCount++
            lastRequestIdPassed = requestId

            createDeferred?.let { return it.await() }

            if (shouldThrowNetworkError) {
                throw IOException("Simulated network timeout")
            }

            return AdminNotificationCampaignMutationResponse(
                success = true,
                replayed = false,
                campaign = AdminNotificationCampaign(
                    id = "c1111111-1111-1111-1111-111111111111",
                    titleVi = titleVi,
                    titleEn = titleEn,
                    bodyVi = bodyVi,
                    bodyEn = bodyEn,
                    defaultLocale = defaultLocale,
                    audienceType = audienceType,
                    status = "draft",
                    revision = 1,
                    createdBy = "admin-1",
                    createdAt = "2026-11-01T12:00:00.000000Z",
                    updatedAt = "2026-11-01T12:00:00.000000Z",
                )
            )
        }

        override suspend fun updateCampaign(
            requestId: String,
            campaignId: String,
            expectedRevision: Int,
            titleVi: String,
            titleEn: String,
            bodyVi: String,
            bodyEn: String,
            defaultLocale: String,
            audienceType: String,
            targetRole: String?,
            selectedUserIds: List<String>?,
            reason: String?,
        ): AdminNotificationCampaignMutationResponse {
            updateCallCount++
            lastRequestIdPassed = requestId

            if (shouldThrowCasConflict) {
                throw IllegalStateException("REVISION_CONFLICT: Expected revision $expectedRevision but found 2")
            }
            if (shouldThrowNetworkError) {
                throw IOException("Network error on update")
            }

            return AdminNotificationCampaignMutationResponse(
                success = true,
                replayed = false,
                campaign = AdminNotificationCampaign(
                    id = campaignId,
                    titleVi = titleVi,
                    titleEn = titleEn,
                    bodyVi = bodyVi,
                    bodyEn = bodyEn,
                    defaultLocale = defaultLocale,
                    audienceType = audienceType,
                    status = "draft",
                    revision = expectedRevision + 1,
                    createdBy = "admin-1",
                    createdAt = "2026-11-01T12:00:00.000000Z",
                    updatedAt = "2026-11-01T12:05:00.000000Z",
                )
            )
        }

        override suspend fun scheduleCampaign(
            requestId: String,
            campaignId: String,
            expectedRevision: Int,
            scheduledForIso: String,
            reason: String?,
        ): AdminNotificationCampaignMutationResponse {
            scheduleCallCount++
            sentIds += requestId
            lastRequestIdPassed = requestId
            lastScheduledForPassed = scheduledForIso

            scheduleGate?.await()

            if (shouldThrowNetworkError) {
                throw IOException("Lost connection during schedule")
            }

            return AdminNotificationCampaignMutationResponse(
                success = true,
                replayed = false,
                campaign = createDummyCampaign(id = campaignId, status = "queued", revision = expectedRevision + 1)
            )
        }

        override suspend fun sendNowCampaign(
            requestId: String,
            campaignId: String,
            expectedRevision: Int,
            reason: String?,
        ): AdminNotificationCampaignMutationResponse {
            sendNowCallCount++
            sentIds += requestId
            lastRequestIdPassed = requestId

            sendGate?.await()

            if (shouldThrowNetworkError) {
                throw IOException("Lost connection during sendNow")
            }

            return AdminNotificationCampaignMutationResponse(
                success = true,
                replayed = false,
                campaign = createDummyCampaign(id = campaignId, status = "queued", revision = expectedRevision + 1)
            )
        }

        override suspend fun cancelCampaign(
            requestId: String,
            campaignId: String,
            expectedRevision: Int,
            reason: String,
        ): AdminNotificationCampaignMutationResponse {
            cancelCallCount++
            sentIds += requestId
            lastRequestIdPassed = requestId

            cancelGate?.await()

            if (shouldThrowNetworkError) {
                throw IOException("Lost connection during cancel")
            }

            return AdminNotificationCampaignMutationResponse(
                success = true,
                replayed = false,
                campaign = createDummyCampaign(id = campaignId, status = "cancelled", revision = expectedRevision + 1)
            )
        }

        override suspend fun listCampaigns(
            status: String?,
            audienceType: String?,
            limit: Int,
            cursor: String?,
        ): AdminListNotificationCampaignsEnvelope {
            listCallCount++
            slowListDeferred?.let { return it.await() }

            if (shouldThrowNetworkError) {
                throw IOException("Network error on list")
            }

            return AdminListNotificationCampaignsEnvelope(
                items = listOf(
                    createDummyCampaign(
                        id = "c1",
                        titleVi = "Vi $status",
                        status = status ?: "draft"
                    )
                ),
                hasMore = cursor == null,
                nextCursor = if (cursor == null) "cursor-1" else null
            )
        }

        override suspend fun getCampaignDetail(
            campaignId: String,
            eventsLimit: Int,
        ): AdminNotificationCampaignDetailResponse {
            detailCallCount++
            slowDetailDeferred?.let { return it.await() }

            return AdminNotificationCampaignDetailResponse(
                campaign = createDummyCampaign(id = campaignId)
            )
        }

        override suspend fun getSchedulerHealth(): AdminNotificationSchedulerHealth {
            healthCallCount++
            slowHealthDeferred?.let { return it.await() }

            return AdminNotificationSchedulerHealth(
                pgCronAvailable = false,
                pgCronJobActive = false,
                schedulerReady = false,
                pendingDueCampaignsCount = 5,
                inFlightCampaignsCount = 0,
                healthStatus = "scheduler_manual_fallback_required"
            )
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- 1. Session Switch Boundaries & Live Guard
    @Test
    fun testStaleDialogAbortOnOwnerSwitchAndStaleGeneration() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)

        var liveUser: String? = "admin-A"
        var liveGen = 10L

        val vm = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { liveUser },
            sessionGenProvider = { liveGen },
            clock = { fixedClockInstant },
        )

        val campA = createDummyCampaign(id = "camp-A", revision = 1)
        vm.openScheduleDialog(campA)

        val dialogA = vm.uiState.value.activeDialog as? NotificationActionDialog.Schedule
        assertNotNull(dialogA)
        assertEquals("admin-A", dialogA!!.ownerUid)

        // Switch to Admin B before confirmation is clicked
        liveUser = "admin-B"
        liveGen = 11L

        // Attempt to execute stale dialog with A's arguments
        vm.executeSchedule(
            campaign = dialogA.campaign,
            dateStr = "2026-11-05",
            timeStr = "10:00",
            reason = "Lý do của A",
            requestId = dialogA.requestId,
            dialogOwner = dialogA.ownerUid,
        )

        // Must abort immediately without calling repository!
        assertEquals("Must abort execution and make 0 schedule mutations", 0, repo.scheduleCallCount)
        assertNull("Dialog must be cleared after abort", vm.uiState.value.activeDialog)

        // Same for SendNow and Cancel
        liveUser = "admin-A"
        liveGen = 12L
        vm.reconcileSession()
        vm.openSendNowDialog(campA)
        val sendDialogA = vm.uiState.value.activeDialog as? NotificationActionDialog.SendNow
        assertNotNull(sendDialogA)

        liveUser = "admin-B"
        vm.executeSendNow(
            campaign = sendDialogA!!.campaign,
            reason = "Send",
            requestId = sendDialogA.requestId,
            dialogOwner = sendDialogA.ownerUid,
        )
        assertEquals("Must abort sendNow and make 0 mutations", 0, repo.sendNowCallCount)
        assertNull("Dialog must be cleared after abort", vm.uiState.value.activeDialog)

        liveUser = "admin-A"
        liveGen = 13L
        vm.reconcileSession()
        vm.openCancelDialog(campA)
        val cancelDialogA = vm.uiState.value.activeDialog as? NotificationActionDialog.Cancel
        assertNotNull(cancelDialogA)

        liveUser = "admin-B"
        vm.executeCancel(
            campaign = cancelDialogA!!.campaign,
            reason = "Cancel reason",
            requestId = cancelDialogA.requestId,
            dialogOwner = cancelDialogA.ownerUid,
        )
        assertEquals("Must abort cancel and make 0 mutations", 0, repo.cancelCallCount)
        assertNull("Dialog must be cleared after abort", vm.uiState.value.activeDialog)
    }

    @Test
    fun testLiveAuthValidationOnEveryCompletion() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)

        var liveUser: String? = "admin-A"
        var liveGen = 10L

        val vm = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { liveUser },
            sessionGenProvider = { liveGen },
            clock = { fixedClockInstant },
        )

        val slowCreate = CompletableDeferred<AdminNotificationCampaignMutationResponse>()
        repo.createDeferred = slowCreate

        vm.onTitleViChange("Tiêu đề của A")
        vm.onTitleEnChange("Title A")
        vm.onBodyViChange("Nội dung")
        vm.onBodyEnChange("Body")
        vm.saveDraft()

        assertTrue(vm.uiState.value.isActionInProgress)

        // Live auth changes to Admin B right before repository returns
        liveUser = "admin-B"
        liveGen = 11L

        slowCreate.complete(
            AdminNotificationCampaignMutationResponse(
                success = true,
                campaign = createDummyCampaign(id = "camp-from-A", titleVi = "Tiêu đề của A")
            )
        )

        assertNotEquals("camp-from-A", vm.uiState.value.editingCampaignId)
    }

    // ---------------------------------------------------------------- 2. Recreation & Unresolved Intent Persistence BEFORE Server Reply
    @Test
    fun testExactConfirmedReasonIsPersistedBeforeServerReply() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)
        val gate = CompletableDeferred<Unit>()
        val handle = SavedStateHandle()

        val original = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-a" },
            savedStateHandle = handle,
            clock = { fixedClockInstant },
        )

        val camp = createDummyCampaign()
        original.openSendNowDialog(camp)
        repo.sendGate = gate

        original.executeSendNow(camp, "Exact confirmed reason before crash")
        assertEquals(1, repo.sentIds.size)

        // Process recreation occurs while RPC is still held at gate (no catch block has run)
        val restored = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-a" },
            savedStateHandle = handle,
            clock = { fixedClockInstant },
        )

        val retry = restored.uiState.value.activeDialog as? NotificationActionDialog.SendNow
        assertNotNull("Submitted ambiguous action must be retained before receiving any response", retry)
        assertEquals("Exact confirmed reason before crash", retry!!.reason)
        assertEquals(repo.sentIds.single(), retry.requestId)

        gate.complete(Unit)
    }

    @Test
    fun testScheduleAndCancelExactConfirmedReasonPersistedBeforeServerReply() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)
        val handle = SavedStateHandle()

        val vm = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-a" },
            savedStateHandle = handle,
            clock = { fixedClockInstant },
        )

        val camp = createDummyCampaign()
        val gateSched = CompletableDeferred<Unit>()
        repo.scheduleGate = gateSched

        vm.openScheduleDialog(camp)
        val schedDialog = vm.uiState.value.activeDialog as? NotificationActionDialog.Schedule
        assertNotNull(schedDialog)

        vm.executeSchedule(camp, "2026-11-05", "10:00", "Confirmed schedule reason before crash", schedDialog!!.requestId, schedDialog.ownerUid)
        assertEquals(1, repo.sentIds.size)

        // Process recreation while schedule RPC is held in flight
        val restored = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-a" },
            savedStateHandle = handle,
            clock = { fixedClockInstant },
        )

        val retrySched = restored.uiState.value.activeDialog as? NotificationActionDialog.Schedule
        assertNotNull("Schedule intent must be retained before response", retrySched)
        assertEquals("Confirmed schedule reason before crash", retrySched!!.reason)
        assertEquals(repo.sentIds.first(), retrySched.requestId)
        assertEquals("2026-11-05", retrySched.scheduledDate)
        assertEquals("10:00", retrySched.scheduledTime)

        gateSched.complete(Unit)
    }

    // ---------------------------------------------------------------- 3. Selected Users & Draft Payload Restoration
    @Test
    fun testDraftSaveRecreationPreservesSelectedAudienceAndUserIds() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)
        val handle = SavedStateHandle()

        val vm1 = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-draft-users" },
            savedStateHandle = handle,
            clock = { fixedClockInstant },
        )

        vm1.onTitleViChange("Tiêu đề chọn người")
        vm1.onTitleEnChange("Selected Audience Title")
        vm1.onBodyViChange("Nội dung tin nhắn")
        vm1.onBodyEnChange("Body content")
        vm1.onAudienceTypeChange("selected_users")
        vm1.onReasonChange("Ghi chú chọn lọc")

        val u1 = AdminMemberItem(id = "user-1", email = "u1@test.local", displayName = "User One", role = "member")
        val u2 = AdminMemberItem(id = "user-2", email = "u2@test.local", displayName = "User Two", role = "member")
        vm1.addSelectedUser(u1)
        vm1.addSelectedUser(u2)

        val reqIdBefore = vm1.uiState.value.draftMutationRequestId
        assertTrue(reqIdBefore.isNotBlank())
        assertEquals(2, vm1.uiState.value.selectedUsers.size)

        // Process recreation
        val vm2 = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-draft-users" },
            savedStateHandle = handle,
            clock = { fixedClockInstant },
        )

        val restored = vm2.uiState.value
        assertEquals("Tiêu đề chọn người", restored.draftTitleVi)
        assertEquals("Selected Audience Title", restored.draftTitleEn)
        assertEquals("Nội dung tin nhắn", restored.draftBodyVi)
        assertEquals("Body content", restored.draftBodyEn)
        assertEquals("selected_users", restored.draftAudienceType)
        assertEquals("Ghi chú chọn lọc", restored.draftReason)
        assertEquals(reqIdBefore, restored.draftMutationRequestId)

        // Verify selected users IDs are preserved
        assertEquals(2, restored.selectedUsers.size)
        assertTrue(restored.selectedUsers.any { it.id == "user-1" })
        assertTrue(restored.selectedUsers.any { it.id == "user-2" })
    }

    // ---------------------------------------------------------------- 4. Editing Draft While Save in Flight
    @Test
    fun testEditingDraftWhileSaveInFlightDoesNotEraseNewUserEdits() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)
        val vm = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-inflight" },
            clock = { fixedClockInstant },
        )

        vm.onTitleViChange("Tiêu đề lần 1")
        vm.onTitleEnChange("Title 1")
        vm.onBodyViChange("Nội dung 1")
        vm.onBodyEnChange("Body 1")

        val deferred = CompletableDeferred<AdminNotificationCampaignMutationResponse>()
        repo.createDeferred = deferred
        vm.saveDraft()

        assertTrue(vm.uiState.value.isActionInProgress)

        // User keeps typing while in flight
        vm.onTitleViChange("Tiêu đề lần 1 (đã gõ thêm)")
        vm.onBodyViChange("Nội dung 1 (bổ sung chi tiết)")

        deferred.complete(
            AdminNotificationCampaignMutationResponse(
                success = true,
                campaign = createDummyCampaign(id = "new-camp-id", revision = 1, titleVi = "Tiêu đề lần 1")
            )
        )

        val state = vm.uiState.value
        assertEquals("new-camp-id", state.editingCampaignId)
        assertEquals(1, state.editingRevision)
        assertEquals("Tiêu đề lần 1 (đã gõ thêm)", state.draftTitleVi)
        assertEquals("Nội dung 1 (bổ sung chi tiết)", state.draftBodyVi)
        assertFalse(state.isActionInProgress)
    }

    // ---------------------------------------------------------------- 5. CAS Conflict Draft Retention
    @Test
    fun testCasConflictPreservesUserDraftAndExplainsConflict() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)
        val vm = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-test-uid" },
            clock = { fixedClockInstant },
        )

        val camp = createDummyCampaign(revision = 1)
        vm.editDraft(camp)

        vm.onTitleViChange("Bản sửa của Quản trị viên A")
        vm.onBodyViChange("Nội dung mới của A")

        repo.shouldThrowCasConflict = true
        vm.saveDraft()

        val state = vm.uiState.value
        assertNotNull("Error state must be set", state.error)
        assertTrue(state.error!!.contains("preserved", ignoreCase = true) || state.error!!.contains("re-verify", ignoreCase = true))

        assertEquals("Bản sửa của Quản trị viên A", state.draftTitleVi)
        assertEquals("Nội dung mới của A", state.draftBodyVi)
        assertFalse(state.isDraftSaving)
    }

    // ---------------------------------------------------------------- 6. Invalidation: closeDetail & Pagination Flags
    @Test
    fun testCloseDetailInvalidatesPendingDetailAndDoesNotReopenDialog() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)
        val vm = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-detail" },
            clock = { fixedClockInstant },
        )

        val slowDetail = CompletableDeferred<AdminNotificationCampaignDetailResponse>()
        repo.slowDetailDeferred = slowDetail

        vm.openDetail("camp-slow-1")
        assertTrue(vm.uiState.value.isDetailLoading)

        vm.closeDetail()
        assertFalse("isDetailLoading should be false after close", vm.uiState.value.isDetailLoading)
        assertNull("Selected detail must be null", vm.uiState.value.selectedDetailCampaign)

        slowDetail.complete(
            AdminNotificationCampaignDetailResponse(
                campaign = createDummyCampaign(id = "camp-slow-1")
            )
        )

        assertNull("Dialog must not reopen from stale detail response", vm.uiState.value.selectedDetailCampaign)
    }

    @Test
    fun testLoadHistoryRefreshResetsPaginationFlags() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)
        val vm = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-refresh" },
            clock = { fixedClockInstant },
        )

        // 1. Initial load has next cursor
        vm.loadHistory()
        assertTrue(vm.uiState.value.historyHasMore)
        assertEquals("cursor-1", vm.uiState.value.historyNextCursor)

        // 2. Failed load more releases loadingMore flag
        repo.shouldThrowNetworkError = true
        vm.loadMoreHistory()
        assertFalse("isHistoryLoadingMore must be false after load more error", vm.uiState.value.isHistoryLoadingMore)

        // 3. Fresh refresh in flight must reset pagination flags and cursor
        repo.shouldThrowNetworkError = false
        val slowRefresh = CompletableDeferred<AdminListNotificationCampaignsEnvelope>()
        repo.slowListDeferred = slowRefresh

        vm.loadHistory()
        assertTrue("isHistoryLoading must be true during refresh", vm.uiState.value.isHistoryLoading)
        assertFalse("isHistoryLoadingMore must be reset on refresh start", vm.uiState.value.isHistoryLoadingMore)
        assertFalse("historyHasMore must be reset on refresh start", vm.uiState.value.historyHasMore)
        assertNull("historyNextCursor must be reset to null on refresh start", vm.uiState.value.historyNextCursor)

        slowRefresh.complete(AdminListNotificationCampaignsEnvelope(items = listOf(), hasMore = false, nextCursor = null))
        assertFalse("isHistoryLoading should be false after completion", vm.uiState.value.isHistoryLoading)
        assertNull("historyNextCursor remains null after empty completion", vm.uiState.value.historyNextCursor)
    }

    // ---------------------------------------------------------------- 7. DateTime Conversion with Injected Clock
    @Test
    fun testDateTimeConversionToUtcAndPastScheduleRejectionWithClock() = runTest {
        val client = createTestClient()
        val repo = TestableRepository(client)
        val vm = AdminNotificationCampaignViewModel(
            repository = repo,
            currentUserIdProvider = { "admin-tz" },
            clock = { fixedClockInstant }, // Fixed at 2026-11-01T00:00:00Z
        )

        val camp = createDummyCampaign(id = "camp-tz-1")

        // 1. Past date (2020-01-01 is before fixedClockInstant) must be rejected
        vm.executeSchedule(
            campaign = camp,
            dateStr = "2020-01-01",
            timeStr = "12:00",
            reason = "Quá khứ"
        )

        assertNotNull("Error must be set for past time", vm.uiState.value.error)
        val dialog = vm.uiState.value.activeDialog as? NotificationActionDialog.Schedule
        assertNotNull("Dialog must be retained on past time error", dialog)

        // 2. Future date relative to fixed clock (2026-11-05 14:30 in Vietnam UTC+7 is 2026-11-05 07:30:00Z)
        vm.executeSchedule(
            campaign = camp,
            dateStr = "2026-11-05",
            timeStr = "14:30",
            reason = "Tương lai",
            requestId = dialog!!.requestId,
            dialogOwner = dialog.ownerUid,
        )

        assertEquals(1, repo.scheduleCallCount)
        assertEquals("2026-11-05T07:30:00Z", repo.lastScheduledForPassed)
    }
}
