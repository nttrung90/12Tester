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
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminRelatedRepositoryRaceTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context.getSharedPreferences("app_config_cache", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun cleanup() {
        context.getSharedPreferences("app_config_cache", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        Dispatchers.resetMain()
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun createClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }

    @Test
    fun staleReadStartedBeforeMutation_doesNotOverwriteSavedConfigInRamOrDisk_andColdRestoreRestoresMutation() = runBlocking {
        withTimeout(10_000) {
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()

            val oldConfig = AppConfig(
                id = "android_client",
                minVersionCode = 1,
                latestVersionCode = 10,
                latestVersionName = "1.0.0",
                forceUpdate = false,
            )
            val newConfig = AppConfig(
                id = "android_client",
                minVersionCode = 2,
                latestVersionCode = 20,
                latestVersionName = "2.0.0",
                forceUpdate = true,
            )

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("app_configs") -> {
                        readStarted.complete(Unit)
                        releaseRead.await()
                        reply(json.encodeToString(listOf(oldConfig)))
                    }
                    path.contains("admin_update_app_config") -> {
                        reply(json.encodeToString(newConfig))
                    }
                    else -> reply("[]")
                }
            }

            try {
                val repo = AppConfigRepository(client, context)

                val readJob = async(Dispatchers.IO) {
                    repo.getAppConfig(forceRefresh = true)
                }

                readStarted.await()

                val updated = repo.updateAppConfig(
                    minVersionCode = 2,
                    latestVersionCode = 20,
                    latestVersionName = "2.0.0",
                    forceUpdate = true,
                    title = "Update required",
                    message = "Please update",
                    storeUrl = "https://play.google.com/store",
                )
                assertEquals(20, updated.latestVersionCode)

                assertEquals(20, repo.getAppConfig(forceRefresh = false)?.latestVersionCode)

                releaseRead.complete(Unit)
                readJob.await()

                assertEquals(
                    "RAM cache must retain newer config from updateAppConfig (code 20), not stale read (code 10)",
                    20,
                    repo.getAppConfig(forceRefresh = false)?.latestVersionCode
                )

                val restoredRepo = AppConfigRepository(client, context)
                val restoredConfig = restoredRepo.getAppConfig(forceRefresh = false)
                assertNotNull(restoredConfig)
                assertEquals(
                    "Cold repo restore from disk must reflect updated config (code 20), not stale read (code 10)",
                    20,
                    restoredConfig?.latestVersionCode
                )
            } finally {
                releaseRead.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun staleWritesCompletedInReverseOrder_doNotOverwriteNewerMutationInCacheOrDisk() = runBlocking {
        withTimeout(10_000) {
            val save1Started = CompletableDeferred<Unit>()
            val releaseSave1 = CompletableDeferred<Unit>()
            val callCount = AtomicInteger(0)

            val config1 = AppConfig(id = "android_client", minVersionCode = 1, latestVersionCode = 15, latestVersionName = "1.5.0")
            val config2 = AppConfig(id = "android_client", minVersionCode = 2, latestVersionCode = 25, latestVersionName = "2.5.0")

            val client = createClient { req ->
                val path = req.url.encodedPath
                if (path.contains("admin_update_app_config")) {
                    val currentCall = callCount.incrementAndGet()
                    if (currentCall == 1) {
                        save1Started.complete(Unit)
                        releaseSave1.await()
                        reply(json.encodeToString(config1))
                    } else {
                        reply(json.encodeToString(config2))
                    }
                } else {
                    reply("[]")
                }
            }

            try {
                val repo = AppConfigRepository(client, context)

                val save1Deferred = async(Dispatchers.IO) {
                    repo.updateAppConfig(
                        minVersionCode = 1,
                        latestVersionCode = 15,
                        latestVersionName = "1.5.0",
                        forceUpdate = false,
                        title = "Save 1",
                        message = "Message 1",
                        storeUrl = null,
                    )
                }

                save1Started.await()

                val save2 = repo.updateAppConfig(
                    minVersionCode = 2,
                    latestVersionCode = 25,
                    latestVersionName = "2.5.0",
                    forceUpdate = true,
                    title = "Save 2",
                    message = "Message 2",
                    storeUrl = null,
                )
                assertEquals(25, save2.latestVersionCode)
                assertEquals(25, repo.getAppConfig(forceRefresh = false)?.latestVersionCode)

                releaseSave1.complete(Unit)
                val save1Result = save1Deferred.await()
                assertEquals("Update 1 must return honest RPC response", 15, save1Result.latestVersionCode)

                assertEquals(
                    "Newer Save 2 (code 25) must remain in RAM cache",
                    25,
                    repo.getAppConfig(forceRefresh = false)?.latestVersionCode
                )

                val coldRepo = AppConfigRepository(client, context)
                assertEquals(
                    "Cold repo restore must load newer Save 2 (code 25) from disk",
                    25,
                    coldRepo.getAppConfig(forceRefresh = false)?.latestVersionCode
                )
            } finally {
                releaseSave1.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun readWhileSaveInFlight_doesNotSupersedeSavedConfig() = runBlocking {
        withTimeout(10_000) {
            val saveStarted = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()

            val initialConfig = AppConfig(id = "android_client", minVersionCode = 1, latestVersionCode = 5, latestVersionName = "0.5.0")
            val savedConfig = AppConfig(id = "android_client", minVersionCode = 3, latestVersionCode = 30, latestVersionName = "3.0.0")

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("admin_update_app_config") -> {
                        saveStarted.complete(Unit)
                        releaseSave.await()
                        reply(json.encodeToString(savedConfig))
                    }
                    path.contains("app_configs") -> {
                        reply(json.encodeToString(listOf(initialConfig)))
                    }
                    else -> reply("[]")
                }
            }

            try {
                val repo = AppConfigRepository(client, context)
                val saveJob = async(Dispatchers.IO) {
                    repo.updateAppConfig(
                        minVersionCode = 3,
                        latestVersionCode = 30,
                        latestVersionName = "3.0.0",
                        forceUpdate = false,
                        title = "Title",
                        message = "Message",
                        storeUrl = null,
                    )
                }

                saveStarted.await()

                repo.getAppConfig(forceRefresh = true)

                releaseSave.complete(Unit)
                val saveResult = saveJob.await()
                assertEquals(30, saveResult.latestVersionCode)

                assertEquals(30, repo.getAppConfig(forceRefresh = false)?.latestVersionCode)
                val restored = AppConfigRepository(client, context)
                assertEquals(30, restored.getAppConfig(forceRefresh = false)?.latestVersionCode)
            } finally {
                releaseSave.complete(Unit)
                client.close()
            }
        }
    }

    @Test
    fun getAppConfig_rethrowsCancellationException() = runBlocking {
        withTimeout(10_000) {
            val requestReceived = CompletableDeferred<Unit>()
            val client = createClient { req ->
                requestReceived.complete(Unit)
                delay(10000)
                reply("[]")
            }

            try {
                val repo = AppConfigRepository(client, context)
                val deferred = async(Dispatchers.IO) {
                    repo.getAppConfig(forceRefresh = true)
                }
                requestReceived.await()
                deferred.cancel(CancellationException("Simulated coroutine cancellation"))

                try {
                    deferred.await()
                    fail("getAppConfig must rethrow CancellationException")
                } catch (e: CancellationException) {
                    // Expected: CancellationException was properly rethrown!
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun forceRefresh_errorBehavior_returnsNullOnForcedRefresh_andCachedOnNormal() = runBlocking {
        withTimeout(10_000) {
            var failNetwork = false
            val validConfig = AppConfig(id = "android_client", minVersionCode = 1, latestVersionCode = 10, latestVersionName = "1.0.0")

            val client = createClient { req ->
                if (failNetwork) {
                    reply("Internal server error", HttpStatusCode.InternalServerError)
                } else {
                    reply(json.encodeToString(listOf(validConfig)))
                }
            }

            try {
                val repo = AppConfigRepository(client, context)
                val first = repo.getAppConfig(forceRefresh = true)
                assertNotNull(first)
                assertEquals(10, first?.latestVersionCode)

                failNetwork = true

                val forcedResult = repo.getAppConfig(forceRefresh = true)
                assertNull("Forced refresh on failure must return null to surface honest error", forcedResult)

                val normalResult = repo.getAppConfig(forceRefresh = false)
                assertNotNull("Non-forced refresh on failure must fall back to cached config", normalResult)
                assertEquals(10, normalResult?.latestVersionCode)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun legacyBilingualFallback_recoversOnCapabilityErrorWithoutEnglish() = runBlocking {
        withTimeout(10_000) {
            var rpcCalls = 0
            val targetConfig = AppConfig(
                id = "android_client",
                minVersionCode = 2,
                latestVersionCode = 12,
                latestVersionName = "1.2.0",
                forceUpdate = false,
            )

            val client = createClient { req ->
                rpcCalls++
                if (rpcCalls == 1) {
                    reply("""{"code":"PGRST202","message":"Could not find a function admin_update_app_config"}""", HttpStatusCode.NotFound)
                } else {
                    reply(json.encodeToString(targetConfig))
                }
            }

            try {
                val repo = AppConfigRepository(client, context)
                val result = repo.updateAppConfig(
                    minVersionCode = 2,
                    latestVersionCode = 12,
                    latestVersionName = "1.2.0",
                    forceUpdate = false,
                    title = "Cập nhật",
                    message = "Nội dung",
                    storeUrl = null,
                    titleVi = "Cập nhật",
                    titleEn = null,
                    messageVi = "Nội dung",
                    messageEn = null,
                )
                assertEquals(2, rpcCalls)
                assertEquals(12, result.latestVersionCode)
                assertEquals(12, repo.getAppConfig(forceRefresh = false)?.latestVersionCode)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun legacyBilingualFallback_rethrowsWhenEnglishIsProvided() = runBlocking {
        withTimeout(10_000) {
            var rpcCalls = 0
            val client = createClient { req ->
                rpcCalls++
                reply("""{"code":"PGRST202","message":"Could not find a function admin_update_app_config"}""", HttpStatusCode.NotFound)
            }

            try {
                val repo = AppConfigRepository(client, context)
                try {
                    repo.updateAppConfig(
                        minVersionCode = 2,
                        latestVersionCode = 12,
                        latestVersionName = "1.2.0",
                        forceUpdate = false,
                        title = "Update",
                        message = "Content",
                        storeUrl = null,
                        titleVi = "Cập nhật",
                        titleEn = "Update",
                        messageVi = "Nội dung",
                        messageEn = "Content",
                    )
                    fail("Expected capability error to be rethrown because English cannot be preserved in legacy schema")
                } catch (e: Exception) {
                    assertEquals(1, rpcCalls)
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun publicGlobalCacheSemantics_respectsTtl() = runBlocking {
        withTimeout(10_000) {
            var queryCalls = 0
            val config = AppConfig(id = "android_client", minVersionCode = 1, latestVersionCode = 7, latestVersionName = "0.7.0")

            val client = createClient { req ->
                queryCalls++
                reply(json.encodeToString(listOf(config)))
            }

            try {
                val repo = AppConfigRepository(client, context)
                val first = repo.getAppConfig(forceRefresh = false)
                assertEquals(1, queryCalls)
                assertEquals(7, first?.latestVersionCode)

                val second = repo.getAppConfig(forceRefresh = false)
                assertEquals(1, queryCalls)
                assertEquals(7, second?.latestVersionCode)

                val third = repo.getAppConfig(forceRefresh = true)
                assertEquals(2, queryCalls)
                assertEquals(7, third?.latestVersionCode)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun atomicRegistrationAndPublication_preventsStaleReadPublishAfterSave() = runBlocking {
        withTimeout(10_000) {
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val readConfig = AppConfig(id = "android_client", minVersionCode = 1, latestVersionCode = 2, latestVersionName = "0.2.0")
            val updatedConfig = AppConfig(id = "android_client", minVersionCode = 5, latestVersionCode = 50, latestVersionName = "5.0.0")

            val client = createClient { req ->
                val path = req.url.encodedPath
                when {
                    path.contains("app_configs") -> {
                        readStarted.complete(Unit)
                        releaseRead.await()
                        reply(json.encodeToString(listOf(readConfig)))
                    }
                    path.contains("admin_update_app_config") -> {
                        reply(json.encodeToString(updatedConfig))
                    }
                    else -> reply("[]")
                }
            }

            try {
                val repo = AppConfigRepository(client, context)

                // Start read
                val readJob = async(Dispatchers.IO) {
                    repo.getAppConfig(forceRefresh = true)
                }
                readStarted.await()

                // Update app config starts, registers under cacheLock, completes RPC, publishes under cacheLock, and unregisters under cacheLock
                val saved = repo.updateAppConfig(
                    minVersionCode = 5,
                    latestVersionCode = 50,
                    latestVersionName = "5.0.0",
                    forceUpdate = true,
                    title = "Save",
                    message = "Save msg",
                    storeUrl = null,
                )
                assertEquals(50, saved.latestVersionCode)

                // Release the stale read
                releaseRead.complete(Unit)
                readJob.await()

                // Memory and disk must have saved config
                assertEquals(50, repo.getAppConfig(forceRefresh = false)?.latestVersionCode)
                val coldRepo = AppConfigRepository(client, context)
                assertEquals(50, coldRepo.getAppConfig(forceRefresh = false)?.latestVersionCode)
            } finally {
                releaseRead.complete(Unit)
                client.close()
            }
        }
    }
}
