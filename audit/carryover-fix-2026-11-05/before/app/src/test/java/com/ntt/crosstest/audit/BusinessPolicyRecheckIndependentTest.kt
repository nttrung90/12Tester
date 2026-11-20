package com.ntt.crosstest.audit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ntt.crosstest.data.*
import com.ntt.crosstest.ui.viewmodels.AdminBusinessPolicyViewModel
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BusinessPolicyRecheckIndependentTest {
    private val dispatcher = StandardTestDispatcher()
    private val adminA = "11111111-1111-1111-1111-111111111111"
    private val adminB = "22222222-2222-2222-2222-222222222222"
    private val future = BusinessPolicy(id = 1, effectiveFrom = "2030-01-02", operatorNote = "Original policy")

    private class RecordingRepository(val client: SupabaseClient) : BusinessPolicyRepository(client) {
        var revision = "rev-1"
        var today = "2030-01-01"
        var loseFirstScheduleReply = false
        var holdFirstScheduleReply: CompletableDeferred<Unit>? = null
        var loseFirstCancelReply = false
        val schedules = mutableListOf<Pair<String?, AdminScheduleBusinessPolicyParams>>()
        val cancellations = mutableListOf<Triple<String?, String, String?>>()
        val cancelReasons = mutableListOf<String>()
        val cancelDates = mutableListOf<String>()
        private var committed: AdminScheduleBusinessPolicyParams? = null
        private val committedCancellations = mutableMapOf<String?, Triple<String, String, String?>>()
        override suspend fun getBusinessPolicyConfig(futureLimit: Int) = BusinessPolicyConfigResponse(
            today = today, revision = revision, activePolicy = BusinessPolicy(),
            futurePolicies = listOf(BusinessPolicy(id = 1, effectiveFrom = "2030-01-02")),
        )
        override suspend fun scheduleBusinessPolicy(params: AdminScheduleBusinessPolicyParams): BusinessPolicyConfigResponse {
            schedules += client.auth.currentUserOrNull()?.id to params
            val receipt = committed
            if (receipt != null && receipt.requestId == params.requestId) {
                if (receipt != params.copy(expectedRevision = receipt.expectedRevision)) throw Exception("REQUEST_ID_CONFLICT")
                return getBusinessPolicyConfig(20)
            }
            if (params.expectedRevision != revision) throw Exception("BUSINESS_POLICY_CONFLICT")
            committed = params
            revision = "rev-2"
            val gate = holdFirstScheduleReply
            holdFirstScheduleReply = null
            gate?.await()
            if (loseFirstScheduleReply) {
                loseFirstScheduleReply = false
                throw IOException("Committed response lost")
            }
            return getBusinessPolicyConfig(20)
        }
        override suspend fun cancelBusinessPolicy(effectiveFrom: String, operatorNote: String, expectedRevision: String, requestId: String?): BusinessPolicyConfigResponse {
            cancellations += Triple(client.auth.currentUserOrNull()?.id, expectedRevision, requestId)
            cancelReasons += operatorNote
            cancelDates += effectiveFrom
            val receipt = committedCancellations[requestId]
            if (receipt != null && receipt.third == requestId) {
                if (receipt.first != effectiveFrom || receipt.second != operatorNote) throw Exception("REQUEST_ID_CONFLICT")
                return getBusinessPolicyConfig(20)
            }
            if (expectedRevision != revision) throw Exception("BUSINESS_POLICY_CONFLICT")
            committedCancellations[requestId] = Triple(effectiveFrom, operatorNote, requestId)
            revision = "rev-cancelled"
            if (loseFirstCancelReply) {
                loseFirstCancelReply = false
                throw IOException("Committed cancellation response lost")
            }
            return getBusinessPolicyConfig(20)
        }
    }

    private suspend fun login(client: SupabaseClient, uid: String) {
        client.auth.awaitInitialization()
        client.auth.importSession(Json.decodeFromString<UserSession>(
            """{"access_token":"token-$uid","refresh_token":"ref-$uid","token_type":"bearer","expires_in":3600,"user":{"id":"$uid","aud":"authenticated"}}"""
        ), autoRefresh = false)
    }

    private suspend fun fixture(block: suspend (SupabaseClient, RecordingRepository, AuthRepository, Localizer, ViewModelStore) -> Unit) {
        Dispatchers.setMain(dispatcher)
        val app: Application = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(app)
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                autoLoadFromStorage = false
                autoSaveToStorage = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
            httpEngine = MockEngine { respond("{}") }
        }
        val store = ViewModelStore()
        try {
            login(client, adminA)
            block(client, RecordingRepository(client), AuthRepository(client, app), Localizer(app), store)
        } finally {
            store.clear()
            client.close()
            SessionCacheManager.clear(app)
            Dispatchers.resetMain()
        }
    }

    @Test fun queuedScheduleCannotExecuteUsingAnotherAdminsSession() = runBlocking {
        fixture { client, repo, auth, localizer, store ->
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.updateDraftEffectiveDate("2030-01-02")
            vm.updateDraftOperatorNote("Confirmed by A")
            vm.schedulePolicy()
            login(client, adminB)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("The queued A intent must be aborted before calling the repository as B", 0, repo.schedules.size)
        }
    }

    @Test fun queuedCancellationCannotExecuteUsingAnotherAdminsSession() = runBlocking {
        fixture { client, repo, auth, localizer, store ->
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.openCancelDialog(future)
            vm.updateCancelReason("Confirmed by A")
            vm.confirmCancelPolicy()
            login(client, adminB)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("The queued A cancellation must not use B credentials", 0, repo.cancellations.size)
        }
    }

    @Test fun explicitCancellationRetryAfterConflictMustUseFreshRevision() = runBlocking {
        fixture { _, repo, auth, localizer, store ->
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.openCancelDialog(future)
            vm.updateCancelReason("Cancel this future policy")
            repo.revision = "rev-external"
            vm.confirmCancelPolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, repo.cancellations.size)
            assertEquals("rev-external", vm.uiState.value.currentRevision)
            assertNotNull(vm.uiState.value.cancelDialogTarget)
            vm.confirmCancelPolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("Explicit retry must acknowledge the refreshed revision", "rev-external", repo.cancellations.last().second)
            assertNull(vm.uiState.value.cancelDialogTarget)
        }
    }

    @Test fun recreationAndDraftEditCannotReplaceUnresolvedConfirmedIntent() = runBlocking {
        fixture { _, repo, auth, localizer, store ->
            val saved = SavedStateHandle()
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer, saved)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.updateDraftEffectiveDate("2030-01-02")
            vm.updateDraftOperatorNote("Original confirmed note")
            repo.loseFirstScheduleReply = true
            vm.schedulePolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, repo.schedules.size)
            val original = repo.schedules.single().second
            assertNotNull(vm.uiState.value.error)
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            store.clear()
            val recreated = AdminBusinessPolicyViewModel(repo, auth, localizer, restored)
            store.put("recreated", recreated)
            dispatcher.scheduler.advanceUntilIdle()
            recreated.updateDraftOperatorNote("A different draft after recreation")
            recreated.schedulePolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse("Editing a restored draft must not replace the unresolved confirmed intent with a new request",
                repo.schedules.drop(1).any { it.second.requestId != original.requestId || it.second.operatorNote != original.operatorNote })
        }
    }

    @Test fun retryAfterRecreationMustReuseOriginalCommittedRequest() = runBlocking {
        fixture { _, repo, auth, localizer, store ->
            val saved = SavedStateHandle()
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer, saved)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.updateDraftEffectiveDate("2030-01-02")
            vm.updateDraftOperatorNote("Original confirmed note")
            repo.loseFirstScheduleReply = true
            vm.schedulePolicy()
            dispatcher.scheduler.advanceUntilIdle()
            val original = repo.schedules.single().second
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            store.clear()
            val recreated = AdminBusinessPolicyViewModel(repo, auth, localizer, restored)
            store.put("recreated", recreated)
            dispatcher.scheduler.advanceUntilIdle()
            recreated.schedulePolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("Explicit retry must reconcile the original operation", 2, repo.schedules.size)
            assertEquals("Process recreation must not create a second operation UUID", original.requestId, repo.schedules.last().second.requestId)
            assertEquals(original.operatorNote, repo.schedules.last().second.operatorNote)
        }
    }

    @Test fun editingReasonCannotChangeUnresolvedCancellationPayload() = runBlocking {
        fixture { _, repo, auth, localizer, store ->
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.openCancelDialog(future)
            vm.updateCancelReason("Original cancellation reason")
            repo.loseFirstCancelReply = true
            vm.confirmCancelPolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertNotNull(vm.uiState.value.error)
            vm.updateCancelReason("Edited after lost reply")
            vm.confirmCancelPolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertFalse("The unresolved confirmed reason must remain immutable until reconciled",
                repo.cancelReasons.drop(1).any { it != "Original cancellation reason" })
        }
    }

    @Test fun dismissingAmbiguousCancellationCannotDiscardItsRequestId() = runBlocking {
        fixture { _, repo, auth, localizer, store ->
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.openCancelDialog(future)
            vm.updateCancelReason("Original cancellation reason")
            repo.loseFirstCancelReply = true
            vm.confirmCancelPolicy()
            dispatcher.scheduler.advanceUntilIdle()
            val originalRequestId = repo.cancellations.single().third
            vm.dismissCancelDialog()
            vm.openCancelDialog(future)
            assertEquals("An unresolved cancellation must retain its original UUID", originalRequestId, vm.uiState.value.cancelRequestId)
            assertEquals("Original cancellation reason", vm.uiState.value.cancelReason)
            vm.confirmCancelPolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(2, repo.cancellations.size)
            assertEquals("Closing a dialog cannot discard a possibly committed request", originalRequestId, repo.cancellations.last().third)
            assertEquals("Original cancellation reason", repo.cancelReasons.last())
        }
    }

    @Test fun pendingCommittedScheduleCanBeReconciledAfterItsEffectiveDatePasses() = runBlocking {
        fixture { _, repo, auth, localizer, store ->
            val saved = SavedStateHandle()
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer, saved)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.updateDraftEffectiveDate("2030-01-02")
            vm.updateDraftOperatorNote("Confirmed before midnight")
            repo.loseFirstScheduleReply = true
            vm.schedulePolicy()
            dispatcher.scheduler.advanceUntilIdle()
            val original = repo.schedules.single().second
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            store.clear()
            repo.today = "2030-01-03"
            val recreated = AdminBusinessPolicyViewModel(repo, auth, localizer, restored)
            store.put("recreated", recreated)
            dispatcher.scheduler.advanceUntilIdle()
            recreated.schedulePolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("A receipt replay must not be blocked by fresh-operation date validation", 2, repo.schedules.size)
            assertEquals(original.requestId, repo.schedules.last().second.requestId)
        }
    }

    @Test fun processRecreationBeforeRpcReturnsMustRetainConfirmedRequest() = runBlocking {
        fixture { _, repo, auth, localizer, store ->
            val saved = SavedStateHandle()
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer, saved)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.updateDraftEffectiveDate("2030-01-02")
            vm.updateDraftOperatorNote("Confirmed before server response")
            repo.holdFirstScheduleReply = CompletableDeferred()
            vm.schedulePolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, repo.schedules.size)
            assertTrue(vm.uiState.value.isActionInProgress)
            val original = repo.schedules.single().second
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            store.clear()
            val recreated = AdminBusinessPolicyViewModel(repo, auth, localizer, restored)
            store.put("recreated", recreated)
            dispatcher.scheduler.advanceUntilIdle()
            recreated.schedulePolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(2, repo.schedules.size)
            assertEquals("Persist the request before RPC dispatch, not only in catch", original.requestId, repo.schedules.last().second.requestId)
        }
    }

    @Test fun anotherCancellationDialogCannotSendAnUndisplayedPendingTarget() = runBlocking {
        fixture { _, repo, auth, localizer, store ->
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.openCancelDialog(future)
            vm.updateCancelReason("Original cancellation reason")
            repo.loseFirstCancelReply = true
            vm.confirmCancelPolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, repo.cancellations.size)
            val originalRequestId = repo.cancellations.single().third
            vm.openCancelDialog(future.copy(id = 2, effectiveFrom = "2030-01-03"))
            val displayedDate = vm.uiState.value.cancelDialogTarget?.effectiveFrom
            if (displayedDate != null) {
                vm.confirmCancelPolicy()
                dispatcher.scheduler.advanceUntilIdle()
                assertFalse("The confirmed dialog must never submit another hidden policy date",
                    repo.cancelDates.drop(1).any { it != displayedDate })
            }
            vm.dismissCancelDialog()
            vm.openCancelDialog(future)
            vm.confirmCancelPolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("Opening another target must not discard the original unresolved cancellation",
                originalRequestId, repo.cancellations.last().third)
        }
    }

    @Test fun numericDraftEditsWhileRpcIsPendingMustRetainTheirDateAndReason() = runBlocking {
        fixture { _, repo, auth, localizer, store ->
            val vm = AdminBusinessPolicyViewModel(repo, auth, localizer)
            store.put("vm", vm)
            dispatcher.scheduler.advanceUntilIdle()
            vm.updateDraftEffectiveDate("2030-01-02")
            vm.updateDraftOperatorNote("Draft still being edited")
            val gate = CompletableDeferred<Unit>()
            repo.holdFirstScheduleReply = gate
            vm.schedulePolicy()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, repo.schedules.size)
            vm.updateDraftPushFee("250")
            gate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, repo.schedules.size)
            assertEquals("250", vm.uiState.value.draftPushFee)
            assertEquals("A numeric edit must retain the rest of the unsubmitted draft", "2030-01-02", vm.uiState.value.draftEffectiveDate)
            assertEquals("Draft still being edited", vm.uiState.value.draftOperatorNote)
        }
    }

    @Test fun staleQueuedActionCannotStrandFreshOwnersActionSpinner() = runBlocking {
        for (cancel in listOf(false, true)) {
            fixture { client, repo, auth, localizer, store ->
                val vm = AdminBusinessPolicyViewModel(repo, auth, localizer)
                store.put("vm", vm)
                dispatcher.scheduler.advanceUntilIdle()
                if (cancel) {
                    vm.openCancelDialog(future)
                    vm.updateCancelReason("Confirmed by A")
                    vm.confirmCancelPolicy()
                } else {
                    vm.updateDraftEffectiveDate("2030-01-02")
                    vm.updateDraftOperatorNote("Confirmed by A")
                    vm.schedulePolicy()
                }
                login(client, adminB)
                // Reconcile B synchronously before the old A coroutine starts.
                vm.loadConfig()
                dispatcher.scheduler.advanceUntilIdle()
                assertEquals(0, repo.schedules.size + repo.cancellations.size)
                assertFalse("Old queued ${if (cancel) "cancellation" else "schedule"} cannot mark B as busy", vm.uiState.value.isActionInProgress)
            }
        }
    }
}
