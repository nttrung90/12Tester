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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailViewModel

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CacheRegressionTest {

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

    // Probe 1: Đọc list hai lần trong TTL -> PASS: một request
    @Test
    fun probe01_readListTwiceWithinTtl_makesSingleNetworkCall() = runBlocking {
        val requests = AtomicInteger(0)
        val client = createTestClient {
            requests.incrementAndGet()
            jsonResponse("""[{"id":"c1","app_name":"App 1","package_name":"com.test.app1","status":"open"}]""")
        }
        val repo = CampaignRepository(client, Localizer(context))
        val r1 = repo.listOpenCampaigns()
        val r2 = repo.listOpenCampaigns()
        assertEquals(1, r1.size)
        assertEquals(1, r2.size)
        assertEquals(1, requests.get())
    }

    // Probe 2: Nhiều ordinary read cùng khóa -> PASS: một request (single-flight)
    @Test
    fun probe02_concurrentOrdinaryReads_coalesceToSingleCall() = runBlocking {
        val requests = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val client = createTestClient {
            requests.incrementAndGet()
            gate.await()
            jsonResponse("""[{"id":"c1","app_name":"App 1","package_name":"com.test.app1","status":"open"}]""")
        }
        val repo = CampaignRepository(client, Localizer(context))
        val d1 = async(Dispatchers.IO) { repo.listOpenCampaigns() }
        val d2 = async(Dispatchers.IO) { repo.listOpenCampaigns() }
        val d3 = async(Dispatchers.IO) { repo.listOpenCampaigns() }

        delay(50)
        gate.complete(Unit)

        val r1 = d1.await()
        val r2 = d2.await()
        val r3 = d3.await()
        assertEquals(1, r1.size)
        assertEquals(1, r2.size)
        assertEquals(1, r3.size)
        assertEquals(1, requests.get())
    }

    // Probe 3: Response paused rỗng thành công -> PASS: cache và dùng lại, một request
    @Test
    fun probe03_emptyPausedCampaignsSuccess_cachedAndReused() = runBlocking {
        val requests = AtomicInteger(0)
        val client = createTestClient {
            requests.incrementAndGet()
            jsonResponse("[]")
        }
        val repo = CampaignRepository(client, Localizer(context))
        val r1 = repo.listPausedCampaigns()
        val r2 = repo.listPausedCampaigns()
        assertEquals(0, r1.size)
        assertEquals(0, r2.size)
        assertEquals(1, requests.get())
    }

    // Probe 4: Profile A rồi chuyển session B -> FAIL trên baseline: nhận profile A thay vì B (F01)
    @Test
    fun probe04_profileA_thenProfileB_neverReturnsProfileAForUserB() = runBlocking {
        val client = createTestClient { req ->
            val url = req.url.toString()
            if (url.contains("userA")) {
                jsonResponse("""[{"id":"userA","credits":100}]""")
            } else if (url.contains("userB")) {
                jsonResponse("""[{"id":"userB","credits":50}]""")
            } else {
                jsonResponse("[]")
            }
        }
        val repo = CampaignRepository(client, Localizer(context))

        importUser(client, "userA")
        val profA = repo.getMyProfile()
        assertEquals("userA", profA?.id)
        assertEquals(100, profA?.credits)

        // Đổi session sang userB
        importUser(client, "userB")
        val profB = repo.getMyProfile(forceRefresh = false)
        assertNotNull(profB)
        assertEquals("userB", profB?.id)
        assertEquals(50, profB?.credits)
    }

    // Probe 5: Response bắt đầu trước clear, trả về sau clear -> FAIL trên baseline: đọc tiếp vẫn nhận old-response (F01/F05)
    @Test
    fun probe05_inFlightReadBeforeClear_completingAfterClear_doesNotRepopulateCache() = runBlocking {
        val requests = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val client = createTestClient {
            val count = requests.incrementAndGet()
            if (count == 1) {
                gate.await()
                jsonResponse("""[{"id":"old-c","app_name":"Old App","package_name":"com.test.old","status":"open"}]""")
            } else {
                jsonResponse("""[{"id":"new-c","app_name":"New App","package_name":"com.test.new","status":"open"}]""")
            }
        }
        val repo = CampaignRepository(client, Localizer(context))

        val inFlight = async(Dispatchers.IO) { repo.listOpenCampaigns() }
        delay(50) // chờ request 1 bắt đầu và chạm gate

        // Clear cache trong khi request 1 đang chờ
        repo.clearMemoryCache()

        // Thả gate cho request 1 hoàn thành
        gate.complete(Unit)
        inFlight.await()

        // Lần đọc tiếp theo sau clear: KHÔNG ĐƯỢC dùng lại kết quả của request 1!
        val next = repo.listOpenCampaigns(forceRefresh = false)
        assertEquals(2, requests.get())
        assertEquals("new-c", next.first().id)
    }

    // Probe 6: Save snapshot bị tạm dừng rồi clear -> FAIL trên baseline: old-user xuất hiện lại từ file (F02)
    @Test
    fun probe06_inFlightSaveSnapshot_thenClear_doesNotReviveOldSessionOnDisk() = runBlocking {
        val dataA = CachedSessionData(userId = "userA", userCredits = 100)
        val saveJob = launch(Dispatchers.IO) {
            SessionCacheManager.save(context, dataA)
        }
        // Đồng thời clear
        SessionCacheManager.clear(context)
        saveJob.join()

        // Snapshot không được phục hồi userA
        val loaded = SessionCacheManager.loadSync(context)
        assertNull("Snapshot của userA không được xuất hiện sau clear", loaded)
        assertFalse(SessionCacheManager.hasCachedSession(context))
    }

    // Probe 7: Mở slot đổi số dư 100->80 -> FAIL trên baseline: getMyProfile(false) vẫn trả 100 (F03)
    @Test
    fun probe07_unlockSlot_mutatesBalance_getMyProfileFalse_returnsUpdatedBalance() = runBlocking {
        var userCredits = 100
        val client = createTestClient { req ->
            val url = req.url.toString()
            if (url.contains("/rpc/unlock_or_renew_slot")) {
                userCredits = 80
                jsonResponse("""{"success":true,"slot_number":2,"cost":20,"balance":80}""")
            } else if (url.contains("profiles")) {
                jsonResponse("""[{"id":"userA","credits":$userCredits}]""")
            } else if (url.contains("user_slots")) {
                jsonResponse("""[{"slot_number":1,"user_id":"userA"}]""")
            } else {
                jsonResponse("[]")
            }
        }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")

        val p1 = repo.getMyProfile()
        assertEquals(100, p1?.credits)

        // Mở slot
        val unlockResult = repo.unlockOrRenewSlot(2)
        assertTrue(unlockResult.success)

        // Đọc lại với forceRefresh = false -> Phải ra 80, KHÔNG ĐƯỢC trả 100 từ profileCache!
        val p2 = repo.getMyProfile(forceRefresh = false)
        assertEquals(80, p2?.credits)
    }

    // Probe 8: Join campaign có cache testers trước đó -> FAIL trên baseline: entry testers vẫn tồn tại (F03)
    @Test
    fun probe08_joinCampaign_invalidatesTestersCache() = runBlocking {
        var membersCount = 1
        val client = createTestClient { req ->
            val url = req.url.toString()
            when {
                req.method.value == "POST" && url.contains("campaign_members") -> {
                    membersCount = 2
                    jsonResponse("""{"campaign_id":"c1","user_id":"userA"}""")
                }
                url.contains("campaign_members") -> {
                    if (membersCount == 1) {
                        jsonResponse("""[{"campaign_id":"c1","user_id":"tester1"}]""")
                    } else {
                        jsonResponse("""[{"campaign_id":"c1","user_id":"tester1"},{"campaign_id":"c1","user_id":"userA"}]""")
                    }
                }
                url.contains("profiles") -> {
                    jsonResponse("""[{"id":"tester1","display_name":"Tester 1"},{"id":"userA","display_name":"User A"}]""")
                }
                url.contains("checkins") -> jsonResponse("[]")
                else -> jsonResponse("[]")
            }
        }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")

        val t1 = repo.getCampaignTesters("c1")
        assertEquals(1, t1.size)

        repo.joinCampaign("c1")

        // Sau khi join, testersCache của c1 phải bị xóa -> đọc lại phải ra 2
        val t2 = repo.getCampaignTesters("c1", forceRefresh = false)
        assertEquals(2, t2.size)
    }

    // Probe 9: Xóa campaign có single-item cache -> FAIL trên baseline: đọc lại còn trả campaign đã xóa (F03)
    @Test
    fun probe09_deleteCampaign_invalidatesSingleItemAndListCache() = runBlocking {
        var exists = true
        val client = createTestClient { req ->
            val url = req.url.toString()
            when {
                req.method.value == "DELETE" && url.contains("campaigns") -> {
                    exists = false
                    jsonResponse("[]")
                }
                url.contains("campaigns") -> {
                    if (exists) {
                        jsonResponse("""[{"id":"c1","owner_id":"userA","app_name":"App 1","package_name":"com.test.app1","status":"open"}]""")
                    } else {
                        jsonResponse("[]")
                    }
                }
                url.contains("profiles") -> jsonResponse("""[{"id":"userA","credits":100,"role":"member"}]""")
                else -> jsonResponse("[]")
            }
        }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")

        val c1 = repo.getCampaign("c1")
        assertNotNull(c1)
        assertEquals("c1", c1?.id)

        repo.deleteCampaign("c1")

        // getCampaign("c1") sau khi xóa KHÔNG ĐƯỢC trả lại campaign từ campaignCache
        val c2 = repo.getCampaign("c1", forceRefresh = false)
        assertNull("Campaign đã xóa không được xuất hiện từ cache", c2)
    }

    // Probe 10: Có paused list tốt rồi forced refresh lỗi mạng -> FAIL trên baseline: bản tốt bị thay bằng empty (F04)
    @Test
    fun probe10_networkFailure_doesNotOverwriteExistingCachedListWithEmpty() = runBlocking {
        var shouldFail = false
        val client = createTestClient {
            if (shouldFail) {
                respond("Network Error", HttpStatusCode.InternalServerError)
            } else {
                jsonResponse("""[{"id":"p1","app_name":"Paused App","package_name":"com.test.paused","status":"paused"}]""")
            }
        }
        val repo = CampaignRepository(client, Localizer(context))

        // Lần 1: tải thành công
        val initial = repo.listPausedCampaigns()
        assertEquals(1, initial.size)

        // Lần 2: mạng lỗi khi forceRefresh
        shouldFail = true
        try {
            repo.listPausedCampaigns(forceRefresh = true)
        } catch (_: Exception) {
            // Mong đợi lỗi hoặc ném exception, không được âm thầm cache emptyList()
        }

        // Lần 3: đọc lại với forceRefresh = false -> Không được biến thành empty do cache lỗi!
        val cached = repo.listPausedCampaigns(forceRefresh = false)
        assertEquals(1, cached.size)
        assertEquals("p1", cached.first().id)
    }

    // Probe 11: Ba forced refresh đồng thời -> FAIL trên baseline: ba request thay vì một (F05)
    @Test
    fun probe11_concurrentForcedRefreshes_coalesceToSingleFlight() = runBlocking {
        val requests = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val client = createTestClient {
            requests.incrementAndGet()
            gate.await()
            jsonResponse("""[{"id":"c1","app_name":"App 1","package_name":"com.test.app1","status":"open"}]""")
        }
        val repo = CampaignRepository(client, Localizer(context))

        val d1 = async(Dispatchers.IO) { repo.listOpenCampaigns(forceRefresh = true) }
        val d2 = async(Dispatchers.IO) { repo.listOpenCampaigns(forceRefresh = true) }
        val d3 = async(Dispatchers.IO) { repo.listOpenCampaigns(forceRefresh = true) }

        delay(50)
        gate.complete(Unit)

        d1.await()
        d2.await()
        d3.await()

        assertEquals("Ba concurrent forced refresh phải được gộp thành 1 network request", 1, requests.get())
    }

    // Probe 12: Lịch sử Credit tải 20 rồi yêu cầu 50 -> FAIL trên baseline: chỉ trả 20 dòng dù server có 50 (F08)
    @Test
    fun probe12_creditLogs_differentLimits_doNotReturnTruncatedCache() = runBlocking {
        val client = createTestClient { req ->
            val url = req.url.toString()
            val limit = if (url.contains("limit=50")) 50 else 20
            val logs = (1..limit).map { i ->
                """{"id":"log-$i","user_id":"userA","amount":10,"action":"test","balance":100}"""
            }.joinToString(",", "[", "]")
            jsonResponse(logs)
        }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")

        val logs20 = repo.getMyCreditLogs(limit = 20)
        assertEquals(20, logs20.size)

        val logs50 = repo.getMyCreditLogs(limit = 50)
        assertEquals("Yêu cầu 50 dòng không được trả lại cache 20 dòng", 50, logs50.size)
    }

    // Probe 13 (R02): Stats are scoped to account
    @Test
    fun statsAreScopedToAccount() = runBlocking {
        val calls = AtomicInteger()
        val client = createTestClient {
            val tested = calls.incrementAndGet() == 1
            jsonResponse("""{"c1":{"has_tested_today":$tested}}""")
        }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")
        assertTrue(repo.getCampaignsStatsMap(listOf("c1"))["c1"]!!.hasTestedToday)
        importUser(client, "userB")
        assertFalse("B must not inherit A's today status", repo.getCampaignsStatsMap(listOf("c1"))["c1"]!!.hasTestedToday)
    }

    // Probe 14 (R05): Different stats ID sets must not share flight result
    @Test
    fun differentStatsIdSetsMustNotShareFlightResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = createTestClient {
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                release.await()
                jsonResponse("""{"c1":{"installed_count":1}}""")
            } else jsonResponse("""{"c2":{"installed_count":2}}""")
        }
        val repo = CampaignRepository(client, Localizer(context))
        val first = async(start = CoroutineStart.UNDISPATCHED) { repo.getCampaignsStatsMap(listOf("c1")) }
        withTimeout(5000) { started.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { repo.getCampaignsStatsMap(listOf("c2")) }
        release.complete(Unit)
        first.await()
        assertEquals("c2 caller must receive c2 stats", setOf("c2"), second.await().keys)
    }

    // Probe 15 (R03): Feed started before clear must not repopulate child cache
    @Test
    fun feedStartedBeforeClearMustNotRepopulateChildCache() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = createTestClient { req ->
            if (req.url.toString().contains("get_home_feed")) {
                started.complete(Unit)
                release.await()
                jsonResponse("""{"open":[{"id":"old-c","app_name":"Old","package_name":"old.pkg"}]}""")
            } else jsonResponse("""[{"id":"new-c","app_name":"New","package_name":"new.pkg"}]""")
        }
        val repo = CampaignRepository(client, Localizer(context))
        val old = async(start = CoroutineStart.UNDISPATCHED) { repo.getHomeFeed() }
        withTimeout(5000) { started.await() }
        repo.clearMemoryCache()
        release.complete(Unit)
        old.await()
        assertEquals("clear must reject old feed from child list cache", "new-c", repo.listOpenCampaigns().first().id)
    }

    // Probe 16 (R01): Notifications failure for B must not return A data
    @Test
    fun notificationsFailureForBMustNotReturnAData() = runBlocking {
        val client = createTestClient { req ->
            if (req.url.toString().contains("userA")) jsonResponse("""[{"id":"a-notif","user_id":"userA"}]""")
            else jsonResponse("{}", HttpStatusCode.InternalServerError)
        }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")
        assertEquals("a-notif", repo.getMyNotifications().first().id)
        importUser(client, "userB")
        val result = try { repo.getMyNotifications() } catch (e: Exception) { emptyList() }
        assertTrue("B must never receive A notifications on network failure", result.none { it.userId == "userA" })
    }

    // Probe 17 (R06): Failed cold count must not cache zero as success
    @Test
    fun failedColdCountMustNotCacheZeroAsSuccess() = runBlocking {
        val calls = AtomicInteger()
        val client = createTestClient {
            calls.incrementAndGet()
            jsonResponse("{}", HttpStatusCode.InternalServerError)
        }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")
        try { repo.getTodayCheckinCount() } catch (_: Exception) {}
        val afterFirst = calls.get()
        try { repo.getTodayCheckinCount() } catch (_: Exception) {}
        assertTrue("second read must retry failed count rather than reuse zero", calls.get() > afterFirst)
    }

    // Probe 18 (R06): Profile network failure must surface as failure
    @Test
    fun profileNetworkFailureMustSurfaceAsFailure() = runBlocking {
        val client = createTestClient { jsonResponse("{}", HttpStatusCode.InternalServerError) }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")
        var failed = false
        try { repo.getMyProfile(forceRefresh = true) } catch (_: Exception) { failed = true }
        assertTrue("network failure is not missing profile / unauthenticated", failed)
    }

    // Probe 19 (R09): Memory peek must sanitize previous day
    @Test
    fun memoryPeekMustSanitizePreviousDay() = runBlocking {
        SessionCacheManager.save(context, CachedSessionData(userId = "userA", todayCheckinCount = 9, dateVn = "2000-01-01"))
        assertEquals("Home peek must not display yesterday as today", 0, SessionCacheManager.peekMemory()!!.todayCheckinCount)
    }

    // Audit Probes (B01 - B08)
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

    @Test
    fun oldNotificationsMustNotReviveAfterReadMutationAndNetworkFailure() = runBlocking {
        val started=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val reads=AtomicInteger()
        val client=createTestClient { req ->
            if(req.method.value=="PATCH") jsonResponse("[]")
            else if(reads.incrementAndGet()==1) {
                started.complete(Unit)
                release.await()
                jsonResponse("""[{"id":"n1","user_id":"userA","is_read":false}]""")
            } else jsonResponse("{}",HttpStatusCode.InternalServerError)
        }
        val repo=CampaignRepository(client,Localizer(context))
        importUser(client,"userA")
        val before=async(start=CoroutineStart.UNDISPATCHED) { try { repo.getMyNotifications() } catch (_:Exception) { emptyList() } }
        withTimeout(5000) { started.await() }
        repo.markAllNotificationsAsRead()
        release.complete(Unit)
        before.await()
        val after=try { repo.getMyNotifications() } catch (_:Exception) { emptyList() }
        assertTrue("response before read mutation must not be served as unread fallback",after.none { !it.isRead })
    }

    @Test
    fun ordinaryAndForceSameVersionMustShareFreshInFlightRead() = runBlocking {
        val started=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val reads=AtomicInteger()
        val client=createTestClient {
            reads.incrementAndGet()
            started.complete(Unit)
            release.await()
            jsonResponse("[]")
        }
        val repo=CampaignRepository(client,Localizer(context))
        val ordinary=async(start=CoroutineStart.UNDISPATCHED) { repo.listOpenCampaigns() }
        withTimeout(5000) { started.await() }
        val forced=async(start=CoroutineStart.UNDISPATCHED) { repo.listOpenCampaigns(forceRefresh=true) }
        release.complete(Unit)
        ordinary.await()
        forced.await()
        assertEquals("same scope/version fresh network flight should satisfy ordinary and force",1,reads.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ownerDetailMustRecheckAccountAfterLoadingSlots() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val enteredSlots=CompletableDeferred<Unit>()
        val releaseSlots=CompletableDeferred<Unit>()
        val client=createTestClient { req ->
            when {
                req.url.toString().contains("get_campaign_detail") -> jsonResponse("""{"campaign":{"id":"c1","owner_id":"userA","app_name":"A private app","package_name":"pkg.c1","slot_number":1},"can_manage":true,"is_actual_owner":true}""")
                req.url.toString().contains("get_campaign_mission") -> jsonResponse("""{"mission":null}""")
                req.url.toString().contains("user_slots") -> {
                    enteredSlots.complete(Unit)
                    releaseSlots.await()
                    jsonResponse("[]")
                }
                else -> jsonResponse("[]")
            }
        }
        importUser(client,"userA")
        val repo=CampaignRepository(client,Localizer(context))
        val vm=OwnerAppDetailViewModel("c1",repo,Localizer(context))

        try {
            withTimeout(5000) { enteredSlots.await() }
            importUser(client,"userB")
            releaseSlots.complete(Unit)
            withTimeout(5000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertFalse("old A detail must not publish after slots await changed account to B",vm.uiState.value.campaign?.ownerId=="userA")
        } finally {
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun feedbackOnlyFailureMustNotOverwriteGoodAverageRating() = runBlocking {
        var failFeedback = false
        val client = createTestClient { req ->
            if (req.url.toString().contains("feedbacks")) {
                if (failFeedback) jsonResponse("{}", HttpStatusCode.InternalServerError)
                else jsonResponse("""[{"campaign_id":"c1","user_id":"testerA","rating":5}]""")
            } else jsonResponse("[]")
        }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")
        assertEquals(5f, repo.getCampaignOwnerStats("c1").averageRating!!, 0f)
        failFeedback = true
        try { repo.getCampaignOwnerStats("c1", forceRefresh = true) } catch (_: Exception) {}
        failFeedback = false
        assertEquals("failed component must not cache successful null average", 5f, repo.getCampaignOwnerStats("c1").averageRating ?: -1f, 0f)
    }

    @Test
    fun checkinOnlyFailureMustNotOverwriteTesterHistoryWithZero() = runBlocking {
        var failCheckins = false
        val client = createTestClient { req ->
            when {
                req.url.toString().contains("campaign_members") -> jsonResponse("""[{"campaign_id":"c1","user_id":"testerA"}]""")
                req.url.toString().contains("checkins") -> {
                    if (failCheckins) jsonResponse("{}", HttpStatusCode.InternalServerError)
                    else jsonResponse("""[{"campaign_id":"c1","user_id":"testerA","checkin_date":"2026-10-27"}]""")
                }
                else -> jsonResponse("[]")
            }
        }
        val repo = CampaignRepository(client, Localizer(context))
        importUser(client, "userA")
        assertEquals(1, repo.getCampaignTesters("c1").first().checkinCount)
        failCheckins = true
        try { repo.getCampaignTesters("c1", forceRefresh = true) } catch (_: Exception) {}
        failCheckins = false
        assertEquals("failed checkins must not cache successful zero history", 1, repo.getCampaignTesters("c1").first().checkinCount)
    }

    @Test
    fun businessDayClockAdvancementMustInvalidateTodayCache() = runBlocking {
        var currentDay = java.time.LocalDate.of(2026, 10, 27)
        val checkinCalls = AtomicInteger()
        val client = createTestClient { req ->
            if (req.url.toString().contains("checkins")) {
                checkinCalls.incrementAndGet()
                jsonResponse("""[{"id":"ch1","user_id":"userA","campaign_id":"c1","checkin_date":"$currentDay"}]""")
            } else {
                jsonResponse("[]")
            }
        }
        val repo = CampaignRepository(
            supabase = client,
            localizer = Localizer(context),
            businessDayClock = { currentDay }
        )
        importUser(client, "userA")
        assertEquals(1, repo.getTodayCheckinCount())
        assertEquals(1, checkinCalls.get())

        // Read again same day -> cached
        assertEquals(1, repo.getTodayCheckinCount())
        assertEquals(1, checkinCalls.get())

        // Clock moves to next day -> must not return stale day's count
        currentDay = java.time.LocalDate.of(2026, 10, 28)
        assertEquals(1, repo.getTodayCheckinCount())
        assertEquals(2, checkinCalls.get())
    }

    @Test
    fun inFlightRequestAcrossMidnightStampsStartDay() = runBlocking {
        var currentDay = java.time.LocalDate.of(2026, 10, 27)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = createTestClient { req ->
            if (req.url.toString().contains("checkins")) {
                started.complete(Unit)
                release.await()
                jsonResponse("""[{"id":"ch1","user_id":"userA","campaign_id":"c1","checkin_date":"2026-10-27"}]""")
            } else {
                jsonResponse("[]")
            }
        }
        val repo = CampaignRepository(
            supabase = client,
            localizer = Localizer(context),
            businessDayClock = { currentDay }
        )
        importUser(client, "userA")
        val job = async { repo.getTodayCheckinCount() }
        started.await()
        // Midnight passes before response finishes
        currentDay = java.time.LocalDate.of(2026, 10, 28)
        release.complete(Unit)
        job.await()

        // Subsequent query on new day must not reuse the 2026-10-27 result
        val calls = AtomicInteger()
        val client2 = createTestClient {
            calls.incrementAndGet()
            jsonResponse("[]")
        }
        val repo2 = CampaignRepository(
            supabase = client2,
            localizer = Localizer(context),
            businessDayClock = { currentDay }
        )
        importUser(client2, "userA")
        assertEquals(0, repo2.getTodayCheckinCount())
    }

    @Test
    fun packageEventUpdatesVersionFlowAndInvalidatesCache() {
        com.ntt.crosstest.util.InstalledAppCache.refreshPackages(context)
        val initialVersion = com.ntt.crosstest.util.InstalledAppCache.version.get()
        com.ntt.crosstest.util.InstalledAppCache.onPackageAdded("com.example.testapp")
        assertTrue(com.ntt.crosstest.util.InstalledAppCache.version.get() > initialVersion)
        assertEquals(com.ntt.crosstest.util.InstalledAppCache.version.get(), com.ntt.crosstest.util.InstalledAppCache.versionFlow.value)
        assertTrue(com.ntt.crosstest.util.InstalledAppCache.isInstalled(context, "com.example.testapp"))

        com.ntt.crosstest.util.InstalledAppCache.onPackageRemoved("com.example.testapp")
        assertFalse(com.ntt.crosstest.util.InstalledAppCache.isInstalled(context, "com.example.testapp"))
    }
}
