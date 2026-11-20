package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.DailyTaskAction
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.util.VnTime
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Behavioral tests for OwnerAppDetailViewModel daily task action gate. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OwnerAppDetailActionGateBehavioralTest {
    private val dispatcher = StandardTestDispatcher()
    private val fixtures = mutableListOf<Fixture>()
    private val context get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        try { WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build()) } catch (_: Exception) { }
        SessionCacheManager.clearSync(context)
    }

    @After fun cleanup() = runBlocking {
        for (f in fixtures) {
            f.release.complete(Unit)
            f.releasePushQuote.complete(Unit)
            val job = f.vm.viewModelScope.coroutineContext[Job]
            f.vm.viewModelScope.cancel()
            if (job != null) { awaitCondition { job.isCompleted }; job.join() }
            f.client.close()
        }
        fixtures.clear()
        SessionCacheManager.clearSync(context)
        Dispatchers.resetMain()
    }

    private suspend fun login(client: SupabaseClient, uid: String) {
        client.auth.awaitInitialization()
        client.auth.importSession(json.decodeFromString<UserSession>(
            """{"access_token":"test-$uid","refresh_token":"refresh-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
        ))
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) = withTimeout(7000) {
        while (true) {
            dispatcher.scheduler.runCurrent()
            if (predicate()) break
            withContext(Dispatchers.IO) { delay(5) }
        }
    }

    private inner class Fixture(val holdMutation: Boolean = false, val initialStatus: String = "open") {
        val met = AtomicBoolean(true)
        val debt = AtomicInteger(0)
        val taskDate = AtomicReference(VnTime.todayString())
        val uid = AtomicReference("owner-action-tester")
        val taskReads = AtomicInteger()
        val detailReads = AtomicInteger()
        val deleteMutations = AtomicInteger()
        val resumeMutations = AtomicInteger()
        val pauseMutations = AtomicInteger()
        val productionMutations = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val failDetailRefresh = AtomicBoolean(false)
        val taskReadHang = AtomicBoolean(false)
        val holdPushQuote = AtomicBoolean(false)
        val pushQuoteStarted = CompletableDeferred<Unit>()
        val releasePushQuote = CompletableDeferred<Unit>()
        val campaignStatus = AtomicReference(initialStatus)

        val client = createSupabaseClient("https://behavioral-fixture.invalid", "fixture-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                val path = request.url.encodedPath
                val body = when {
                    path.contains("get_campaign_detail") -> {
                        detailReads.incrementAndGet()
                        if (failDetailRefresh.get()) {
                            respond("Internal Server Error", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "text/plain"))
                            return@MockEngine respond("Error", HttpStatusCode.InternalServerError)
                        }
                        """{"campaign":{"id":"test-campaign","owner_id":"owner-action-tester","app_name":"Test App","status":"${campaignStatus.get()}","slot_number":1,"created_at":"${VnTime.todayString()}T00:00:00Z"},"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"${uid.get()}","credits":500,"role":"member"}}"""
                    }
                    path.contains("get_my_daily_task_status") -> {
                        taskReads.incrementAndGet()
                        if (taskReadHang.get()) {
                            // Simulate timeout or failure
                            respond("Gateway Timeout", HttpStatusCode.GatewayTimeout, headersOf(HttpHeaders.ContentType, "text/plain"))
                            return@MockEngine respond("Timeout", HttpStatusCode.GatewayTimeout)
                        }
                        """{"status_date":"${taskDate.get()}","mode":"owner","active_app_count":2,"required_tests":6,"effective_required_tests":6,"completed_tests":${if(met.get()) 6 else 3},"available_apps":12,"met":${met.get()},"carry_in_tests":0,"carryover_remaining_tests":${debt.get()},"carryover_ready":true,"history_ready":true}"""
                    }
                    path.contains("delete_owned_campaign") -> {
                        deleteMutations.incrementAndGet()
                        if (holdMutation) release.await()
                        """{"success":true,"status":"removed","campaign_id":"test-campaign","owner_id":"${uid.get()}"}"""
                    }
                    path.contains("resume_campaign") -> {
                        resumeMutations.incrementAndGet()
                        if (holdMutation) release.await()
                        campaignStatus.set("open")
                        """{"success":true,"status":"open","campaign_id":"test-campaign"}"""
                    }
                    path.contains("update_campaign_status") || (request.method.value == "PATCH" && path.contains("campaigns")) -> {
                        pauseMutations.incrementAndGet()
                        if (holdMutation) release.await()
                        campaignStatus.set("paused")
                        """[{"id":"test-campaign","status":"paused","paused_reason":"owner_manual"}]"""
                    }
                    path.contains("confirm_campaign_production") -> {
                        productionMutations.incrementAndGet()
                        if (holdMutation) release.await()
                        campaignStatus.set("production")
                        """{"success":true,"campaign_id":"test-campaign","status":"production"}"""
                    }
                    path.contains("get_campaign_mission") -> """{"mission":null}"""
                    path.contains("get_business_quote") -> {
                        if (holdPushQuote.get()) {
                            pushQuoteStarted.complete(Unit)
                            releasePushQuote.await()
                        }
                        """{"quote_type":"push","cost":100,"revision":"test-rev"}"""
                    }
                    path.endsWith("/profiles") -> """[{"id":"${uid.get()}","credits":500,"role":"member"}]"""
                    path.endsWith("/campaigns") -> """[{"id":"test-campaign","owner_id":"owner-action-tester","app_name":"Test App","status":"${campaignStatus.get()}","slot_number":1}]"""
                    else -> "[]"
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        lateinit var vm: OwnerAppDetailViewModel
    }

    private suspend fun fixture(hold: Boolean = false, initialStatus: String = "open"): Fixture {
        val f = Fixture(hold, initialStatus)
        login(f.client, f.uid.get())
        val localizer = Localizer(context)
        f.vm = OwnerAppDetailViewModel("test-campaign", CampaignRepository(f.client, localizer), localizer)
        fixtures += f
        awaitCondition { f.vm.uiState.value.campaign != null && !f.vm.uiState.value.isLoading && f.vm.uiState.value.dailyTask != null }
        return f
    }

    @Test
    fun freshReadBlocksDeleteWhenDailyTaskBecomesUnmet() = runBlocking {
        val f = fixture()
        val initialReads = f.taskReads.get()
        f.met.set(false) // Daily task unmet right before delete confirmation
        var deletedCalled = false
        f.vm.deleteCampaign(onDeleted = { deletedCalled = true })
        awaitCondition { !f.vm.uiState.value.isActionLoading && f.vm.uiState.value.error != null }
        assertEquals("delete RPC must not be called when fresh daily task is unmet", 0, f.deleteMutations.get())
        assertFalse("onDeleted callback must not be invoked", deletedCalled)
        assertTrue("fresh task was fetched", f.taskReads.get() > initialReads)
        assertNotNull(f.vm.uiState.value.error)
    }

    @Test
    fun freshReadBlocksResumeWhenCarryoverDebtAppears() = runBlocking {
        val f = fixture(initialStatus = "paused")
        val initialReads = f.taskReads.get()
        f.debt.set(3) // Unresolved carryover debt appears
        f.vm.togglePauseResume()
        awaitCondition { !f.vm.uiState.value.isActionLoading && f.vm.uiState.value.error != null }
        assertEquals("resume RPC must not be called when carryover debt > 0", 0, f.resumeMutations.get())
        assertTrue("fresh task was fetched", f.taskReads.get() > initialReads)
        assertNotNull(f.vm.uiState.value.error)
    }

    @Test
    fun duplicateDeleteTapsShareSingleInFlightOperation() = runBlocking {
        val f = fixture(hold = true)
        f.vm.deleteCampaign(onDeleted = {})
        awaitCondition { f.deleteMutations.get() == 1 }
        assertTrue(f.vm.uiState.value.isActionLoading)
        f.vm.deleteCampaign(onDeleted = {})
        dispatcher.scheduler.runCurrent()
        repeat(20) { dispatcher.scheduler.runCurrent(); withContext(Dispatchers.IO) { delay(10) } }
        val beforeRelease = f.deleteMutations.get()
        f.release.complete(Unit)
        assertEquals("duplicate delete taps must only dispatch 1 mutation", 1, beforeRelease)
    }

    @Test
    fun duplicateTogglePauseResumeTapsShareSingleInFlightOperation() = runBlocking {
        val f = fixture(hold = true, initialStatus = "paused")
        f.vm.togglePauseResume()
        awaitCondition { f.resumeMutations.get() == 1 }
        assertTrue(f.vm.uiState.value.isActionLoading)
        f.vm.togglePauseResume()
        dispatcher.scheduler.runCurrent()
        repeat(20) { dispatcher.scheduler.runCurrent(); withContext(Dispatchers.IO) { delay(10) } }
        val beforeRelease = f.resumeMutations.get()
        f.release.complete(Unit)
        assertEquals("duplicate resume taps must only dispatch 1 mutation", 1, beforeRelease)
    }

    @Test
    fun dayRolloverAbortsMutationAndFailsClosed() = runBlocking {
        val f = fixture()
        f.taskDate.set("2026-11-11") // Rollover to yesterday or mismatch date
        f.vm.markProduction()
        awaitCondition { !f.vm.uiState.value.isActionLoading && f.vm.uiState.value.error != null }
        assertEquals("mutation must not proceed on day rollover", 0, f.productionMutations.get())
        assertNotNull(f.vm.uiState.value.error)
    }

    @Test
    fun timeoutOnFreshReadFailsClosedWithoutMutation() = runBlocking {
        val f = fixture()
        f.taskReadHang.set(true)
        f.vm.markProduction()
        awaitCondition { !f.vm.uiState.value.isActionLoading && f.vm.uiState.value.error != null }
        assertEquals("mutation must not proceed when task read fails or times out", 0, f.productionMutations.get())
        assertNotNull(f.vm.uiState.value.error)
    }

    @Test
    fun inspectGateDoesNotTriggerMutations() = runBlocking {
        val f = fixture()
        f.met.set(false)
        f.vm.inspectGate(DailyTaskAction.DELETE)
        f.vm.inspectGate(DailyTaskAction.PRODUCTION)
        f.vm.inspectGate(DailyTaskAction.RESUME)
        f.vm.inspectGate(DailyTaskAction.MANUAL_PAUSE)
        dispatcher.scheduler.runCurrent()
        assertEquals(0, f.deleteMutations.get())
        assertEquals(0, f.productionMutations.get())
        assertEquals(0, f.resumeMutations.get())
        assertEquals(0, f.pauseMutations.get())
    }

    @Test
    fun fallbackRefreshDoesNotOverwriteNewerUnmetTaskAfterAwaits() = runBlocking {
        val f = fixture()
        // Configure fallback refresh to trigger when get_campaign_detail fails
        f.failDetailRefresh.set(true)
        f.holdPushQuote.set(true)
        f.met.set(true) // Initial task in fallback read is allowed

        // Trigger fallback refresh
        f.vm.refresh()
        // Wait until fallback refresh has completed dailyTask read and reached get_business_quote
        awaitCondition { f.pushQuoteStarted.isCompleted }

        // While fallback refresh is held waiting for pushQuote, a newer update marks task unmet
        f.met.set(false)
        f.vm.refreshDailyTaskState()
        awaitCondition { f.vm.uiState.value.dailyTask?.rawMet == false }
        assertFalse("gate must reflect newer unmet task", f.vm.uiState.value.productionGateResult.isAllowed)

        // Release the held fallback push quote request
        f.releasePushQuote.complete(Unit)
        dispatcher.scheduler.runCurrent()
        repeat(30) { dispatcher.scheduler.runCurrent(); withContext(Dispatchers.IO) { delay(10) } }

        // The older fallback refresh must not have overwritten the newer unmet task
        assertEquals("older fallback allowed task must not overwrite newer unmet task", false, f.vm.uiState.value.dailyTask?.rawMet)
        assertFalse("gate must remain blocked by unmet daily task", f.vm.uiState.value.productionGateResult.isAllowed)
    }
}
