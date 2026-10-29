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
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReportRpcFallbackAuditTest {
    private val success = """{"success":true,"report_id":"report-fixture","status":"pending","provisional_granted":true,"provisional_amount":10}"""
    private val requestId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val jsonSerializer = Json { encodeDefaults = true }

    private suspend fun client(handler: (Map<String, String>) -> Pair<String, HttpStatusCode>): io.github.jan.supabase.SupabaseClient {
        val fixture = createSupabaseClient("https://fixture.invalid", "fixture-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                val fields = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    .mapValues { it.value.jsonPrimitive.content }
                val (body, status) = handler(fields)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        fixture.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
            """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"tester","aud":"authenticated"}}"""
        ))
        return fixture
    }

    @Test
    fun sevenArgumentsSuccessPath() = runBlocking {
        val bodies = mutableListOf<Map<String, String>>()
        val backend = client { fields ->
            bodies += fields
            success to HttpStatusCode.OK
        }
        try {
            val repo = CampaignRepository(backend, Localizer(RuntimeEnvironment.getApplication()))
            val result = repo.submitFeedback(
                campaignId = "camp-123",
                rating = 4,
                comment = "tested thoroughly",
                screenshotUrl = "https://fixture.invalid/full.jpg",
                imageHash = "0123456789abcdef",
                clientSubmitId = requestId,
                requireIdempotency = true,
                thumbnailUrl = "https://fixture.invalid/thumb.jpg",
            )
            assertTrue(result.success)
            assertEquals("report-fixture", result.reportId)
            assertEquals(1, bodies.size)
            assertEquals("camp-123", bodies[0]["p_campaign_id"])
            assertEquals("tested thoroughly", bodies[0]["p_content"])
            assertEquals("https://fixture.invalid/full.jpg", bodies[0]["p_screenshot_url"])
            assertEquals("4", bodies[0]["p_rating"])
            assertEquals("0123456789abcdef", bodies[0]["p_image_hash"])
            assertEquals("https://fixture.invalid/thumb.jpg", bodies[0]["p_thumbnail_url"])
            assertEquals(requestId, bodies[0]["p_client_submit_id"])
        } finally { backend.close() }
    }

    @Test
    fun http500MustNotTriggerFallback() = runBlocking {
        var calls = 0
        val backend = client {
            calls++
            """{"code":"500","message":"Internal Server Error"}""" to HttpStatusCode.InternalServerError
        }
        try {
            val repo = CampaignRepository(backend, Localizer(RuntimeEnvironment.getApplication()))
            try {
                repo.submitFeedback(
                    campaignId = "camp-123",
                    rating = 5,
                    comment = "tested",
                    screenshotUrl = "https://fixture.invalid/full.jpg",
                    imageHash = "0123456789abcdef",
                    clientSubmitId = requestId,
                    requireIdempotency = true,
                    thumbnailUrl = "https://fixture.invalid/thumb.jpg",
                )
                fail("HTTP 500 must throw and not trigger fallback")
            } catch (e: Exception) {
                assertFalse("Must not be idempotency unavailable", e is IllegalStateException && e.message == "report_idempotency_unavailable")
                assertTrue(e.message.orEmpty().contains("500") || e.message.orEmpty().contains("Internal Server Error"))
            }
            assertEquals("Must not retry or fall back on 500", 1, calls)
        } finally { backend.close() }
    }

    @Test
    fun cancellationMustNotTriggerFallback() = runBlocking {
        var calls = 0
        val backend = createSupabaseClient("https://fixture.invalid", "fixture-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine {
                calls++
                throw CancellationException("Simulated external cancellation")
            }
        }
        backend.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
            """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"tester","aud":"authenticated"}}"""
        ))
        try {
            val repo = CampaignRepository(backend, Localizer(RuntimeEnvironment.getApplication()))
            try {
                repo.submitFeedback(
                    campaignId = "camp-123",
                    rating = 5,
                    comment = "tested",
                    screenshotUrl = "https://fixture.invalid/full.jpg",
                    imageHash = "0123456789abcdef",
                    clientSubmitId = requestId,
                    requireIdempotency = true,
                    thumbnailUrl = "https://fixture.invalid/thumb.jpg",
                )
                fail("CancellationException must be rethrown")
            } catch (e: CancellationException) {
                assertEquals("Simulated external cancellation", e.message)
            }
            assertEquals("Cancellation must not trigger retry or fallback", 1, calls)
        } finally { backend.close() }
    }

    @Test
    fun legacyDtoHasExactlyFiveParameters() {
        val legacy = SubmitTesterReportLegacyParams(
            campaignId = "camp-legacy",
            content = "legacy content",
            screenshotUrl = "https://fixture.invalid/full.jpg",
            rating = 4,
            imageHash = "legacyhash",
        )
        val json = jsonSerializer.encodeToJsonElement(SubmitTesterReportLegacyParams.serializer(), legacy).jsonObject
        val expectedKeys = setOf(
            "p_campaign_id",
            "p_content",
            "p_screenshot_url",
            "p_rating",
            "p_image_hash",
        )
        assertEquals("SubmitTesterReportLegacyParams must serialize exactly 5 fields", expectedKeys, json.keys)
        assertEquals(5, json.size)
        assertFalse("Legacy DTO must not have thumbnail", json.containsKey("p_thumbnail_url"))
        assertFalse("Legacy DTO must not have client_submit_id", json.containsKey("p_client_submit_id"))
    }

    @Test
    fun sixParamsDtoHasExactlySixParameters() {
        val six = SubmitTesterReportSixParams(
            campaignId = "camp-six",
            content = "six content",
            screenshotUrl = "https://fixture.invalid/full.jpg",
            rating = 5,
            imageHash = "sixhash",
            clientSubmitId = requestId,
        )
        val json = jsonSerializer.encodeToJsonElement(SubmitTesterReportSixParams.serializer(), six).jsonObject
        val expectedKeys = setOf(
            "p_campaign_id",
            "p_content",
            "p_screenshot_url",
            "p_rating",
            "p_image_hash",
            "p_client_submit_id",
        )
        assertEquals("SubmitTesterReportSixParams must serialize exactly 6 fields", expectedKeys, json.keys)
        assertEquals(6, json.size)
        assertFalse("Six-params DTO must not have thumbnail", json.containsKey("p_thumbnail_url"))
        assertTrue("Six-params DTO must preserve client_submit_id", json.containsKey("p_client_submit_id"))
    }

    @Test
    fun fallbackToFiveArgumentsWhenIdempotencyNotRequired() = runBlocking {
        val bodies = mutableListOf<Map<String, String>>()
        val backend = client { fields ->
            bodies += fields
            if ("p_thumbnail_url" in fields) {
                """{"code":"PGRST202","message":"Could not find the function submit_tester_report with 7 args in schema cache"}""" to HttpStatusCode.NotFound
            } else if ("p_client_submit_id" in fields) {
                """{"code":"PGRST202","message":"Could not find the function submit_tester_report with 6 args in schema cache"}""" to HttpStatusCode.NotFound
            } else {
                success to HttpStatusCode.OK
            }
        }
        try {
            val repo = CampaignRepository(backend, Localizer(RuntimeEnvironment.getApplication()))
            val result = repo.submitFeedback(
                campaignId = "camp-fallback",
                rating = 5,
                comment = "tested",
                screenshotUrl = "https://fixture.invalid/full.jpg",
                imageHash = "0123456789abcdef",
                clientSubmitId = requestId,
                requireIdempotency = false,
                thumbnailUrl = "https://fixture.invalid/thumb.jpg",
            )
            assertTrue(result.success)
            assertEquals("Should attempt 7-param, then 6-param, then fall back to 5-param", 3, bodies.size)
            // Call 1: 7 args
            assertTrue(bodies[0].containsKey("p_thumbnail_url"))
            assertTrue(bodies[0].containsKey("p_client_submit_id"))
            // Call 2: 6 args
            assertFalse(bodies[1].containsKey("p_thumbnail_url"))
            assertTrue(bodies[1].containsKey("p_client_submit_id"))
            // Call 3: 5 args (legacy)
            assertFalse(bodies[2].containsKey("p_thumbnail_url"))
            assertFalse(bodies[2].containsKey("p_client_submit_id"))
            assertEquals("camp-fallback", bodies[2]["p_campaign_id"])
        } finally { backend.close() }
    }
}
