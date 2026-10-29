package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel
import io.github.jan.supabase.createSupabaseClient
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** These probes assert the observed defects, not the desired product behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TwoPlansReviewProbeTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }
    private fun MockRequestHandleScope.jsonResponse(body: String) = respond(body, HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"))
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
    private suspend fun login(client: io.github.jan.supabase.SupabaseClient, uid: String) {
        client.auth.importSession(json.decodeFromString<UserSession>("""{
            "access_token":"token-$uid","refresh_token":"refresh-$uid","token_type":"bearer",
            "expires_in":3600,"user":{"id":"$uid","aud":"authenticated"}}
        """))
    }
    @Before fun before() { SessionCacheManager.clear(context) }
    @After fun after() { SessionCacheManager.clear(context) }

    @Test fun reproduceOldPurchaseResponseOverwritesNewAccountSnapshot() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val c = client { req ->
            if (req.url.toString().contains("push_campaign")) {
                entered.complete(Unit)
                release.await()
                jsonResponse("""{"success":true,"receipt_id":"rA","campaign_id":"cA",
                    "client_request_id":"reqA","balance_after":900,"charged_credits":100,
                    "business_date":"2026-10-29","expires_at":"2026-10-29T17:00:00Z","push_order":1}""")
            } else jsonResponse("[]")
        }
        login(c, "userA")
        val repo = CampaignRepository(c, Localizer(context))
        val pending = async { repo.pushCampaign("cA", "reqA") }
        withTimeout(5000) { entered.await() }
        SessionCacheManager.clear(context)
        login(c, "userB")
        SessionCacheManager.save(context, CachedSessionData(userId="userB", userProfile=Profile(id="userB",credits=77),userCredits=77))
        release.complete(Unit)
        withTimeout(5000) { pending.await() }
        val snapshot = SessionCacheManager.peekMemory()!!
        assertEquals("userB", snapshot.userId)
        assertEquals("BUG REPRODUCED: A response wrote A balance into B snapshot",900,snapshot.userCredits)
        assertEquals(900,snapshot.userProfile?.credits)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun reproducePendingReceiptCannotResolveWhenBalanceBelow100() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val purchaseCalls = AtomicInteger()
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse("""{
                    "campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"open","slot_number":1},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":0}}
                """)
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("push_campaign") -> {
                    purchaseCalls.incrementAndGet()
                    jsonResponse("{\"success\":true,\"replayed\":true}")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c,"userA")
        val requestId=CampaignPushStore.getOrCreatePendingRequestId(context,"userA","cA")
        val vm=OwnerAppDetailViewModel("cA",CampaignRepository(c,Localizer(context)),Localizer(context))
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isBackgroundSyncing } }
            vm.pushApp(context)
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals("BUG REPRODUCED: existing request never reaches server replay",0,purchaseCalls.get())
            assertNotNull(vm.uiState.value.pushError)
            assertEquals(requestId,CampaignPushStore.peekPendingRequestId(context,"userA","cA"))
        } finally {
            CampaignPushStore.clearPendingRequestId(context,"userA","cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun reproducePushActiveWithMissingExpiry() {
        val bad=Campaign(id="bad",pushBusinessDate="2026-10-29",pushOrder=1,pushExpiresAt=null)
        assertTrue("BUG REPRODUCED: missing expiry metadata accepted",bad.isPushActive("2026-10-29"))
    }
}
