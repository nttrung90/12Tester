package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.ntt.crosstest.ui.viewmodels.AdminUsersViewModel
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.SupabaseClient
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminDailyTaskHardeningRegressionTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        SessionCacheManager.clear(context)
    }

    @After
    fun cleanup() {
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
    }

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }

    private suspend fun login(c: SupabaseClient, uid: String) {
        c.auth.awaitInitialization()
        c.auth.importSession(
            json.decodeFromString<UserSession>(
                """{"access_token":"fixture-$uid","refresh_token":"fixture-ref-$uid","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""
            )
        )
    }

    private fun config(rev: String = "rev-A", futureCount: Int = 0): String {
        val futureItems = (1..futureCount).joinToString(",") { i ->
            """{"id":$i,"tests_per_active_app":6,"effective_from":"2026-11-${String.format("%02d", (i % 28) + 1)}","is_baseline":false,"operator_note":"Lịch $i"}"""
        }
        val hasMore = futureCount >= 50
        val nextCursorDate = if (hasMore) "\"2026-11-22\"" else "null"
        val nextCursorId = if (hasMore) "50" else "null"
        return """{
            "today":"2026-10-30",
            "timezone":"Asia/Ho_Chi_Minh",
            "revision":"$rev",
            "active_policy":{"id":1,"tests_per_active_app":6,"is_baseline":true},
            "future_policies":[$futureItems],
            "has_more":$hasMore,
            "next_cursor_date":$nextCursorDate,
            "next_cursor_id":$nextCursorId,
            "total_future_policies":$futureCount
        }"""
    }

    private fun vm(c: SupabaseClient) = AdminUsersViewModel(
        CampaignRepository(c, Localizer(context)),
        AuthRepository(c),
        AppConfigRepository(c),
        Localizer(context)
    )

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(5000) {
            while (!predicate()) delay(5)
        }
    }

    @Test
    fun concurrentSaveAndCancelMustBeDebounced() = runBlocking {
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val saveCalls = AtomicInteger(0)
        val cancelCalls = AtomicInteger(0)

        val c = client { req ->
            when {
                req.url.toString().endsWith("admin_get_daily_task_config") -> reply(config("rev-1"))
                req.url.toString().endsWith("admin_schedule_daily_task_policy") -> {
                    saveCalls.incrementAndGet()
                    saveEntered.complete(Unit)
                    releaseSave.await()
                    reply("""{"success":true,"config":${config("rev-2")},"revision":"rev-2"}""")
                }
                req.url.toString().endsWith("admin_cancel_daily_task_policy") -> {
                    cancelCalls.incrementAndGet()
                    reply("""{"success":true,"config":${config("rev-3")},"revision":"rev-3"}""")
                }
                else -> reply("[]")
            }
        }
        login(c, "admin-A")
        val v = vm(c)
        try {
            v.loadDailyTaskConfig()
            waitUntil { v.uiState.value.dailyTaskConfig != null }

            // Phát động save đầu tiên
            v.saveDailyTaskSchedule(5, "2026-10-31", "Save 1")
            withTimeout(5000) { saveEntered.await() }
            assertTrue("ViewModel phải đang ở trạng thái saving", v.uiState.value.isDailyTaskSaving)

            // Trong lúc save đang chạy, cố bấm save lần 2 và cancel
            v.saveDailyTaskSchedule(6, "2026-10-31", "Save 2 (concurrent)")
            v.cancelDailyTaskSchedule("2026-10-31", "Cancel (concurrent)")

            assertEquals("Save thứ 2 phải bị chặn ngay trong ViewModel", 1, saveCalls.get())
            assertEquals("Cancel khi đang save phải bị chặn ngay trong ViewModel", 0, cancelCalls.get())

            // Nhả save đầu tiên
            releaseSave.complete(Unit)
            waitUntil { !v.uiState.value.isDailyTaskSaving }

            assertEquals("rev-2", v.uiState.value.dailyTaskConfig?.revision)
            assertNotNull(v.uiState.value.dailyTaskActionMessage)
        } finally {
            releaseSave.complete(Unit)
            v.viewModelScope.cancel()
            c.close()
        }
    }

    @Test
    fun staleConfigResponseMustNotOverwriteNewerMutationSnapshot() = runBlocking {
        val configEntered = CompletableDeferred<Unit>()
        val releaseConfig = CompletableDeferred<Unit>()
        val n = AtomicInteger(0)

        val c = client { req ->
            when {
                req.url.toString().endsWith("admin_get_daily_task_config") -> {
                    if (n.incrementAndGet() == 1) {
                        reply(config("rev-initial"))
                    } else {
                        configEntered.complete(Unit)
                        releaseConfig.await()
                        reply(config("rev-stale"))
                    }
                }
                req.url.toString().endsWith("admin_schedule_daily_task_policy") -> {
                    reply("""{"success":true,"config":${config("rev-saved")},"revision":"rev-saved"}""")
                }
                else -> reply("[]")
            }
        }
        login(c, "admin-A")
        val v = vm(c)
        try {
            // Tải config ban đầu
            v.loadDailyTaskConfig()
            waitUntil { v.uiState.value.dailyTaskConfig?.revision == "rev-initial" }

            // Bắt đầu một request tải config mới (bị hoãn)
            v.loadDailyTaskConfig()
            withTimeout(5000) { configEntered.await() }

            // Trong lúc tải đang hoãn, lưu lịch thành công cập nhật sang rev-saved
            v.saveDailyTaskSchedule(4, "2026-10-31", "Ghi chú")
            waitUntil { v.uiState.value.dailyTaskConfig?.revision == "rev-saved" }

            // Nhả response config cũ rev-stale trả về muộn
            releaseConfig.complete(Unit)
            delay(150)

            // Response cũ trả về muộn không được ghi đè rev-saved
            assertEquals("Config cũ trả về muộn không được ghi đè snapshot mutation mới", "rev-saved", v.uiState.value.dailyTaskConfig?.revision)
        } finally {
            releaseConfig.complete(Unit)
            v.viewModelScope.cancel()
            c.close()
        }
    }

    @Test
    fun initialLoadFailureMustNotExposeFakeLevelsOrAllowMutation() = runBlocking {
        val c = client { req ->
            when {
                req.url.toString().endsWith("admin_get_daily_task_config") ->
                    reply("""{"message":"Internal server error"}""", HttpStatusCode.InternalServerError)
                else -> reply("[]")
            }
        }
        login(c, "admin-A")
        val v = vm(c)
        try {
            v.loadDailyTaskConfig()
            waitUntil { !v.uiState.value.isDailyTaskLoading }

            // Cấu hình phải là null, không bịa đặt tỷ lệ 3 hay 6
            assertNull("Cấu hình khi lỗi phải là null", v.uiState.value.dailyTaskConfig)
            assertNotNull("Lỗi phải được hiển thị", v.uiState.value.dailyTaskError)

            // Khi chưa có revision từ server, gọi save phải bị chặn
            v.saveDailyTaskSchedule(5, "2026-10-31", "Note")
            assertFalse("Không được bật isSaving khi thiếu revision", v.uiState.value.isDailyTaskSaving)
        } finally {
            v.viewModelScope.cancel()
            c.close()
        }
    }

    @Test
    fun conflictPreservesDraftAndMaintainsErrorMessageAcrossReload() = runBlocking {
        val reloadN = AtomicInteger(0)
        val c = client { req ->
            when {
                req.url.toString().endsWith("admin_get_daily_task_config") -> {
                    val count = reloadN.incrementAndGet()
                    if (count == 1) reply(config("rev-1")) else reply(config("rev-2"))
                }
                req.url.toString().endsWith("admin_schedule_daily_task_policy") -> {
                    reply("""{"code":"P0001","message":"DAILY_TASK_POLICY_CONFLICT"}""", HttpStatusCode.BadRequest)
                }
                else -> reply("[]")
            }
        }
        login(c, "admin-A")
        val v = vm(c)
        try {
            v.loadDailyTaskConfig()
            waitUntil { v.uiState.value.dailyTaskConfig != null }

            // Admin sửa bản nháp sang tỷ lệ 8, ngày 2026-11-05
            v.setDailyTaskDraft(ratio = 8, effectiveFrom = "2026-11-05", note = "Tăng chỉ tiêu")
            v.saveDailyTaskSchedule(8, "2026-11-05", "Tăng chỉ tiêu")

            waitUntil { v.uiState.value.dailyTaskConfig?.revision == "rev-2" && !v.uiState.value.isDailyTaskLoading }

            // Bản nháp vẫn phải giữ nguyên 8 và ngày 2026-11-05
            assertEquals(8, v.uiState.value.dailyTaskDraftRatio)
            assertEquals("2026-11-05", v.uiState.value.dailyTaskDraftEffectiveFrom)
            assertEquals("Tăng chỉ tiêu", v.uiState.value.dailyTaskDraftNote)

            // Thông báo lỗi xung đột phải còn nguyên để admin đọc và quyết định
            assertNotNull("Lỗi conflict phải hiển thị sau reload ngầm", v.uiState.value.dailyTaskError)
        } finally {
            v.viewModelScope.cancel()
            c.close()
        }
    }

    @Test
    fun cancelFailurePreservesCancelTargetAndReason() = runBlocking {
        val c = client { req ->
            when {
                req.url.toString().endsWith("admin_get_daily_task_config") -> reply(config("rev-1"))
                req.url.toString().endsWith("admin_cancel_daily_task_policy") ->
                    reply("""{"code":"P0001","message":"DAILY_TASK_POLICY_CONFLICT"}""", HttpStatusCode.BadRequest)
                else -> reply("[]")
            }
        }
        login(c, "admin-A")
        val v = vm(c)
        try {
            v.loadDailyTaskConfig()
            waitUntil { v.uiState.value.dailyTaskConfig != null }

            val initialSignal = v.uiState.value.dailyTaskSaveSignal
            v.cancelDailyTaskSchedule("2026-10-31", "Lý do hủy quan trọng")
            waitUntil { !v.uiState.value.isDailyTaskCancelling }

            // Khi thất bại, saveSignal không được tăng
            assertEquals("Thất bại không được tăng saveSignal", initialSignal, v.uiState.value.dailyTaskSaveSignal)
            // Lỗi phải xuất hiện
            assertNotNull("Lỗi hủy phải được ghi nhận", v.uiState.value.dailyTaskError)
        } finally {
            v.viewModelScope.cancel()
            c.close()
        }
    }

    @Test
    fun paginatingAll55FutureSchedulesMergesWithoutLossOrDuplicate() = runBlocking {
        val c = client { req ->
            when {
                req.url.toString().endsWith("admin_get_daily_task_config") ->
                    reply(config("rev-1", futureCount = 50))
                req.url.toString().endsWith("admin_list_daily_task_policies") -> {
                    val items = (51..55).joinToString(",") { i ->
                        """{"id":$i,"tests_per_active_app":4,"effective_from":"2026-11-${String.format("%02d", (i % 28) + 1)}","is_baseline":false,"operator_note":"Lịch $i"}"""
                    }
                    reply("""{"items":[$items],"has_more":false,"next_cursor_date":null,"next_cursor_id":null}""")
                }
                else -> reply("[]")
            }
        }
        login(c, "admin-A")
        val v = vm(c)
        try {
            v.loadDailyTaskConfig()
            waitUntil { v.uiState.value.dailyTaskConfig != null && v.uiState.value.dailyTaskFuturePolicies.size == 50 }

            assertEquals(50, v.uiState.value.dailyTaskFuturePolicies.size)
            assertTrue(v.uiState.value.dailyTaskFutureHasMore)

            // Tải thêm trang 2
            v.loadMoreDailyTaskPolicies()
            waitUntil { v.uiState.value.dailyTaskFuturePolicies.size == 55 }

            // Phải có đủ 55 lịch, không trùng lặp và không bỏ sót
            assertEquals(55, v.uiState.value.dailyTaskFuturePolicies.size)
            assertFalse("Trang 2 là trang cuối", v.uiState.value.dailyTaskFutureHasMore)
            val ids = v.uiState.value.dailyTaskFuturePolicies.map { it.id }.toSet()
            assertEquals("Tất cả 55 ID đều duy nhất", 55, ids.size)
            assertTrue("Có mặt lịch thứ 51", ids.contains(51L))
            assertTrue("Có mặt lịch thứ 55", ids.contains(55L))
        } finally {
            v.viewModelScope.cancel()
            c.close()
        }
    }

    @Test
    fun mutationInvalidatesHistoryAndRefreshFetchesNewAudit() = runBlocking {
        var historyCount = 1
        val c = client { req ->
            when {
                req.url.toString().endsWith("admin_get_daily_task_config") -> reply(config("rev-1"))
                req.url.toString().endsWith("admin_schedule_daily_task_policy") -> {
                    historyCount = 2
                    reply("""{"success":true,"config":${config("rev-2")},"revision":"rev-2"}""")
                }
                req.url.toString().endsWith("admin_get_daily_task_policy_history") -> {
                    if (historyCount == 1) {
                        reply("""{"items":[{"id":1,"operation":"INSERT","changed_at":"2026-10-30T00:00:00Z","actor_id":"1111","actor_name":"Admin 1"}],"has_more":false}""")
                    } else {
                        reply("""{"items":[{"id":2,"operation":"UPDATE","changed_at":"2026-10-30T01:00:00Z","actor_id":"1111","actor_name":"Admin 1"},{"id":1,"operation":"INSERT","changed_at":"2026-10-30T00:00:00Z","actor_id":"1111","actor_name":"Admin 1"}],"has_more":false}""")
                    }
                }
                else -> reply("[]")
            }
        }
        login(c, "admin-A")
        val v = vm(c)
        try {
            v.loadDailyTaskConfig()
            waitUntil { v.uiState.value.dailyTaskConfig != null }

            // Nạp history lần đầu
            v.loadDailyTaskHistory(true)
            waitUntil { v.uiState.value.dailyTaskHistory.isNotEmpty() }
            assertEquals(1, v.uiState.value.dailyTaskHistory.size)
            assertEquals(1L, v.uiState.value.dailyTaskHistory[0].id)

            // Lưu lịch mới
            v.saveDailyTaskSchedule(6, "2026-10-31", "Ghi chú mới")
            waitUntil { v.uiState.value.dailyTaskConfig?.revision == "rev-2" }

            // Mutation thành công phải xóa danh sách history cũ
            assertTrue("History cũ phải được invalidate sau mutation", v.uiState.value.dailyTaskHistory.isEmpty())

            // Làm mới history
            v.loadDailyTaskHistory(true)
            waitUntil { v.uiState.value.dailyTaskHistory.isNotEmpty() }
            assertEquals(2, v.uiState.value.dailyTaskHistory.size)
            assertEquals(2L, v.uiState.value.dailyTaskHistory[0].id)
        } finally {
            v.viewModelScope.cancel()
            c.close()
        }
    }
}
