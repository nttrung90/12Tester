package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.ui.viewmodels.AppUpdateReleaseViewModel
import com.ntt.crosstest.ui.viewmodels.CampaignDetailUiState
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel
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
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppUpdateRegressionTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionCacheManager.clear(context)
    }
    @After fun after() { store.clear(); SessionCacheManager.clear(context); Dispatchers.resetMain() }

    private val release = """{"id":"release-A","campaign_id":"campaign","package_name":"com.fixture.target","target_version_code":10,"status":"active","paid_count":19,"remaining_credits":6,"has_claimed":false}"""
    private fun detail(rel: String) = """{"campaign":{"id":"campaign","owner_id":"owner","app_name":"Fixture","package_name":"com.fixture.target","status":"open"},"is_member":true,"reports":[],"app_update":$rel}"""
    private suspend fun fixture(handler: suspend (String) -> String): Pair<io.github.jan.supabase.SupabaseClient, CampaignRepository> {
        val client = createSupabaseClient("https://fixture.invalid", "fixture-key") {
            install(Auth) {
                sessionManager=MemorySessionManager(); codeVerifierCache=MemoryCodeVerifierCache()
                autoLoadFromStorage=false; autoSaveToStorage=false; enableLifecycleCallbacks=false
            }
            install(Postgrest)
            httpEngine=MockEngine { req -> respond(handler(req.url.encodedPath),HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json")) }
        }
        client.auth.importSession(Json { ignoreUnknownKeys=true }.decodeFromString<UserSession>(
            """{"access_token":"fixture-token","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"tester","aud":"authenticated"}}"""))
        return client to CampaignRepository(client, Localizer(context))
    }

    @Test fun ineligibleTesterMustNotSeeRewardOffer() {
        val ui=CampaignDetailUiState(isOwner=false,isMember=true,
            campaign=Campaign(id="campaign",status="open"),
            appUpdate=AppUpdateRelease(id="release-A",isEligible=false,ineligibilityReason="joined_after_release"))
        assertFalse("Tester joined after publication must not see +6 Credit offer",ui.showAppUpdateBlock)
    }
    @Test fun pausedCampaignMustHideUpdateAction() {
        val ui=CampaignDetailUiState(isOwner=false,isMember=true,
            campaign=Campaign(id="campaign",status="paused"),
            appUpdate=AppUpdateRelease(id="release-A",isEligible=false,ineligibilityReason="campaign_paused"))
        assertFalse("Paused campaign must hide update reward action",ui.showAppUpdateBlock)
    }
    @Test fun twentiethClaimMustNotReloadCachedNineteenClaimSnapshot() = runBlocking {
        var claimed=false
        val (client,repo)=fixture { path -> when {
            path.endsWith("/get_campaign_detail") -> detail(if(claimed) "null" else release)
            path.endsWith("/claim_app_update_reward") -> {claimed=true; """{"success":true,"newly_awarded":true,"reward":6,"tester_balance":106,"paid_count":20,"release_status":"exhausted"}"""}
            else -> "[]"
        }}
        try {
            assertEquals(19,repo.getCampaignDetailPage("campaign",true)!!.appUpdate!!.paidCount)
            repo.claimAppUpdateReward("release-A",10).getOrThrow()
            val after=repo.getCampaignDetailPage("campaign",false)
            assertNull("After the 20th claim detail cache must not restore active 19/20 release",after!!.appUpdate)
        } finally {client.close()}
    }
    @Test fun closeReleaseMustInvalidateOwnerDetailCache() = runBlocking {
        var closed=false
        val (client,repo)=fixture { path -> when {
            path.endsWith("/get_campaign_detail") -> detail(if(closed) "null" else release)
            path.endsWith("/close_app_update_release") -> {closed=true; """{"success":true,"refund_amount":6,"owner_credits":106,"release_status":"closed"}"""}
            else -> "[]"
        }}
        try {
            repo.getCampaignDetailPage("campaign",true)
            repo.closeAppUpdateRelease("release-A").getOrThrow()
            assertNull("Closing a release must not keep active budget/progress in detail cache",repo.getCampaignDetailPage("campaign",false)!!.appUpdate)
        } finally {client.close()}
    }
    @Test fun receiptReplayMustRefreshStaleProfileBalance() = runBlocking {
        var claimed=false
        val (client,repo)=fixture { path -> when {
            path.endsWith("/profiles") -> """[{"id":"tester","credits":${if(claimed)106 else 100}}]"""
            path.endsWith("/claim_app_update_reward") -> {claimed=true; """{"success":true,"newly_awarded":false,"reward":6,"tester_balance":106,"paid_count":1,"release_status":"active"}"""}
            else -> "[]"
        }}
        try {
            assertEquals(100,repo.getMyProfile()!!.credits)
            repo.claimAppUpdateReward("release-A",10).getOrThrow()
            assertEquals("Replay after a lost response must reconcile the displayed balance",106,repo.getMyProfile()!!.credits)
        } finally {client.close()}
    }
    @Test fun publishOperationIdentityMustSurviveRefreshWhileRetryIsPossible() = runBlocking {
        val (client,repo)=fixture {path -> when {
            path.endsWith("/get_campaign_detail") -> detail("null")
            path.endsWith("/profiles") -> """[{"id":"tester","credits":1000}]"""
            else -> "[]"
        }}
        try {
            val vm=AppUpdateReleaseViewModel("campaign",repo,Localizer(context)); store.put("owner",vm)
            withTimeout(10000) {vm.uiState.first{!it.isLoading}}
            vm.onVersionNameChange("v10"); vm.onVersionCodeChange("10"); vm.onReleaseNotesChange("Notes")
            val before=vm.uiState.value.clientOperationId
            vm.refresh()
            withTimeout(10000) {vm.uiState.first{!it.isLoading}}
            assertEquals("Refresh must not replace a pending publish idempotency identity",before,vm.uiState.value.clientOperationId)
        } finally {client.close()}
    }
    @Test fun oldClaimResponseMustNotMarkNewReleaseAsClaimed() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val claimStarted=CompletableDeferred<Unit>()
        val releaseClaim=CompletableDeferred<Unit>()
        val releaseRefresh=CompletableDeferred<Unit>()
        var detailCalls=0
        var newer=false
        val (client,repo)=fixture {path -> when {
            path.endsWith("/get_campaign_detail") -> {
                detailCalls++
                if(detailCalls>=3) releaseRefresh.await()
                detail(if(newer) release.replace("release-A","release-B").replace("\"target_version_code\":10","\"target_version_code\":20") else release)
            }
            path.endsWith("/get_campaign_mission") -> """{"mission":null}"""
            path.endsWith("/claim_app_update_reward") -> {
                claimStarted.complete(Unit); releaseClaim.await()
                """{"success":true,"newly_awarded":true,"reward":6,"tester_balance":106,"paid_count":20,"release_status":"exhausted","receipt":{"release_id":"release-A"}}"""
            }
            else -> "[]"
        }}
        val states=mutableListOf<CampaignDetailUiState>()
        var collector: Job?=null
        try {
            val vm=CampaignDetailViewModel("campaign",repo,ReportSubmitManager(context),"tester",Localizer(context),context)
            store.put("tester",vm)
            withTimeout(10000) {vm.uiState.first{it.appUpdate?.id=="release-A"&&!it.isLoading&&!it.isBackgroundSyncing}}
            collector=launch(Dispatchers.Unconfined) {vm.uiState.collect{states.add(it)}}
            vm.claimAppUpdateReward("release-A",10)
            withTimeout(10000) {claimStarted.await()}
            newer=true; vm.refresh(forceRefresh=true)
            withTimeout(10000) {vm.uiState.first{it.appUpdate?.id=="release-B"&&!it.isBackgroundSyncing}}
            // Hold the reconciliation read so an invalid intermediate publication is observable.
            repo.invalidateCampaign("campaign")
            releaseClaim.complete(Unit)
            withTimeout(10000) {vm.uiState.first{!it.isClaimingUpdateReward}}
            assertFalse("A response for release-A must never mark release-B paid/exhausted",vm.uiState.value.appUpdate?.hasClaimed==true)
        } finally {releaseClaim.complete(Unit);releaseRefresh.complete(Unit);collector?.cancel();client.close()}
    }
}
