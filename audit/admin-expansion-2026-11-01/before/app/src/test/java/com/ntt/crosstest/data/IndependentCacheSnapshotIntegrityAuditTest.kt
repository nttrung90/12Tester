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
class IndependentCacheSnapshotIntegrityAuditTest {

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

    private fun forgetRam() {
        SessionCacheManager::class.java.getDeclaredField("inMemoryCache").apply { isAccessible=true }.set(SessionCacheManager,null)
    }

    @Test
    fun corruptSnapshotMustNotLeaveAuthenticatedCacheMetadataTrue() = runBlocking {
        SessionCacheManager.clearSync(context)
        SessionCacheManager.save(context,CachedSessionData(userId="userA",timestamp=System.currentTimeMillis()))
        assertTrue(SessionCacheManager.hasCachedSession(context))
        forgetRam()
        java.io.File(context.filesDir,"last_session_cache.json").writeText("{not valid JSON")
        assertNull(SessionCacheManager.loadSync(context))
        assertFalse("corrupt snapshot must not keep routing metadata authenticated",SessionCacheManager.hasCachedSession(context))
    }

    @Test
    fun snapshotOwnerMustMatchDurableSessionMetadata() = runBlocking {
        SessionCacheManager.clearSync(context)
        SessionCacheManager.save(context,CachedSessionData(userId="userA",timestamp=System.currentTimeMillis()))
        assertTrue(SessionCacheManager.hasCachedSession(context))
        forgetRam()
        val mismatched=CachedSessionData(userId="userB",timestamp=System.currentTimeMillis())
        java.io.File(context.filesDir,"last_session_cache.json").writeText(json.encodeToString(CachedSessionData.serializer(),mismatched))
        assertNull("file owner B must not validate against metadata owner A",SessionCacheManager.loadSync(context))
        assertFalse(SessionCacheManager.hasCachedSession(context))
    }
}
