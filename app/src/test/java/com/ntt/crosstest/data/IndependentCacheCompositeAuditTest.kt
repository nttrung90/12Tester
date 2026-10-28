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
class IndependentCacheCompositeAuditTest {

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
    fun feedbackOnlyFailureMustNotOverwriteGoodAverageRating() = runBlocking {
        var failFeedback=false
        val client=createTestClient { req ->
            if(req.url.toString().contains("feedbacks")) {
                if(failFeedback) jsonResponse("{}",HttpStatusCode.InternalServerError)
                else jsonResponse("""[{"campaign_id":"c1","user_id":"testerA","rating":5}]""")
            } else jsonResponse("[]")
        }
        val repo=CampaignRepository(client,Localizer(context))
        importUser(client,"userA")
        assertEquals(5f,repo.getCampaignOwnerStats("c1").averageRating!!,0f)
        failFeedback=true
        try { repo.getCampaignOwnerStats("c1",forceRefresh=true) } catch (_:Exception) {}
        failFeedback=false
        assertEquals("failed component must not cache successful null average",5f,repo.getCampaignOwnerStats("c1").averageRating ?: -1f,0f)
    }

    @Test
    fun checkinOnlyFailureMustNotOverwriteTesterHistoryWithZero() = runBlocking {
        var failCheckins=false
        val client=createTestClient { req ->
            when {
                req.url.toString().contains("campaign_members") -> jsonResponse("""[{"campaign_id":"c1","user_id":"testerA"}]""")
                req.url.toString().contains("checkins") -> {
                    if(failCheckins) jsonResponse("{}",HttpStatusCode.InternalServerError)
                    else jsonResponse("""[{"campaign_id":"c1","user_id":"testerA","checkin_date":"2026-10-27"}]""")
                }
                else -> jsonResponse("[]")
            }
        }
        val repo=CampaignRepository(client,Localizer(context))
        importUser(client,"userA")
        assertEquals(1,repo.getCampaignTesters("c1").first().checkinCount)
        failCheckins=true
        try { repo.getCampaignTesters("c1",forceRefresh=true) } catch (_:Exception) {}
        failCheckins=false
        assertEquals("failed checkins must not cache successful zero history",1,repo.getCampaignTesters("c1").first().checkinCount)
    }
}

