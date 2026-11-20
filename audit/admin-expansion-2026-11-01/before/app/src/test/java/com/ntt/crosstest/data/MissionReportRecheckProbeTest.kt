package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel
import com.ntt.crosstest.ui.viewmodels.HomeViewModel
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionReportRecheckProbeTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()

    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionCacheManager.clear(context)
        PendingReportStore.clear(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun after() {
        store.clear()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        PendingReportStore.clear(context)
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }

    private fun detail(name: String) = """{"campaign":{"id":"campaign","owner_id":"owner","app_name":"$name"},"is_member":true,"reports":[]}"""
    private fun mission(expired: Boolean) = """{"mission":{"id":"mission","campaign_id":"campaign","title":"Mission","status":"${if(expired) "expired" else "open"}","has_joined":true,"ends_at":"${Instant.now().plusSeconds(if(expired) -3600 else 3600)}"}}"""

    private suspend fun fixture(handler: suspend (String) -> Pair<String, HttpStatusCode>): Pair<io.github.jan.supabase.SupabaseClient, CampaignRepository> {
        val client = createSupabaseClient("https://fixture.invalid", "fixture-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                val (body, status) = handler(request.url.encodedPath)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        client.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
            """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"tester","aud":"authenticated"}}"""
        ))
        return client to CampaignRepository(client, Localizer(context))
    }

    private suspend fun submitWithUnavailableMission(expired: Boolean) {
        val (client, repo) = fixture { path ->
            when {
                path.endsWith("/get_campaign_detail") -> detail("Fixture") to HttpStatusCode.OK
                path.endsWith("/get_campaign_mission") ->
                    if (expired) mission(true) to HttpStatusCode.OK
                    else """{"message":"mission context temporarily unavailable"}""" to HttpStatusCode.InternalServerError
                else -> "[]" to HttpStatusCode.OK
            }
        }
        try {
            val manager = ReportSubmitManager(context)
            val vm = CampaignDetailViewModel("campaign", repo, manager, "tester", Localizer(context), context, initialMissionId = "mission")
            store.put("vm", vm)
            withTimeout(10_000) { vm.uiState.first { !it.isLoading && !it.isBackgroundSyncing && it.campaign != null } }
            vm.startSubmit()
            val submitted = manager.state.value
            assertTrue("Explicit mission intent must be retained or blocked; got state=$submitted, missionFlag=${vm.uiState.value.isMissionReport}, deadlineFlag=${vm.uiState.value.missionDeadlinePassed}",
                submitted == null || submitted.isMissionReport)
        } finally { client.close() }
    }

    @Test fun expiredInitialMissionMustNeverEnqueueOrdinaryReport() = runBlocking {
        submitWithUnavailableMission(expired = true)
    }

    @Test fun failedMissionContextLoadMustNeverEnqueueOrdinaryReport() = runBlocking {
        submitWithUnavailableMission(expired = false)
    }

    @Test fun oldMissionContextResponseMustNotUndoNewDetailRefresh() = runBlocking {
        val oldMissionStarted = CompletableDeferred<Unit>()
        val releaseOldMission = CompletableDeferred<Unit>()
        var detailCalls = 0
        var missionCalls = 0
        val (client, repo) = fixture { path ->
            when {
                path.endsWith("/get_campaign_detail") -> {
                    detailCalls++
                    detail(if (detailCalls == 1) "Old snapshot" else "New snapshot") to HttpStatusCode.OK
                }
                path.endsWith("/get_campaign_mission") -> {
                    missionCalls++
                    if (missionCalls == 1) {
                        oldMissionStarted.complete(Unit)
                        releaseOldMission.await()
                    }
                    mission(false) to HttpStatusCode.OK
                }
                else -> "[]" to HttpStatusCode.OK
            }
        }
        try {
            val vm = CampaignDetailViewModel("campaign", repo, ReportSubmitManager(context), "tester", Localizer(context), context, initialMissionId = "mission")
            store.put("vm", vm)
            withTimeout(10_000) { oldMissionStarted.await() }
            val oldRefreshJob = vm.viewModelScope.coroutineContext[Job]!!.children.last()
            vm.refresh(forceRefresh = true)
            withTimeout(10_000) { vm.uiState.first { it.campaign?.appName == "New snapshot" } }
            releaseOldMission.complete(Unit)
            withTimeout(10_000) { oldRefreshJob.join() }
            assertEquals("A response started before the newer refresh must not republish old detail after its mission context arrives", "New snapshot", vm.uiState.value.campaign?.appName)
        } finally { releaseOldMission.complete(Unit); client.close() }
    }

    @Test fun homeDeadlineTimerMustPruneEachDeadlineWithoutRefresh() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val (client, _) = fixture { path ->
            when {
                path.endsWith("/get_open_missions") -> """{"server_now":"2026-10-29T00:00:00Z","missions":[
                  {"id":"first","status":"full","has_joined":true,"ends_at":"2026-10-29T00:00:01Z"},
                  {"id":"second","status":"paused_by_creator","ends_at":"2026-10-29T00:00:02Z"}
                ]}""" to HttpStatusCode.OK
                else -> "[]" to HttpStatusCode.OK
            }
        }
        try {
            val repo = CampaignRepository(client, Localizer(context), monotonicClock = { scheduler.currentTime })
            val vm = HomeViewModel(repo, localizer = Localizer(context))
            store.put("timer", vm)
            vm.refreshMissions(forceRefresh = true)
            withTimeout(10_000) {
                vm.uiState.first { it.missions.size == 2 }
                val deadlineField = HomeViewModel::class.java.getDeclaredField("missionDeadlineJob").apply { isAccessible = true }
                while (deadlineField.get(vm) == null) yield()
            }
            scheduler.advanceTimeBy(1_201)
            scheduler.runCurrent()
            assertEquals(listOf("second"), vm.uiState.value.missions.map { it.id })
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
            assertTrue("The second deadline must also be pruned without another network refresh", vm.uiState.value.missions.isEmpty())
        } finally { store.clear(); client.close() }
    }
}
