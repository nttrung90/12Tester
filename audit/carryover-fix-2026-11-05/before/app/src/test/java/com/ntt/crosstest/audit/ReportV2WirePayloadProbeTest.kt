package com.ntt.crosstest.audit

import android.app.Application
import com.ntt.crosstest.R
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.ReportSubmissionFailure
import com.ntt.crosstest.data.ReportSubmitPolicy
import com.ntt.crosstest.ui.viewmodels.friendlyError
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression test: assert required submission context is preserved on wire for daily and mission payloads. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReportV2WirePayloadProbeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val localizer get() = Localizer(RuntimeEnvironment.getApplication())

    private suspend fun fixtureClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): io.github.jan.supabase.SupabaseClient {
        val client = createSupabaseClient("https://fixture.invalid", "fixture-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine { request -> handler(request) }
        }
        client.auth.awaitInitialization()
        client.auth.importSession(json.decodeFromString<UserSession>(
            """{"access_token":"fixture","refresh_token":"fixture","expires_in":3600,"token_type":"bearer","user":{"id":"tester","aud":"authenticated"}}"""
        ))
        return client
    }

    @Test fun inspectActualDailyAndMissionWirePayload() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val endpoints = mutableListOf<String>()
        val client = fixtureClient { request ->
            endpoints += request.url.encodedPath
            bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond("""{"success":true,"report_id":"fixture"}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val repository = CampaignRepository(client, localizer)
            repository.submitFeedback("campaign", 5, "daily", clientSubmitId = "request-daily")
            repository.submitFeedback("campaign", 5, "mission", clientSubmitId = "request-mission", missionId = "mission-xyz")

            assertTrue("Daily endpoint must call submit_tester_report_v2", endpoints[0].endsWith("/submit_tester_report_v2"))
            assertTrue("Mission endpoint must call submit_tester_report_v2", endpoints[1].endsWith("/submit_tester_report_v2"))
            assertEquals("app_test", bodies[0]["p_submission_context"]?.jsonPrimitive?.content)
            assertEquals("campaign", bodies[0]["p_campaign_id"]?.jsonPrimitive?.content)
            assertEquals("request-daily", bodies[0]["p_client_submit_id"]?.jsonPrimitive?.content)
            assertFalse("Daily payload must omit missionId", bodies[0].containsKey("p_mission_id"))

            assertEquals("mission", bodies[1]["p_submission_context"]?.jsonPrimitive?.content)
            assertEquals("mission-xyz", bodies[1]["p_mission_id"]?.jsonPrimitive?.content)
            assertEquals("campaign", bodies[1]["p_campaign_id"]?.jsonPrimitive?.content)
            assertEquals("request-mission", bodies[1]["p_client_submit_id"]?.jsonPrimitive?.content)
        } finally { client.close() }
    }

    @Test fun mockRejectsMissingSubmissionContextAndAcceptsWhenPresent() = runBlocking {
        val client = fixtureClient { request ->
            assertTrue("Must call submit_tester_report_v2", request.url.encodedPath.endsWith("/submit_tester_report_v2"))
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            if (!body.containsKey("p_submission_context")) {
                respond("""{"code":"PGRST202","message":"Could not find the function submit_tester_report_v2 in the schema cache"}""",
                    HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                val context = body["p_submission_context"]?.jsonPrimitive?.content
                val reqId = body["p_client_submit_id"]?.jsonPrimitive?.content
                val missionId = body["p_mission_id"]?.jsonPrimitive?.content
                if (context == "mission") {
                    assertNotNull("Mission submission must provide missionId", missionId)
                }
                assertNotNull("Submission must provide requestId", reqId)
                respond("""{"success":true,"report_id":"rep-$reqId","status":"pending"}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            val repository = CampaignRepository(client, localizer)
            val dailyResult = repository.submitFeedback("campaign", 5, "daily", clientSubmitId = "req-daily-1")
            assertTrue("Daily submission with valid context must succeed", dailyResult.success)
            assertEquals("rep-req-daily-1", dailyResult.reportId)

            val missionResult = repository.submitFeedback("campaign", 5, "mission",
                clientSubmitId = "req-mission-2", missionId = "mission-789")
            assertTrue("Mission submission with valid context must succeed", missionResult.success)
            assertEquals("rep-req-mission-2", missionResult.reportId)
        } finally { client.close() }
    }

    @Test fun wrappedPgrst202PreservesOriginalCodeAndMetadataInCauseChain() = runBlocking {
        val client = fixtureClient { _ ->
            respond("""{"code":"PGRST202","message":"Could not find the function submit_tester_report_v2 in the schema cache"}""",
                HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val repository = CampaignRepository(client, localizer)
            val error = runCatching {
                repository.submitFeedback("campaign", 5, "test", clientSubmitId = "req-fail-pgrst")
            }.exceptionOrNull()

            assertNotNull("Must throw exception on missing RPC", error)
            assertTrue("Must be IllegalStateException", error is IllegalStateException)
            assertEquals("server_update_required", error?.message)

            val cause = error?.cause
            assertNotNull("Cause must be preserved in exception chain", cause)
            assertTrue("Cause must be PostgrestRestException", cause is PostgrestRestException)
            val pgEx = cause as PostgrestRestException
            assertEquals("PGRST202", pgEx.code)
            assertEquals(404, pgEx.statusCode)

            assertEquals("report_rpc_PGRST202", ReportSubmissionFailure.codeFor(error as Exception))
            assertEquals("PGRST202", ReportSubmissionFailure.originalCodeFor(error))
            assertEquals(404, ReportSubmissionFailure.httpStatusFor(error))
            assertEquals(localizer.get(R.string.error_rpc_not_available), friendlyError(error, localizer))
        } finally { client.close() }
    }

    @Test fun businessP0001PreservesCodeAndIsNotRetryable() = runBlocking {
        val client = fixtureClient { _ ->
            respond("""{"code":"P0001","message":"SAME_SCREENSHOT_REJECTED"}""",
                HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val repository = CampaignRepository(client, localizer)
            val error = runCatching {
                repository.submitFeedback("campaign", 5, "test", clientSubmitId = "req-fail-business")
            }.exceptionOrNull()

            assertNotNull(error)
            assertTrue(error is PostgrestRestException)
            val pgEx = error as PostgrestRestException
            assertEquals("P0001", pgEx.code)
            assertEquals(400, pgEx.statusCode)
            assertFalse("Business failure must not be retryable", ReportSubmitPolicy.isRetryable(error as Exception))
            assertEquals("report_rpc_P0001", ReportSubmissionFailure.codeFor(error))
            assertEquals("P0001", ReportSubmissionFailure.originalCodeFor(error))
            assertEquals(400, ReportSubmissionFailure.httpStatusFor(error))

            val structured = ReportSubmissionFailure("report_rpc_P0001", "rpc", "req-fail-business", "SAME_SCREENSHOT_REJECTED")
            assertEquals(localizer.get(R.string.error_same_screenshot), friendlyError(structured, localizer))
        } finally { client.close() }
    }

    @Test fun http503IsRetryableAndNotClassifiedAsMissingRpc() = runBlocking {
        val client = fixtureClient { _ ->
            respond("""{"code":"PGRST002","message":"schema cache unavailable"}""",
                HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            val repository = CampaignRepository(client, localizer)
            val error = runCatching {
                repository.submitFeedback("campaign", 5, "test", clientSubmitId = "req-503")
            }.exceptionOrNull()

            assertNotNull(error)
            assertTrue("HTTP 503 is a RestException", error is RestException)
            val restEx = error as RestException
            assertEquals(503, restEx.statusCode)
            assertNotEquals("Must not be misclassified as server_update_required", "server_update_required", error.message)
            assertTrue("HTTP 503 must be retryable", ReportSubmitPolicy.isRetryable(error as Exception))
        } finally { client.close() }
    }

    @Test fun cancellationPropagatesWithoutFallbackOrRetry() = runBlocking {
        val client = fixtureClient { _ ->
            throw CancellationException("user cancelled submission")
        }
        try {
            val repository = CampaignRepository(client, localizer)
            var caughtCancellation: CancellationException? = null
            try {
                repository.submitFeedback("campaign", 5, "test", clientSubmitId = "req-cancel")
            } catch (e: CancellationException) {
                caughtCancellation = e
            }
            assertNotNull("CancellationException must be propagated", caughtCancellation)
            assertEquals("user cancelled submission", caughtCancellation?.message)
            assertNotNull(ReportSubmitPolicy.cancellationCause(caughtCancellation!!))
            assertFalse("Cancellation must not be retryable", ReportSubmitPolicy.isRetryable(caughtCancellation))
        } finally { client.close() }
    }
}
