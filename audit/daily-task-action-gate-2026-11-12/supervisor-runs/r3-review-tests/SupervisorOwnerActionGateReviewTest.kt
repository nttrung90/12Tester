package com.ntt.crosstest.ui.viewmodels

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.CampaignRepository
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

/** Independent supervisor behavior tests; network is always the local MockEngine. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SupervisorOwnerActionGateReviewTest {
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
            f.releaseTaskRead.complete(Unit)
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

    private inner class Fixture(val holdMutation: Boolean) {
        val met = AtomicBoolean(true)
        val uid = AtomicReference("review-owner-a")
        val taskReads = AtomicInteger()
        val hangFreshRead = AtomicBoolean(false)
        val freshReadStarted = CompletableDeferred<Unit>()
        val delayNextTaskRead = AtomicBoolean(false)
        val heldTaskReadStarted = CompletableDeferred<Unit>()
        val releaseTaskRead = CompletableDeferred<Unit>()
        val detailReads = AtomicInteger()
        val mutations = AtomicInteger()
        val rejectProductionAsUnmet = AtomicBoolean(false)
        val release = CompletableDeferred<Unit>()
        val campaignStatus = AtomicReference("open")
        val client = createSupabaseClient("https://supervisor-fixture.invalid", "fixture-key") {
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
                        """{"campaign":{"id":"review-campaign","owner_id":"review-owner-a","app_name":"Review App","status":"${campaignStatus.get()}","slot_number":1,"created_at":"${VnTime.todayString()}T00:00:00Z"},"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"${uid.get()}","credits":500,"role":"member"}}"""
                    }
                    path.contains("get_my_daily_task_status") -> {
                        taskReads.incrementAndGet()
                        if (hangFreshRead.get()) {
                            freshReadStarted.complete(Unit)
                            kotlinx.coroutines.awaitCancellation()
                        }
                        val payload = """{"status_date":"${VnTime.todayString()}","mode":"owner","active_app_count":2,"required_tests":6,"effective_required_tests":6,"completed_tests":${if(met.get()) 6 else 3},"available_apps":12,"met":${met.get()},"carry_in_tests":0,"carryover_remaining_tests":0,"carryover_ready":true,"history_ready":true}"""
                        if (delayNextTaskRead.compareAndSet(true, false)) {
                            heldTaskReadStarted.complete(Unit)
                            releaseTaskRead.await()
                        }
                        payload
                    }
                    path.contains("confirm_campaign_production") -> {
                        mutations.incrementAndGet()
                        if (rejectProductionAsUnmet.get()) {
                            met.set(false)
                            return@MockEngine respond(
                                """{"code":"P0001","message":"OWNER_INACTIVE: Daily task changed before commit","details":null,"hint":null}""",
                                HttpStatusCode.BadRequest,
                                headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                        if (holdMutation) release.await()
                        campaignStatus.set("production")
                        """{"success":true,"campaign_id":"review-campaign","status":"production"}"""
                    }
                    path.contains("get_campaign_mission") -> """{"mission":null}"""
                    path.contains("get_business_quote") -> """{"quote_type":"push","cost":100,"revision":"review-rev"}"""
                    path.endsWith("/profiles") -> """[{"id":"${uid.get()}","credits":500,"role":"member"}]"""
                    path.endsWith("/campaigns") -> """[{"id":"review-campaign","owner_id":"review-owner-a","app_name":"Review App","status":"${campaignStatus.get()}","slot_number":1}]"""
                    else -> "[]"
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        lateinit var vm: OwnerAppDetailViewModel
    }

    private suspend fun fixture(hold: Boolean = false): Fixture {
        val f = Fixture(hold)
        login(f.client, f.uid.get())
        val localizer = Localizer(context)
        f.vm = OwnerAppDetailViewModel("review-campaign", CampaignRepository(f.client, localizer), localizer)
        fixtures += f
        awaitCondition { f.vm.uiState.value.campaign != null && !f.vm.uiState.value.isLoading && f.vm.uiState.value.dailyTask != null }
        assertTrue("fixture starts with an allowed cached task", f.vm.uiState.value.productionGateResult.isAllowed)
        return f
    }

    @Test fun confirmationReadsFreshTaskBeforeProductionMutation() = runBlocking {
        val f = fixture()
        val initialReads = f.taskReads.get()
        f.met.set(false) // Another app/task changed after the screen was loaded.
        f.vm.markProduction()
        awaitCondition { f.mutations.get() > 0 || (!f.vm.uiState.value.isActionLoading && f.vm.uiState.value.error != null) }
        assertEquals("fresh unmet status must stop mutation despite cached met=true", 0, f.mutations.get())
        assertTrue("confirmation must fetch current task", f.taskReads.get() > initialReads)
        assertNotNull(f.vm.uiState.value.error)
        assertTrue("unmet message must include the known remaining task count", f.vm.uiState.value.error.orEmpty().contains("3"))
    }

    @Test fun duplicateProductionTapsShareOneInFlightOperation() = runBlocking {
        val f = fixture(hold = true)
        f.vm.markProduction()
        awaitCondition { f.mutations.get() == 1 }
        assertTrue(f.vm.uiState.value.isActionLoading)
        f.vm.markProduction()
        dispatcher.scheduler.runCurrent()
        // Both requests use MockEngine; hold the first response while scheduling the second tap.
        repeat(30) { dispatcher.scheduler.runCurrent(); withContext(Dispatchers.IO) { delay(10) } }
        val beforeRelease = f.mutations.get()
        f.release.complete(Unit)
        assertEquals("a repeated tap must not send another mutation", 1, beforeRelease)
    }

    @Test fun oldAccountProductionResponseDoesNotRefreshNewAccountPage() = runBlocking {
        val f = fixture(hold = true)
        f.vm.markProduction()
        awaitCondition { f.mutations.get() == 1 }
        SessionCacheManager.clearSync(context)
        f.uid.set("review-owner-b")
        login(f.client, f.uid.get())
        dispatcher.scheduler.runCurrent()
        val readsAfterSwitch = f.detailReads.get()
        f.release.complete(Unit)
        repeat(40) { dispatcher.scheduler.runCurrent(); withContext(Dispatchers.IO) { delay(10) } }
        assertEquals("old response must not trigger a detail refresh in account B", readsAfterSwitch, f.detailReads.get())
    }

    @Test fun actualFreshReadDeadlineShowsPendingInsteadOfSilentCancellation() = runBlocking {
        val f = fixture()
        f.hangFreshRead.set(true)
        f.vm.markProduction()
        awaitCondition { f.freshReadStarted.isCompleted }
        dispatcher.scheduler.advanceTimeBy(5001)
        dispatcher.scheduler.runCurrent()
        awaitCondition { !f.vm.uiState.value.isActionLoading }
        assertEquals("an expired fresh-read deadline must not mutate", 0, f.mutations.get())
        assertNotNull("the user must receive a pending/retry message after an actual deadline", f.vm.uiState.value.error)
        assertTrue("a known failed fresh read must not leave cached eligibility enabled", !f.vm.uiState.value.productionGateResult.isAllowed)
    }

    @Test fun olderConfirmationReadCannotOverwriteNewerTaskRefresh() = runBlocking {
        val f = fixture()
        f.delayNextTaskRead.set(true)
        f.vm.markProduction()
        awaitCondition { f.heldTaskReadStarted.isCompleted }
        f.met.set(false)
        f.vm.refreshDailyTaskState()
        awaitCondition { f.vm.uiState.value.dailyTask?.rawMet == false }
        f.releaseTaskRead.complete(Unit)
        awaitCondition { f.mutations.get() > 0 || (!f.vm.uiState.value.isActionLoading && f.vm.uiState.value.error != null) }
        assertEquals("a superseded confirmation response must not authorize mutation", 0, f.mutations.get())
        assertEquals("old action read must not overwrite newer task status", false, f.vm.uiState.value.dailyTask?.rawMet)
    }

    @Test fun serverUnmetRejectionInvalidatesEligibilityAndShowsReadableMessage() = runBlocking {
        val f = fixture()
        f.rejectProductionAsUnmet.set(true)
        f.vm.markProduction()
        awaitCondition { f.mutations.get() == 1 && !f.vm.uiState.value.isActionLoading && f.vm.uiState.value.error != null }
        assertEquals("a server rejection must not fake a production commit", "open", f.campaignStatus.get())
        val message = f.vm.uiState.value.error.orEmpty()
        assertTrue("the user needs a readable rejection message", message.isNotBlank())
        assertTrue("a missing count must not leak an unresolved format placeholder", !message.contains("%"))
        assertTrue("known unmet rejection must invalidate the earlier allowed task", !f.vm.uiState.value.productionGateResult.isAllowed)
    }

    @Test fun oldRefreshCannotReenableEligibilityAfterNewerConfirmationDeadline() = runBlocking {
        val f = fixture()
        f.delayNextTaskRead.set(true)
        f.vm.refreshDailyTaskState()
        awaitCondition { f.heldTaskReadStarted.isCompleted }
        f.hangFreshRead.set(true)
        f.vm.markProduction()
        awaitCondition { f.freshReadStarted.isCompleted }
        dispatcher.scheduler.advanceTimeBy(5001)
        dispatcher.scheduler.runCurrent()
        awaitCondition { !f.vm.uiState.value.isActionLoading && f.vm.uiState.value.error != null }
        assertTrue("the failed latest read must first invalidate eligibility", !f.vm.uiState.value.productionGateResult.isAllowed)
        f.releaseTaskRead.complete(Unit)
        // The older refresh's allowed payload was captured before the failed fresh read.
        // Its campaign fetch is local and immediate; drain it while the retry read hangs.
        repeat(40) { dispatcher.scheduler.runCurrent(); withContext(Dispatchers.IO) { delay(10) } }
        assertEquals("a deadline must never send a mutation", 0, f.mutations.get())
        assertTrue("an older successful refresh must not undo a newer known read failure", !f.vm.uiState.value.productionGateResult.isAllowed)
    }

    @Test fun inspectingBlockedActionPreservesOtherOperationLoading() = runBlocking {
        val f = fixture(hold = true)
        f.vm.markProduction()
        awaitCondition { f.mutations.get() == 1 }
        f.met.set(false)
        f.vm.refreshDailyTaskState()
        awaitCondition { f.vm.uiState.value.dailyTask?.rawMet == false }
        assertTrue("production is still waiting for its response", f.vm.uiState.value.isActionLoading)
        f.vm.inspectGate(com.ntt.crosstest.data.DailyTaskAction.DELETE)
        assertTrue("viewing a blocked action must not clear another operation's loading", f.vm.uiState.value.isActionLoading)
    }
}
