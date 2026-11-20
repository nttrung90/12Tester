package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.ui.viewmodels.HomeViewModel
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Independent supervisor probes: controlled HTTP races, not synthetic cache counters. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SupervisorImmediateQuotaRaceTest {
    private val owner = "supervisor-quota-owner"
    private val day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
    private fun quota(base: Int, date: LocalDate = day) =
        """{"status_date":"$date","mode":"owner","active_app_count":${base / 12},"base_required_tests":$base,"required_tests":$base,"effective_required_tests":$base,"available_apps":40,"completed_tests":0,"carry_in_tests":0,"carryover_remaining_tests":0,"logged_in_today":true,"met":false,"carryover_ready":true,"history_ready":true}"""
    private fun dashboard(base: Int) = """{"profile":{"id":"$owner","credits":1000},"daily_task":${quota(base)},"quest_summary":{}}"""
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun yesterdayQuotaPayloadCannotBePublishedAsCurrentDay() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        val responseIndex = AtomicInteger()
        val invalidPayloads = listOf(
            quota(12, day.minusDays(1)),
            quota(12).replace("\"status_date\":\"$day\",", ""),
            quota(12).replace("\"status_date\":\"$day\"", "\"status_date\":null")
        )
        val client = createSupabaseClient("https://supervisor.invalid", "test-key") {
            install(Auth) { sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache(); autoLoadFromStorage = false; autoSaveToStorage = false; enableLifecycleCallbacks = false }
            install(Postgrest)
            httpEngine = MockEngine { reply(invalidPayloads[responseIndex.getAndIncrement()]) }
        }
        var collector: Job? = null
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json.decodeFromString<UserSession>("""{"access_token":"test-token","refresh_token":"test-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$owner","aud":"authenticated"}}"""))
            val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
            val events = mutableListOf<DailyTaskChangeEvent>()
            collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { repository.dailyTaskEvents.collect { events.add(it) } }
            repeat(invalidPayloads.size) { index ->
                events.clear()
                val result = repository.refreshDailyTaskAfterCampaignChange(owner, SessionCacheManager.currentGeneration())
                assertNull("Wrong/missing date payload $index must be rejected even though local business date did not change", result)
                assertFalse("An unverifiable date must never be labeled as a current successful update", events.any { it is DailyTaskChangeEvent.Updated })
                assertTrue("A rejected payload must request a bounded scoped refresh", events.any { it is DailyTaskChangeEvent.StalePending })
            }
        } finally { collector?.cancelAndJoin(); client.close(); SessionCacheManager.clear(context) }
    }

    @Test
    fun delayedDashboardCannotOverwriteQuotaAfterCampaignMutation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val client = createSupabaseClient("https://supervisor.invalid", "test-key") {
            install(Auth) { sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache(); autoLoadFromStorage = false; autoSaveToStorage = false; enableLifecycleCallbacks = false }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                when {
                    request.url.encodedPath.contains("get_my_dashboard") -> {
                        if (requests.incrementAndGet() == 1) { started.complete(Unit); release.await(); reply(dashboard(12)) }
                        else reply(dashboard(24))
                    }
                    request.url.encodedPath.contains("get_home_feed") -> reply("""{"open":[],"paused":[],"completed":[],"owner_names":{},"stats":{}}""")
                    request.url.encodedPath.contains("get_my_daily_task_status") -> reply(quota(24))
                    request.url.encodedPath.contains("get_open_missions") -> reply("""{"missions":[]}""")
                    else -> reply("[]")
                }
            }
        }
        var vm: HomeViewModel? = null
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json.decodeFromString<UserSession>("""{"access_token":"test-token","refresh_token":"test-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$owner","aud":"authenticated"}}"""))
            val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
            val home = HomeViewModel(repository, localizer = Localizer(context)); vm = home
            withTimeout(10_000) { started.await() }
            repository.refreshDailyTaskAfterCampaignChange(owner, SessionCacheManager.currentGeneration())
            withTimeout(10_000) { home.uiState.first { it.dailyTask?.baseRequiredTests == 24 } }
            release.complete(Unit)
            withTimeout(10_000) { home.uiState.first { !it.isLoading && !it.isBackgroundSyncing } }
            assertEquals("The response begun before posting must not restore the old quota", 24, home.uiState.value.dailyTask?.baseRequiredTests)
        } finally { release.complete(Unit); vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin(); client.close(); SessionCacheManager.clear(context); Dispatchers.resetMain() }
    }

    @Test
    fun postCommitQuotaReadFailureRetriesEvenWhenPreviousQuotaWasReady() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val quotaRequests = AtomicInteger()
        val client = createSupabaseClient("https://supervisor.invalid", "test-key") {
            install(Auth) { sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache(); autoLoadFromStorage = false; autoSaveToStorage = false; enableLifecycleCallbacks = false }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                when {
                    request.url.encodedPath.contains("get_my_dashboard") -> reply(dashboard(12))
                    request.url.encodedPath.contains("get_home_feed") -> reply("""{"open":[],"paused":[],"completed":[],"owner_names":{},"stats":{}}""")
                    request.url.encodedPath.contains("get_my_daily_task_status") -> {
                        if (quotaRequests.incrementAndGet() == 1) reply("""{"message":"temporary read failure"}""", HttpStatusCode.InternalServerError)
                        else reply(quota(24))
                    }
                    request.url.encodedPath.contains("get_open_missions") -> reply("""{"missions":[]}""")
                    else -> reply("[]")
                }
            }
        }
        var vm: HomeViewModel? = null
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json.decodeFromString<UserSession>("""{"access_token":"test-token","refresh_token":"test-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$owner","aud":"authenticated"}}"""))
            val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
            val home = HomeViewModel(repository, localizer = Localizer(context)); vm = home
            withTimeout(10_000) { home.uiState.first { it.dailyTask?.baseRequiredTests == 12 && !it.isLoading && !it.isBackgroundSyncing } }
            assertEquals("Fixture starts with no carry/history retry needed", false, home.uiState.value.dailyTask?.isCarryoverHistoryPending)
            repository.refreshDailyTaskAfterCampaignChange(owner, SessionCacheManager.currentGeneration())
            withTimeout(15_000) {
                while (home.uiState.value.dailyTask?.baseRequiredTests != 24) {
                    scheduler.advanceTimeBy(1000)
                    scheduler.runCurrent()
                    delay(10) // Permit the real mock HTTP dispatcher to complete each scoped read.
                }
            }
            assertTrue("A scoped read must retry after the first failure", quotaRequests.get() >= 2)
        } finally { vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin(); client.close(); SessionCacheManager.clear(context); Dispatchers.resetMain() }
    }

    @Test
    fun diskSaveQueuedBeforeMutationCannotRestoreOldQuotaInMemoryOrDisk() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        val responseSent = CompletableDeferred<Unit>()
        val client = createSupabaseClient("https://supervisor.invalid", "test-key") {
            install(Auth) { sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache(); autoLoadFromStorage = false; autoSaveToStorage = false; enableLifecycleCallbacks = false }
            install(Postgrest)
            httpEngine = MockEngine { responseSent.complete(Unit); reply(quota(24)) }
        }
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        var gate: Thread? = null
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json.decodeFromString<UserSession>("""{"access_token":"test-token","refresh_token":"test-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$owner","aud":"authenticated"}}"""))
            val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
            val generation = SessionCacheManager.currentGeneration()
            val old = CachedSessionData(userId = owner, dateVn = day.toString(), sessionGeneration = generation,
                dailyTask = Json { ignoreUnknownKeys = true }.decodeFromString<DailyTaskStatus>(quota(12)))
            SessionCacheManager.save(context, old, generation)
            val field = SessionCacheManager::class.java.getDeclaredField("diskLock").apply { isAccessible = true }
            val lock = field.get(SessionCacheManager)
            gate = Thread {
                synchronized(lock) { acquired.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            }.apply { start() }
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            val writerThread = AtomicReference<Thread>()
            val save = async(Dispatchers.IO) {
                writerThread.set(Thread.currentThread())
                SessionCacheManager.save(context, old, generation)
            }
            withTimeout(5000) { while (writerThread.get()?.state != Thread.State.BLOCKED) delay(5) }
            val fresh = async(Dispatchers.IO) { repository.refreshDailyTaskAfterCampaignChange(owner, generation) }
            withTimeout(5000) { responseSent.await() }
            delay(100) // Allow quota decode/cache update to compete with the queued disk writer.
            release.countDown()
            withTimeout(5000) { save.await(); assertEquals(24, fresh.await()?.baseRequiredTests) }
            assertEquals("An older queued disk save must not overwrite the quota emitted after mutation", 24, SessionCacheManager.peekMemory()?.dailyTask?.baseRequiredTests)
            // A stale Home snapshot may only enter Dispatchers.IO after the mutation finished.
            SessionCacheManager.save(context, old, generation)
            assertEquals("A pre-mutation snapshot must also be rejected when its save starts after the mutation", 24, SessionCacheManager.peekMemory()?.dailyTask?.baseRequiredTests)
            // Drop RAM only to prove the actual persisted quota, preserving UID/generation/tombstone.
            SessionCacheManager::class.java.getDeclaredField("inMemoryCache").apply { isAccessible = true }.set(SessionCacheManager, null)
            assertEquals("The next offline load must also keep the latest quota", 24, SessionCacheManager.loadSync(context)?.dailyTask?.baseRequiredTests)
        } finally { release.countDown(); gate?.join(5000); client.close(); SessionCacheManager.clear(context) }
    }

    @Test
    fun persistentQuotaReadFailureHasBoundedRetries() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val quotaRequests = AtomicInteger()
        val client = createSupabaseClient("https://supervisor.invalid", "test-key") {
            install(Auth) { sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache(); autoLoadFromStorage = false; autoSaveToStorage = false; enableLifecycleCallbacks = false }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                when {
                    request.url.encodedPath.contains("get_my_dashboard") -> reply(dashboard(12))
                    request.url.encodedPath.contains("get_home_feed") -> reply("""{"open":[],"paused":[],"completed":[],"owner_names":{},"stats":{}}""")
                    request.url.encodedPath.contains("get_my_daily_task_status") -> { quotaRequests.incrementAndGet(); reply("""{"message":"persistent read failure"}""", HttpStatusCode.InternalServerError) }
                    request.url.encodedPath.contains("get_open_missions") -> reply("""{"missions":[]}""")
                    else -> reply("[]")
                }
            }
        }
        var vm: HomeViewModel? = null
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json.decodeFromString<UserSession>("""{"access_token":"test-token","refresh_token":"test-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$owner","aud":"authenticated"}}"""))
            val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
            val home = HomeViewModel(repository, localizer = Localizer(context)); vm = home
            withTimeout(10_000) { home.uiState.first { it.dailyTask?.baseRequiredTests == 12 && !it.isLoading && !it.isBackgroundSyncing } }
            repository.refreshDailyTaskAfterCampaignChange(owner, SessionCacheManager.currentGeneration())
            repeat(120) { scheduler.advanceTimeBy(500); scheduler.runCurrent(); delay(5) }
            assertTrue("At most the original read plus five automatic retries; errors must not restart their own retry budget (actual ${quotaRequests.get()})", quotaRequests.get() <= 6)
        } finally { vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin(); client.close(); SessionCacheManager.clear(context); Dispatchers.resetMain() }
    }

    @Test
    fun dashboardSnapshotAfterRepeatedEqualReadsCannotOverwriteLaterQuotaMutation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        SessionCacheManager.clear(context)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val currentBase = AtomicInteger(24)
        val failQuotaRead = AtomicBoolean(false)
        val gateOldFailure = AtomicBoolean(false)
        val oldFailureStarted = CompletableDeferred<Unit>()
        val releaseOldFailure = CompletableDeferred<Unit>()
        val client = createSupabaseClient("https://supervisor.invalid", "test-key") {
            install(Auth) { sessionManager = MemorySessionManager(); codeVerifierCache = MemoryCodeVerifierCache(); autoLoadFromStorage = false; autoSaveToStorage = false; enableLifecycleCallbacks = false }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                when {
                    request.url.encodedPath.contains("get_my_dashboard") -> reply(dashboard(currentBase.get()))
                    request.url.encodedPath.contains("get_home_feed") -> reply("""{"open":[],"paused":[],"completed":[],"owner_names":{},"stats":{}}""")
                    request.url.encodedPath.contains("get_my_daily_task_status") -> when {
                        gateOldFailure.getAndSet(false) -> {
                            oldFailureStarted.complete(Unit)
                            releaseOldFailure.await()
                            reply("""{"message":"obsolete read failure"}""", HttpStatusCode.InternalServerError)
                        }
                        failQuotaRead.get() -> reply("""{"message":"post-commit read failure"}""", HttpStatusCode.InternalServerError)
                        else -> reply(quota(currentBase.get()))
                    }
                    request.url.encodedPath.contains("get_open_missions") -> reply("""{"missions":[]}""")
                    else -> reply("[]")
                }
            }
        }
        var vm: HomeViewModel? = null
        try {
            client.auth.awaitInitialization()
            client.auth.importSession(Json.decodeFromString<UserSession>("""{"access_token":"test-token","refresh_token":"test-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$owner","aud":"authenticated"}}"""))
            val generation = SessionCacheManager.currentGeneration()
            SessionCacheManager.save(context, CachedSessionData(userId = owner, userCredits = 7, dateVn = day.toString(), sessionGeneration = generation,
                dailyTask = Json { ignoreUnknownKeys = true }.decodeFromString<DailyTaskStatus>(quota(12))), generation)
            val repository = CampaignRepository(client, Localizer(context), businessDayClock = { day })
            // Actual repeated successful responses with equal content, e.g. replays or refreshes.
            repeat(4) { repository.refreshDailyTaskAfterCampaignChange(owner, generation) }
            val home = HomeViewModel(repository, context, Localizer(context)); vm = home
            withTimeout(10_000) {
                home.uiState.first { it.dailyTask?.baseRequiredTests == 24 && !it.isLoading && !it.isBackgroundSyncing }
                while (SessionCacheManager.peekMemory()?.userCredits != 1000) delay(5)
            }
            val snapshotBeforeNextMutation = SessionCacheManager.peekMemory()!!
            failQuotaRead.set(true)
            repository.refreshDailyTaskAfterCampaignChange(owner, generation)
            assertEquals(true, SessionCacheManager.peekMemory()?.isDailyTaskPendingRefresh)
            SessionCacheManager.save(context, snapshotBeforeNextMutation, generation)
            assertEquals("An old ready snapshot must not erase a pending post-mutation refresh", true, SessionCacheManager.peekMemory()?.isDailyTaskPendingRefresh)
            val snapshotWhilePending = SessionCacheManager.peekMemory()!!
            failQuotaRead.set(false)
            currentBase.set(36)
            repository.refreshDailyTaskAfterCampaignChange(owner, generation, triggerSource = "stale_pending_retry")
            assertEquals(36, SessionCacheManager.peekMemory()?.dailyTask?.baseRequiredTests)
            SessionCacheManager.save(context, snapshotWhilePending, generation)
            assertEquals("An earlier pending snapshot must not restore obsolete quota after a successful scoped retry", 36, SessionCacheManager.peekMemory()?.dailyTask?.baseRequiredTests)
            assertEquals(false, SessionCacheManager.peekMemory()?.isDailyTaskPendingRefresh)
            SessionCacheManager.save(context, snapshotBeforeNextMutation, generation)
            assertEquals("Repeated equal responses must not let an older real Home snapshot outrank the next task mutation", 36, SessionCacheManager.peekMemory()?.dailyTask?.baseRequiredTests)
            gateOldFailure.set(true)
            val obsoleteRead = async(Dispatchers.IO) { repository.refreshDailyTaskAfterCampaignChange(owner, generation) }
            withTimeout(5000) { oldFailureStarted.await() }
            currentBase.set(12)
            repository.refreshDailyTaskAfterCampaignChange(owner, generation)
            releaseOldFailure.complete(Unit)
            withTimeout(5000) { obsoleteRead.await() }
            assertEquals("An obsolete failed read must not mark a newer successful quota stale in cache", false, SessionCacheManager.peekMemory()?.isDailyTaskPendingRefresh)
            SessionCacheManager.save(context, snapshotBeforeNextMutation, generation)
            assertEquals("The latest lower quota must also outrank an older higher quota", 12, SessionCacheManager.peekMemory()?.dailyTask?.baseRequiredTests)
        } finally { releaseOldFailure.complete(Unit); vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin(); client.close(); SessionCacheManager.clear(context); Dispatchers.resetMain() }
    }
}
