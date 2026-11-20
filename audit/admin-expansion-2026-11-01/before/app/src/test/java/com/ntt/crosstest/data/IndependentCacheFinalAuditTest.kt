package com.ntt.crosstest.data

import android.app.Application
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IndependentCacheFinalAuditTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() {
        SessionCacheManager.clear(context)
    }

    @After
    fun tearDown() {
        SessionCacheManager.clear(context)
    }

    private fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )

    private suspend fun importUser(client: io.github.jan.supabase.SupabaseClient, userId: String) {
        val sessionJson = """
            {
                "access_token": "token-$userId",
                "refresh_token": "ref-$userId",
                "token_type": "bearer",
                "expires_in": 3600,
                "user": {
                    "id": "$userId",
                    "aud": "authenticated"
                }
            }
        """.trimIndent()
        client.auth.importSession(json.decodeFromString<UserSession>(sessionJson))
    }

    private fun createTestClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): io.github.jan.supabase.SupabaseClient {
        return createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
    }


    @Test
    fun guestStatsMustNotCarryIntoAuthenticatedUser() = runBlocking {
        val calls=AtomicInteger()
        val client=createTestClient { jsonResponse("""{"c1":{"has_tested_today":${calls.incrementAndGet()==1}}}""") }
        val repo=CampaignRepository(client,Localizer(context))
        assertTrue(repo.getCampaignsStatsMap(listOf("c1"))["c1"]!!.hasTestedToday)
        importUser(client,"userB")
        assertFalse("guest entry cannot be reused as B data",repo.getCampaignsStatsMap(listOf("c1"))["c1"]!!.hasTestedToday)
    }

    @Test
    fun managerReportsMustBeScopedToRequestingAccount() = runBlocking {
        val client=createTestClient { req ->
            if(req.url.toString().contains("get_campaign_reports_for_owner")) {
                if(req.headers[HttpHeaders.Authorization]?.contains("userA")==true)
                    jsonResponse("""[{"id":"private-report-A","campaign_id":"c1","user_id":"testerA"}]""")
                else jsonResponse("[]")
            } else jsonResponse("[]")
        }
        val repo=CampaignRepository(client,Localizer(context))
        importUser(client,"userA")
        assertEquals(1,repo.getCampaignReports("c1",canManage=true).size)
        importUser(client,"userB")
        assertTrue("B must fetch under own permission rather than reuse A manager cache",repo.getCampaignReports("c1",canManage=true).isEmpty())
    }

    @Test
    fun adminProfilesMustNotCarryIntoAnotherAccount() = runBlocking {
        val client=createTestClient { req ->
            if(req.headers[HttpHeaders.Authorization]?.contains("userA")==true)
                jsonResponse("""[{"id":"private-A","credits":100}]""") else jsonResponse("[]")
        }
        val repo=CampaignRepository(client,Localizer(context))
        importUser(client,"userA")
        assertEquals(1,repo.getAllProfiles().size)
        importUser(client,"userB")
        assertTrue("admin data must not survive account transition",repo.getAllProfiles().isEmpty())
    }

    @Test
    fun ownerStatsFailureMustNotOverwriteGoodCount() = runBlocking {
        var fail=false
        val client=createTestClient { req ->
            if(fail) jsonResponse("{}",HttpStatusCode.InternalServerError)
            else if(req.url.toString().contains("campaign_members")) jsonResponse("""[{"campaign_id":"c1","user_id":"testerA"}]""")
            else jsonResponse("[]")
        }
        val repo=CampaignRepository(client,Localizer(context))
        importUser(client,"userA")
        assertEquals(1,repo.getCampaignOwnerStats("c1").installedTestersCount)
        fail=true
        try { repo.getCampaignOwnerStats("c1",forceRefresh=true) } catch (_:Exception) {}
        fail=false
        assertEquals("network failures must not become fresh zero stats",1,repo.getCampaignOwnerStats("c1").installedTestersCount)
    }

    @Test
    fun homeFeed500MustNotLookLikeMissingRpc() = runBlocking {
        val client=createTestClient { jsonResponse("{}",HttpStatusCode.InternalServerError) }
        val repo=CampaignRepository(client,Localizer(context))
        var failure=false
        try { repo.getHomeFeed(forceRefresh=true) } catch (_:Exception) { failure=true }
        assertTrue("500 must surface, not return missing-RPC null and trigger legacy",failure)
    }

    @Test
    fun stats500MustNotManufactureSuccessfulZeroStats() = runBlocking {
        val client=createTestClient { jsonResponse("{}",HttpStatusCode.InternalServerError) }
        val repo=CampaignRepository(client,Localizer(context))
        var failure=false
        try { repo.getCampaignsStatsMap(listOf("c1")) } catch (_:Exception) { failure=true }
        assertTrue("RPC and legacy network failure must not become successful zero stats",failure)
    }

    @Test
    fun creditLogCoverageMustSliceRequestedLimit() = runBlocking {
        val client=createTestClient { req ->
            val amount=if(req.url.toString().contains("limit=50")) 50 else 20
            jsonResponse((1..amount).joinToString(",","[","]") { """{"id":"log-$it","user_id":"userA","amount":10,"action":"test","balance":100}""" })
        }
        val repo=CampaignRepository(client,Localizer(context))
        importUser(client,"userA")
        assertEquals(50,repo.getMyCreditLogs(limit=50).size)
        assertEquals("coverage reuse must respect requested limit",20,repo.getMyCreditLogs(limit=20).size)
    }

    @Test
    fun campaignUpdateMustInvalidateSlotInfoDependency() = runBlocking {
        var name="Old"
        val client=createTestClient { req ->
            when {
                req.method.value=="PATCH" -> { name="New"; jsonResponse("[]") }
                req.url.toString().contains("user_slots") -> jsonResponse("[]")
                req.url.toString().contains("campaigns") -> jsonResponse("""[{"id":"c1","owner_id":"userA","app_name":"$name","package_name":"pkg.c1","status":"open","slot_number":1}]""")
                else -> jsonResponse("[]")
            }
        }
        val repo=CampaignRepository(client,Localizer(context))
        importUser(client,"userA")
        assertEquals("Old",repo.getMySlotsInfo().first().currentCampaign?.appName)
        repo.updateCampaignDetails("c1","New","desc","play","test","u","p")
        assertEquals("slot info depends on campaign fields","New",repo.getMySlotsInfo().first().currentCampaign?.appName)
    }

    @Test
    fun previousDaySnapshotMustClearOwnerTodayStats() {
        val old=CachedSessionData(dateVn="2000-01-01",ownerStatsMap=mapOf("c1" to CampaignOwnerStats(testedTodayCount=7)))
        assertEquals("owner tested-today is daily data too",0,old.sanitizeForDate().ownerStatsMap["c1"]?.testedTodayCount ?: 0)
    }
}

