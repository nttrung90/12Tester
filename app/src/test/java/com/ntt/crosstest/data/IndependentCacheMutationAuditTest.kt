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
class IndependentCacheMutationAuditTest {

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
}

