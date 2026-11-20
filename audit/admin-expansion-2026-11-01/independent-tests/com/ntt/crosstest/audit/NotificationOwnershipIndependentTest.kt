package com.ntt.crosstest.audit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.ntt.crosstest.data.*
import com.ntt.crosstest.ui.viewmodels.AdminNotificationCampaignViewModel
import com.ntt.crosstest.ui.viewmodels.NotificationActionDialog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotificationOwnershipIndependentTest {
    private fun campaign() = AdminNotificationCampaign(
        id = "11111111-1111-1111-1111-111111111111",
        titleVi = "Nội dung A", titleEn = "A content", bodyVi = "Tin nhắn A", bodyEn = "Message A",
        createdBy = "admin-a", createdAt = "2026-11-01T00:00:00Z", updatedAt = "2026-11-01T00:00:00Z",
    )
    private fun client() = createSupabaseClient("https://fixture.invalid", "dummy-key") {
        install(Postgrest)
        httpEngine = MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
    }
    private inner class Repository(client: SupabaseClient) : AdminNotificationCampaignRepository(client) {
        var pageGate: CompletableDeferred<AdminListNotificationCampaignsEnvelope>? = null
        val sentIds = mutableListOf<String>()
        var loseSendReply = false
        var sendGate: CompletableDeferred<Unit>? = null
        override suspend fun listCampaigns(status: String?, audienceType: String?, limit: Int, cursor: String?) =
            pageGate?.await() ?: AdminListNotificationCampaignsEnvelope()
        override suspend fun getSchedulerHealth() = AdminNotificationSchedulerHealth()
        override suspend fun getCampaignDetail(campaignId: String, eventsLimit: Int) =
            AdminNotificationCampaignDetailResponse(campaign = campaign().copy(id = campaignId))
        override suspend fun sendNowCampaign(requestId: String, campaignId: String, expectedRevision: Int, reason: String?): AdminNotificationCampaignMutationResponse {
            sentIds += requestId
            sendGate?.await()
            if (loseSendReply) throw IOException("Response lost after server committed")
            return AdminNotificationCampaignMutationResponse(success = true, replayed = true, campaign = campaign().copy(status = "scheduled"))
        }
    }

    @Test fun confirmationFromAccountACannotBeExecutedAsAccountB() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val client = client()
        try {
            var uid = "admin-a"
            val repo = Repository(client)
            val vm = AdminNotificationCampaignViewModel(repo, currentUserIdProvider = { uid })
            val old = campaign()
            vm.openSendNowDialog(old)
            uid = "admin-b"
            vm.executeSendNow(old, "Confirmed only by A")
            assertEquals("B must explicitly confirm a fresh intent", 0, repo.sentIds.size)
            assertNull(vm.uiState.value.activeDialog)
        } finally { client.close(); Dispatchers.resetMain() }
    }

    @Test fun stalePageCannotPublishWhenProviderChangedBeforeCollectorRuns() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val client = client()
        try {
            var uid = "admin-a"
            val repo = Repository(client)
            val vm = AdminNotificationCampaignViewModel(repo, currentUserIdProvider = { uid })
            val gate = CompletableDeferred<AdminListNotificationCampaignsEnvelope>()
            repo.pageGate = gate
            vm.loadHistory()
            uid = "admin-b"
            gate.complete(AdminListNotificationCampaignsEnvelope(items = listOf(campaign())))
            assertTrue("A response must not be published after actual auth owner changed", vm.uiState.value.campaigns.isEmpty())
        } finally { client.close(); Dispatchers.resetMain() }
    }

    @Test fun unresolvedSendNowRetainsExactRequestIdAcrossRecreation() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val client = client()
        try {
            val handle = SavedStateHandle()
            val repo = Repository(client)
            val original = AdminNotificationCampaignViewModel(repo, savedStateHandle = handle, currentUserIdProvider = { "admin-a" })
            val campaign = campaign()
            original.openSendNowDialog(campaign)
            repo.loseSendReply = true
            original.executeSendNow(campaign, "Confirmed A message")
            assertEquals(1, repo.sentIds.size)
            val originalId = repo.sentIds.single()
            val restored = AdminNotificationCampaignViewModel(repo, savedStateHandle = handle, currentUserIdProvider = { "admin-a" })
            val retry = restored.uiState.value.activeDialog as? NotificationActionDialog.SendNow
            assertNotNull("Explicit retry must survive process recreation", retry)
            repo.loseSendReply = false
            restored.executeSendNow(retry!!.campaign, retry.reason, retry.requestId)
            assertEquals(listOf(originalId, originalId), repo.sentIds)
        } finally { client.close(); Dispatchers.resetMain() }
    }

    @Test fun exactConfirmedReasonIsPersistedBeforeServerReply() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val client = client()
        val gate = CompletableDeferred<Unit>()
        try {
            val handle = SavedStateHandle()
            val repo = Repository(client)
            val original = AdminNotificationCampaignViewModel(repo, savedStateHandle = handle, currentUserIdProvider = { "admin-a" })
            original.openSendNowDialog(campaign())
            repo.sendGate = gate
            original.executeSendNow(campaign(), "Exact confirmed reason before crash")
            assertEquals(1, repo.sentIds.size)
            // Recreate while the original RPC has not returned: no catch block has run.
            val restored = AdminNotificationCampaignViewModel(repo, savedStateHandle = handle, currentUserIdProvider = { "admin-a" })
            val retry = restored.uiState.value.activeDialog as? NotificationActionDialog.SendNow
            assertNotNull("Submitted ambiguous action must be retained before receiving any response", retry)
            assertEquals("Exact confirmed reason before crash", retry!!.reason)
            assertEquals(repo.sentIds.single(), retry.requestId)
        } finally { gate.complete(Unit); client.close(); Dispatchers.resetMain() }
    }
}
