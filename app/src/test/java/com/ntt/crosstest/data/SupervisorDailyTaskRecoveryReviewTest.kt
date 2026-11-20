package com.ntt.crosstest.data

import android.app.Application
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Independent supervisor probes: exercise repository behavior through its public API. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SupervisorDailyTaskRecoveryReviewTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() { SessionCacheManager.clear(context) }
    @After fun tearDown() { SessionCacheManager.clear(context) }

    private fun client(dispatcher: CoroutineDispatcher = Dispatchers.IO, hang: Boolean = false) = createSupabaseClient("https://supervisor.fixture.invalid", "fixture-key") {
        install(Auth) {
            sessionManager = MemorySessionManager()
            codeVerifierCache = MemoryCodeVerifierCache()
            autoLoadFromStorage = false
            autoSaveToStorage = false
            enableLifecycleCallbacks = false
        }
        install(Postgrest)
        httpEngine = MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler {
                if (hang) awaitCancellation()
                else throw CancellationException("supervisor-request-cancelled")
            }
        })
    }

    @Test
    fun invalidatingCommittedReportsInvalidatesDailyTaskVersion() = runBlocking {
        val client = client()
        try {
            val repository = CampaignRepository(client, Localizer(context))
            val before = repository.getMutationVersion("daily_task")
            repository.invalidateReportsAndStats("supervisor-campaign")
            assertTrue(
                "An older daily-task request must become stale when a committed report invalidates caches",
                repository.getMutationVersion("daily_task") > before,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellationFromDailyTaskRequestIsNotConvertedToNull() = runBlocking {
        val client = client()
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
                """{"access_token":"fixture-token","refresh_token":"fixture-refresh","token_type":"bearer","expires_in":3600,"user":{"id":"supervisor-owner","aud":"authenticated"}}"""
            ))
            val repository = CampaignRepository(client, Localizer(context))
            var cancellationObserved = false
            try {
                repository.getMyDailyTaskStatus()
            } catch (_: CancellationException) {
                cancellationObserved = true
            }
            assertTrue(
                "Cancellation must propagate so a stale request cannot continue into a retry or publish path",
                cancellationObserved,
            )
        } finally {
            client.close()
        }
    }

    @Test(timeout = 20_000)
    fun repositoryOwnRequestTimeoutSettlesWithoutCancellingItsCaller() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val client = client(dispatcher, hang = true)
        var pending: kotlinx.coroutines.Deferred<DailyTaskStatus?>? = null
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
                """{"access_token":"fixture-token","refresh_token":"fixture-refresh","token_type":"bearer","expires_in":3600,"user":{"id":"supervisor-owner","aud":"authenticated"}}"""
            ))
            val repository = CampaignRepository(client, Localizer(context))
            val request = async(dispatcher) { repository.getMyDailyTaskStatus() }
            pending = request
            scheduler.advanceTimeBy(15_000L)
            scheduler.runCurrent()
            assertTrue("The repository must bound a hanging task request", request.isCompleted)
            assertFalse("Its own request timeout is a load failure, not caller cancellation", request.isCancelled)
            assertNull("The nullable compatibility API must settle on a load failure", request.await())
        } finally {
            pending?.cancel()
            client.close()
        }
    }
}
