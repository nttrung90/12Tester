package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.ui.viewmodels.AdminUsersViewModel
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminDailyTaskIndependentReviewTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()); SessionCacheManager.clear(context) }
    @After fun cleanup() { SessionCacheManager.clear(context); Dispatchers.resetMain() }
    private fun MockRequestHandleScope.reply(body:String,status:HttpStatusCode=HttpStatusCode.OK) =
        respond(body,status,headersOf(HttpHeaders.ContentType,"application/json"))
    private fun client(handler:suspend MockRequestHandleScope.(HttpRequestData)->HttpResponseData)=
        createSupabaseClient("https://fixture.invalid","dummy-key") {
            install(Auth) { sessionManager=MemorySessionManager(); codeVerifierCache=MemoryCodeVerifierCache(); autoLoadFromStorage=false; autoSaveToStorage=false }
            install(Postgrest); httpEngine=MockEngine(handler)
        }
    private suspend fun login(c:SupabaseClient,uid:String) {
        c.auth.importSession(json.decodeFromString<UserSession>("""{"access_token":"fixture-$uid","refresh_token":"fixture-ref-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""))
    }
    private fun config(rev:String="rev-A")="""{"today":"2026-10-30","revision":"$rev","active_policy":{"id":1,"tests_per_active_app":6,"is_baseline":true},"future_policies":[]}"""
    private fun vm(c:SupabaseClient)=AdminUsersViewModel(CampaignRepository(c,Localizer(context)),AuthRepository(c),AppConfigRepository(c),Localizer(context))
    private suspend fun waitUntil(predicate:()->Boolean) { withTimeout(5000) { while(!predicate()) delay(5) } }

    @Test fun expandingHistoryMustNotDiscardConfigRequest()=runBlocking {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val c=client { req -> when {
            req.url.toString().endsWith("admin_get_daily_task_config") -> { entered.complete(Unit); release.await(); reply(config()) }
            req.url.toString().endsWith("admin_get_daily_task_policy_history") -> reply("""{"items":[],"has_more":false}""")
            else -> reply("[]")
        } }
        login(c,"admin-A"); val v=vm(c)
        try {
            v.loadDailyTaskConfig(); withTimeout(5000){entered.await()}
            v.loadDailyTaskHistory(true); waitUntil{!v.uiState.value.isDailyTaskHistoryLoading}
            release.complete(Unit); waitUntil{!v.uiState.value.isDailyTaskLoading}
            assertNotNull("Opening history must not erase the independently loaded admin config",v.uiState.value.dailyTaskConfig)
        } finally {release.complete(Unit);v.viewModelScope.cancel();c.close()}
    }

    @Test fun expandingHistoryMustNotDiscardSuccessfulSave()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val c=client {req -> when {
            req.url.toString().endsWith("admin_get_daily_task_config")->reply(config())
            req.url.toString().endsWith("admin_schedule_daily_task_policy")->{entered.complete(Unit);release.await();reply("""{"success":true,"config":${config("rev-saved")},"revision":"rev-saved"}""")}
            req.url.toString().endsWith("admin_get_daily_task_policy_history")->reply("""{"items":[],"has_more":false}""")
            else->reply("[]")
        } }
        login(c,"admin-A");val v=vm(c)
        try {
            v.loadDailyTaskConfig();waitUntil{v.uiState.value.dailyTaskConfig!=null}
            v.saveDailyTaskSchedule(4,"2026-10-31","fixture");withTimeout(5000){entered.await()}
            v.loadDailyTaskHistory(true);waitUntil{!v.uiState.value.isDailyTaskHistoryLoading}
            release.complete(Unit);waitUntil{!v.uiState.value.isDailyTaskSaving}
            assertEquals("A committed save must remain visible even if history loaded while saving","rev-saved",v.uiState.value.dailyTaskConfig?.revision)
            assertNotNull(v.uiState.value.dailyTaskActionMessage)
        } finally {release.complete(Unit);v.viewModelScope.cancel();c.close()}
    }

    @Test fun oldAccountResponseMustNotEraseNewAccountConfig()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val n=AtomicInteger()
        val c=client { req -> when {
            req.url.toString().endsWith("admin_get_daily_task_config") -> {
                if(n.incrementAndGet()==1){entered.complete(Unit);release.await();reply(config("rev-A"))}else reply(config("rev-B"))
            }
            else -> reply("[]")
        } }
        login(c,"admin-A");val v=vm(c)
        try {
            v.loadDailyTaskConfig();withTimeout(5000){entered.await()}
            login(c,"admin-B");v.refresh();v.loadDailyTaskConfig();waitUntil{v.uiState.value.dailyTaskConfig?.revision=="rev-B"}
            release.complete(Unit);delay(150)
            assertEquals("A response for account A must not clear account B's fresh config","rev-B",v.uiState.value.dailyTaskConfig?.revision)
        } finally {release.complete(Unit);v.viewModelScope.cancel();c.close()}
    }

    @Test fun conflictMustRemainVisibleAfterAutomaticReload()=runBlocking {
        val n=AtomicInteger()
        val c=client {req->when {
            req.url.toString().endsWith("admin_get_daily_task_config")->reply(config(if(n.incrementAndGet()==1)"rev-A" else "rev-B"))
            req.url.toString().endsWith("admin_schedule_daily_task_policy")->reply("""{"code":"P0001","message":"DAILY_TASK_POLICY_CONFLICT"}""",HttpStatusCode.BadRequest)
            else->reply("[]")
        } }
        login(c,"admin-A");val v=vm(c)
        try {
            v.loadDailyTaskConfig();waitUntil{v.uiState.value.dailyTaskConfig!=null}
            v.saveDailyTaskSchedule(4,"2026-10-31","keep draft");waitUntil{v.uiState.value.dailyTaskConfig?.revision=="rev-B" && !v.uiState.value.isDailyTaskLoading}
            assertNotNull("The admin must still see the conflict and review the draft before resubmitting",v.uiState.value.dailyTaskError)
        } finally {v.viewModelScope.cancel();c.close()}
    }

    @Test fun successfulMutationMustInvalidatePreviouslyLoadedHistory()=runBlocking {
        val c=client {req->when {
            req.url.toString().endsWith("admin_get_daily_task_config")->reply(config())
            req.url.toString().endsWith("admin_get_daily_task_policy_history")->reply("""{"items":[{"id":1,"operation":"INSERT","changed_at":"2026-10-30T00:00:00Z"}],"has_more":false}""")
            req.url.toString().endsWith("admin_schedule_daily_task_policy")->reply("""{"success":true,"config":${config("rev-saved")}}""")
            else->reply("[]")
        } }
        login(c,"admin-A");val v=vm(c)
        try {
            v.loadDailyTaskConfig();waitUntil{v.uiState.value.dailyTaskConfig!=null}
            v.loadDailyTaskHistory(true);waitUntil{v.uiState.value.dailyTaskHistory.isNotEmpty()}
            v.saveDailyTaskSchedule(4,"2026-10-31","fixture");waitUntil{v.uiState.value.dailyTaskConfig?.revision=="rev-saved"}
            assertTrue("History loaded before a mutation must be invalidated or refreshed instead of staying stale with no reload control",v.uiState.value.dailyTaskHistory.isEmpty() || v.uiState.value.dailyTaskHistory.first().id!=1L)
        } finally {v.viewModelScope.cancel();c.close()}
    }
}
