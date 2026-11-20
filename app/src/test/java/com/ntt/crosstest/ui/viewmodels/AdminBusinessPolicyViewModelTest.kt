package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import com.ntt.crosstest.data.AdminScheduleBusinessPolicyParams
import com.ntt.crosstest.data.AuthRepository
import com.ntt.crosstest.data.BusinessPolicy
import com.ntt.crosstest.data.BusinessPolicyConfigResponse
import com.ntt.crosstest.data.BusinessPolicyHistoryEnvelope
import com.ntt.crosstest.data.BusinessPolicyHistoryItem
import com.ntt.crosstest.data.BusinessPolicyRepository
import com.ntt.crosstest.data.DailyPenaltyTiers
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class AdminBusinessPolicyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dummySupabase: SupabaseClient
    private lateinit var localizer: Localizer
    private lateinit var authRepository: AuthRepository

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        Dispatchers.setMain(testDispatcher)
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        dummySupabase = createSupabaseClient("https://fixture.invalid", "dummy") {
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine { respond("{}") }
        }
        val testUid = "00000000-0000-0000-0000-000000000001"
        dummySupabase.auth.awaitInitialization()
        dummySupabase.auth.importSession(
            kotlinx.serialization.json.Json.decodeFromString<UserSession>(
                """{"access_token":"token-$testUid","refresh_token":"ref-$testUid","token_type":"bearer","expires_in":3600,"user":{"id":"$testUid","aud":"authenticated"}}"""
            ),
            autoRefresh = false
        )
        localizer = Localizer(context)
        authRepository = AuthRepository(dummySupabase, context)
    }

    @After
    fun tearDown() {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        kotlinx.coroutines.runBlocking { dummySupabase.close() }
        Dispatchers.resetMain()
    }

    private class FakeBusinessPolicyRepository : BusinessPolicyRepository(
        createSupabaseClient("https://fixture.invalid", "dummy") {
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine { respond("{}") }
        }
    ) {
        var currentRevision = "rev-initial-1"
        var scheduleCallCount = 0
        var cancelCallCount = 0
        var shouldThrowConflict = false
        var lastScheduleParams: AdminScheduleBusinessPolicyParams? = null

        var activePolicy = BusinessPolicy(
            id = 1,
            welcomeCredits = 100,
            pushFee = 100,
            operatorNote = "Baseline",
            isBaseline = true,
        )
        var futurePolicies = mutableListOf<BusinessPolicy>()
        var historyItems = mutableListOf<BusinessPolicyHistoryItem>()

        override suspend fun getBusinessPolicyConfig(futureLimit: Int): BusinessPolicyConfigResponse {
            return BusinessPolicyConfigResponse(
                today = "2026-11-01",
                timezone = "Asia/Ho_Chi_Minh",
                revision = currentRevision,
                activePolicy = activePolicy,
                futurePolicies = futurePolicies.toList(),
                futureHasMore = false,
                futureNextCursor = null,
            )
        }

        override suspend fun scheduleBusinessPolicy(params: AdminScheduleBusinessPolicyParams): BusinessPolicyConfigResponse {
            scheduleCallCount++
            lastScheduleParams = params
            if (shouldThrowConflict) {
                currentRevision = "rev-conflict-2"
                throw Exception("BUSINESS_POLICY_CONFLICT: Revision mismatch")
            }
            currentRevision = "rev-scheduled-success"
            val scheduled = BusinessPolicy(
                id = 99,
                effectiveFrom = params.effectiveFrom,
                welcomeCredits = params.welcomeCredits,
                pushFee = params.pushFee,
                operatorNote = params.operatorNote,
            )
            futurePolicies.add(scheduled)
            return getBusinessPolicyConfig()
        }

        override suspend fun cancelBusinessPolicy(
            effectiveFrom: String,
            operatorNote: String,
            expectedRevision: String,
            requestId: String?,
        ): BusinessPolicyConfigResponse {
            cancelCallCount++
            if (shouldThrowConflict) {
                currentRevision = "rev-cancel-conflict"
                throw Exception("BUSINESS_POLICY_CONFLICT: Revision mismatch")
            }
            futurePolicies.removeAll { it.effectiveFrom == effectiveFrom }
            currentRevision = "rev-cancel-success"
            return getBusinessPolicyConfig()
        }

        override suspend fun getBusinessPolicyHistory(
            limit: Int,
            cursorId: Long?,
        ): BusinessPolicyHistoryEnvelope {
            val filtered = if (cursorId == null) historyItems else historyItems.filter { it.id < cursorId }
            val page = filtered.take(limit)
            val hasMore = filtered.size > limit
            val nextCursor = if (hasMore) page.lastOrNull()?.id else null
            return BusinessPolicyHistoryEnvelope(
                items = page,
                nextCursor = nextCursor,
                hasMore = hasMore,
            )
        }
    }

    @Test
    fun init_loadsActiveConfigAndDefaults() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("rev-initial-1", state.currentRevision)
        assertEquals("2026-11-01", state.todayDate)
        assertEquals(100, state.activePolicy?.welcomeCredits)
        assertEquals("100", state.draftWelcomeCredits)
        assertEquals("100", state.draftPushFee)
    }

    @Test
    fun selectTab_updatesActiveTab() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.selectTab(BusinessPolicyAdminTab.EDITOR)
        assertEquals(BusinessPolicyAdminTab.EDITOR, viewModel.uiState.value.activeTab)

        viewModel.selectTab(BusinessPolicyAdminTab.SCHEDULED)
        assertEquals(BusinessPolicyAdminTab.SCHEDULED, viewModel.uiState.value.activeTab)
    }

    @Test
    fun derivedUpdateBudget_calculatesDynamically() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.updateDraftAppUpdateRewardPerClaim("8")
        viewModel.updateDraftAppUpdateMaxClaims("25")
        assertEquals(200, viewModel.uiState.value.derivedUpdateBudget)

        viewModel.updateDraftAppUpdateRewardPerClaim("10")
        viewModel.updateDraftAppUpdateMaxClaims("30")
        assertEquals(300, viewModel.uiState.value.derivedUpdateBudget)
    }

    @Test
    fun schedulePolicy_validatesUnorderedPenaltyTiers() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.updateDraftEffectiveDate("2026-12-01")
        viewModel.updateDraftOperatorNote("Test schedule")
        // Invalid ordering: tier 1 > tier 2
        viewModel.updateDraftPenaltyTier1("15")
        viewModel.updateDraftPenaltyTier2("5")
        viewModel.updateDraftPenaltyTier3("20")
        viewModel.updateDraftPenaltyTier4("25")

        viewModel.schedulePolicy()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
        assertEquals(0, repo.scheduleCallCount)
    }

    @Test
    fun schedulePolicy_validatesPastOrTodayDate() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.updateDraftEffectiveDate("2026-11-01") // Today
        viewModel.updateDraftOperatorNote("Note")

        viewModel.schedulePolicy()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.error)
        assertEquals(0, repo.scheduleCallCount)
    }

    @Test
    fun schedulePolicy_conflictRetainsDraftWhileReloadingLatestRevision() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.updateDraftEffectiveDate("2026-12-01")
        viewModel.updateDraftWelcomeCredits("250")
        viewModel.updateDraftPushFee("180")
        viewModel.updateDraftOperatorNote("Draft changes")

        repo.shouldThrowConflict = true
        viewModel.schedulePolicy()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // Error message set
        assertNotNull(state.error)
        // User's draft values must NOT be lost
        assertEquals("2026-12-01", state.draftEffectiveDate)
        assertEquals("250", state.draftWelcomeCredits)
        assertEquals("180", state.draftPushFee)
        assertEquals("Draft changes", state.draftOperatorNote)
        // Revision was reloaded to the new conflict revision
        assertEquals("rev-conflict-2", state.currentRevision)

        // Retry with latest revision succeeds
        repo.shouldThrowConflict = false
        viewModel.schedulePolicy()
        advanceUntilIdle()

        val successState = viewModel.uiState.value
        assertNull(successState.error)
        assertNotNull(successState.successMessage)
        assertEquals(BusinessPolicyAdminTab.SCHEDULED, successState.activeTab)
        assertEquals("rev-scheduled-success", successState.currentRevision)
    }

    @Test
    fun cancelPolicy_flowExecutesAndClearsDialog() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val scheduled = BusinessPolicy(id = 10, effectiveFrom = "2026-12-15", operatorNote = "To cancel")
        repo.futurePolicies.add(scheduled)

        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.futurePolicies.size)

        viewModel.openCancelDialog(scheduled)
        assertNotNull(viewModel.uiState.value.cancelDialogTarget)

        viewModel.updateCancelReason("Admin cancellation reason")
        viewModel.confirmCancelPolicy()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.cancelDialogTarget)
        assertNotNull(state.successMessage)
        assertEquals(0, state.futurePolicies.size)
        assertEquals(1, repo.cancelCallCount)
    }

    @Test
    fun historyPagination_loadsItemsAndAppendsOnLoadMore() = runTest {
        val repo = FakeBusinessPolicyRepository()
        for (i in 1..25) {
            repo.historyItems.add(
                BusinessPolicyHistoryItem(
                    id = i.toLong(),
                    policyId = 1,
                    operation = "UPDATE",
                    operatorNote = "Change #$i",
                    changedAt = "2026-11-01T00:00:00Z"
                )
            )
        }
        repo.historyItems.sortByDescending { it.id }

        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.selectTab(BusinessPolicyAdminTab.HISTORY)
        advanceUntilIdle()

        val state1 = viewModel.uiState.value
        assertEquals(20, state1.historyItems.size)
        assertTrue(state1.historyHasMore)

        viewModel.loadMoreHistory()
        advanceUntilIdle()

        val state2 = viewModel.uiState.value
        assertEquals(25, state2.historyItems.size)
        assertFalse(state2.historyHasMore)
    }

    @Test
    fun cancelPolicy_conflictRetainsDialogAndUuidForExplicitRetry() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val scheduled = BusinessPolicy(id = 15, effectiveFrom = "2026-12-20", operatorNote = "Will conflict")
        repo.futurePolicies.add(scheduled)

        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.openCancelDialog(scheduled)
        val initialReqId = viewModel.uiState.value.cancelRequestId
        assertNotNull(initialReqId)

        viewModel.updateCancelReason("Reason for cancel")
        repo.shouldThrowConflict = true
        viewModel.confirmCancelPolicy()
        advanceUntilIdle()

        val conflictState = viewModel.uiState.value
        // Error shown
        assertNotNull(conflictState.error)
        // Dialog target and reason are RETAINED, NOT dropped!
        assertNotNull(conflictState.cancelDialogTarget)
        assertEquals("Reason for cancel", conflictState.cancelReason)
        assertEquals(initialReqId, conflictState.cancelRequestId)
        // Revision updated to new conflict revision
        assertEquals("rev-cancel-conflict", conflictState.currentRevision)

        // Retry succeeds with the SAME intent and dialog is closed
        repo.shouldThrowConflict = false
        viewModel.confirmCancelPolicy()
        advanceUntilIdle()

        val successState = viewModel.uiState.value
        assertNull(successState.cancelDialogTarget)
        assertNotNull(successState.successMessage)
    }

    @Test
    fun schedulePolicy_synchronousActionGate_blocksDoubleInvoke() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.updateDraftEffectiveDate("2026-12-05")
        viewModel.updateDraftOperatorNote("Single launch check")

        // First invoke initiates schedule
        viewModel.schedulePolicy()
        // Second invoke immediately while action is in progress is blocked by synchronous gate
        viewModel.schedulePolicy()
        advanceUntilIdle()

        assertEquals(1, repo.scheduleCallCount)
    }

    @Test
    fun schedulePolicy_editingInputs_rotatesDraftRequestId() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.updateDraftEffectiveDate("2026-12-05")
        viewModel.updateDraftOperatorNote("Initial note")
        val reqId1 = viewModel.uiState.value.draftMutationRequestId

        // 1. First submit with simulated conflict
        repo.shouldThrowConflict = true
        viewModel.schedulePolicy()
        advanceUntilIdle()

        assertEquals(reqId1, repo.lastScheduleParams?.requestId)
        assertEquals("Initial note", repo.lastScheduleParams?.operatorNote)

        // 2. Unresolved retry without editing inputs retains same UUID
        viewModel.schedulePolicy()
        advanceUntilIdle()
        assertEquals(reqId1, repo.lastScheduleParams?.requestId)

        // 3. User edits note -> intent changed -> fresh UUID rotated
        viewModel.updateDraftOperatorNote("Updated note with new requirements")
        val reqId2 = viewModel.uiState.value.draftMutationRequestId
        assertNotEquals(reqId1, reqId2)
    }

    @Test
    fun savedStateHandle_restoresPrimitiveDraftAcrossRecreation() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val savedState = androidx.lifecycle.SavedStateHandle()

        val vm1 = AdminBusinessPolicyViewModel(repo, authRepository, localizer, savedState)
        advanceUntilIdle()

        vm1.updateDraftEffectiveDate("2026-12-10")
        vm1.updateDraftWelcomeCredits("300")
        vm1.updateDraftPushFee("250")
        vm1.updateDraftOperatorNote("Persisted note across config changes")

        // Simulate ViewModel recreation with same SavedStateHandle
        val vm2 = AdminBusinessPolicyViewModel(repo, authRepository, localizer, savedState)
        advanceUntilIdle()

        val state = vm2.uiState.value
        assertEquals("2026-12-10", state.draftEffectiveDate)
        assertEquals("300", state.draftWelcomeCredits)
        assertEquals("250", state.draftPushFee)
        assertEquals("Persisted note across config changes", state.draftOperatorNote)
    }

    @Test
    fun scheduleAndCancelPolicy_refreshesHistoryWhenAlreadyLoaded() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        // Pre-load history tab
        repo.historyItems.add(
            BusinessPolicyHistoryItem(
                id = 1,
                policyId = 10,
                operation = "INSERT",
                operatorNote = "Initial history item",
                changedAt = "2026-11-01T10:00:00Z",
            )
        )
        viewModel.selectTab(BusinessPolicyAdminTab.HISTORY)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.historyItems.size)

        // Schedule new policy while history was loaded -> history should auto-refresh
        repo.historyItems.add(
            0,
            BusinessPolicyHistoryItem(
                id = 2,
                policyId = 99,
                operation = "INSERT",
                operatorNote = "Scheduled item",
                changedAt = "2026-11-01T10:05:00Z",
            )
        )
        viewModel.updateDraftEffectiveDate("2026-12-05")
        viewModel.updateDraftOperatorNote("Scheduled item")
        viewModel.schedulePolicy()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.historyItems.size)
        assertEquals("Scheduled item", viewModel.uiState.value.historyItems.first().operatorNote)
    }

    @Test
    fun schedulePolicy_dispatchesNewCampaignPostingFields() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.updateDraftEffectiveDate("2026-12-12")
        viewModel.updateDraftMinCreditsToPost("25")
        viewModel.updateDraftSlotFee1("15")
        viewModel.updateDraftTarget15Fee("12")
        viewModel.updateDraftTarget20Fee("30")
        viewModel.updateDraftOperatorNote("Configure publishing policy")

        viewModel.schedulePolicy()
        advanceUntilIdle()

        assertEquals(1, repo.scheduleCallCount)
        val dispatched = repo.lastScheduleParams
        assertNotNull(dispatched)
        assertEquals(25, dispatched!!.minCreditsToPostApp)
        assertEquals(15, dispatched.slotFee1)
        assertEquals(12, dispatched.target15Fee)
        assertEquals(30, dispatched.target20Fee)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun schedulePolicy_validatesTarget15ExceedingTarget20() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val viewModel = AdminBusinessPolicyViewModel(repo, authRepository, localizer)
        advanceUntilIdle()

        viewModel.updateDraftEffectiveDate("2026-12-12")
        viewModel.updateDraftTarget15Fee("35")
        viewModel.updateDraftTarget20Fee("20")
        viewModel.updateDraftOperatorNote("Invalid target fee order")

        viewModel.schedulePolicy()
        advanceUntilIdle()

        assertEquals(0, repo.scheduleCallCount)
        assertEquals(
            localizer.get(com.ntt.crosstest.R.string.admin_policy_target_order_error),
            viewModel.uiState.value.error
        )
    }

    @Test
    fun savedStateHandleAndPopulateDraft_preservePublishingControls() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val savedState = androidx.lifecycle.SavedStateHandle()

        val vm1 = AdminBusinessPolicyViewModel(repo, authRepository, localizer, savedState)
        advanceUntilIdle()

        val sourcePolicy = BusinessPolicy(
            id = 42,
            effectiveFrom = "2026-12-20",
            welcomeCredits = 120,
            minCreditsToPostApp = 30,
            slotFees = com.ntt.crosstest.data.SlotFees(slot1 = 18, slot2 = 60, slot3 = 120, slot4Plus = 240, renewalDays = 30),
            targetFees = com.ntt.crosstest.data.TargetFees(target12 = 0, target15 = 15, target20 = 35),
            operatorNote = "Holiday publishing rules",
        )
        vm1.populateDraftFromPolicy(sourcePolicy, "2026-12-20")

        val vm2 = AdminBusinessPolicyViewModel(repo, authRepository, localizer, savedState)
        advanceUntilIdle()

        val restored = vm2.uiState.value
        assertEquals("2026-12-20", restored.draftEffectiveDate)
        assertEquals("30", restored.draftMinCreditsToPostApp)
        assertEquals("18", restored.draftSlotFee1)
        assertEquals("15", restored.draftTarget15Fee)
        assertEquals("35", restored.draftTarget20Fee)
        assertEquals("Holiday publishing rules", restored.draftOperatorNote)
    }

    @Test
    fun schedulePolicy_clearsDraftStateAndDraftDirtyOnSuccess() = runTest {
        val repo = FakeBusinessPolicyRepository()
        val savedState = androidx.lifecycle.SavedStateHandle()
        val vm = AdminBusinessPolicyViewModel(repo, authRepository, localizer, savedState)
        advanceUntilIdle()

        vm.updateDraftEffectiveDate("2026-12-25")
        vm.updateDraftOperatorNote("Test clearing draft")
        vm.updateDraftMinCreditsToPost("40")
        vm.updateDraftSlotFee1("25")

        assertTrue(vm.hasSavedDraft())

        vm.schedulePolicy()
        advanceUntilIdle()

        // After success: hasSavedDraft must be false
        assertFalse(vm.hasSavedDraft())

        // Recreating ViewModel with the same SavedStateHandle should not restore the cleared draft
        val vm2 = AdminBusinessPolicyViewModel(repo, authRepository, localizer, savedState)
        advanceUntilIdle()
        assertFalse(vm2.hasSavedDraft())
        assertEquals("", vm2.uiState.value.draftOperatorNote)
    }
}
