package com.ntt.crosstest.data

import android.app.Application
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
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
class ReportRpcCompatibilityTest {
    private val success = """{"success":true,"report_id":"report-fixture","status":"pending","provisional_granted":true,"provisional_amount":10}"""
    private val requestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

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
    fun missingThumbnailSignatureMustRetrySixArgumentsKeepingIdempotency() = runBlocking {
        val bodies = mutableListOf<Map<String, String>>()
        val backend = client { fields ->
            bodies += fields
            if ("p_thumbnail_url" in fields)
                """{"code":"PGRST202","message":"Could not find the function public.submit_tester_report(p_campaign_id, p_client_submit_id, p_content, p_image_hash, p_rating, p_screenshot_url, p_thumbnail_url) in the schema cache"}""" to HttpStatusCode.NotFound
            else success to HttpStatusCode.OK
        }
        try {
            val repo = CampaignRepository(backend, Localizer(RuntimeEnvironment.getApplication()))
            val result = repo.submitFeedback("campaign", 5, "tested", "https://fixture.invalid/full.jpg",
                "0123456789abcdef", requestId, requireIdempotency = true,
                thumbnailUrl = "https://fixture.invalid/thumb.jpg")
            assertTrue(result.success)
            assertEquals(2, bodies.size)
            assertTrue(bodies[0].containsKey("p_thumbnail_url"))
            assertEquals(requestId, bodies[1]["p_client_submit_id"])
            assertFalse(bodies[1].containsKey("p_thumbnail_url"))
            assertEquals(bodies[0].filterKeys { it != "p_thumbnail_url" }, bodies[1])
        } finally { backend.close() }
    }

    @Test
    fun absentIdempotentSignatureMustNotFallBackToUnsafeFiveArguments() = runBlocking {
        val bodies = mutableListOf<Map<String, String>>()
        val backend = client { fields ->
            bodies += fields
            """{"code":"PGRST202","message":"Could not find the function public.submit_tester_report in the schema cache"}""" to HttpStatusCode.NotFound
        }
        try {
            val repo = CampaignRepository(backend, Localizer(RuntimeEnvironment.getApplication()))
            try {
                repo.submitFeedback("campaign", 5, "tested", clientSubmitId = requestId,
                    requireIdempotency = true, thumbnailUrl = "https://fixture.invalid/thumb.jpg")
                fail("Must fail closed when no idempotent RPC exists")
            } catch (expected: IllegalStateException) {
                assertEquals("report_idempotency_unavailable", expected.message)
            }
            assertTrue(bodies.all { it["p_client_submit_id"] == requestId })
        } finally { backend.close() }
    }

    @Test
    fun businessFailureMustNotRetryWithoutThumbnail() = runBlocking {
        var calls = 0
        val backend = client {
            calls++
            """{"code":"P0001","message":"campaign_not_open"}""" to HttpStatusCode.BadRequest
        }
        try {
            val repo = CampaignRepository(backend, Localizer(RuntimeEnvironment.getApplication()))
            try {
                repo.submitFeedback("campaign", 5, "tested", clientSubmitId = requestId,
                    requireIdempotency = true, thumbnailUrl = "https://fixture.invalid/thumb.jpg")
                fail("Must preserve business failure")
            } catch (expected: Exception) { assertTrue(expected.message.orEmpty().contains("campaign_not_open")) }
            assertEquals(1, calls)
        } finally { backend.close() }
    }

    @Test
    fun schemaCacheOutageMustPreserveServerFailureWithoutChangingSignature() = runBlocking {
        var calls = 0
        val backend = client {
            calls++
            if (calls == 1)
                """{"code":"PGRST002","message":"schema cache unavailable for submit_tester_report"}""" to HttpStatusCode.ServiceUnavailable
            else success to HttpStatusCode.OK
        }
        try {
            val repo = CampaignRepository(backend, Localizer(RuntimeEnvironment.getApplication()))
            val failure = runCatching {
                repo.submitFeedback("campaign", 5, "tested", clientSubmitId = requestId, requireIdempotency = true)
            }.exceptionOrNull()
            assertTrue("A schema-cache outage is a retryable server error, not a missing RPC signature", failure is RestException)
            assertEquals(503, (failure as RestException).statusCode)
            assertEquals(1, calls)
        } finally { backend.close() }
    }

    @Test
    fun schemaCachePermissionFailureMustNotTryAnotherSignature() = runBlocking {
        var calls = 0
        val backend = client {
            calls++
            """{"code":"42501","message":"permission denied for submit_tester_report schema cache"}""" to HttpStatusCode.Forbidden
        }
        try {
            val repo = CampaignRepository(backend, Localizer(RuntimeEnvironment.getApplication()))
            val failure = runCatching {
                repo.submitFeedback("campaign", 5, "tested", clientSubmitId = requestId, requireIdempotency = true)
            }.exceptionOrNull()
            assertTrue("Permission failure must propagate unchanged", failure is RestException)
            assertEquals(403, (failure as RestException).statusCode)
            assertEquals(1, calls)
        } finally { backend.close() }
    }
}
