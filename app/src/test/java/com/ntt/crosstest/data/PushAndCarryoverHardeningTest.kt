package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.R
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

/**
 * Regression tests verifying desired hardening behavior for items R05, R06, R08, R11, R12.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PushAndCarryoverHardeningTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }

    private fun MockRequestHandleScope.jsonResponse(body: String) = respond(
        body,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json")
    )

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
        client.auth.importSession(
            json.decodeFromString<UserSession>(
                """{
                "access_token":"token-$uid","refresh_token":"refresh-$uid","token_type":"bearer",
                "expires_in":3600,"user":{"id":"$uid","aud":"authenticated"}}
            """
            )
        )
    }

    @Before
    fun before() {
        SessionCacheManager.clear(context)
    }

    @After
    fun after() {
        SessionCacheManager.clear(context)
    }

    /**
     * R05: User A's delayed push purchase response must NOT overwrite User B's cache snapshot.
     */
    @Test
    fun oldPurchaseResponseDoesNotOverwriteNewAccountSnapshot() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val c = client { req ->
            if (req.url.toString().contains("push_campaign")) {
                entered.complete(Unit)
                release.await()
                jsonResponse(
                    """{"success":true,"receipt_id":"rA","campaign_id":"cA",
                    "client_request_id":"reqA","balance_after":900,"charged_credits":100,
                    "business_date":"2026-10-29","expires_at":"2026-10-29T17:00:00Z","push_order":1}"""
                )
            } else jsonResponse("[]")
        }
        login(c, "userA")
        val repo = CampaignRepository(c, Localizer(context))
        val pending = async { repo.pushCampaign("cA", "reqA") }
        withTimeout(5000) { entered.await() }

        // Switch session to userB with 77 credits
        SessionCacheManager.clear(context)
        login(c, "userB")
        SessionCacheManager.save(
            context,
            CachedSessionData(userId = "userB", userProfile = Profile(id = "userB", credits = 77), userCredits = 77)
        )

        // Release delayed response of userA
        release.complete(Unit)
        withTimeout(5000) { pending.await() }

        // Snapshot must remain userB with 77 credits, NOT corrupted to 900
        val snapshot = SessionCacheManager.peekMemory()!!
        assertEquals("userB", snapshot.userId)
        assertEquals("Hardened: User A response was prevented from overwriting User B snapshot", 77, snapshot.userCredits)
        assertEquals(77, snapshot.userProfile?.credits)
    }

    /**
     * R06: Pending push request replay proceeds even when account balance is below 100 Credits.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pendingReceiptResolvesWhenBalanceBelow100() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val purchaseCalls = AtomicInteger()
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"open","slot_number":1},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":0}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("push_campaign") -> {
                    purchaseCalls.incrementAndGet()
                    jsonResponse(
                        """{
                        "success":true,"replayed":true,"receipt_id":"rOld",
                        "client_request_id":"reqOld","campaign_id":"cA","charged_credits":100,
                        "balance_after":50,"business_date":"2026-10-29",
                        "expires_at":"2026-10-29T17:00:00Z","push_order":10
                    }"""
                    )
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val requestId = CampaignPushStore.getOrCreatePendingRequestId(context, "userA", "cA")
        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isBackgroundSyncing } }
            vm.pushApp(context)
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            // Hardened: pending request reaches server replay regardless of balance < 100
            assertEquals("Hardened: pending request successfully called server replay", 1, purchaseCalls.get())
            assertNull("Hardened: no push error when replay succeeds", vm.uiState.value.pushError)
            assertNotNull("Hardened: success message set", vm.uiState.value.message)
            // Pending request store must be cleared after successful replay
            assertNull("Hardened: pending request store cleared after replay", CampaignPushStore.peekPendingRequestId(context, "userA", "cA"))
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * Expiration metadata validation: missing expiry must not be considered active.
     */
    @Test
    fun pushActiveRejectsMissingOrExpiredExpiry() {
        // Missing expiry -> false
        val missingExpiry = Campaign(id = "c1", pushBusinessDate = "2026-10-29", pushOrder = 1, pushExpiresAt = null)
        assertFalse(missingExpiry.isPushActive("2026-10-29"))

        // Expired timestamp (in the past relative to 2026-10-29T12:00:00Z) -> false
        val expired = Campaign(id = "c2", pushBusinessDate = "2026-10-29", pushOrder = 1, pushExpiresAt = "2026-10-29T10:00:00Z")
        // When checking at a time after 10:00Z
        val nowMs = java.time.Instant.parse("2026-10-29T12:00:00Z").toEpochMilli()
        assertFalse(expired.isPushActive("2026-10-29", nowMs))

        // Valid timestamp (in the future relative to 12:00Z) -> true
        val active = Campaign(id = "c3", pushBusinessDate = "2026-10-29", pushOrder = 1, pushExpiresAt = "2026-10-29T17:00:00Z")
        assertTrue(active.isPushActive("2026-10-29", nowMs))
    }

    /**
     * R11: Format strings verify correct argument order for limited source and waived carryover notes.
     */
    @Test
    fun testCarryoverStringFormattingArguments() {
        // A=5, T=5, R=3
        val limitedEn = context.getString(R.string.daily_task_carryover_limited_note, 5, 5, 3)
        assertTrue(limitedEn.contains("5 apps are available"))
        assertTrue(limitedEn.contains("target is 5 apps"))
        assertTrue(limitedEn.contains("3 remaining carried-over apps stay preserved"))

        // Waived with R=8
        val waivedEn = context.getString(R.string.daily_task_waived_with_carryover_note, 8)
        assertTrue(waivedEn.contains("8 carried-over apps remain preserved"))
    }
}
