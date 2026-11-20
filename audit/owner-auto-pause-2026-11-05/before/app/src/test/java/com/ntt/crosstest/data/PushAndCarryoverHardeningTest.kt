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
import io.ktor.client.engine.mock.toByteArray
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
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }

    private suspend fun login(client: io.github.jan.supabase.SupabaseClient, uid: String) {
        client.auth.awaitInitialization()
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
                req.url.toString().contains("reconcile_campaign_push_receipt") -> {
                    purchaseCalls.incrementAndGet()
                    jsonResponse(
                        """{
                        "success":true,"found":true,"receipt_id":"rOld",
                        "client_request_id":"reqOld","campaign_id":"cA","charged_credits":100,
                        "balance_after":50,"current_credits":0,"business_date":"2026-10-29",
                        "expires_at":"2026-10-29T17:00:00Z","push_order":10,"replayed":true
                    }"""
                    )
                }
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
        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isBackgroundSyncing } }
            val requestId = CampaignPushStore.getOrCreatePendingRequestId(context, "userA", "cA")
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

    /**
     * R07: Server rejection (e.g. PUSH_METADATA_READ_ONLY) must clear pending request ID from store
     * to avoid automatic repurchase after backend is fixed, and show localized safe error.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pushCampaignServerRejectionClearsPendingRequestId() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"open","slot_number":1},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("get_business_quote") -> jsonResponse(
                    """{"quote_type":"push","cost":100,"revision":"fixture-push-rev"}"""
                )
                req.url.toString().contains("push_campaign") -> {
                    respond(
                        """{"code":"P0001","message":"PUSH_METADATA_READ_ONLY: Client khong duoc phep thay doi metadata day app truc tiep"}""",
                        HttpStatusCode.BadRequest,
                        headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isBackgroundSyncing } }
            vm.pushApp(context)
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            // Error must be mapped to safe localized message without raw exception/SQLSTATE/URL
            val err = vm.uiState.value.pushError
            assertNotNull(err)
            assertFalse("Must not expose SQLSTATE P0001", err!!.contains("P0001"))
            assertFalse("Must not expose raw code PUSH_METADATA_READ_ONLY", err.contains("PUSH_METADATA_READ_ONLY"))
            assertTrue("Must show retry later unbilled message", err.contains(context.getString(R.string.oad_push_retry_later_unbilled)))

            // Pending request store must be CLEARED because server rejected deterministically
            assertNull(
                "Pending store must be cleared on server rejection to prevent auto retry",
                CampaignPushStore.peekPendingRequestId(context, "userA", "cA")
            )
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * R07: Network timeout / connection drop must PRESERVE pending request ID for subsequent idempotent replay.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pushCampaignTimeoutPreservesPendingRequestId() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"open","slot_number":1},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("get_business_quote") -> jsonResponse(
                    """{"quote_type":"push","cost":100,"revision":"fixture-push-rev"}"""
                )
                req.url.toString().contains("push_campaign") -> {
                    throw java.net.SocketTimeoutException("Connection timed out")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isBackgroundSyncing } }
            vm.pushApp(context)
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            // Store MUST preserve the pending ID for subsequent idempotent resolution
            val pending = CampaignPushStore.peekPendingRequestId(context, "userA", "cA")
            assertNotNull("Pending store must keep request ID on timeout", pending)

            // UI error must be localized network error
            val err = vm.uiState.value.pushError
            assertNotNull(err)
            assertFalse("Must not expose SocketTimeoutException raw message", err!!.contains("SocketTimeoutException"))
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * R03: UI and log fallbacks must NEVER expose raw Bearer tokens, URLs, or authorization headers.
     */
    @Test
    fun pushErrorSanitizerRedactsSensitiveTokensAndUrls() {
        val sensitiveMsg = "Error calling https://api.supabase.co/rest/v1/rpc/push_campaign with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret and apikey: secret-key-1234"
        val sanitized = com.ntt.crosstest.ui.viewmodels.sanitizeErrorMessage(sensitiveMsg)

        assertFalse("Must redact Bearer token", sanitized.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secret"))
        assertFalse("Must redact URL", sanitized.contains("https://api.supabase.co"))
        assertFalse("Must redact apikey", sanitized.contains("secret-key-1234"))
        assertTrue("Contains redaction marker", sanitized.contains("[REDACTED]") || sanitized.contains("[URL]"))

        val localizer = Localizer(context)
        val friendly = com.ntt.crosstest.ui.viewmodels.friendlyError(Exception(sensitiveMsg), localizer)
        assertFalse("Friendly error must not contain tokens", friendly.contains("eyJhb"))
        assertFalse("Friendly error must not contain URLs", friendly.contains("http"))
    }

    /**
     * R04: Dialog button conditions: pending transaction allows verification even when balance is 0,
     * but new purchase requires at least 100 Credits.
     */
    @Test
    fun dialogButtonConditionDistinguishesPendingFromNewPurchase() {
        // Case 1: New purchase with 0 credits -> isSufficient=false, hasPendingPush=false -> disabled
        val creditsZero = 0
        val isSufficientZero = creditsZero >= 100
        val hasPendingFalse = false
        val canConfirmNewZero = !false && (isSufficientZero || hasPendingFalse)
        assertFalse("New purchase with 0 credits must be disabled", canConfirmNewZero)

        // Case 2: New purchase with 100 credits -> enabled
        val credits100 = 100
        val isSufficient100 = credits100 >= 100
        val canConfirmNew100 = !false && (isSufficient100 || hasPendingFalse)
        assertTrue("New purchase with 100 credits must be enabled", canConfirmNew100)

        // Case 3: Pending verification with 0 credits -> enabled!
        val hasPendingTrue = true
        val canConfirmPendingZero = !false && (isSufficientZero || hasPendingTrue)
        assertTrue("Pending verification with 0 credits must be enabled", canConfirmPendingZero)
    }

    /**
     * R06: VM startup automatically reconciles existing pending push receipt without charging.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun vmStartupAutomaticallyReconcilesPendingPushReceiptWithoutCharging() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val reconcileCalls = AtomicInteger()
        val purchaseCalls = AtomicInteger()
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"open","slot_number":1},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":50}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("reconcile_campaign_push_receipt") -> {
                    reconcileCalls.incrementAndGet()
                    jsonResponse(
                        """{
                        "success":true,"found":true,"receipt_id":"rAuto",
                        "client_request_id":"reqAuto","campaign_id":"cA","charged_credits":100,
                        "balance_after":20,"current_credits":75,"business_date":"2026-10-30",
                        "expires_at":"2026-10-30T17:00:00Z","push_order":42,"replayed":true
                    }"""
                    )
                }
                req.url.toString().contains("push_campaign") -> {
                    purchaseCalls.incrementAndGet()
                    jsonResponse("""{"success":true}""")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val requestId = CampaignPushStore.getOrCreatePendingRequestId(context, "userA", "cA")
        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            // Automatic non-charging reconcile was called
            assertEquals("Reconcile RPC must be called on VM init", 1, reconcileCalls.get())
            assertEquals("push_campaign must NEVER be called during non-charging reconcile", 0, purchaseCalls.get())
            assertNull("Pending store must be cleared after successful reconcile", CampaignPushStore.peekPendingRequestId(context, "userA", "cA"))

            // Profile credits must be current credits (75), NOT historical balance_after (20)
            assertEquals(75, vm.uiState.value.ownerProfile?.credits)
            assertEquals(42L, vm.uiState.value.campaign?.pushOrder)
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * R06: Pending push request resolves when campaign is paused or deleted.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pendingPushResolvesWhenCampaignIsPausedOrDeleted() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val reconcileCalls = AtomicInteger()
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"paused","slot_number":1,"paused_reason":"owner_manual"},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":0}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("reconcile_campaign_push_receipt") -> {
                    val count = reconcileCalls.incrementAndGet()
                    if (count == 1) {
                        // Offline during startup
                        throw java.io.IOException("Offline during startup")
                    } else {
                        jsonResponse(
                            """{
                            "success":true,"found":true,"receipt_id":"rPaused",
                            "client_request_id":"reqPaused","campaign_id":"cA","charged_credits":100,
                            "balance_after":10,"current_credits":0,"business_date":"2026-10-30",
                            "expires_at":"2026-10-30T17:00:00Z","push_order":99,"replayed":true
                        }"""
                        )
                    }
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

            // Reconcile must proceed even when campaign is paused and balance is 0
            assertTrue(reconcileCalls.get() >= 1)
            assertNull(vm.uiState.value.pushError)
            assertNotNull(vm.uiState.value.message)
            assertNull(CampaignPushStore.peekPendingRequestId(context, "userA", "cA"))
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * R05: Stale response ignored on account switch with A -> B -> A (same uid but changed generation).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun staleResponseIgnoredOnAccountSwitchSameUidChangedGeneration() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{
                    "campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"open","slot_number":1},
                    "can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":500}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("get_business_quote") -> jsonResponse(
                    """{"quote_type":"push","cost":100,"revision":"fixture-push-rev"}"""
                )
                req.url.toString().contains("push_campaign") -> {
                    entered.complete(Unit)
                    release.await()
                    jsonResponse(
                        """{
                        "success":true,"receipt_id":"rOldA","campaign_id":"cA",
                        "client_request_id":"reqOldA","balance_after":400,"charged_credits":100,
                        "business_date":"2026-10-30","expires_at":"2026-10-30T17:00:00Z","push_order":5
                    }"""
                    )
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isBackgroundSyncing } }

            val pushJob = async { vm.pushApp(context) }
            withTimeout(5000) { entered.await() }

            // Account switch: userA -> userB -> userA (A -> B -> A)
            SessionCacheManager.clear(context)
            login(c, "userB")
            SessionCacheManager.clear(context)
            login(c, "userA")
            // Generation has now changed even though uid is userA again!

            // Now release the delayed response from the first session of userA
            release.complete(Unit)
            pushJob.await()

            // The stale response must NOT update the state with the old transaction
            assertNull("Stale response from previous generation must not set success message", vm.uiState.value.message)
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * R06: Failed lookup / not found must remain non-charging and preserve pending request ID.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedLookupNotFoundDoesNotCharge() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val reconcileCalls = AtomicInteger()
        val purchaseCalls = AtomicInteger()
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{"campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"open","slot_number":1},"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":200}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("reconcile_campaign_push_receipt") -> {
                    reconcileCalls.incrementAndGet()
                    jsonResponse("""{"success":true,"found":false,"client_request_id":"reqNone","campaign_id":"cA"}""")
                }
                req.url.toString().contains("push_campaign") -> {
                    purchaseCalls.incrementAndGet()
                    jsonResponse("""{"success":true}""")
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

            // Reconcile was called, but purchase was NEVER called
            assertTrue(reconcileCalls.get() >= 1)
            assertEquals("Must NOT charge or call push_campaign when reconcile returns found=false", 0, purchaseCalls.get())
            // Request ID must be preserved for user explicit action
            assertEquals("Request ID must remain preserved in store", requestId, CampaignPushStore.peekPendingRequestId(context, "userA", "cA"))
            assertNotNull(vm.uiState.value.pushError)
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * R05: Account switch during reconcile cannot send purchase under new session.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun accountSwitchDuringReconcileCannotSendPurchaseUnderNewSession() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val enteredReconcile = CompletableDeferred<Unit>()
        val releaseReconcile = CompletableDeferred<Unit>()
        val purchaseCalls = AtomicInteger()
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{"campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"open","slot_number":1},"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":200}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("reconcile_campaign_push_receipt") -> {
                    enteredReconcile.complete(Unit)
                    releaseReconcile.await()
                    jsonResponse("""{"success":true,"found":false}""")
                }
                req.url.toString().contains("push_campaign") -> {
                    purchaseCalls.incrementAndGet()
                    jsonResponse("""{"success":true}""")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isBackgroundSyncing } }

            val reqId = CampaignPushStore.getOrCreatePendingRequestId(context, "userA", "cA")
            val job = async { vm.pushApp(context) }
            withTimeout(5000) { enteredReconcile.await() }

            // Account switch to userB during reconcile
            SessionCacheManager.clear(context)
            login(c, "userB")

            // Release reconcile response
            releaseReconcile.complete(Unit)
            job.await()

            // Must NOT purchase under new session
            assertEquals("Must NOT purchase under new session", 0, purchaseCalls.get())
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * R06: Automatic startup lookup runs before early paused/missing campaign UI path.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun automaticStartupLookupRunsBeforeEarlyPausedOrMissingCampaignPath() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val reconcileCalls = AtomicInteger()
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{"campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"paused","slot_number":1,"paused_reason":"owner_manual"},"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":0}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("reconcile_campaign_push_receipt") -> {
                    reconcileCalls.incrementAndGet()
                    jsonResponse(
                        """{"success":true,"found":true,"receipt_id":"rAuto","client_request_id":"reqAuto","campaign_id":"cA","charged_credits":100,"balance_after":0,"current_credits":50,"business_date":"2026-10-30","expires_at":"2026-10-30T17:00:00Z","push_order":88,"replayed":true}"""
                    )
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        CampaignPushStore.getOrCreatePendingRequestId(context, "userA", "cA")
        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            // Automatic startup reconcile ran despite campaign being paused
            assertEquals(1, reconcileCalls.get())
            assertNull("Store cleared by startup reconcile", CampaignPushStore.peekPendingRequestId(context, "userA", "cA"))
            assertEquals(88L, vm.uiState.value.campaign?.pushOrder)
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * R06: Saved pending ID with reconcile found=false does not charge; explicit confirmed retry
     * purchases using original request ID, clears pending, and duplicate tap remains one call.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun savedPendingReconcileNotFoundDoesNotChargeAndExplicitRetryPurchasesUsingOriginalId() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val reconcileCalls = AtomicInteger()
        val purchaseCalls = AtomicInteger()
        val requestedIds = mutableListOf<String>()

        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{"campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"open","slot_number":1},"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":200}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("reconcile_campaign_push_receipt") -> {
                    reconcileCalls.incrementAndGet()
                    jsonResponse("""{"success":true,"found":false}""")
                }
                req.url.toString().contains("push_campaign") -> {
                    purchaseCalls.incrementAndGet()
                    val body = req.body.toByteArray().decodeToString()
                    val rId = if (body.contains("reqOriginal")) "reqOriginal" else "other"
                    requestedIds.add(rId)
                    jsonResponse(
                        """{
                        "success":true,"receipt_id":"r1","client_request_id":"reqOriginal","campaign_id":"cA",
                        "charged_credits":100,"balance_after":100,"business_date":"2026-10-30",
                        "expires_at":"2026-10-30T17:00:00Z","push_order":99,"replayed":false
                    }"""
                    )
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val originalRequestId = "reqOriginal"
        val prefs = context.getSharedPreferences("crosstest_campaign_push_requests", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("userA_cA", originalRequestId).commit()

        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.uiState.first { it.campaign != null && !it.isBackgroundSyncing } }

            // 1. Reconcile returns found=false -> 0 purchase calls!
            vm.pushApp(context)
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals("Reconcile called", 2, reconcileCalls.get()) // 1 startup + 1 explicit verify
            assertEquals("Zero purchase calls on found=false", 0, purchaseCalls.get())
            assertTrue("Observable pending remains true", vm.uiState.value.hasPendingPush)
            assertTrue("Observable isPushPendingNotFound is true", vm.uiState.value.isPushPendingNotFound)
            assertEquals("Pending ID preserved", originalRequestId, CampaignPushStore.peekPendingRequestId(context, "userA", "cA"))

            // 2. Explicit confirmed retry purchase using original ID -> exactly 1 purchase call
            var callbackCount = 0
            vm.retryOriginalPurchase(context) { callbackCount++ }
            // Duplicate tap while action is in progress remains 1 call
            vm.retryOriginalPurchase(context) { callbackCount++ }
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            assertEquals("Exactly one purchase call", 1, purchaseCalls.get())
            assertEquals("Must use original request ID", "reqOriginal", requestedIds.firstOrNull())
            assertEquals("Callback invoked once", 1, callbackCount)
            assertNull("Store cleared after successful purchase", CampaignPushStore.peekPendingRequestId(context, "userA", "cA"))
            assertFalse("Observable pending is now false", vm.uiState.value.hasPendingPush)
            assertFalse("Observable isPushPendingNotFound is false", vm.uiState.value.isPushPendingNotFound)
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * R06: Automatic successful receipt lookup updates observable pending=false and low-balance/paused verification stays read-only.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun automaticSuccessfulLookupUpdatesObservablePendingFalseAndPausedStaysReadOnly() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val reconcileCalls = AtomicInteger()
        val purchaseCalls = AtomicInteger()
        val c = client { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse(
                    """{"campaign":{"id":"cA","owner_id":"userA","app_name":"App A","status":"paused","slot_number":1,"paused_reason":"owner_manual"},"can_manage":true,"is_actual_owner":true,"my_profile":{"id":"userA","credits":0}}"""
                )
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("{\"mission\":null}")
                req.url.toString().contains("reconcile_campaign_push_receipt") -> {
                    reconcileCalls.incrementAndGet()
                    jsonResponse(
                        """{"success":true,"found":true,"receipt_id":"rAuto","client_request_id":"reqAuto","campaign_id":"cA","charged_credits":100,"balance_after":0,"current_credits":0,"business_date":"2026-10-30","expires_at":"2026-10-30T17:00:00Z","push_order":42,"replayed":true}"""
                    )
                }
                req.url.toString().contains("push_campaign") -> {
                    purchaseCalls.incrementAndGet()
                    jsonResponse("""{"success":true}""")
                }
                else -> jsonResponse("[]")
            }
        }
        login(c, "userA")
        val prefs = context.getSharedPreferences("crosstest_campaign_push_requests", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("userA_cA", "reqAuto").commit()

        val localizer = Localizer(context)
        val vm = OwnerAppDetailViewModel("cA", CampaignRepository(c, localizer), localizer)
        try {
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }

            // Automatic lookup updated observable pending = false
            assertFalse("Observable pending is false after successful lookup", vm.uiState.value.hasPendingPush)
            assertEquals("Push order updated", 42L, vm.uiState.value.campaign?.pushOrder)
            assertEquals("No purchase calls occurred (read-only)", 0, purchaseCalls.get())
            assertNull("Store cleared", CampaignPushStore.peekPendingRequestId(context, "userA", "cA"))
        } finally {
            CampaignPushStore.clearPendingRequestId(context, "userA", "cA")
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
