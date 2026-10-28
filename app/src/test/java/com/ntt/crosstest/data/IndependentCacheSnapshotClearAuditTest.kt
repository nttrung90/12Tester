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
class IndependentCacheSnapshotClearAuditTest {

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
    fun clearMustImmediatelyPreventReloadEvenWhenDiskCleanupIsQueued() = runBlocking {
        assertEquals("fixture must exercise main-thread async clear",android.os.Looper.getMainLooper(),android.os.Looper.myLooper())
        SessionCacheManager.clearSync(context)
        SessionCacheManager.save(context,CachedSessionData(userId="old-user",userCredits=100))
        assertNotNull(SessionCacheManager.peekMemory())
        val field=SessionCacheManager::class.java.getDeclaredField("diskLock").apply { isAccessible=true }
        val lock=field.get(SessionCacheManager)
        synchronized(lock) {
            SessionCacheManager.clear(context)
            // Main holds disk lock reentrantly; queued IO cleanup cannot run yet.
            // A read after clear must not resurrect the still-present old file.
            assertNull("clear must tombstone old disk snapshot before queued deletion",SessionCacheManager.loadSync(context))
            assertNull("RAM must stay empty after clear until a valid new save",SessionCacheManager.peekMemory())
        }
    }

    @Test
    fun validSnapshotMustSurviveRuntimeEpochResetAndStillRespectNextClear() = runBlocking {
        SessionCacheManager.clearSync(context)
        val previousRuntimeGen=SessionCacheManager.currentGeneration()
        assertTrue(previousRuntimeGen>1L)
        SessionCacheManager.save(context,CachedSessionData(userId="valid-user",userCredits=100,timestamp=System.currentTimeMillis()))
        assertTrue(SessionCacheManager.hasCachedSession(context))
        val memoryField=SessionCacheManager::class.java.getDeclaredField("inMemoryCache").apply { isAccessible=true }
        val diskField=SessionCacheManager::class.java.getDeclaredField("diskLock").apply { isAccessible=true }
        try {
            // Recreate current singleton's boot state: RAM gone, runtime epoch starts at 1.
            memoryField.set(SessionCacheManager,null)
            SessionCacheManager.generation.set(1L)
            assertEquals("persistent snapshot ownership must not depend on prior runtime's counter","valid-user",SessionCacheManager.loadSync(context)?.userId)
            synchronized(diskField.get(SessionCacheManager)) {
                SessionCacheManager.clear(context)
                assertNull("clear in new runtime must tombstone old durable file too",SessionCacheManager.loadSync(context))
            }
        } finally {
            SessionCacheManager.generation.set(previousRuntimeGen+1L)
            SessionCacheManager.clearSync(context)
        }
    }

    @Test
    fun mainThreadClearMustNotWaitForPendingDiskIoLock() {
        assertEquals(android.os.Looper.getMainLooper(),android.os.Looper.myLooper())
        SessionCacheManager.clearSync(context)
        val lock=SessionCacheManager::class.java.getDeclaredField("diskLock").apply { isAccessible=true }.get(SessionCacheManager)
        val held=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val aboutToClear=java.util.concurrent.CountDownLatch(1)
        val returned=java.util.concurrent.CountDownLatch(1)
        val blocked=java.util.concurrent.atomic.AtomicBoolean(false)
        val writer=kotlin.concurrent.thread(isDaemon=true,name="controlled-snapshot-io") {
            synchronized(lock) { held.countDown(); release.await() }
        }
        assertTrue("fixture must hold disk IO critical section",held.await(5,java.util.concurrent.TimeUnit.SECONDS))
        val watchdog=kotlin.concurrent.thread(isDaemon=true,name="clear-liveness-gate") {
            aboutToClear.await()
            if(!returned.await(3,java.util.concurrent.TimeUnit.SECONDS)) blocked.set(true)
            release.countDown()
        }
        try {
            aboutToClear.countDown()
            SessionCacheManager.clear(context)
            returned.countDown()
            watchdog.join(5000)
            writer.join(5000)
            assertFalse("main clear must invalidate immediately without waiting for disk IO",blocked.get())
        } finally {
            returned.countDown()
            release.countDown()
            watchdog.join(5000)
            writer.join(5000)
        }
    }
}
