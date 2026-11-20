package com.ntt.crosstest.data

import android.app.Application
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Reviewer-owned behavioral tests verifying candidate correctness, legacy missing-RPC
 * behavior, mutation refresh snapshot retention, honest telemetry HTTP counting,
 * single-flight coalescence, trigger metadata preservation, and capability TTL expiration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EgressFinalClientReviewTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 11, 10)
    private val campaign = "f9000000-0000-0000-0000-000000000004"

    private var originalHomeFlag = false
    private var originalCandidatesFlag = false
    private var originalDetailFlag = false
    private var originalDashboardFlag = false
    private var originalClock: () -> Long = { 0L }

    @Before
    fun prepare() {
        originalHomeFlag = EgressOptimizationConfig.isHomeFeedSyncV2Enabled
        originalCandidatesFlag = EgressOptimizationConfig.isCandidatesV2Enabled
        originalDetailFlag = EgressOptimizationConfig.isCampaignDetailSyncV2Enabled
        originalDashboardFlag = EgressOptimizationConfig.isDashboardSyncV2Enabled
        originalClock = EgressOptimizationConfig.clock

        EgressOptimizationConfig.resetCapabilityCache()
        EgressOptimizationConfig.setAllEnabled(false)
        EgressTelemetry.resetStats()
        SessionCacheManager.clear(context)
    }

    @After
    fun cleanup() {
        EgressOptimizationConfig.isHomeFeedSyncV2Enabled = originalHomeFlag
        EgressOptimizationConfig.isCandidatesV2Enabled = originalCandidatesFlag
        EgressOptimizationConfig.isCampaignDetailSyncV2Enabled = originalDetailFlag
        EgressOptimizationConfig.isDashboardSyncV2Enabled = originalDashboardFlag
        EgressOptimizationConfig.clock = originalClock

        EgressOptimizationConfig.resetCapabilityCache()
        EgressTelemetry.resetStats()
        SessionCacheManager.clear(context)
    }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                autoLoadFromStorage = false
                autoSaveToStorage = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun requestTokens(request: HttpRequestData): JsonObject {
        val text = when (val body = request.body) {
            is TextContent -> body.text
            is ByteArrayContent -> body.bytes().toString(Charsets.UTF_8)
            else -> error("Unexpected request body ${body::class}")
        }
        val parsed = Json.parseToJsonElement(text).jsonObject
        return parsed["p_tokens"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun candidatesJson(count: Int): String {
        val items = (1..count).joinToString(",") { i ->
            val id = "c0000000-0000-0000-0000-" + i.toString().padStart(12, '0')
            """{
              "campaign": {
                "id": "$id",
                "owner_id": "owner-$i",
                "app_name": "App $i",
                "package_name": "fixture.app$i",
                "play_url": "https://play.google.com/store/apps/details?id=fixture.app$i",
                "status": "open",
                "target_testers": 20,
                "target_days": 14
              },
              "installed_count": $i
            }"""
        }
        return "[$items]"
    }

    private fun fullHomeResponse(tag: String, statValue: Int = 1): String {
        val token = if (tag.length == 1 && tag[0] in "abcdef0123456789") tag.repeat(64)
            else java.security.MessageDigest.getInstance("SHA-256")
                .digest(tag.toByteArray()).joinToString("") { "%02x".format(it) }
        return """{
          "schema_version":2,"business_date":"$day","blocks":{
            "open":{"token":"$token","changed":true,"value":[{"id":"$campaign","app_name":"$tag","package_name":"fixture.$tag","owner_id":"other-owner"}]},
            "paused":{"token":"$token","changed":true,"value":[]},
            "completed":{"token":"$token","changed":true,"value":[]},
            "owner_names":{"token":"$token","changed":true,"value":{}},
            "stats":{"token":"$token","changed":true,"value":{"$campaign":{"installed_count":$statValue,"today_tested_count":0,"has_tested_today":false,"stats_date":"$day"}}}
          }
        }""".trimIndent()
    }

    @Test
    fun fifteenCandidatesReturnedUnchanged_v2Only_noHomeRequest() = runBlocking {
        withTimeout(15_000) {
            EgressOptimizationConfig.isCandidatesV2Enabled = true
            val candidateCalls = AtomicInteger()
            val homeCalls = AtomicInteger()
            val api = client { request ->
                val path = request.url.encodedPath
                if (path.endsWith("/get_next_campaign_candidates_v2")) {
                    candidateCalls.incrementAndGet()
                    reply(candidatesJson(15))
                } else if (path.contains("home_feed")) {
                    homeCalls.incrementAndGet()
                    reply(fullHomeResponse("home"))
                } else {
                    error("Unexpected request: $path")
                }
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })
                val result = repo.getNextCampaignCandidates("exclude-none")
                assertEquals("All 15 candidates returned without truncation", 15, result.size)
                assertEquals("App 1", result[0].campaign.appName)
                assertEquals("fixture.app1", result[0].campaign.packageName)
                assertEquals(1, result[0].installedCount)
                assertEquals("App 15", result[14].campaign.appName)
                assertEquals(15, result[14].installedCount)
                assertEquals("Candidate v2 RPC must be invoked once", 1, candidateCalls.get())
                assertEquals("Home feed must not be requested when candidate RPC succeeds", 0, homeCalls.get())
                assertEquals("Total network attempts must be exactly 1", 1L, EgressTelemetry.getStats()["totalRequests"])
                assertTrue("Decoded bytes must be honestly measured", EgressTelemetry.getStats()["totalEstimatedDecodedBytes"]!! > 0L)
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun candidateForbiddenServerErrorMalformed_doesNotSilentlyFallbackOrMarkMissing() = runBlocking {
        withTimeout(15_000) {
            EgressOptimizationConfig.isCandidatesV2Enabled = true

            // 403 Forbidden
            val api403 = client { request ->
                if (request.url.encodedPath.endsWith("/get_next_campaign_candidates_v2")) {
                    reply("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden)
                } else {
                    error("Must not fallback on 403")
                }
            }
            try {
                val repo = CampaignRepository(api403, Localizer(context), businessDayClock = { day })
                val res = runCatching { repo.getNextCampaignCandidates("test-id") }
                assertTrue("403 must propagate error, not fallback", res.isFailure)
                assertTrue("403 must not mark capability unavailable", EgressOptimizationConfig.isRpcAvailable("get_next_campaign_candidates_v2"))
            } finally {
                api403.close()
            }

            // 500 Server Error
            val api500 = client { request ->
                if (request.url.encodedPath.endsWith("/get_next_campaign_candidates_v2")) {
                    reply("""{"message":"Internal Server Error"}""", HttpStatusCode.InternalServerError)
                } else {
                    error("Must not fallback on 500")
                }
            }
            try {
                val repo = CampaignRepository(api500, Localizer(context), businessDayClock = { day })
                val res = runCatching { repo.getNextCampaignCandidates("test-id") }
                assertTrue("500 must propagate error, not fallback", res.isFailure)
                assertTrue("500 must not mark capability unavailable", EgressOptimizationConfig.isRpcAvailable("get_next_campaign_candidates_v2"))
            } finally {
                api500.close()
            }

            // Malformed JSON
            val apiMalformed = client { request ->
                if (request.url.encodedPath.endsWith("/get_next_campaign_candidates_v2")) {
                    reply("""{"error":"unexpected_envelope"}""", HttpStatusCode.OK)
                } else {
                    error("Must not fallback on malformed response")
                }
            }
            try {
                val repo = CampaignRepository(apiMalformed, Localizer(context), businessDayClock = { day })
                val res = runCatching { repo.getNextCampaignCandidates("test-id") }
                assertTrue("Malformed response must propagate deserialization error", res.isFailure)
                assertTrue("Deserialization error must not mark capability unavailable", EgressOptimizationConfig.isRpcAvailable("get_next_campaign_candidates_v2"))
            } finally {
                apiMalformed.close()
            }
        }
    }

    @Test
    fun genuinePgrst202OnCandidateEndpoint_legacyHomeFallback_negativeTtlCaches_expiryRetries() = runBlocking {
        withTimeout(15_000) {
            EgressOptimizationConfig.isCandidatesV2Enabled = true
            EgressOptimizationConfig.isHomeFeedSyncV2Enabled = true
            var simulatedNow = 100_000L
            EgressOptimizationConfig.clock = { simulatedNow }

            val candidateAttempts = AtomicInteger()
            val homeAttempts = AtomicInteger()
            val api = client { request ->
                val path = request.url.encodedPath
                if (path.endsWith("/get_next_campaign_candidates_v2")) {
                    candidateAttempts.incrementAndGet()
                    reply("""{"code":"PGRST202","message":"Could not find the function public.get_next_campaign_candidates_v2() in the schema cache"}""", HttpStatusCode.NotFound)
                } else if (path.endsWith("/get_home_feed_sync_v2")) {
                    homeAttempts.incrementAndGet()
                    reply(fullHomeResponse("c3"))
                } else {
                    error("Unexpected call $path")
                }
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })

                // Call 1: candidate fails PGRST202 -> marks unavailable -> fallback to Home
                val res1 = repo.getNextCampaignCandidates("none")
                assertEquals(1, res1.size)
                assertEquals("Candidate probe attempt must be 1", 1, candidateAttempts.get())
                assertEquals("Fallback Home attempt must be 1", 1, homeAttempts.get())
                assertEquals("Total requests must equal candidate probe + Home fallback", 2L, EgressTelemetry.getStats()["totalRequests"])

                // Call 2 within negative TTL: candidate probe skipped, directly calls Home
                val res2 = repo.getNextCampaignCandidates("none")
                assertEquals(1, res2.size)
                assertEquals("Candidate probe must be skipped within negative TTL", 1, candidateAttempts.get())
                assertEquals("Home feed called for fallback", 2, homeAttempts.get())
                assertEquals("Total requests incremented by 1 (Home only)", 3L, EgressTelemetry.getStats()["totalRequests"])

                // Advance clock past NEGATIVE_CAPABILITY_TTL_MS
                simulatedNow += EgressOptimizationConfig.NEGATIVE_CAPABILITY_TTL_MS + 1000L

                // Call 3 after expiry: candidate probe retried
                val res3 = repo.getNextCampaignCandidates("none")
                assertEquals(1, res3.size)
                assertEquals("Candidate probe must retry after negative TTL expiry", 2, candidateAttempts.get())
                assertEquals("Home feed called again after candidate probe failure", 3, homeAttempts.get())
                assertEquals("Observed totalRequests equals actual mock HTTP attempts (including probes + fallback)", 5L, EgressTelemetry.getStats()["totalRequests"])
                assertEquals((candidateAttempts.get() + homeAttempts.get()).toLong(), EgressTelemetry.getStats()["totalRequests"])
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun candidateStaleGenerationOrScopeChange_rejectsResult() = runBlocking {
        withTimeout(15_000) {
            EgressOptimizationConfig.isCandidatesV2Enabled = true
            val candidateEntered = CompletableDeferred<Unit>()
            val releaseCandidate = CompletableDeferred<Unit>()
            val api = client { request ->
                if (request.url.encodedPath.endsWith("/get_next_campaign_candidates_v2")) {
                    candidateEntered.complete(Unit)
                    releaseCandidate.await()
                    reply(candidatesJson(5))
                } else {
                    error("Unexpected request")
                }
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })
                val deferred = async(Dispatchers.IO) { repo.getNextCampaignCandidates("none") }
                candidateEntered.await()

                // Session generation bumped / cache cleared during in-flight network call
                repo.clearMemoryCache()
                releaseCandidate.complete(Unit)

                val candidates = deferred.await()
                assertTrue("Stale session generation result must be rejected to avoid cross-session contamination", candidates.isEmpty())
            } finally {
                releaseCandidate.complete(Unit)
                api.close()
            }
        }
    }

    @Test
    fun afterLocalReportInvalidation_freshnessInvalidated_newServerRequest_reusesTokens_changedStatsUpdate() = runBlocking {
        withTimeout(15_000) {
            EgressOptimizationConfig.isHomeFeedSyncV2Enabled = true
            val sentTokens = CopyOnWriteArrayList<JsonObject>()
            val callCount = AtomicInteger()
            val tokenA = "a".repeat(64)
            val tokenB = "b".repeat(64)

            val api = client { request ->
                val tokens = requestTokens(request)
                sentTokens.add(tokens)
                val n = callCount.incrementAndGet()
                if (n == 1) {
                    reply(fullHomeResponse("a", statValue = 10))
                } else if (n == 2) {
                    // Partial sync: open/paused/completed/owner_names unchanged, stats changed!
                    reply("""{
                      "schema_version":2,"business_date":"$day","blocks":{
                        "open":{"token":"$tokenA","changed":false},
                        "paused":{"token":"$tokenA","changed":false},
                        "completed":{"token":"$tokenA","changed":false},
                        "owner_names":{"token":"$tokenA","changed":false},
                        "stats":{"token":"$tokenB","changed":true,"value":{"$campaign":{"installed_count":20,"today_tested_count":1,"has_tested_today":true,"stats_date":"$day"}}}
                      }
                    }""".trimIndent())
                } else {
                    reply(fullHomeResponse("c", statValue = 30))
                }
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })
                val res1 = repo.getHomeFeed(forceRefresh = true)
                assertEquals(10, res1!!.stats[campaign]!!.installedCount)
                assertEquals("Initial request must have empty tokens", 0, sentTokens[0].size)

                // Local mutation invalidates normal UI cache freshness
                repo.invalidateReportsAndStats(campaign)

                // Next read must make a new server request because UI cache is invalidated
                val res2 = repo.getHomeFeed(forceRefresh = false)
                assertEquals("New server request must occur after local mutation invalidation", 2, callCount.get())
                assertEquals("Tokens from previous server response must be retained and reused as delta base", tokenA, sentTokens[1]["open"]!!.jsonPrimitive.content)
                assertEquals("Unchanged campaigns retained from previous raw snapshot", "a", res2!!.open.single().appName)
                assertEquals("Changed stats updated correctly from partial sync", 20, res2.stats[campaign]!!.installedCount)

                // clearMemoryCache drops both payload and raw tokens
                repo.clearMemoryCache()
                val res3 = repo.getHomeFeed(forceRefresh = true)
                assertEquals(3, callCount.get())
                assertTrue("clearMemoryCache must completely drop tokens and prevent token-only state", sentTokens[2].isEmpty())
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun concurrentEquivalentRefreshes_shareOneHttpRequest_singleFlightJoin_cacheHitAddsZeroHttpAttempts() = runBlocking {
        withTimeout(15_000) {
            EgressOptimizationConfig.isHomeFeedSyncV2Enabled = true
            val httpAttempts = AtomicInteger()
            val gate = CompletableDeferred<Unit>()

            val api = client { request ->
                httpAttempts.incrementAndGet()
                gate.await()
                reply(fullHomeResponse("cf"))
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })
                val job1 = async(Dispatchers.IO) { repo.getHomeFeed(forceRefresh = true) }
                val job2 = async(Dispatchers.IO) { repo.getHomeFeed(forceRefresh = true) }

                while (EgressTelemetry.getStats()["totalSingleFlightDeduplicated"] == 0L) yield()
                gate.complete(Unit)

                val r1 = job1.await()
                val r2 = job2.await()

                assertEquals("cf", r1!!.open.single().appName)
                assertEquals("cf", r2!!.open.single().appName)
                assertEquals("Single-flight must coalesce concurrent requests to exactly 1 HTTP attempt", 1, httpAttempts.get())
                assertEquals("totalRequests must equal 1", 1L, EgressTelemetry.getStats()["totalRequests"])
                assertTrue("Single-flight join must be recorded in telemetry", EgressTelemetry.getStats()["totalSingleFlightDeduplicated"]!! >= 1L)

                // Subsequent read within freshness window is a local cache hit
                val r3 = repo.getHomeFeed(forceRefresh = false)
                assertEquals("cf", r3!!.open.single().appName)
                assertEquals("Cache hit must make zero HTTP attempts", 1, httpAttempts.get())
                assertEquals("Local cache hit must add ZERO to totalRequests counter", 1L, EgressTelemetry.getStats()["totalRequests"])
                assertTrue("Local cache hit must be recorded in telemetry", EgressTelemetry.getStats()["totalLocalCacheHits"]!! >= 1L)
            } finally {
                gate.complete(Unit)
                api.close()
            }
        }
    }

    @Test
    fun explicitPrefetchResumeMutationMetadata_notPullRefresh_decodedBytesAccurate() = runBlocking {
        withTimeout(15_000) {
            EgressOptimizationConfig.isHomeFeedSyncV2Enabled = true
            EgressOptimizationConfig.isCandidatesV2Enabled = true

            val api = client { request ->
                val path = request.url.encodedPath
                if (path.endsWith("/get_next_campaign_candidates_v2")) {
                    reply(candidatesJson(3))
                } else {
                    reply(fullHomeResponse("meta"))
                }
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })

                // RESUME trigger
                repo.getHomeFeed(forceRefresh = false, triggerSource = EgressTriggerSource.RESUME)
                val recordResume = EgressTelemetry.getRecentRecords().last()
                assertEquals("RESUME metadata must not be overwritten as PULL_REFRESH", EgressTriggerSource.RESUME, recordResume.triggerSource)
                assertNull("Wire bytes should remain null when unmeasured", recordResume.wireBytesEstimate)
                assertTrue("Decoded bytes must correspond to actual payload", recordResume.decodedBytesEstimate > 0L)

                // PREFETCH trigger on candidate RPC
                repo.getNextCampaignCandidates("none", triggerSource = EgressTriggerSource.PREFETCH)
                val recordPrefetch = EgressTelemetry.getRecentRecords().last()
                assertEquals("Candidate RPC must be labeled PREFETCH", EgressTriggerSource.PREFETCH, recordPrefetch.triggerSource)
                assertNull(recordPrefetch.wireBytesEstimate)
                assertTrue("Candidate decoded bytes must be non-zero", recordPrefetch.decodedBytesEstimate > 0L)

                // MUTATION trigger
                repo.getHomeFeed(forceRefresh = true, triggerSource = EgressTriggerSource.MUTATION)
                val recordMutation = EgressTelemetry.getRecentRecords().last()
                assertEquals("MUTATION metadata preserved without falling back to PULL_REFRESH", EgressTriggerSource.MUTATION, recordMutation.triggerSource)
                assertTrue("Unmeasured wire bytes tracked in unknown wire counter", EgressTelemetry.getStats()["totalUnknownWireBytes"]!! >= 3L)
            } finally {
                api.close()
            }
        }
    }
}
