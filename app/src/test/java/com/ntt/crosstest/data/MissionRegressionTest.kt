package com.ntt.crosstest.data

import android.app.Application
import com.ntt.crosstest.ui.viewmodels.MissionCreateUiState
import com.ntt.crosstest.ui.viewmodels.HomeUiState
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailUiState
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionRegressionTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        SessionCacheManager.clear(context)
    }

    @After
    fun tearDown() {
        SessionCacheManager.clear(context)
    }

    /**
     * M01: Kiểm tra logic hiển thị của Mission khi nhiệm vụ full nhưng tester đã tham gia (hasJoined = true).
     * Phải ưu tiên người đã nhận được đi test app thay vì bị chặn bởi nhãn full.
     */
    @Test
    fun `M01 - when mission is full and user has joined then action prioritizes joined over full`() {
        val joinedAndFullMission = Mission(
            id = "m1",
            title = "Test Mission",
            maxParticipants = 5,
            participantCount = 5,
            status = "full",
            hasJoined = true,
            hasCompleted = false,
        )

        assertTrue("Mission phải là full", joinedAndFullMission.isFull)
        assertTrue("Mission phải ghi nhận hasJoined = true", joinedAndFullMission.hasJoined)
        assertFalse("Mission chưa hoàn thành", joinedAndFullMission.hasCompleted)

        // Mô phỏng cấu trúc when {} trong QuestsScreen:
        // hasCompleted -> Mine -> Paused -> Ended -> hasJoined -> isFull -> Join CTA
        val action = when {
            joinedAndFullMission.hasCompleted -> "COMPLETED"
            joinedAndFullMission.isMine -> "MINE"
            joinedAndFullMission.isPaused -> "PAUSED"
            joinedAndFullMission.isStopped || joinedAndFullMission.isExpired -> "ENDED"
            joinedAndFullMission.hasJoined -> "GO_TEST_APP"
            joinedAndFullMission.isFull -> "SHOW_FULL_LABEL"
            else -> "JOIN"
        }

        assertEquals("M01: Khi full nhưng tester đã join, phải hiện nút đi test app", "GO_TEST_APP", action)

        // Đối với tester chưa join:
        val notJoinedFullMission = joinedAndFullMission.copy(hasJoined = false)
        val notJoinedAction = when {
            notJoinedFullMission.hasCompleted -> "COMPLETED"
            notJoinedFullMission.isMine -> "MINE"
            notJoinedFullMission.isPaused -> "PAUSED"
            notJoinedFullMission.isStopped || notJoinedFullMission.isExpired -> "ENDED"
            notJoinedFullMission.hasJoined -> "GO_TEST_APP"
            notJoinedFullMission.isFull -> "SHOW_FULL_LABEL"
            else -> "JOIN"
        }
        assertEquals("M01: Tester chưa join thấy nhãn Đã đủ người", "SHOW_FULL_LABEL", notJoinedAction)
    }

    /**
     * M08: Kiểm tra các trạng thái lỗi riêng biệt của UI, phân biệt lỗi tải,
     * tất cả app đang bận (allAppsBusy), không có app nào, và danh sách rỗng.
     */
    @Test
    fun `M08 - distinct UI error and busy states for missions`() {
        // MissionCreateUiState phân biệt lỗi tải và allAppsBusy
        val errorState = MissionCreateUiState(
            isLoading = false,
            initialLoadError = "Lỗi kết nối mạng",
            allAppsBusy = false,
            myCampaigns = emptyList(),
        )
        assertNotNull("Phải có error state", errorState.initialLoadError)
        assertFalse("Không phải allAppsBusy khi có error", errorState.allAppsBusy)

        val allBusyState = MissionCreateUiState(
            isLoading = false,
            initialLoadError = null,
            allAppsBusy = true,
            myCampaigns = emptyList(),
        )
        assertNull("Không có error", allBusyState.initialLoadError)
        assertTrue("Tất cả app đang bận", allBusyState.allAppsBusy)

        // HomeUiState có missionsError riêng biệt
        val homeState = HomeUiState(
            isMissionsLoading = false,
            missions = emptyList(),
            missionsError = "Không thể tải danh sách nhiệm vụ",
        )
        assertNotNull("Home state lưu trữ missionsError", homeState.missionsError)

        // OwnerAppDetailUiState có missionError riêng biệt
        val ownerState = OwnerAppDetailUiState(
            isMissionLoading = false,
            mission = null,
            missionError = "Lỗi tải thông tin nhiệm vụ",
        )
        assertNotNull("Owner state lưu trữ missionError", ownerState.missionError)
    }

    /**
     * M09: Cache openMissions phải được scope theo userId để không bị rò rỉ dữ liệu
     * giữa các phiên / người dùng khác nhau (has_joined, is_mine).
     */
    @Test
    fun `M09 - getOpenMissions cache is scoped to userId`() = runBlocking {
        var callCount = 0
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            if (path.contains("get_open_missions")) {
                callCount++
                respond(
                    content = """{"missions":[{"id":"m1","title":"Mission 1","status":"open","has_joined":true,"reward_credits":10,"max_participants":5,"participant_count":1,"ends_at":"2026-10-28T00:00:00Z","created_at":"2026-10-27T00:00:00Z","campaign_id":"c1","app_name":"App 1","creator_id":"u1","creator_name":"User 1","is_mine":false,"has_completed":false}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

        val supabase = createSupabaseClient("https://mock.supabase.co", "anon-key") {
            httpEngine = mockEngine
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
        }

        val repo = CampaignRepository(supabase, Localizer(context))

        // 1. Gọi lần 1 -> gọi network (callCount = 1)
        val m1 = repo.getOpenMissions(forceRefresh = false)
        assertEquals(1, m1.size)
        assertEquals(1, callCount)

        // 2. Gọi lần 2 ngay sau đó -> trúng cache (callCount vẫn = 1)
        val m2 = repo.getOpenMissions(forceRefresh = false)
        assertEquals(1, m2.size)
        assertEquals(1, callCount)

        // 3. Đổi session generation (đăng nhập / đổi tài khoản) -> cache cũ bị vô hiệu hóa
        repo.sessionGeneration.incrementAndGet()
        val m3 = repo.getOpenMissions(forceRefresh = false)
        assertEquals(1, m3.size)
        assertEquals("Đổi session generation phải kích hoạt gọi lại network", 2, callCount)
    }
}
