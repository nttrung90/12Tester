package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OwnerRemovalLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clientsToClose = mutableListOf<SupabaseClient>()
    private val vmsToCancel = mutableListOf<androidx.lifecycle.ViewModel>()

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var localizer: Localizer
    private val ownerUserId = "user-owner-88"
    private val adminUserId = "user-admin-99"
    private val testCampaignId = "camp-del-777"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        localizer = Localizer(context)
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        } catch (_: Exception) {}
        SessionCacheManager.clearSync(context)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        for (vm in vmsToCancel) {
            try { vm.viewModelScope.cancel() } catch (_: Exception) {}
        }
        vmsToCancel.clear()

        for (c in clientsToClose) {
            try { c.close() } catch (_: Exception) {}
        }
        clientsToClose.clear()
        Dispatchers.resetMain()
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun extractBodyText(request: HttpRequestData): String {
        return when (val body = request.body) {
            is io.ktor.http.content.TextContent -> body.text
            is io.ktor.http.content.ByteArrayContent -> String(body.bytes())
            else -> body.toString()
        }
    }

    private suspend fun createTestClient(
        userId: String = ownerUserId,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): SupabaseClient {
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
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
        val json = Json { ignoreUnknownKeys = true }
        client.auth.awaitInitialization()
        client.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"token-$userId","refresh_token":"refresh-$userId","token_type":"bearer","expires_in":3600,"user":{"id":"$userId","aud":"authenticated"}}"""
            )
        )
        clientsToClose += client
        return client
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 3000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            // Start queued actions before checking an initially idle loading flag.
            testDispatcher.scheduler.runCurrent()
            if (condition()) return
            if (System.nanoTime() > deadline) {
                fail("Condition timed out after ${timeoutMs}ms")
            }
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.delay(1)
            }
        }
    }

    @Test
    fun `owner RPC submits only p_campaign_id and decodes status pending_removal`() = runBlocking {
        var capturedBody = ""
        var directDeleteAttempted = false

        val client = createTestClient(userId = ownerUserId) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id":"$ownerUserId","credits":100,"role":"member"}]""")
                path.contains("delete_owned_campaign") -> {
                    capturedBody = extractBodyText(req)
                    reply(
                        """
                        {
                            "success": true,
                            "status": "pending_removal",
                            "campaign_id": "$testCampaignId",
                            "app_name": "Test Pending App",
                            "owner_id": "$ownerUserId",
                            "message": "Obligations retained"
                        }
                        """
                    )
                }
                req.method.value == "DELETE" && path.contains("campaigns") -> {
                    directDeleteAttempted = true
                    reply("[]")
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val result = repo.deleteCampaign(testCampaignId)

        assertTrue("Body must contain p_campaign_id", capturedBody.contains(""""p_campaign_id":"$testCampaignId""""))
        assertFalse("Body must NOT contain extra p_reason", capturedBody.contains("p_reason"))
        assertFalse("Direct table DELETE must not be attempted when RPC exists", directDeleteAttempted)

        assertTrue(result.success)
        assertEquals("pending_removal", result.status)
        assertTrue(result.isPendingRemoval)
        assertFalse(result.isRemoved)
    }

    @Test
    fun `admin delete RPC decodes status pending_removal and preserves reason`() = runBlocking {
        var capturedBody = ""
        val client = createTestClient(userId = adminUserId) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id":"$adminUserId","credits":100,"role":"admin"}]""")
                path.contains("admin_delete_campaign") -> {
                    capturedBody = extractBodyText(req)
                    reply(
                        """
                        {
                            "success": true,
                            "status": "pending_removal",
                            "campaign_id": "$testCampaignId",
                            "app_name": "Admin Violation App",
                            "owner_id": "$ownerUserId",
                            "reason": "policy_violation"
                        }
                        """
                    )
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val result = repo.deleteCampaign(testCampaignId)

        assertTrue(capturedBody.contains(""""p_campaign_id":"$testCampaignId""""))
        assertTrue(result.isPendingRemoval)
        assertFalse(result.isRemoved)
        assertEquals("policy_violation", result.reason)
    }

    @Test
    fun `admin legacy body omitting status is compatible as removed`() = runBlocking {
        val client = createTestClient(userId = adminUserId) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id":"$adminUserId","credits":100,"role":"admin"}]""")
                path.contains("admin_delete_campaign") -> {
                    // Legacy admin RPC body omitting status
                    reply(
                        """
                        {
                            "success": true,
                            "campaign_id": "$testCampaignId",
                            "app_name": "Legacy App",
                            "owner_id": "$ownerUserId"
                        }
                        """
                    )
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val result = repo.adminDeleteCampaign(testCampaignId)

        assertTrue(result.success)
        assertNull(result.status)
        assertTrue("Legacy admin response with null status must be compatible as removed", result.isRemoved)
        assertFalse(result.isPendingRemoval)
    }

    @Test
    fun `OwnerAppDetailViewModel pending does NOT fire onDeleted but DOES fire onPendingRemoval`() = runBlocking {
        val onDeletedFired = AtomicBoolean(false)
        val onPendingFired = AtomicBoolean(false)

        val client = createTestClient(userId = ownerUserId) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id":"$ownerUserId","credits":100,"role":"member"}]""")
                path.contains("delete_owned_campaign") -> {
                    reply(
                        """
                        {
                            "success": true,
                            "status": "pending_removal",
                            "campaign_id": "$testCampaignId",
                            "app_name": "Owner App",
                            "owner_id": "$ownerUserId"
                        }
                        """
                    )
                }
                path.contains("get_campaign_detail_sync_v2") -> {
                    reply("""{"code":"PGRST202","message":"Could not find the function public.get_campaign_detail_sync_v2"}""", HttpStatusCode.NotFound)
                }
                path.contains("get_campaign_detail") -> {
                    reply(
                        """{
                            "campaign":{"id":"$testCampaignId","owner_id":"$ownerUserId","app_name":"Owner App","package_name":"com.test.app","status":"open","slot_number":1,"target_testers":20,"target_days":14},
                            "is_member":false,
                            "is_actual_owner":true,
                            "can_manage":true,
                            "my_profile":{"id":"$ownerUserId","role":"member","credits":100},
                            "reports":[],
                            "testers":[],
                            "feedbacks":[],
                            "checked_in_confirmed_today":false
                        }"""
                    )
                }
                path.contains("get_campaign_mission") -> reply("""{"mission":null}""")
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> {
                    reply("""[{"id":"$testCampaignId","owner_id":"$ownerUserId","app_name":"Owner App","package_name":"com.test.app","status":"open"}]""")
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = OwnerAppDetailViewModel(testCampaignId, repo, localizer)
        vmsToCancel += vm

        // Wait for initial load
        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

        vm.deleteCampaign(
            onDeleted = { onDeletedFired.set(true) },
            onPendingRemoval = { onPendingFired.set(true) },
        )

        waitUntil { !vm.uiState.value.isActionLoading }

        assertFalse("onDeleted must NOT be called for pending_removal", onDeletedFired.get())
        assertTrue("onPendingRemoval MUST be called for pending_removal", onPendingFired.get())
        assertFalse(vm.uiState.value.isActionLoading)
        assertNotNull(vm.uiState.value.message)
    }

    @Test
    fun `CampaignDetailViewModel admin delete pending does NOT fire onDeleted but DOES fire onPendingRemoval`() = runBlocking {
        val onDeletedFired = AtomicBoolean(false)
        val onPendingFired = AtomicBoolean(false)

        val client = createTestClient(userId = adminUserId) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id":"$adminUserId","credits":100,"role":"admin"}]""")
                path.contains("admin_delete_campaign") -> {
                    reply(
                        """
                        {
                            "success": true,
                            "status": "pending_removal",
                            "campaign_id": "$testCampaignId",
                            "app_name": "Admin App",
                            "owner_id": "$ownerUserId"
                        }
                        """
                    )
                }
                path.contains("get_campaign_detail") -> {
                    reply(
                        """{
                            "campaign":{"id":"$testCampaignId","owner_id":"$ownerUserId","app_name":"Admin App","package_name":"com.test.app","status":"open","target_testers":20,"target_days":14},
                            "is_member":false,
                            "is_actual_owner":false,
                            "can_manage":true,
                            "my_profile":{"id":"$adminUserId","role":"admin","credits":100},
                            "reports":[],
                            "testers":[],
                            "feedbacks":[],
                            "checked_in_confirmed_today":false
                        }"""
                    )
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val submitManager = ReportSubmitManager(context)
        val vm = CampaignDetailViewModel(
            campaignId = testCampaignId,
            repository = repo,
            reportSubmitManager = submitManager,
            myUserId = adminUserId,
            localizer = localizer,
            appContext = context,
        )
        vmsToCancel += vm

        waitUntil { !vm.uiState.value.isLoading }

        vm.adminDeleteCampaign(
            reason = "fraud",
            onDeleted = { onDeletedFired.set(true) },
            onPendingRemoval = { onPendingFired.set(true) },
        )

        waitUntil { !vm.uiState.value.isActionLoading }

        assertFalse("onDeleted must NOT be called for admin pending_removal", onDeletedFired.get())
        assertTrue("onPendingRemoval MUST be called for admin pending_removal", onPendingFired.get())
        assertFalse(vm.uiState.value.isActionLoading)
        assertNotNull(vm.uiState.value.message)
    }

    @Test
    fun `server 500 error on owner delete does NOT fallback to table DELETE`() = runBlocking {
        var directDeleteAttempted = false

        val client = createTestClient(userId = ownerUserId) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id":"$ownerUserId","credits":100,"role":"member"}]""")
                path.contains("delete_owned_campaign") -> {
                    reply("""{"code":"500","message":"Internal Server Error"}""", HttpStatusCode.InternalServerError)
                }
                req.method.value == "DELETE" && path.contains("campaigns") -> {
                    directDeleteAttempted = true
                    reply("[]")
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        try {
            repo.deleteCampaign(testCampaignId)
            fail("Expected exception on HTTP 500")
        } catch (_: Exception) {}

        assertFalse("HTTP 500 error must NOT trigger table DELETE fallback", directDeleteAttempted)
    }

    @Test
    fun `genuine PGRST202 404 missing RPC triggers legacy table DELETE fallback`() = runBlocking {
        var directDeleteAttempted = false

        val client = createTestClient(userId = ownerUserId) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id":"$ownerUserId","credits":100,"role":"member"}]""")
                path.contains("delete_owned_campaign") -> {
                    reply("""{"code":"PGRST202","message":"Could not find the function public.delete_owned_campaign"}""", HttpStatusCode.NotFound)
                }
                req.method.value == "DELETE" && path.contains("campaigns") -> {
                    directDeleteAttempted = true
                    reply("[]")
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val result = repo.deleteCampaign(testCampaignId)

        assertTrue("Genuine missing RPC 404 must trigger table DELETE fallback", directDeleteAttempted)
        assertTrue(result.success)
        assertEquals("removed", result.status)
        assertTrue(result.isRemoved)
    }

    @Test
    fun `success false or unknown status does NOT acknowledge as removed`() = runBlocking {
        val onDeletedFired = AtomicBoolean(false)
        val onPendingFired = AtomicBoolean(false)

        val client = createTestClient(userId = ownerUserId) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id":"$ownerUserId","credits":100,"role":"member"}]""")
                path.contains("delete_owned_campaign") -> {
                    // Unknown status
                    reply(
                        """
                        {
                            "success": true,
                            "status": "some_unknown_status",
                            "campaign_id": "$testCampaignId"
                        }
                        """
                    )
                }
                path.contains("get_campaign_detail_sync_v2") -> {
                    reply("""{"code":"PGRST202","message":"Could not find the function public.get_campaign_detail_sync_v2"}""", HttpStatusCode.NotFound)
                }
                path.contains("get_campaign_detail") -> {
                    reply(
                        """{
                            "campaign":{"id":"$testCampaignId","owner_id":"$ownerUserId","app_name":"Owner App","package_name":"com.test.app","status":"open","slot_number":1,"target_testers":20,"target_days":14},
                            "is_member":false,
                            "is_actual_owner":true,
                            "can_manage":true,
                            "my_profile":{"id":"$ownerUserId","role":"member","credits":100},
                            "reports":[],
                            "testers":[],
                            "feedbacks":[],
                            "checked_in_confirmed_today":false
                        }"""
                    )
                }
                path.contains("get_campaign_mission") -> reply("""{"mission":null}""")
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> {
                    reply("""[{"id":"$testCampaignId","owner_id":"$ownerUserId","app_name":"Owner App","package_name":"com.test.app","status":"open"}]""")
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = OwnerAppDetailViewModel(testCampaignId, repo, localizer)
        vmsToCancel += vm

        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

        vm.deleteCampaign(
            onDeleted = { onDeletedFired.set(true) },
            onPendingRemoval = { onPendingFired.set(true) },
        )

        waitUntil { !vm.uiState.value.isActionLoading }

        assertFalse("onDeleted must NOT fire on unknown status", onDeletedFired.get())
        assertFalse("onPendingRemoval must NOT fire on unknown status", onPendingFired.get())
        assertNotNull("Error must be set on unknown status", vm.uiState.value.error)
    }

    @Test
    fun `delayed owner delete response arriving after account switch does NOT trigger navigation`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val onDeletedFired = AtomicBoolean(false)
        val onPendingFired = AtomicBoolean(false)

        val client = createTestClient(userId = ownerUserId) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("profiles") -> reply("""[{"id":"$ownerUserId","credits":100,"role":"member"}]""")
                path.contains("delete_owned_campaign") -> {
                    gate.await()
                    reply(
                        """
                        {
                            "success": true,
                            "status": "removed",
                            "campaign_id": "$testCampaignId"
                        }
                        """
                    )
                }
                path.contains("get_campaign_detail_sync_v2") -> {
                    reply("""{"code":"PGRST202","message":"Could not find the function public.get_campaign_detail_sync_v2"}""", HttpStatusCode.NotFound)
                }
                path.contains("get_campaign_detail") -> {
                    reply(
                        """{
                            "campaign":{"id":"$testCampaignId","owner_id":"$ownerUserId","app_name":"Owner App","package_name":"com.test.app","status":"open","slot_number":1,"target_testers":20,"target_days":14},
                            "is_member":false,
                            "is_actual_owner":true,
                            "can_manage":true,
                            "my_profile":{"id":"$ownerUserId","role":"member","credits":100},
                            "reports":[],
                            "testers":[],
                            "feedbacks":[],
                            "checked_in_confirmed_today":false
                        }"""
                    )
                }
                path.contains("get_campaign_mission") -> reply("""{"mission":null}""")
                path.contains("user_slots") -> reply("[]")
                path.contains("campaigns") -> {
                    reply("""[{"id":"$testCampaignId","owner_id":"$ownerUserId","app_name":"Owner App","package_name":"com.test.app","status":"open"}]""")
                }
                else -> reply("[]")
            }
        }

        val repo = CampaignRepository(client, localizer)
        val vm = OwnerAppDetailViewModel(testCampaignId, repo, localizer)
        vmsToCancel += vm

        waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.campaign != null }

        vm.deleteCampaign(
            onDeleted = { onDeletedFired.set(true) },
            onPendingRemoval = { onPendingFired.set(true) },
        )

        try {
            waitUntil { vm.uiState.value.isActionLoading }
            val operation = vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!
                .children.first { !it.isCompleted }

            // A stale result must finish without changing the new session's UI.
            SessionCacheManager.generation.incrementAndGet()
            gate.complete(Unit)
            waitUntil(timeoutMs = 1500) { operation.isCompleted }
            assertFalse("onDeleted must not be called after session generation change", onDeletedFired.get())
            assertFalse("onPendingRemoval must not be called after session generation change", onPendingFired.get())
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun `admin RPC actual deleted status is recognized as completed removal`() = runBlocking {
        var calledAdminRpc = false
        val client = createTestClient(userId = adminUserId) { request ->
            if (request.url.encodedPath.endsWith("/rpc/admin_delete_campaign")) {
                calledAdminRpc = true
                reply("""{"success":true,"status":"deleted","campaign_id":"$testCampaignId","owner_id":"$ownerUserId"}""")
            } else {
                reply("[]")
            }
        }
        val result = CampaignRepository(client, localizer).adminDeleteCampaign(testCampaignId, "Fixture removal")
        assertTrue(calledAdminRpc)
        assertTrue("The actual server admin completion state must navigate as completed", result.isRemoved)
        assertFalse(result.isPendingRemoval)
    }

    @Test
    fun `owner RPC missing status is unconfirmed and never falls back to table delete`() = runBlocking {
        var directDeleteAttempted = false
        val client = createTestClient { request ->
            when {
                request.method.value == "DELETE" -> {
                    directDeleteAttempted = true
                    reply("[]")
                }
                request.url.encodedPath.contains("profiles") ->
                    reply("""[{"id":"$ownerUserId","credits":100,"role":"member"}]""")
                request.url.encodedPath.endsWith("/rpc/delete_owned_campaign") ->
                    reply("""{"success":true,"campaign_id":"$testCampaignId","owner_id":"$ownerUserId"}""")
                else -> reply("[]")
            }
        }
        val result = runCatching { CampaignRepository(client, localizer).deleteCampaign(testCampaignId) }
        assertTrue("Missing owner status cannot acknowledge a completed or pending mutation", result.isFailure)
        assertFalse("An unconfirmed response is not a missing-RPC fallback", directDeleteAttempted)
    }
}
