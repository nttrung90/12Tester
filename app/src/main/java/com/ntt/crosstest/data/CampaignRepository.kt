package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import com.ntt.crosstest.R
import com.ntt.crosstest.util.VnTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Một app đủ điều kiện để gợi ý làm bài test kế tiếp.
 * [installedCount] là số người đã cài app (campaign_members) — dùng để ưu tiên
 * app có lượt cài thấp cho tài khoản mới.
 */
data class NextCampaignCandidate(
    val campaign: Campaign,
    val installedCount: Int = 0,
)

/**
 * All database access for campaigns, members, check-ins and feedback.
 * Row Level Security on the database guarantees users can only write their own rows;
 * every method also scopes writes to the current user id.
 */
class CampaignRepository(
    private val supabase: SupabaseClient,
    private val localizer: Localizer,
) {

    companion object {
        const val CACHE_TTL_MS = 3 * 60 * 1000L // 3 minutes cache TTL
        private const val SHORT_CACHE_TTL_MS = 60 * 1000L // warnings/notifications/logs
        private const val PACKAGES_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val AUTO_ACCEPT_THROTTLE_MS = 10 * 60 * 1000L

        /**
         * Danh sách cột của bảng `campaigns` khớp model [Campaign].
         * Chọn cột tường minh thay vì `select *` giúp giảm payload cho đường legacy.
         */
        private val CAMPAIGN_COLUMNS = Columns.list(
            "id", "owner_id", "app_name", "package_name", "play_url", "testing_url",
            "description", "target_testers", "target_days", "start_date", "status",
            "icon_url", "test_username", "test_password", "tip_credits", "is_boosted",
            "slot_number", "paused_reason", "admin_note", "completed_at", "created_at",
        )
    }

    // Cache RAM có TTL. Các biến vô hướng đều @Volatile vì repository được gọi
    // từ cả main thread (ViewModel) lẫn thread nền (WorkManager nộp báo cáo).
    // Hợp đồng ghi: luôn ghi GIÁ TRỊ trước, TIMESTAMP sau — nhờ happens-before
    // của volatile, reader thấy timestamp mới thì chắc chắn thấy cả giá trị mới.
    @Volatile
    private var cachedOpenCampaigns: List<Campaign>? = null
    @Volatile
    private var openCampaignsTimestamp: Long = 0L

    @Volatile
    private var cachedPausedCampaigns: List<Campaign>? = null
    @Volatile
    private var pausedCampaignsTimestamp: Long = 0L

    @Volatile
    private var cachedCompletedCampaigns: List<Campaign>? = null
    @Volatile
    private var completedCampaignsTimestamp: Long = 0L

    /** Stats theo từng campaign id, mỗi entry mang timestamp riêng (TTL độc lập). */
    private val statsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<CampaignStats, Long>>()

    private val profileCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Profile, Long>>()

    @Volatile
    private var cachedMyCampaigns: List<Campaign>? = null
    @Volatile
    private var myCampaignsTimestamp: Long = 0L

    @Volatile
    private var cachedMyJoinedCampaigns: List<Campaign>? = null
    @Volatile
    private var myJoinedCampaignsTimestamp: Long = 0L

    @Volatile
    private var cachedLeaderboard: List<LeaderboardUser>? = null
    @Volatile
    private var leaderboardTimestamp: Long = 0L
    @Volatile
    private var cachedTestLeaderboard: List<LeaderboardUser>? = null
    @Volatile
    private var testLeaderboardTimestamp: Long = 0L

    /** Hạng của tôi theo từng loại bảng ("credit"/"test") — cache riêng từng sort. */
    private val myRankCache = java.util.concurrent.ConcurrentHashMap<String, Pair<MyRank, Long>>()

    @Volatile
    private var cachedMyProfile: Profile? = null
    @Volatile
    private var myProfileTimestamp: Long = 0L

    @Volatile
    private var cachedTodayCheckins: Int? = null
    @Volatile
    private var todayCheckinsTimestamp: Long = 0L

    @Volatile
    private var cachedPendingReports: Int? = null
    @Volatile
    private var pendingReportsTimestamp: Long = 0L

    @Volatile
    private var cachedQuestSummary: QuestSummary? = null
    @Volatile
    private var questSummaryTimestamp: Long = 0L

    private val ownerStatsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<CampaignOwnerStats, Long>>()
    private val campaignCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Campaign, Long>>()
    @Volatile
    private var cachedSlotsInfo: List<SlotInfo>? = null
    @Volatile
    private var slotsInfoTimestamp: Long = 0L
    private val reportsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<TesterReport>, Long>>()
    private val testersCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<TesterItem>, Long>>()
    @Volatile
    private var cachedAllProfiles: List<Profile>? = null
    @Volatile
    private var allProfilesTimestamp: Long = 0L

    private val membersCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<CampaignMember>, Long>>()
    private val checkinsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Checkin>, Long>>()
    private val feedbacksCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<Feedback>, Long>>()
    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<CampaignDetailResult, Long>>()
    @Volatile
    private var cachedUnreadWarnings: List<UserWarning>? = null
    @Volatile
    private var unreadWarningsTimestamp: Long = 0L
    @Volatile
    private var cachedWeeklyWarnings: Int? = null
    @Volatile
    private var weeklyWarningsTimestamp: Long = 0L
    @Volatile
    private var cachedCreditLogs: List<CreditLog>? = null
    @Volatile
    private var creditLogsTimestamp: Long = 0L
    @Volatile
    private var cachedTopupPackages: List<TopupPackage>? = null
    @Volatile
    private var topupPackagesTimestamp: Long = 0L
    @Volatile
    private var cachedNotifications: List<UserNotification>? = null
    @Volatile
    private var notificationsTimestamp: Long = 0L
    @Volatile
    private var cachedHomeFeed: HomeFeedResult? = null
    @Volatile
    private var homeFeedTimestamp: Long = 0L
    @Volatile
    private var cachedDashboard: MyDashboardResult? = null
    @Volatile
    private var dashboardCacheUid: String? = null
    @Volatile
    private var dashboardHasLeaderboard = false
    @Volatile
    private var dashboardTimestamp: Long = 0L
    @Volatile
    private var lastAutoAcceptRunMs: Long = 0L
    @Volatile
    private var cachedUserSlots: List<UserSlot>? = null
    @Volatile
    private var userSlotsTimestamp: Long = 0L
    @Volatile
    private var cachedOpenMissions: List<Mission>? = null
    @Volatile
    private var openMissionsTimestamp: Long = 0L

    /**
     * Single-flight: nhiều caller cùng khoá gọi song song chỉ chạy 1 truy vấn.
     * Người chờ sau khi khoá mở sẽ đọc lại cache (đã được người thắng ghi).
     */
    private val flightLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> deduped(
        key: String,
        forceRefresh: Boolean,
        isFresh: () -> Boolean,
        getCached: () -> T?,
        fetch: suspend () -> T,
    ): T {
        if (forceRefresh) return fetch()
        if (isFresh()) getCached()?.let { return it }
        val lock = flightLocks.computeIfAbsent(key) { Mutex() }
        if (!lock.tryLock()) {
            lock.lock()
            lock.unlock()
        } else {
            return try {
                if (isFresh()) getCached()?.let { return it }
                fetch()
            } finally {
                lock.unlock()
            }
        }
        // Sau khi caller cùng khoá hoàn tất: ưu tiên cache mới, nếu vẫn cũ thì tự truy vấn.
        if (isFresh()) getCached()?.let { return it }
        return fetch()
    }

    fun clearMemoryCache() {
        cachedOpenCampaigns = null
        openCampaignsTimestamp = 0L
        cachedPausedCampaigns = null
        pausedCampaignsTimestamp = 0L
        cachedCompletedCampaigns = null
        completedCampaignsTimestamp = 0L
        statsCache.clear()
        profileCache.clear()
        cachedMyCampaigns = null
        myCampaignsTimestamp = 0L
        cachedMyJoinedCampaigns = null
        myJoinedCampaignsTimestamp = 0L
        cachedLeaderboard = null
        leaderboardTimestamp = 0L
        cachedTestLeaderboard = null
        testLeaderboardTimestamp = 0L
        myRankCache.clear()
        cachedMyProfile = null
        myProfileTimestamp = 0L
        cachedTodayCheckins = null
        todayCheckinsTimestamp = 0L
        cachedPendingReports = null
        pendingReportsTimestamp = 0L
        cachedQuestSummary = null
        questSummaryTimestamp = 0L
        ownerStatsCache.clear()
        campaignCache.clear()
        cachedSlotsInfo = null
        slotsInfoTimestamp = 0L
        cachedUserSlots = null
        userSlotsTimestamp = 0L
        cachedOpenMissions = null
        openMissionsTimestamp = 0L
        reportsCache.clear()
        testersCache.clear()
        cachedAllProfiles = null
        allProfilesTimestamp = 0L
        membersCache.clear()
        checkinsCache.clear()
        feedbacksCache.clear()
        detailCache.clear()
        cachedUnreadWarnings = null
        unreadWarningsTimestamp = 0L
        cachedWeeklyWarnings = null
        weeklyWarningsTimestamp = 0L
        cachedCreditLogs = null
        creditLogsTimestamp = 0L
        cachedTopupPackages = null
        topupPackagesTimestamp = 0L
        cachedNotifications = null
        notificationsTimestamp = 0L
        cachedHomeFeed = null
        homeFeedTimestamp = 0L
        cachedDashboard = null
        dashboardCacheUid = null
        dashboardTimestamp = 0L
    }

    private fun uid(): String =
        supabase.auth.currentUserOrNull()?.id
            ?: throw IllegalStateException(localizer.get(R.string.campaign_login_required))

    // ---------------------------------------------------------------- campaigns

    suspend fun listOpenCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val isFresh = { cachedOpenCampaigns != null && System.currentTimeMillis() - openCampaignsTimestamp < CACHE_TTL_MS }
        return deduped("campaigns:open", forceRefresh, isFresh, { cachedOpenCampaigns }) {
            val result = supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                filter { eq("status", "open") }
                order("created_at", Order.DESCENDING)
            }.decodeList<Campaign>()
            cachedOpenCampaigns = result
            openCampaignsTimestamp = System.currentTimeMillis()
            result
        }
    }

    /** Tab "App tạm dừng" trên Trang chủ. */
    suspend fun listPausedCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val isFresh = { cachedPausedCampaigns != null && System.currentTimeMillis() - pausedCampaignsTimestamp < CACHE_TTL_MS }
        return deduped("campaigns:paused", forceRefresh, isFresh, { cachedPausedCampaigns }) {
            val result = try {
                supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                    filter { eq("status", "paused") }
                    order("created_at", Order.DESCENDING)
                }.decodeList<Campaign>()
            } catch (_: Exception) {
                emptyList()
            }
            cachedPausedCampaigns = result
            pausedCampaignsTimestamp = System.currentTimeMillis()
            result
        }
    }

    /** Tab "Đã hoàn thành Kiểm thử" trên Trang chủ (lên production hoặc hết 30 ngày). */
    suspend fun listCompletedCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val isFresh = { cachedCompletedCampaigns != null && System.currentTimeMillis() - completedCampaignsTimestamp < CACHE_TTL_MS }
        return deduped("campaigns:completed", forceRefresh, isFresh, { cachedCompletedCampaigns }) {
            val result = try {
                supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                    filter { isIn("status", listOf("production", "completed")) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<Campaign>()
            } catch (_: Exception) {
                emptyList()
            }
            cachedCompletedCampaigns = result
            completedCampaignsTimestamp = System.currentTimeMillis()
            result
        }
    }

    /** Xóa cache danh sách campaign theo trạng thái (dùng sau khi sync vòng đời). */
    private fun invalidateCampaignLists() {
        cachedOpenCampaigns = null
        cachedPausedCampaigns = null
        cachedCompletedCampaigns = null
        cachedMyCampaigns = null
        statsCache.clear()
        cachedSlotsInfo = null
        cachedHomeFeed = null
    }

    /** Dashboard tổng hợp phải tải lại khi dữ liệu cá nhân thay đổi. */
    private fun invalidateDashboardCache() {
        cachedDashboard = null
        dashboardCacheUid = null
    }

    /** Xóa cache fast-path của màn chi tiết cho mọi người dùng hiện có. */
    private fun invalidateDetailCache(campaignId: String) {
        detailCache.keys.removeIf { it.startsWith("$campaignId|") }
    }

    /** Tự động duyệt báo cáo quá hạn tối đa 1 lần / 10 phút để tiết kiệm round-trip. */
    private suspend fun maybeAutoAcceptExpiredReports() {
        val now = System.currentTimeMillis()
        if (now - lastAutoAcceptRunMs < AUTO_ACCEPT_THROTTLE_MS) return
        lastAutoAcceptRunMs = now
        try {
            autoAcceptExpiredReports()
        } catch (_: Exception) {
        }
    }

    /**
     * Đồng bộ vòng đời campaign phía server: hoàn thành app quá 30 ngày hiển thị,
     * tạm dừng app của chủ app thiếu nhiệm vụ ngày 7 ngày liên tục.
     * Best-effort: bỏ qua nếu offline hoặc DB chưa chạy migration 017.
     */
    suspend fun syncCampaignLifecycle() {
        if (supabase.auth.currentUserOrNull() == null) return
        try {
            val result = supabase.postgrest.rpc("sync_campaign_lifecycle")
                .decodeAs<LifecycleSyncResult>()
            if (result.completed > 0 || result.paused > 0 || result.slotPaused > 0) {
                invalidateCampaignLists()
            }
        } catch (_: Exception) {
            // Migration chưa chạy hoặc mạng lỗi — không chặn tải Trang chủ.
        }
    }

    /** Chủ app bật test lại app đang tạm dừng (RPC kiểm tra cửa sổ 30 ngày + chủ app đã hoạt động lại). */
    suspend fun resumeCampaign(campaignId: String): ResumeCampaignResult {
        val result = supabase.postgrest.rpc(
            "resume_campaign",
            ResumeCampaignParams(campaignId = campaignId)
        ).decodeAs<ResumeCampaignResult>()
        invalidateCampaignLists()
        campaignCache.remove(campaignId)
        ownerStatsCache.remove(campaignId)
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
        return result
    }

    suspend fun getCampaign(id: String, forceRefresh: Boolean = false): Campaign? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            // Fast-path từ danh sách chỉ tin khi danh sách còn trong TTL;
            // cũ hơn TTL thì rơi xuống campaignCache / truy vấn mới.
            val openList = cachedOpenCampaigns
            if (openList != null && now - openCampaignsTimestamp < CACHE_TTL_MS) {
                openList.find { it.id == id }?.let { return it }
            }
            val myList = cachedMyCampaigns
            if (myList != null && now - myCampaignsTimestamp < CACHE_TTL_MS) {
                myList.find { it.id == id }?.let { return it }
            }
            val joinedList = cachedMyJoinedCampaigns
            if (joinedList != null && now - myJoinedCampaignsTimestamp < CACHE_TTL_MS) {
                joinedList.find { it.id == id }?.let { return it }
            }
            val cached = campaignCache[id]
            if (cached != null && now - cached.second < CACHE_TTL_MS) {
                return cached.first
            }
        }
        val fetched = supabase.from("campaigns").select {
            filter { eq("id", id) }
            limit(1)
        }.decodeList<Campaign>().firstOrNull()
        if (fetched != null) {
            campaignCache[id] = Pair(fetched, now)
        }
        return fetched
    }

    /**
     * Trả về TOÀN BỘ app đủ điều kiện để gợi ý làm bài test kế tiếp, kèm số người đã cài.
     *
     * Việc ưu tiên app đã cài trên máy / app có lượt cài thấp được quyết định ở client
     * vì chỉ client mới biết package nào đang có trên thiết bị của tester.
     *
     * Điều kiện giữ nguyên như RPC cũ: app open, không phải app hiện tại, không phải
     * app của chính tester và chưa được tester đó test hôm nay.
     */
    suspend fun getNextCampaignCandidates(currentCampaignId: String): List<NextCampaignCandidate> {
        // Prefetch chỉ chạy khi người dùng mở form nộp bài, nên lấy dữ liệu mới
        // để trạng thái "đã test hôm nay" không bị cũ theo TTL 3 phút.
        val feed = getHomeFeed(forceRefresh = true)
        // Đọc uid sau khi gọi feed: nếu phiên vừa khôi phục xong thì vẫn lọc
        // đúng app của chính tester.
        val currentUid = supabase.auth.currentUserOrNull()?.id
        val open: List<Campaign>
        val stats: Map<String, CampaignStats>
        if (feed != null) {
            open = feed.open
            stats = feed.stats
        } else {
            open = listOpenCampaigns(forceRefresh = true)
            stats = getCampaignsStatsMap(
                open.mapNotNull { it.id },
                forceRefresh = true,
            )
        }

        return open.mapNotNull { camp ->
            val id = camp.id ?: return@mapNotNull null
            if (id == currentCampaignId) return@mapNotNull null
            if (currentUid != null && camp.ownerId == currentUid) return@mapNotNull null
            // Thiếu stats nghĩa là chưa chắc chắn trạng thái hôm nay — bỏ qua
            // còn hơn gợi ý lại một app người dùng đã test.
            val stat = stats[id] ?: return@mapNotNull null
            if (stat.hasTestedToday) return@mapNotNull null
            NextCampaignCandidate(campaign = camp, installedCount = stat.installedCount)
        }
    }

    /**
     * Người dùng đã từng test trước phiên hiện tại chưa.
     * Dùng HEAD + count để payload tối thiểu; ưu tiên checkins vì mọi bài test
     * đều tạo check-in (kể cả check-in tạm tính), sau đó mới xét báo cáo legacy.
     */
    suspend fun hasTestedBefore(): Boolean {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return false
        val checkinCount = try {
            supabase.from("checkins").select {
                head = true
                count(Count.EXACT)
                filter { eq("user_id", uid) }
            }.countOrNull() ?: 0L
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (checkinCount > 0) return true

        val reportCount = try {
            supabase.from("tester_reports").select {
                head = true
                count(Count.EXACT)
                filter { eq("user_id", uid) }
            }.countOrNull() ?: 0L
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return reportCount > 0
    }

    suspend fun createCampaign(campaign: Campaign) {
        // getMyProfile() ném lỗi mạng khi fetch thất bại (nổi lên UI là lỗi mạng),
        // chỉ trả null khi chưa đăng nhập / không tìm thấy hồ sơ.
        val myProfile = getMyProfile() ?: throw IllegalStateException("NOT_AUTHENTICATED")
        if (myProfile.credits <= 10) {
            throw IllegalStateException("INSUFFICIENT_CREDITS")
        }
        val toInsert = campaign.copy(
            ownerId = uid(),
            startDate = campaign.startDate?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString(),
            status = "open",
        )
        supabase.from("campaigns").insert(toInsert)
        // Invalidate campaign caches
        cachedOpenCampaigns = null
        cachedMyCampaigns = null
        cachedMyProfile = null
        cachedHomeFeed = null
        invalidateDashboardCache()
    }

    /**
     * Owner/Admin update CHỈ các cột chỉ định. Trước đây đây là read-modify-write
     * upsert cả row: admin pause / lifecycle sync chạy xen giữa lúc đọc và ghi sẽ bị
     * ghi đè mất. Update cột mục tiêu không có cửa sổ đua đó; lỗi mạng ném lên caller.
     * Body dùng [JsonObject] + overload `update(body: JsonElement)` của postgrest-kt:
     * Map<String, Any?> qua serializer reified sẽ lỗi runtime ("Serializer for class
     * 'Any' is not found") vì Any? không có serializer builtin.
     * Không ép owner_id = uid(): Admin cũng được RLS phép cập nhật campaign của người khác,
     * nếu ép owner_id thì thao tác của Admin sẽ vô tình chuyển quyền sở hữu app.
     */
    private suspend fun updateCampaignFields(campaignId: String, fields: JsonObject) {
        supabase.from("campaigns").update(fields) {
            filter { eq("id", campaignId) }
        }
        cachedOpenCampaigns = null
        cachedPausedCampaigns = null
        cachedCompletedCampaigns = null
        cachedMyCampaigns = null
        statsCache.clear()
        cachedHomeFeed = null
        campaignCache.remove(campaignId)
        ownerStatsCache.remove(campaignId)
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
    }

    suspend fun updateCampaignIcon(campaignId: String, iconUrl: String) {
        val user = supabase.auth.currentUserOrNull() ?: return
        val current = getCampaign(campaignId) ?: return
        if (current.ownerId == user.id) {
            updateCampaignFields(
                campaignId,
                buildJsonObject { put("icon_url", iconUrl) },
            )
        }
    }

    fun isAuthenticated(): Boolean = supabase.auth.currentUserOrNull() != null

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    /** Trạng thái phiên đăng nhập để ViewModel phản ứng khi session khôi phục xong. */
    val sessionStatus: kotlinx.coroutines.flow.StateFlow<io.github.jan.supabase.auth.status.SessionStatus>
        get() = supabase.auth.sessionStatus

    suspend fun myCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val user = supabase.auth.currentUserOrNull() ?: return emptyList()
        val isFresh = { cachedMyCampaigns != null && System.currentTimeMillis() - myCampaignsTimestamp < CACHE_TTL_MS }
        return deduped("myCampaigns", forceRefresh, isFresh, { cachedMyCampaigns }) {
            val result = supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                filter { eq("owner_id", user.id) }
                order("created_at", Order.DESCENDING)
            }.decodeList<Campaign>()
            cachedMyCampaigns = result
            myCampaignsTimestamp = System.currentTimeMillis()
            result
        }
    }

    // ---------------------------------------------------------------- members

    suspend fun joinCampaign(campaignId: String) {
        supabase.from("campaign_members")
            .insert(CampaignMember(campaignId = campaignId, userId = uid()))
        cachedMyJoinedCampaigns = null
        membersCache.remove(campaignId)
        invalidateCampaignLists()
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
    }

    suspend fun leaveCampaign(campaignId: String) {
        val userId = uid()
        supabase.from("campaign_members").delete {
            filter {
                eq("campaign_id", campaignId)
                eq("user_id", userId)
            }
        }
        cachedMyJoinedCampaigns = null
        membersCache.remove(campaignId)
        invalidateCampaignLists()
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
    }

    suspend fun isMember(campaignId: String, userId: String? = null): Boolean {
        val uid = userId ?: supabase.auth.currentUserOrNull()?.id ?: return false
        return supabase.from("campaign_members").select {
            filter {
                eq("campaign_id", campaignId)
                eq("user_id", uid)
            }
            limit(1)
        }.decodeList<CampaignMember>().isNotEmpty()
    }

    suspend fun getMembers(campaignId: String, forceRefresh: Boolean = false): List<CampaignMember> {
        val isFresh = { membersCache[campaignId]?.let { System.currentTimeMillis() - it.second < CACHE_TTL_MS } == true }
        return deduped("members:$campaignId", forceRefresh, isFresh, { membersCache[campaignId]?.first }) {
            val result = supabase.from("campaign_members").select {
                filter { eq("campaign_id", campaignId) }
                order("joined_at", Order.ASCENDING)
            }.decodeList<CampaignMember>()
            membersCache[campaignId] = Pair(result, System.currentTimeMillis())
            result
        }
    }

    suspend fun myJoinedCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val user = supabase.auth.currentUserOrNull() ?: return emptyList()
        val isFresh = { cachedMyJoinedCampaigns != null && System.currentTimeMillis() - myJoinedCampaignsTimestamp < CACHE_TTL_MS }
        return deduped("myJoinedCampaigns", forceRefresh, isFresh, { cachedMyJoinedCampaigns }) {
            val memberRows = supabase.from("campaign_members").select {
                filter { eq("user_id", user.id) }
            }.decodeList<CampaignMember>()
            val ids = memberRows.map { it.campaignId }.distinct()
            if (ids.isEmpty()) {
                cachedMyJoinedCampaigns = emptyList()
                myJoinedCampaignsTimestamp = System.currentTimeMillis()
                return@deduped emptyList()
            }
            val campaigns = supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                filter { isIn("id", ids) }
                order("created_at", Order.DESCENDING)
            }.decodeList<Campaign>()
            val filtered = campaigns.filter { it.ownerId != user.id }
            cachedMyJoinedCampaigns = filtered
            myJoinedCampaignsTimestamp = System.currentTimeMillis()
            filtered
        }
    }

    // ---------------------------------------------------------------- check-ins

    suspend fun hasCheckedInToday(campaignId: String, userId: String? = null): Boolean {
        val uid = userId ?: supabase.auth.currentUserOrNull()?.id ?: return false
        val today = VnTime.today().toString()
        return supabase.from("checkins").select {
            filter {
                eq("campaign_id", campaignId)
                eq("user_id", uid)
                eq("checkin_date", today)
            }
            limit(1)
        }.decodeList<Checkin>().isNotEmpty()
    }

    /**
     * True only when today's check-in is confirmed (owner approved / auto-accepted / plain check-in),
     * NOT the provisional check-in created while a report is still pending review.
     * Mirrors the database rule: provisional check-ins carry a note containing "tạm tính".
     */
    suspend fun hasConfirmedCheckinToday(campaignId: String, userId: String? = null): Boolean {
        val uid = userId ?: supabase.auth.currentUserOrNull()?.id ?: return false
        val today = VnTime.today().toString()
        return try {
            supabase.from("checkins").select {
                filter {
                    eq("campaign_id", campaignId)
                    eq("user_id", uid)
                    eq("checkin_date", today)
                }
                limit(1)
            }.decodeList<Checkin>().any { !it.note.orEmpty().contains("tạm tính") }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getCheckins(campaignId: String, forceRefresh: Boolean = false): List<Checkin> {
        val isFresh = { checkinsCache[campaignId]?.let { System.currentTimeMillis() - it.second < CACHE_TTL_MS } == true }
        return deduped("checkins:$campaignId", forceRefresh, isFresh, { checkinsCache[campaignId]?.first }) {
            val result = supabase.from("checkins").select {
                filter { eq("campaign_id", campaignId) }
                order("checkin_date", Order.DESCENDING)
            }.decodeList<Checkin>()
            checkinsCache[campaignId] = Pair(result, System.currentTimeMillis())
            result
        }
    }

    // ---------------------------------------------------------------- feedback

    /** One feedback row per user per campaign (upsert = update my own review). */
    suspend fun submitFeedback(
        campaignId: String,
        rating: Int,
        comment: String?,
        screenshotUrl: String? = null,
        imageHash: String? = null,
        clientSubmitId: String? = null,
        requireIdempotency: Boolean = false,
    ): SubmitTesterReportResult {
        // RPC nguyên tử (migration 012/015/016/033). Không còn fallback ghi thẳng DB:
        // server thiếu RPC hoặc lỗi mạng đều ném lại để UI báo lỗi, tránh cộng
        // trùng Credit / sai trạng thái báo cáo.
        val params = SubmitTesterReportParams(
            campaignId = campaignId,
            // null khi tester không nhập nội dung -> server giữ comment cũ.
            content = comment?.trim()?.ifBlank { null },
            screenshotUrl = screenshotUrl,
            rating = rating.coerceIn(1, 5),
            imageHash = imageHash,
            clientSubmitId = clientSubmitId,
        )
        val result = try {
            supabase.postgrest.rpc(
                function = "submit_tester_report",
                parameters = params,
            ).decodeAs<SubmitTesterReportResult>()
        } catch (e: Exception) {
            // Migration 033 có thể chưa được deploy trong lúc app đã cập nhật.
            // Chỉ fallback khi lỗi đúng kiểu "function/parameter không tồn tại";
            // lỗi mạng/timeout không được fallback để tránh nộp trùng.
            if (clientSubmitId == null || !isMissingSubmitRpc(e)) throw e
            if (requireIdempotency) throw IllegalStateException("report_idempotency_unavailable", e)
            supabase.postgrest.rpc(
                function = "submit_tester_report",
                parameters = SubmitTesterReportLegacyParams(
                    campaignId = campaignId,
                    content = params.content,
                    screenshotUrl = params.screenshotUrl,
                    rating = params.rating,
                    imageHash = params.imageHash,
                ),
            ).decodeAs<SubmitTesterReportResult>()
        }
        statsCache.clear()
        cachedTodayCheckins = null
        cachedQuestSummary = null
        cachedMyProfile = null
        cachedCreditLogs = null
        // get_home_feed chứa has_tested_today của chính user. Không xoá cache này
        // thì quay lại Trang chủ trong 3 phút vẫn thấy "Chưa test hôm nay".
        cachedHomeFeed = null
        homeFeedTimestamp = 0L
        ownerStatsCache.remove(campaignId)
        membersCache.remove(campaignId)
        checkinsCache.remove(campaignId)
        feedbacksCache.remove(campaignId)
        reportsCache.remove("${campaignId}_true")
        reportsCache.remove("${campaignId}_false")
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
        return result
    }

    private fun isMissingSubmitRpc(error: Exception): Boolean {
        val message = error.message.orEmpty().lowercase()
        return "pgrst202" in message ||
            "could not find the function" in message ||
            ("submit_tester_report" in message && "schema cache" in message)
    }

    suspend fun getFeedbacks(campaignId: String, forceRefresh: Boolean = false): List<Feedback> {
        val isFresh = { feedbacksCache[campaignId]?.let { System.currentTimeMillis() - it.second < CACHE_TTL_MS } == true }
        return deduped("feedbacks:$campaignId", forceRefresh, isFresh, { feedbacksCache[campaignId]?.first }) {
            val result = supabase.from("feedbacks").select {
                filter { eq("campaign_id", campaignId) }
                order("created_at", Order.DESCENDING)
            }.decodeList<Feedback>()
            feedbacksCache[campaignId] = Pair(result, System.currentTimeMillis())
            result
        }
    }

    // ---------------------------------------------------------------- profiles

    suspend fun getProfiles(userIds: List<String>, forceRefresh: Boolean = false): Map<String, Profile> {
        if (userIds.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, Profile>()
        val missingIds = mutableListOf<String>()

        if (!forceRefresh) {
            for (id in userIds) {
                val cached = profileCache[id]
                if (cached != null && now - cached.second < CACHE_TTL_MS) {
                    result[id] = cached.first
                } else {
                    missingIds.add(id)
                }
            }
        } else {
            missingIds.addAll(userIds)
        }

        if (missingIds.isNotEmpty()) {
            val fetched = try {
                supabase.from("profiles").select {
                    filter { isIn("id", missingIds.distinct()) }
                }.decodeList<Profile>()
            } catch (_: Exception) {
                emptyList()
            }
            val fetchTime = System.currentTimeMillis()
            for (p in fetched) {
                profileCache[p.id] = Pair(p, fetchTime)
                result[p.id] = p
            }
        }
        return result
    }

    suspend fun getMyProfile(forceRefresh: Boolean = false): Profile? {
        val user = supabase.auth.currentUserOrNull() ?: return null
        val isFresh = { cachedMyProfile != null && System.currentTimeMillis() - myProfileTimestamp < CACHE_TTL_MS }
        return deduped("myProfile", forceRefresh, isFresh, { cachedMyProfile }) {
            val profile = getProfiles(listOf(user.id), forceRefresh = forceRefresh)[user.id]
            cachedMyProfile = profile
            myProfileTimestamp = System.currentTimeMillis()
            profile
        }
    }

    suspend fun getTodayCheckinCount(forceRefresh: Boolean = false): Int {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return 0
        val isFresh = { cachedTodayCheckins != null && System.currentTimeMillis() - todayCheckinsTimestamp < CACHE_TTL_MS }
        return deduped("todayCheckins", forceRefresh, isFresh, { cachedTodayCheckins }) {
            val today = VnTime.today().toString()
            val count = try {
                val checkins = supabase.from("checkins").select {
                    filter {
                        eq("user_id", uid)
                        eq("checkin_date", today)
                    }
                }.decodeList<Checkin>()
                val checkedInCampIds = checkins.mapNotNull { it.campaignId }

                val reports = supabase.from("tester_reports").select {
                    filter {
                        eq("user_id", uid)
                    }
                    order("created_at", Order.DESCENDING)
                    limit(50)
                }.decodeList<TesterReport>()
                val pendingOrApprovedReportCampIds = reports
                    .filter { it.isCreatedToday() && (it.isPending || it.isApproved) }
                    .map { it.campaignId }

                (checkedInCampIds + pendingOrApprovedReportCampIds).distinct().size
            } catch (_: Exception) {
                0
            }
            cachedTodayCheckins = count
            todayCheckinsTimestamp = System.currentTimeMillis()
            count
        }
    }

    suspend fun getMyCreditLogs(limit: Long = 20, forceRefresh: Boolean = false): List<CreditLog> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        val isFresh = { cachedCreditLogs != null && System.currentTimeMillis() - creditLogsTimestamp < SHORT_CACHE_TTL_MS }
        return deduped("creditLogs", forceRefresh, isFresh, { cachedCreditLogs }) {
            // Lỗi mạng ném thẳng lên UI để dialog "Lịch sử Credit" báo tải thất bại
            // thay vì hiển thị danh sách rỗng gây hiểu nhầm là không có giao dịch.
            val result = supabase.from("credit_logs").select {
                filter { eq("user_id", uid) }
                order("created_at", Order.DESCENDING)
                limit(limit)
            }.decodeList<CreditLog>()
            cachedCreditLogs = result
            creditLogsTimestamp = System.currentTimeMillis()
            result
        }
    }

    // ---------------------------------------------------------------- leaderboard

    /**
     * Bảng xếp hạng công khai.
     * @param sort "credit" = top theo Credits (mặc định, cơ chế cũ);
     *             "test" = top theo tổng lượt test toàn thời gian.
     */
    suspend fun getLeaderboard(sort: String = "credit", forceRefresh: Boolean = false): List<LeaderboardUser> {
        val isTest = sort == "test"
        val cacheKey = if (isTest) "leaderboard:test" else "leaderboard:credit"
        val getCached = { if (isTest) cachedTestLeaderboard else cachedLeaderboard }
        val isFresh = {
            val timestamp = if (isTest) testLeaderboardTimestamp else leaderboardTimestamp
            getCached()?.isNotEmpty() == true && System.currentTimeMillis() - timestamp < CACHE_TTL_MS
        }
        val writeCache: (List<LeaderboardUser>) -> Unit = { list ->
            if (list.isNotEmpty() || getCached() == null) {
                if (isTest) {
                    cachedTestLeaderboard = list
                    testLeaderboardTimestamp = System.currentTimeMillis()
                } else {
                    cachedLeaderboard = list
                    leaderboardTimestamp = System.currentTimeMillis()
                }
            }
        }
        return deduped(cacheKey, forceRefresh, isFresh, getCached) {
            // RPC-first: 1 round-trip thay vì tải profiles + toàn bộ checkins.
            try {
                val rpcList = supabase.postgrest.rpc(
                    "get_leaderboard_v2",
                    LeaderboardParams(limit = 100, sort = if (isTest) "test" else "credit")
                ).decodeAs<List<LeaderboardUser>>()
                writeCache(rpcList)
                return@deduped rpcList
            } catch (_: Exception) {
                // Migration chưa deploy — rơi xuống đường legacy bên dưới.
            }
            val res = try {
                val profiles = supabase.from("profiles").select {
                    order("credits", Order.DESCENDING)
                    limit(100)
                }.decodeList<Profile>()

                if (profiles.isEmpty()) return@deduped emptyList()

                val userIds = profiles.map { it.id }
                val checkins = try {
                    supabase.from("checkins").select {
                        filter { isIn("user_id", userIds) }
                    }.decodeList<Checkin>()
                } catch (_: Exception) {
                    emptyList()
                }

                val checkinsByUser = checkins.groupBy { it.userId }

                val mapped = profiles.map { profile ->
                    val userCheckins = checkinsByUser[profile.id].orEmpty()
                    val distinctCampaigns = userCheckins.mapNotNull { it.campaignId }.distinct().size
                    val name = profile.displayName?.takeIf { it.isNotBlank() }
                        ?: profile.email?.substringBefore("@")
                        ?: "Tester"
                    LeaderboardUser(
                        userId = profile.id,
                        displayName = name,
                        avatarUrl = profile.avatarUrl,
                        credits = profile.credits,
                        checkinCount = userCheckins.size,
                        campaignsCount = distinctCampaigns,
                    )
                }.sortedWith(
                    if (isTest) {
                        compareByDescending<LeaderboardUser> { it.checkinCount }
                            .thenByDescending { it.credits }
                            .thenByDescending { it.campaignsCount }
                    } else {
                        compareByDescending<LeaderboardUser> { it.credits }
                            .thenByDescending { it.checkinCount }
                            .thenByDescending { it.campaignsCount }
                    }
                )

                // Top Test chỉ gồm thành viên đã thực hiện ít nhất 1 lượt test.
                val ranked = if (isTest) mapped.filter { it.checkinCount > 0 } else mapped

                ranked.mapIndexed { index, user ->
                    user.copy(rank = index + 1)
                }
            } catch (_: Exception) {
                emptyList()
            }
            writeCache(res)
            res
        }
    }

    /**
     * Hạng của người dùng hiện tại theo cùng thứ tự sắp xếp của [getLeaderboard].
     * RPC-first (get_my_rank_v1) — chính xác kể cả khi ngoài top 100, kèm tổng
     * số thành viên. Nếu migration chưa deploy thì fallback theo tầng: quét
     * leaderboard đã tải, rồi đếm xấp xỉ qua PostgREST (bỏ qua tie-break
     * checkins/apps nên có thể lệch vài hạng khi đồng hạng credits).
     */
    suspend fun getMyRank(sort: String = "credit", forceRefresh: Boolean = false): MyRank? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        // Cache riêng theo sort: dùng chung một biến sẽ trả hạng credit cho
        // bảng Top Test (hoặc ngược lại) khi hai sort gọi gần nhau trong TTL.
        val isFresh = { myRankCache[sort]?.let { System.currentTimeMillis() - it.second < CACHE_TTL_MS } == true }
        val writeCache: (MyRank) -> MyRank = { result ->
            myRankCache[sort] = Pair(result, System.currentTimeMillis())
            result
        }
        return deduped("myRank:$sort", forceRefresh, isFresh, { myRankCache[sort]?.first }) {
            // RPC-first: hạng chính xác + tổng số thành viên trong 1 round-trip.
            try {
                val rpcResult = supabase.postgrest.rpc("get_my_rank_v1", MyRankParams(sort)).decodeAs<MyRank>()
                if (rpcResult.rank > 0) {
                    return@deduped writeCache(rpcResult)
                }
            } catch (_: Exception) {
                // Migration chưa deploy — thử các đường fallback bên dưới.
            }

            // Fallback 1: mình đang nằm trong top đã tải của bảng xếp hạng.
            val fromList = try {
                getLeaderboard(sort, forceRefresh).firstOrNull { it.userId == uid }
            } catch (_: Exception) {
                null
            }
            if (fromList != null && fromList.rank > 0) {
                return@deduped writeCache(
                    MyRank(
                        rank = fromList.rank,
                        credits = fromList.credits,
                        checkinCount = fromList.checkinCount,
                        campaignsCount = fromList.campaignsCount,
                    )
                )
            }

            // Fallback 2: đếm số người có credits cao hơn mình (xấp xỉ).
            val approx = try {
                val myProfile = getMyProfile(forceRefresh) ?: return@deduped null
                val above = supabase.from("profiles").select {
                    head = true
                    count(Count.EXACT)
                    filter { gt("credits", myProfile.credits) }
                }.countOrNull() ?: 0L
                val total = supabase.from("profiles").select {
                    head = true
                    count(Count.EXACT)
                }.countOrNull() ?: 0L
                MyRank(
                    rank = above.toInt() + 1,
                    credits = myProfile.credits,
                    totalUsers = total.toInt(),
                )
            } catch (_: Exception) {
                null
            }
            if (approx != null && approx.rank > 0) writeCache(approx) else approx
        }
    }

    // ---------------------------------------------------------------- owner app management

    suspend fun getCampaignOwnerStats(campaignId: String, forceRefresh: Boolean = false): CampaignOwnerStats {
        val isFresh = { ownerStatsCache[campaignId]?.let { System.currentTimeMillis() - it.second < CACHE_TTL_MS } == true }
        return deduped("ownerStats:$campaignId", forceRefresh, isFresh, { ownerStatsCache[campaignId]?.first }) {
            val now = System.currentTimeMillis()
            val members = try { getMembers(campaignId, forceRefresh) } catch (_: Exception) { emptyList() }
            val today = VnTime.today().toString()
            val checkins = try { getCheckins(campaignId, forceRefresh) } catch (_: Exception) { emptyList() }
            val feedbacks = try {
                getFeedbacks(campaignId, forceRefresh)
            } catch (_: Exception) {
                emptyList<Feedback>()
            }
            val avgRating = if (feedbacks.isNotEmpty()) {
                feedbacks.map { it.rating }.average().toFloat()
            } else null

            val reports = try { getCampaignReports(campaignId, canManage = true) } catch (_: Exception) { emptyList() }
            val todayCheckins = (
                checkins.filter { it.checkinDate == today }.map { it.userId } +
                reports.filter { it.isCreatedToday() && !it.isRejected }.map { it.userId }
            ).distinct().size
            val pendingCount = reports.count { it.status == "pending" }
            // Khớp đường fast-path (detail.reportsCount): đếm báo cáo thật, không lấy
            // check-in làm sàn để tránh hai đường tải hiển thị hai con số khác nhau.
            val totalReports = reports.size

            val stats = CampaignOwnerStats(
                installedTestersCount = members.size,
                testedTodayCount = todayCheckins,
                totalReportsCount = totalReports,
                pendingReportsCount = pendingCount,
                averageRating = avgRating,
            )
            ownerStatsCache[campaignId] = Pair(stats, now)
            stats
        }
    }

    suspend fun getPendingReportsCountForOwner(forceRefresh: Boolean = false): Int {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return 0
        val isFresh = { cachedPendingReports != null && System.currentTimeMillis() - pendingReportsTimestamp < CACHE_TTL_MS }
        return deduped("pendingReports", forceRefresh, isFresh, { cachedPendingReports }) {
            val count = try {
                val myCamps = myCampaigns(forceRefresh = forceRefresh)
                val campIds = myCamps.mapNotNull { it.id }
                if (campIds.isEmpty()) return@deduped 0
                val reports = supabase.from("tester_reports").select {
                    filter {
                        isIn("campaign_id", campIds)
                        eq("status", "pending")
                    }
                }.decodeList<TesterReport>()
                reports.size
            } catch (_: Exception) {
                0
            }
            cachedPendingReports = count
            pendingReportsTimestamp = System.currentTimeMillis()
            count
        }
    }

    suspend fun getCampaignTesters(campaignId: String, forceRefresh: Boolean = false): List<TesterItem> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = testersCache[campaignId]
            if (cached != null && now - cached.second < CACHE_TTL_MS) {
                return cached.first
            }
        }
        val members = try { getMembers(campaignId, forceRefresh) } catch (_: Exception) { emptyList() }
        if (members.isEmpty()) return emptyList()
        val userIds = members.map { it.userId }
        val profilesMap = try { getProfiles(userIds, forceRefresh) } catch (_: Exception) { emptyMap() }
        val checkins = try {
            getCheckins(campaignId, forceRefresh).filter { it.userId in userIds }
        } catch (_: Exception) {
            emptyList()
        }
        val checkinsByUser = checkins.groupBy { it.userId }
        val result = members.map { member ->
            val userCheckins = checkinsByUser[member.userId].orEmpty()
            val latestDate = userCheckins.mapNotNull { it.checkinDate }.maxOrNull()
            TesterItem(
                member = member,
                profile = profilesMap[member.userId],
                checkinCount = userCheckins.size,
                lastCheckinDate = latestDate,
            )
        }
        testersCache[campaignId] = Pair(result, now)
        return result
    }

    suspend fun getCampaignReports(campaignId: String, canManage: Boolean = false, forceRefresh: Boolean = false): List<TesterReport> {
        val cacheKey = "${campaignId}_$canManage"
        val isFresh = { reportsCache[cacheKey]?.let { System.currentTimeMillis() - it.second < CACHE_TTL_MS } == true }
        return deduped("reports:$cacheKey", forceRefresh, isFresh, { reportsCache[cacheKey]?.first }) {
            val now = System.currentTimeMillis()
            // Đường của tester: tự động duyệt báo cáo quá hạn (throttle 10 phút).
            // Đường của chủ app/admin/moderator dùng RPC vốn đã tự chạy auto-accept phía server.
            if (!canManage) {
                maybeAutoAcceptExpiredReports()
            }

            // Đường của chủ app: RPC security definer. RPC THÀNH CÔNG (kể cả danh sách
            // rỗng — campaign mới) là kết quả cuối: cache và trả về, không chạy fallback
            // làm trông. RPC LỖI mới rơi xuống truy vấn bảng trực tiếp; cả hai lỗi thì ném
            // lên UI thay vì nuốt rồi cache danh sách rỗng gây hiểu "không có báo cáo".
            if (canManage) {
                val rpcResult = try {
                    val rpcReports = supabase.postgrest.rpc(
                        function = "get_campaign_reports_for_owner",
                        parameters = GetOwnerReportsParams(campaignId = campaignId)
                    ).decodeAs<List<TesterReport>>()
                    val userIds = rpcReports.map { it.userId }.distinct()
                    val profiles = getProfiles(userIds)
                    rpcReports.map { rep ->
                        rep.copy(testerProfile = profiles[rep.userId])
                    }
                } catch (rpcError: Exception) {
                    try {
                        fetchReportsDirect(campaignId)
                    } catch (fallbackError: Exception) {
                        fallbackError.addSuppressed(rpcError)
                        throw fallbackError
                    }
                }
                reportsCache[cacheKey] = Pair(rpcResult, now)
                return@deduped rpcResult
            }

            // Đường của tester: truy vấn trực tiếp (RLS chỉ trả báo cáo của chính mình).
            // Lỗi mạng ném lên để UI báo tải thất bại thay vì hiển thị rỗng.
            val finalResult = fetchReportsDirect(campaignId)
            reportsCache[cacheKey] = Pair(finalResult, now)
            finalResult
        }
    }

    /** Truy vấn thẳng bảng `tester_reports` (theo RLS) + gắn profile tester. */
    private suspend fun fetchReportsDirect(campaignId: String): List<TesterReport> {
        val list = supabase.from("tester_reports").select {
            filter { eq("campaign_id", campaignId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<TesterReport>()
        if (list.isEmpty()) return emptyList()
        val userIds = list.map { it.userId }.distinct()
        val profiles = getProfiles(userIds)
        return list.map { it.copy(testerProfile = profiles[it.userId]) }
    }

    suspend fun getMyLatestReport(campaignId: String): TesterReport? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        return try {
            val list = supabase.from("tester_reports").select {
                filter {
                    eq("campaign_id", campaignId)
                    eq("user_id", uid)
                }
                order("created_at", Order.DESCENDING)
                limit(1)
            }.decodeList<TesterReport>()
            list.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getMyTodayReport(campaignId: String): TesterReport? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        return try {
            val list = supabase.from("tester_reports").select {
                filter {
                    eq("campaign_id", campaignId)
                    eq("user_id", uid)
                }
                order("created_at", Order.DESCENDING)
                limit(5)
            }.decodeList<TesterReport>()
            list.firstOrNull { it.isCreatedToday() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun approveReport(report: TesterReport, bonusCredits: Int = 0): ApproveReportResult {
        val reportId = report.id ?: return ApproveReportResult(false)
        reportsCache.remove("${report.campaignId}_true")
        reportsCache.remove("${report.campaignId}_false")
        ownerStatsCache.remove(report.campaignId)
        cachedPendingReports = null
        checkinsCache.remove(report.campaignId)
        cachedMyProfile = null
        cachedCreditLogs = null
        creditLogsTimestamp = 0L
        profileCache.clear()
        invalidateDetailCache(report.campaignId)
        invalidateDashboardCache()
        // RPC nguyên tử (migration 014+). Không fallback ghi thẳng DB: server thiếu RPC
        // hoặc lỗi mạng đều ném lại để UI báo lỗi, tránh double-apply trạng thái + Credit.
        return supabase.postgrest.rpc(
            function = "approve_tester_report",
            parameters = ApproveReportParams(reportId = reportId, bonus = bonusCredits)
        ).decodeAs<ApproveReportResult>()
    }

    suspend fun rejectReport(report: TesterReport, reason: String? = null): RejectReportResult {
        val reportId = report.id ?: return RejectReportResult(false)
        reportsCache.remove("${report.campaignId}_true")
        reportsCache.remove("${report.campaignId}_false")
        ownerStatsCache.remove(report.campaignId)
        cachedPendingReports = null
        checkinsCache.remove(report.campaignId)
        profileCache.clear()
        invalidateDetailCache(report.campaignId)
        invalidateDashboardCache()
        // RPC nguyên tử (migration 014+). Không fallback ghi thẳng DB: server thiếu RPC
        // hoặc lỗi mạng đều ném lại để UI báo lỗi, tránh double-apply trạng thái + clawback.
        return supabase.postgrest.rpc(
            function = "reject_tester_report",
            parameters = RejectReportParams(reportId = reportId, reason = reason)
        ).decodeAs<RejectReportResult>()
    }

    suspend fun autoAcceptExpiredReports(): Int {
        return try {
            supabase.postgrest.rpc(function = "auto_accept_expired_reports").decodeAs<Int>()
        } catch (_: Exception) {
            0
        }
    }

    suspend fun selfUnlockAccount(): SelfUnlockResult {
        val result = supabase.postgrest.rpc(function = "self_unlock_account").decodeAs<SelfUnlockResult>()
        cachedMyProfile = null
        cachedCreditLogs = null
        invalidateDashboardCache()
        return result
    }

    suspend fun getUnreadWarnings(forceRefresh: Boolean = false): List<UserWarning> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        val isFresh = { cachedUnreadWarnings != null && System.currentTimeMillis() - unreadWarningsTimestamp < SHORT_CACHE_TTL_MS }
        return deduped("unreadWarnings", forceRefresh, isFresh, { cachedUnreadWarnings }) {
            val result = try {
                supabase.from("user_warnings").select {
                    filter {
                        eq("user_id", uid)
                        eq("acknowledged", false)
                    }
                    order("created_at", Order.DESCENDING)
                }.decodeList<UserWarning>()
            } catch (_: Exception) {
                emptyList()
            }
            cachedUnreadWarnings = result
            unreadWarningsTimestamp = System.currentTimeMillis()
            result
        }
    }

    /** Cập nhật server trước, cache sau. Ném lỗi khi mạng thất bại (caller tự hoàn tác UI). */
    suspend fun acknowledgeWarning(warningId: String) {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return
        supabase.from("user_warnings").update(buildJsonObject { put("acknowledged", true) }) {
            filter {
                eq("id", warningId)
                eq("user_id", uid)
            }
        }
        cachedUnreadWarnings = cachedUnreadWarnings?.filterNot { it.id == warningId }
        invalidateDashboardCache()
    }

    suspend fun getWeeklyWarningsCount(forceRefresh: Boolean = false): Int {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return 0
        val isFresh = { cachedWeeklyWarnings != null && System.currentTimeMillis() - weeklyWarningsTimestamp < SHORT_CACHE_TTL_MS }
        return deduped("weeklyWarnings", forceRefresh, isFresh, { cachedWeeklyWarnings }) {
            val result = try {
                val sevenDaysAgo = VnTime.today().minusDays(6).toString()
                supabase.from("user_warnings").select {
                    filter {
                        eq("user_id", uid)
                        gte("warning_date", sevenDaysAgo)
                    }
                }.decodeList<UserWarning>().size
            } catch (_: Exception) {
                0
            }
            cachedWeeklyWarnings = result
            weeklyWarningsTimestamp = System.currentTimeMillis()
            result
        }
    }

    suspend fun updateCampaignStatus(campaignId: String, newStatus: String, pausedReason: String? = null) {
        // put(key, value: String?) ghi JsonNull khi null — pause_reason được xoá đúng
        // khi chuyển trạng thái không phải paused (giữ semantics của code cũ).
        updateCampaignFields(
            campaignId,
            buildJsonObject {
                put("status", newStatus)
                put("paused_reason", pausedReason)
            },
        )
    }

    /**
     * Chủ app đặt mức tip mỗi lượt duyệt (0/1/2/3/5/10 Credit). Server sẽ trừ
     * Credit chủ app và cộng cho tester khi báo cáo được duyệt (RPC set_campaign_tip).
     */
    suspend fun setCampaignTip(campaignId: String, tipCredits: Int): SetCampaignTipResult {
        val result = supabase.postgrest.rpc(
            function = "set_campaign_tip",
            parameters = SetCampaignTipParams(campaignId = campaignId, tipCredits = tipCredits)
        ).decodeAs<SetCampaignTipResult>()
        cachedOpenCampaigns = null
        cachedMyCampaigns = null
        cachedHomeFeed = null
        cachedMyProfile = null
        campaignCache.remove(campaignId)
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
        return result
    }

    suspend fun updateCampaignDetails(
        campaignId: String,
        appName: String,
        description: String,
        playUrl: String,
        testingUrl: String,
        testUsername: String,
        testPassword: String,
    ) {
        updateCampaignFields(
            campaignId,
            buildJsonObject {
                put("app_name", appName)
                put("description", description)
                put("play_url", playUrl)
                put("testing_url", testingUrl)
                put("test_username", testUsername)
                put("test_password", testPassword)
            },
        )
    }

    suspend fun adminDeleteCampaign(campaignId: String, reason: String? = null): AdminDeleteCampaignResult {
        val result = supabase.postgrest.rpc(
            "admin_delete_campaign",
            AdminDeleteCampaignParams(campaignId = campaignId, reason = reason)
        ).decodeAs<AdminDeleteCampaignResult>()
        cachedOpenCampaigns = null
        cachedMyCampaigns = null
        statsCache.clear()
        cachedHomeFeed = null
        ownerStatsCache.remove(campaignId)
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
        return result
    }

    /** Admin tạm dừng nhận tester cho app của bất kỳ chủ app nào. */
    suspend fun adminPauseCampaign(campaignId: String, reason: String? = null): AdminCampaignControlResult {
        val result = supabase.postgrest.rpc(
            "admin_pause_campaign",
            AdminPauseCampaignParams(campaignId = campaignId, reason = reason?.trim()?.ifBlank { null })
        ).decodeAs<AdminCampaignControlResult>()
        invalidateAfterAdminCampaignControl(campaignId)
        return result
    }

    /** Admin mở lại app đang tạm dừng (kể cả app bị auto-pause vì chủ vắng). */
    suspend fun adminResumeCampaign(campaignId: String): AdminCampaignControlResult {
        val result = supabase.postgrest.rpc(
            "admin_resume_campaign",
            CampaignIdParams(campaignId = campaignId)
        ).decodeAs<AdminCampaignControlResult>()
        invalidateAfterAdminCampaignControl(campaignId)
        return result
    }

    private fun invalidateAfterAdminCampaignControl(campaignId: String) {
        invalidateCampaignLists()
        campaignCache.remove(campaignId)
        ownerStatsCache.remove(campaignId)
        reportsCache.remove("${campaignId}_true")
        reportsCache.remove("${campaignId}_false")
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
    }

    suspend fun deleteCampaign(campaignId: String) {
        val myProfile = getMyProfile()
        if (myProfile?.isAdmin == true) {
            try {
                adminDeleteCampaign(campaignId)
                return
            } catch (_: Exception) {
                // Fall back to direct delete below if RPC is not present
            }
        }
        supabase.from("campaigns").delete {
            filter {
                eq("id", campaignId)
                if (myProfile?.isAdmin != true) {
                    eq("owner_id", uid())
                }
            }
        }
        cachedOpenCampaigns = null
        cachedMyCampaigns = null
        statsCache.clear()
        cachedHomeFeed = null
        ownerStatsCache.remove(campaignId)
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
    }

    suspend fun getCampaignsStatsMap(campaignIds: List<String>, forceRefresh: Boolean = false): Map<String, CampaignStats> {
        if (campaignIds.isEmpty()) return emptyMap()
        // Khoá single-flight cố định (không nối danh sách id vào key để map lock
        // không phình vô hạn); isFresh đã tự kiểm tra đủ id được yêu cầu.
        val isFresh = {
            val now = System.currentTimeMillis()
            campaignIds.all { id -> statsCache[id]?.let { now - it.second < CACHE_TTL_MS } == true }
        }
        return deduped(
            "statsMap",
            forceRefresh,
            isFresh,
            { campaignIds.mapNotNull { id -> statsCache[id]?.let { id to it.first } }.toMap() },
        ) {
            fun cacheAll(stats: Map<String, CampaignStats>) {
                val at = System.currentTimeMillis()
                for ((statId, stat) in stats) {
                    statsCache[statId] = Pair(stat, at)
                }
            }
            // RPC-first: 1 round-trip thay vì 3 (members + checkins + reports).
            try {
                val rpcStats = supabase.postgrest.rpc(
                    "get_campaigns_stats",
                    CampaignIdsParams(campaignIds = campaignIds)
                ).decodeAs<Map<String, CampaignStats>>()
                if (rpcStats.isNotEmpty()) {
                    cacheAll(rpcStats)
                    return@deduped rpcStats
                }
            } catch (_: Exception) {
                // Migration chưa deploy — rơi xuống đường legacy bên dưới.
            }
            val uid = supabase.auth.currentUserOrNull()?.id
            val today = VnTime.today().toString()

            val membersMap = try {
                val members = supabase.from("campaign_members").select {
                    filter { isIn("campaign_id", campaignIds) }
                }.decodeList<CampaignMember>()
                members.groupBy { it.campaignId }.mapValues { it.value.size }
            } catch (_: Exception) {
                emptyMap()
            }

            val todayCheckins = try {
                supabase.from("checkins").select {
                    filter {
                        isIn("campaign_id", campaignIds)
                        eq("checkin_date", today)
                    }
                }.decodeList<Checkin>()
            } catch (_: Exception) {
                emptyList()
            }

            val todayCountMap = todayCheckins.groupBy { it.campaignId }.mapValues { it.value.size }
            val userCheckedInIds = if (uid != null) {
                todayCheckins.filter { it.userId == uid }.map { it.campaignId }.toSet()
            } else emptySet()

            val userSubmittedTodayCampIds = if (uid != null) {
                try {
                    val reports = supabase.from("tester_reports").select {
                        filter {
                            isIn("campaign_id", campaignIds)
                            eq("user_id", uid)
                        }
                        order("created_at", Order.DESCENDING)
                    }.decodeList<TesterReport>()
                    reports.filter { it.isCreatedToday() && !it.isRejected }.map { it.campaignId }.toSet()
                } catch (_: Exception) {
                    emptySet()
                }
            } else emptySet()

            val testedTodayIds = userCheckedInIds + userSubmittedTodayCampIds

            val resultMap = campaignIds.associateWith { id ->
                CampaignStats(
                    installedCount = membersMap[id] ?: 0,
                    todayTestedCount = todayCountMap[id] ?: 0,
                    hasTestedToday = testedTodayIds.contains(id),
                )
            }
            cacheAll(resultMap)
            resultMap
        }
    }

    // ---------------------------------------------------------------- batched RPC fast-path (migration 020)

    /**
     * Một round-trip lấy đủ 3 tab Trang chủ + tên chủ app + thống kê.
     * Trả null khi RPC chưa được deploy → caller rơi xuống đường legacy.
     */
    suspend fun getHomeFeed(forceRefresh: Boolean = false): HomeFeedResult? {
        val isFresh = { cachedHomeFeed != null && System.currentTimeMillis() - homeFeedTimestamp < CACHE_TTL_MS }
        return deduped("homeFeed", forceRefresh, isFresh, { cachedHomeFeed }) {
            val now = System.currentTimeMillis()
            val result = try {
                supabase.postgrest.rpc("get_home_feed").decodeAs<HomeFeedResult>()
            } catch (_: Exception) {
                return@deduped null
            }
            cachedHomeFeed = result
            homeFeedTimestamp = now
            cachedOpenCampaigns = result.open
            openCampaignsTimestamp = now
            cachedPausedCampaigns = result.paused
            pausedCampaignsTimestamp = now
            cachedCompletedCampaigns = result.completed
            completedCampaignsTimestamp = now
            if (result.stats.isNotEmpty()) {
                for ((statId, stat) in result.stats) {
                    statsCache[statId] = Pair(stat, now)
                }
            }
            result
        }
    }

    /**
     * Một round-trip lấy toàn bộ dữ liệu cá nhân của Trang chủ (profile, campaigns,
     * leaderboard, nhiệm vụ, cảnh báo, thông báo...). Trả null khi chưa deploy RPC.
     * Client chủ động bỏ bảng xếp hạng (tải riêng khi mở tab) để phản hồi nhanh hơn.
     */
    suspend fun getMyDashboard(forceRefresh: Boolean = false, includeLeaderboard: Boolean = false): MyDashboardResult? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val isFresh = {
            cachedDashboard != null && dashboardCacheUid == uid &&
                System.currentTimeMillis() - dashboardTimestamp < CACHE_TTL_MS &&
                // Snapshot cache không leaderboard không được phục vụ cho
                // caller yêu cầu có leaderboard (và ngược lại thì vô hại).
                (!includeLeaderboard || dashboardHasLeaderboard)
        }
        return deduped("dashboard", forceRefresh, isFresh, { cachedDashboard }) {
            // Báo cáo quá 8h cần được tự động duyệt trước khi đếm số chờ duyệt trên Trang chủ.
            maybeAutoAcceptExpiredReports()
            val now = System.currentTimeMillis()
            val result = try {
                supabase.postgrest.rpc(
                    "get_my_dashboard",
                    MyDashboardParams(includeLeaderboard = includeLeaderboard)
                ).decodeAs<MyDashboardResult>()
            } catch (_: Exception) {
                // Server cũ chưa có tham số — thử lại chữ ký không tham số.
                try {
                    supabase.postgrest.rpc("get_my_dashboard").decodeAs<MyDashboardResult>()
                } catch (_: Exception) {
                    return@deduped null
                }
            }
            cachedDashboard = result
            dashboardCacheUid = uid
            dashboardHasLeaderboard = includeLeaderboard && result.leaderboard.isNotEmpty()
            dashboardTimestamp = now
            result.profile?.let {
                cachedMyProfile = it
                myProfileTimestamp = now
                profileCache[it.id] = Pair(it, now)
            }
            cachedMyCampaigns = result.myCampaigns
            myCampaignsTimestamp = now
            cachedMyJoinedCampaigns = result.joinedCampaigns
            myJoinedCampaignsTimestamp = now
            if (result.leaderboard.isNotEmpty()) {
                cachedLeaderboard = result.leaderboard
                leaderboardTimestamp = now
            }
            cachedTodayCheckins = result.todayCheckinCount
            todayCheckinsTimestamp = now
            cachedPendingReports = result.pendingReportsCount
            pendingReportsTimestamp = now
            cachedUnreadWarnings = result.unreadWarnings
            unreadWarningsTimestamp = now
            cachedWeeklyWarnings = result.weeklyWarningsCount
            weeklyWarningsTimestamp = now
            cachedCreditLogs = result.creditLogs
            creditLogsTimestamp = now
            if (result.topupPackages.isNotEmpty()) {
                cachedTopupPackages = result.topupPackages
                topupPackagesTimestamp = now
            }
            cachedNotifications = result.notifications
            notificationsTimestamp = now
            if (result.ownerStats.isNotEmpty()) {
                for ((statCampaignId, stats) in result.ownerStats) {
                    ownerStatsCache[statCampaignId] = Pair(stats, now)
                }
            }
            result.slots?.let {
                cachedUserSlots = it
                userSlotsTimestamp = now
            }
            result.questSummary?.let {
                cachedQuestSummary = it
                questSummaryTimestamp = now
            }
            result
        }
    }

    /**
     * Trạng thái nhiệm vụ ngày hiện tại (read-only). Dùng cho background worker:
     * TUYỆT ĐỐI không gọi `getMyDashboard` ở đó vì RPC đó ghi nhận đăng nhập.
     * Trả null khi server chưa deploy migration 036.
     */
    suspend fun getMyDailyTaskStatus(): DailyTaskStatus? {
        if (supabase.auth.currentUserOrNull() == null) return null
        return try {
            supabase.postgrest.rpc("get_my_daily_task_status").decodeAs<DailyTaskStatus>()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Ghi nhận đăng nhập trong ngày (idempotent phía server). Chỉ gọi tối đa
     * một lần mỗi ngày VN từ client.
     */
    suspend fun recordDailyLogin(): Boolean {
        if (supabase.auth.currentUserOrNull() == null) return false
        return try {
            supabase.postgrest.rpc("record_daily_login")
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Một round-trip lấy toàn bộ dữ liệu màn chi tiết (campaign, members, checkins,
     * feedbacks, reports, profiles, cảnh báo...). Trả null khi chưa deploy RPC.
     */
    suspend fun getCampaignDetailPage(campaignId: String, forceRefresh: Boolean = false): CampaignDetailResult? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val cacheKey = "${campaignId}|${uid}"
        val isFresh = { detailCache[cacheKey]?.let { System.currentTimeMillis() - it.second < CACHE_TTL_MS } == true }
        return deduped("detail:$cacheKey", forceRefresh, isFresh, { detailCache[cacheKey]?.first }) {
            val now = System.currentTimeMillis()
            val result = try {
                supabase.postgrest.rpc("get_campaign_detail", CampaignIdParams(campaignId = campaignId))
                    .decodeAs<CampaignDetailResult>()
            } catch (_: Exception) {
                return@deduped null
            }
            detailCache[cacheKey] = Pair(result, now)
            result.campaign.id?.let { campaignCache[it] = Pair(result.campaign, now) }
            feedbacksCache[campaignId] = Pair(result.feedbacks, now)
            val reportsWithProfiles = result.reports.map { report ->
                report.copy(testerProfile = result.profiles[report.userId])
            }
            if (result.canManage) {
                reportsCache["${campaignId}_true"] = Pair(reportsWithProfiles, now)
            } else {
                reportsCache["${campaignId}_false"] = Pair(reportsWithProfiles, now)
            }
            for ((profileId, profile) in result.profiles) {
                profileCache[profileId] = Pair(profile, now)
            }
            result.myProfile?.let {
                cachedMyProfile = it
                myProfileTimestamp = now
            }
            result
        }
    }

    // ---------------------------------------------------------------- slots

    suspend fun getUserSlots(forceRefresh: Boolean = false): List<UserSlot> {
        val user = supabase.auth.currentUserOrNull() ?: return emptyList()
        val isFresh = { cachedUserSlots != null && System.currentTimeMillis() - userSlotsTimestamp < CACHE_TTL_MS }
        return deduped("userSlots", forceRefresh, isFresh, { cachedUserSlots }) {
            val result = try {
                supabase.from("user_slots").select {
                    filter { eq("user_id", user.id) }
                    order("slot_number", Order.ASCENDING)
                }.decodeList<UserSlot>()
            } catch (_: Exception) {
                emptyList()
            }
            cachedUserSlots = result
            userSlotsTimestamp = System.currentTimeMillis()
            result
        }
    }

    suspend fun unlockOrRenewSlot(slotNumber: Int): UnlockSlotResult {
        val result = supabase.postgrest.rpc(
            "unlock_or_renew_slot",
            UnlockSlotParams(slotNumber)
        ).decodeAs<UnlockSlotResult>()
        cachedMyProfile = null
        cachedSlotsInfo = null
        cachedUserSlots = null
        cachedAllProfiles = null
        invalidateDashboardCache()
        return result
    }

    suspend fun getMySlotsInfo(forceRefresh: Boolean = false): List<SlotInfo> {
        val isFresh = { cachedSlotsInfo != null && System.currentTimeMillis() - slotsInfoTimestamp < CACHE_TTL_MS }
        return deduped("slotsInfo", forceRefresh, isFresh, { cachedSlotsInfo }) {
            val now = System.currentTimeMillis()
            val userSlots = getUserSlots(forceRefresh = forceRefresh)
            val myCamps = try { myCampaigns(forceRefresh = forceRefresh) } catch (_: Exception) { emptyList() }
            val activeCamps = myCamps.filter { it.isOpen || it.isPaused }

            val result = (1..6).map { slotNum ->
                if (slotNum == 1) {
                    val camp = activeCamps.find { it.slotNumber == 1 }
                    SlotInfo(
                        slotNumber = 1,
                        isPermanent = true,
                        isUnlocked = true,
                        isExpired = false,
                        cost = 0,
                        expiresAt = null,
                        remainingDays = 0,
                        currentCampaign = camp,
                    )
                } else {
                    val userSlot = userSlots.find { it.slotNumber == slotNum }
                    val isUnlocked = userSlot != null
                    val isExpired = userSlot?.isExpired ?: true
                    val camp = if (isUnlocked && !isExpired) {
                        activeCamps.find { it.slotNumber == slotNum }
                    } else null

                    SlotInfo(
                        slotNumber = slotNum,
                        isPermanent = false,
                        isUnlocked = isUnlocked,
                        isExpired = isExpired,
                        cost = SlotInfo.costForSlot(slotNum),
                        expiresAt = userSlot?.expiresAt,
                        remainingDays = userSlot?.remainingDays ?: 0,
                        currentCampaign = camp,
                    )
                }
            }
            cachedSlotsInfo = result
            slotsInfoTimestamp = now
            result
        }
    }

    // ---------------------------------------------------------------- admin operations

    suspend fun getAllProfiles(forceRefresh: Boolean = false): List<Profile> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedAllProfiles != null && now - allProfilesTimestamp < CACHE_TTL_MS) {
            return cachedAllProfiles!!
        }
        val list = supabase.from("profiles").select {
            order("created_at", Order.DESCENDING)
            limit(500)
        }.decodeList<Profile>()
        cachedAllProfiles = list
        allProfilesTimestamp = now
        return list
    }

    suspend fun adminSetUserLock(
        targetUserId: String,
        locked: Boolean,
        reason: String? = null
    ): AdminSetUserLockResult {
        cachedAllProfiles = null
        return supabase.postgrest.rpc(
            "admin_set_user_lock",
            AdminSetUserLockParams(
                targetUserId = targetUserId,
                locked = locked,
                reason = reason,
            )
        ).decodeAs<AdminSetUserLockResult>()
    }

    suspend fun adminAdjustCredits(
        targetUserId: String,
        amount: Int,
        reason: String? = null
    ): AdminAdjustCreditsResult {
        val result = supabase.postgrest.rpc(
            "admin_adjust_credits",
            AdminAdjustCreditsParams(
                targetUserId = targetUserId,
                amount = amount,
                reason = reason,
            )
        ).decodeAs<AdminAdjustCreditsResult>()
        cachedAllProfiles = null
        if (targetUserId == uid()) {
            cachedMyProfile = null
            cachedCreditLogs = null
        }
        invalidateDashboardCache()
        return result
    }

    // ---------------------------------------------------------------- contact & bug reports

    /** User gửi phản hồi liên hệ/báo lỗi cho Admin (insert trực tiếp, RLS chặn spoof). */
    suspend fun submitContactReport(
        category: String,
        message: String,
        appVersion: String?,
        platform: String?,
        deviceModel: String?,
    ) {
        supabase.from("contact_reports").insert(
            ContactReportInsert(
                userId = uid(),
                category = category,
                message = message,
                appVersion = appVersion,
                platform = platform,
                deviceModel = deviceModel,
            )
        )
    }

    /** Danh sách phản hồi cho Admin (RPC security definer, server kiểm tra role). */
    suspend fun adminGetContactReports(status: String? = null): List<ContactReport> {
        return supabase.postgrest.rpc(
            "admin_get_contact_reports",
            AdminGetContactReportsParams(status = status),
        ).decodeAs<List<ContactReport>>()
    }

    /** Admin đánh dấu đã xử lý / mở lại một phản hồi. */
    suspend fun adminUpdateContactReportStatus(reportId: String, resolved: Boolean): ContactReport {
        return supabase.postgrest.rpc(
            "admin_update_report_status",
            AdminUpdateReportStatusParams(reportId = reportId, resolved = resolved),
        ).decodeAs<ContactReport>()
    }

    /** Admin trả lời phản hồi; server tự đẩy notification cho user. */
    suspend fun adminReplyContactReport(reportId: String, message: String): ContactReport {
        return supabase.postgrest.rpc(
            "admin_reply_contact_report",
            AdminReplyContactReportParams(reportId = reportId, message = message),
        ).decodeAs<ContactReport>()
    }

    // ---------------------------------------------------------------- credit top-up

    suspend fun getTopupPackages(forceRefresh: Boolean = false): List<TopupPackage> {
        val isFresh = {
            cachedTopupPackages != null &&
                System.currentTimeMillis() - topupPackagesTimestamp < PACKAGES_CACHE_TTL_MS
        }
        return deduped("topupPackages", forceRefresh, isFresh, { cachedTopupPackages }) {
            val result = try {
                val packages = supabase.from("topup_packages").select {
                    filter { eq("is_active", true) }
                    order("sort_order", Order.ASCENDING)
                }.decodeList<TopupPackage>()
                if (packages.isNotEmpty()) packages else com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES
            } catch (_: Exception) {
                com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES
            }
            cachedTopupPackages = result
            topupPackagesTimestamp = System.currentTimeMillis()
            result
        }
    }

    suspend fun processTopupOrder(
        packageId: String,
        purchaseToken: String? = null,
        orderRef: String? = null,
        paymentMethod: String = "google_play",
        rawReceipt: String? = null,
    ): ProcessTopupResult {
        val result = supabase.postgrest.rpc(
            "process_topup_order",
            ProcessTopupParams(
                packageId = packageId,
                purchaseToken = purchaseToken,
                orderRef = orderRef,
                paymentMethod = paymentMethod,
                rawReceipt = rawReceipt,
            )
        ).decodeAs<ProcessTopupResult>()
        cachedMyProfile = null
        cachedCreditLogs = null
        invalidateDashboardCache()
        return result
    }

    // ---------------------------------------------------------------- notifications

    suspend fun getMyNotifications(limit: Long = 50, forceRefresh: Boolean = false): List<UserNotification> {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        val isFresh = { cachedNotifications != null && System.currentTimeMillis() - notificationsTimestamp < SHORT_CACHE_TTL_MS }
        return deduped("notifications", forceRefresh, isFresh, { cachedNotifications }) {
            val result = try {
                supabase.from("notifications").select {
                    filter { eq("user_id", currentUid) }
                    order("created_at", Order.DESCENDING)
                    limit(limit)
                }.decodeList<UserNotification>()
            } catch (_: Exception) {
                emptyList()
            }
            cachedNotifications = result
            notificationsTimestamp = System.currentTimeMillis()
            result
        }
    }

    /** Cập nhật server trước, cache sau. Ném lỗi khi mạng thất bại (caller tự hoàn tác UI). */
    suspend fun markNotificationAsRead(id: String) {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
        supabase.from("notifications").update(buildJsonObject { put("is_read", true) }) {
            filter {
                eq("id", id)
                eq("user_id", currentUid)
            }
        }
        cachedNotifications = cachedNotifications?.map { if (it.id == id) it.copy(isRead = true) else it }
        invalidateDashboardCache()
    }

    /** Cập nhật server trước, cache sau. Ném lỗi khi mạng thất bại (caller tự hoàn tác UI). */
    suspend fun markAllNotificationsAsRead() {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
        supabase.from("notifications").update(buildJsonObject { put("is_read", true) }) {
            filter {
                eq("user_id", currentUid)
                eq("is_read", false)
            }
        }
        cachedNotifications = cachedNotifications?.map { it.copy(isRead = true) }
        invalidateDashboardCache()
    }

    /** Cập nhật server trước, cache sau. Ném lỗi khi mạng thất bại (caller tự hoàn tác UI). */
    suspend fun deleteNotification(id: String) {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
        supabase.from("notifications").delete {
            filter {
                eq("id", id)
                eq("user_id", currentUid)
            }
        }
        cachedNotifications = cachedNotifications?.filterNot { it.id == id }
        invalidateDashboardCache()
    }

    suspend fun adminSendNotification(
        targetUserId: String?,
        title: String,
        message: String,
        type: String = "admin",
    ): AdminSendNotificationResult {
        return supabase.postgrest.rpc(
            "admin_send_notification",
            AdminSendNotificationParams(
                targetUserId = targetUserId,
                title = title,
                message = message,
                type = type,
            )
        ).decodeAs<AdminSendNotificationResult>()
    }

    suspend fun getQuestSummary(forceRefresh: Boolean = false): QuestSummary {
        val isFresh = { cachedQuestSummary != null && System.currentTimeMillis() - questSummaryTimestamp < CACHE_TTL_MS }
        return deduped("questSummary", forceRefresh, isFresh, { cachedQuestSummary }) {
            val summary = try {
                supabase.postgrest.rpc("get_user_quest_summary").decodeAs<QuestSummary>()
            } catch (_: Exception) {
                val uid = supabase.auth.currentUserOrNull()?.id
                val today = VnTime.today().toString()
                val dailyCount = if (uid != null) {
                    try {
                        supabase.from("checkins").select {
                            filter {
                                eq("user_id", uid)
                                eq("checkin_date", today)
                            }
                        }.decodeList<Checkin>().size
                    } catch (_: Exception) { 0 }
                } else 0
                QuestSummary(dailyCount = dailyCount)
            }
            cachedQuestSummary = summary
            questSummaryTimestamp = System.currentTimeMillis()
            summary
        }
    }

    suspend fun claimQuestReward(questType: String, milestone: Int): ClaimQuestResult {
        return try {
            val result = supabase.postgrest.rpc(
                function = "claim_quest_reward",
                parameters = ClaimQuestParams(questType = questType, milestone = milestone)
            ).decodeAs<ClaimQuestResult>()
            if (result.success) {
                cachedMyProfile = null
                cachedQuestSummary = null
                cachedCreditLogs = null
                cachedNotifications = null
                invalidateDashboardCache()
            }
            result
        } catch (e: Exception) {
            ClaimQuestResult(
                success = false,
                questType = questType,
                milestone = milestone,
                error = e.message,
                message = e.message ?: localizer.get(R.string.repo_connection_failed)
            )
        }
    }

    // ---------------------------------------------------------------- missions (migration 039)

    /** Danh sách nhiệm vụ cho tab "Nhiệm vụ App"; RPC tự expire nhiệm vụ quá hạn. */
    suspend fun getOpenMissions(forceRefresh: Boolean = false): List<Mission> {
        val isFresh = { cachedOpenMissions != null && System.currentTimeMillis() - openMissionsTimestamp < CACHE_TTL_MS }
        return deduped("openMissions", forceRefresh, isFresh, { cachedOpenMissions }) {
            val result = supabase.postgrest.rpc("get_open_missions").decodeAs<OpenMissionsResult>()
            cachedOpenMissions = result.missions
            openMissionsTimestamp = System.currentTimeMillis()
            result.missions
        }
    }

    /** Chủ app mở nhiệm vụ: server trừ phí 50 Credit × số ngày và kiểm tra số dư ≥ 300. */
    suspend fun createMission(params: CreateMissionParams): CreateMissionResult {
        val result = supabase.postgrest.rpc("create_mission", params).decodeAs<CreateMissionResult>()
        cachedMyProfile = null
        cachedCreditLogs = null
        cachedOpenMissions = null
        openMissionsTimestamp = 0L
        invalidateDashboardCache()
        return result
    }

    /** Người tham gia nhận nhiệm vụ (server kiểm tra còn chỗ, chưa tham gia, còn hạn). */
    suspend fun joinMission(missionId: String): JoinMissionResult {
        val result = supabase.postgrest.rpc(
            "join_mission",
            JoinMissionParams(missionId = missionId)
        ).decodeAs<JoinMissionResult>()
        cachedOpenMissions = null
        openMissionsTimestamp = 0L
        return result
    }

    /** Nhiệm vụ hiện tại của 1 app cho màn quản lý của chủ nhiệm vụ (migration 040). */
    suspend fun getCampaignMission(campaignId: String): Mission? {
        val result = supabase.postgrest.rpc(
            "get_campaign_mission",
            CampaignIdParams(campaignId = campaignId)
        ).decodeAs<CampaignMissionResult>()
        return result.mission
    }

    /** Chủ nhiệm vụ dừng hẳn nhiệm vụ: không nhận người mới, không trả thưởng trong lúc dừng. */
    suspend fun pauseMission(missionId: String): MissionActionResult =
        missionAction("pause_mission", missionId)

    /** Mở lại nhiệm vụ đã dừng; thời hạn gốc giữ nguyên. */
    suspend fun resumeMission(missionId: String): MissionActionResult =
        missionAction("resume_mission", missionId)

    /** Xóa mềm nhiệm vụ (ẩn khỏi danh sách, không hoàn phí). */
    suspend fun deleteMission(missionId: String): MissionActionResult =
        missionAction("delete_mission", missionId)

    private suspend fun missionAction(function: String, missionId: String): MissionActionResult {
        val result = supabase.postgrest.rpc(
            function,
            MissionIdParams(missionId = missionId)
        ).decodeAs<MissionActionResult>()
        cachedOpenMissions = null
        openMissionsTimestamp = 0L
        return result
    }
}
