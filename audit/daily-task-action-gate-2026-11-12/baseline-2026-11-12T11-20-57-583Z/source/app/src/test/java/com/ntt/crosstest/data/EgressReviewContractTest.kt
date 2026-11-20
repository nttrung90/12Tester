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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Reviewer-owned tests exercise the repository's real HTTP/merge/cache path. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EgressReviewContractTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 11, 10)
    private val campaign = "f9000000-0000-0000-0000-000000000004"
    private var originalFlag = false

    @Before
    fun prepare() {
        originalFlag = EgressOptimizationConfig.isHomeFeedSyncV2Enabled
        EgressOptimizationConfig.isHomeFeedSyncV2Enabled = true
        EgressOptimizationConfig.resetCapabilityCache()
        SessionCacheManager.clear(context)
    }

    @After
    fun cleanup() {
        EgressOptimizationConfig.isHomeFeedSyncV2Enabled = originalFlag
        EgressOptimizationConfig.resetCapabilityCache()
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
        return Json.parseToJsonElement(text).jsonObject["p_tokens"]!!.jsonObject
    }

    private fun fullResponse(tag: String, omitOpenValue: Boolean = false): String {
        val token = tag.repeat(64)
        val openValue = if (omitOpenValue) "" else ",\"value\":[{\"id\":\"$campaign\",\"app_name\":\"$tag\",\"package_name\":\"fixture.$tag\"}]"
        return """{
          "schema_version":2,"business_date":"$day","blocks":{
            "open":{"token":"$token","changed":true$openValue},
            "paused":{"token":"$token","changed":true,"value":[]},
            "completed":{"token":"$token","changed":true,"value":[]},
            "owner_names":{"token":"$token","changed":true,"value":{}},
            "stats":{"token":"$token","changed":true,"value":{}}
          }
        }""".trimIndent()
    }

    @Test
    fun changedBlockWithoutValue_repairsOnceInsteadOfPublishingEmptyList() = runBlocking {
        withTimeout(15_000) {
            val calls = AtomicInteger()
            val api = client { request ->
                assertTrue(request.url.encodedPath.endsWith("/get_home_feed_sync_v2"))
                val n = calls.incrementAndGet()
                assertTrue("Initial load and repair both need empty tokens", requestTokens(request).isEmpty())
                reply(fullResponse("a", omitOpenValue = n == 1))
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })
                val result = repo.getHomeFeed(forceRefresh = true)
                assertEquals("Malformed changed block must trigger one full repair", 2, calls.get())
                assertEquals(campaign, result!!.open.single().id)
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun evictingPayloadEvictsCorrespondingRequestTokens() = runBlocking {
        withTimeout(15_000) {
            val sent = CopyOnWriteArrayList<JsonObject>()
            val api = client { request ->
                sent += requestTokens(request)
                reply(fullResponse("a"))
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })
                repo.getHomeFeed(forceRefresh = true)
                repo.clearMemoryCache()
                repo.getHomeFeed(forceRefresh = true)
                assertEquals(2, sent.size)
                assertTrue("Payload eviction must never leave token-only state", sent[1].isEmpty())
            } finally {
                api.close()
            }
        }
    }

    @Test
    fun previousGenerationResponseCannotOverwriteNewGenerationTokens() = runBlocking {
        withTimeout(15_000) {
            val enteredOld = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val sent = CopyOnWriteArrayList<JsonObject>()
            val api = client { request ->
                sent += requestTokens(request)
                when (calls.incrementAndGet()) {
                    1 -> {
                        enteredOld.complete(Unit)
                        releaseOld.await()
                        reply(fullResponse("a"))
                    }
                    else -> reply(fullResponse("b"))
                }
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })
                val old = async(Dispatchers.IO) { runCatching { repo.getHomeFeed(forceRefresh = true) } }
                enteredOld.await()
                repo.clearMemoryCache()
                assertEquals("b", repo.getHomeFeed(forceRefresh = true)!!.open.single().appName)
                releaseOld.complete(Unit)
                old.await()
                repo.getHomeFeed(forceRefresh = true)
                assertEquals(3, sent.size)
                assertEquals("Old response must not poison the current snapshot token", "b".repeat(64), sent[2]["open"]!!.jsonPrimitive.content)
            } finally {
                releaseOld.complete(Unit)
                api.close()
            }
        }
    }

    @Test
    fun missingRpcErrorForDifferentFunctionMustNotSilentlyFallback() = runBlocking {
        withTimeout(15_000) {
            val legacyCalls = AtomicInteger()
            val api = client { request ->
                if (request.url.encodedPath.endsWith("/get_home_feed_sync_v2")) {
                    reply("""{"code":"PGRST202","message":"Could not find the function public.some_other_rpc() in the schema cache","details":null,"hint":null}""", HttpStatusCode.NotFound)
                } else {
                    legacyCalls.incrementAndGet()
                    reply("""{"open":[],"paused":[],"completed":[],"owner_names":{},"stats":{}}""")
                }
            }
            try {
                val repo = CampaignRepository(api, Localizer(context), businessDayClock = { day })
                val result = runCatching { repo.getHomeFeed(forceRefresh = true) }
                assertTrue("A PGRST202 for a different function is an error, not evidence this endpoint is absent", result.isFailure)
                assertEquals("Unrelated missing-function errors must not trigger a second legacy request", 0, legacyCalls.get())
            } finally {
                api.close()
            }
        }
    }
}
