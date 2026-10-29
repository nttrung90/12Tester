package com.ntt.crosstest.data

import android.os.SystemClock
import com.ntt.crosstest.R
import com.ntt.crosstest.util.VnTime
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Má»™t app Ä‘á»§ Ä‘iá»u kiá»‡n Ä‘á»ƒ gá»£i Ã½ lÃ m bÃ i test káº¿ tiáº¿p.
 * [installedCount] lÃ  sá»‘ ngÆ°á»i Ä‘Ã£ cÃ i app (campaign_members) â€” dÃ¹ng Ä‘á»ƒ Æ°u tiÃªn
 * app cÃ³ lÆ°á»£t cÃ i tháº¥p cho tÃ i khoáº£n má»›i.
 */
data class NextCampaignCandidate(
    val campaign: Campaign,
    val installedCount: Int = 0,
)

/**
 * Entry lÆ°u trong bá»™ nhá»› RAM kÃ¨m metadata xÃ¡c thá»±c tÃ­nh tÆ°Æ¡i má»›i (C01, C04).
 */
data class CacheEntry<T>(
    val value: T,
    val monotonicCreatedAtMs: Long,
    val sessionGeneration: Long,
    val mutationVersion: Long,
    val scopeUid: String? = null,
    val limit: Long? = null,
    val isPublic: Boolean = false,
    val businessDate: LocalDate? = null,
) {
    fun isFresh(
        ttlMs: Long,
        currentGeneration: Long,
        currentMutationVersion: Long,
        currentUid: String?,
        requestedLimit: Long? = null,
        currentBusinessDate: LocalDate? = null,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (sessionGeneration != currentGeneration) return false
        if (mutationVersion != currentMutationVersion) return false
        if (!isPublic && scopeUid != currentUid) return false
        if (requestedLimit != null && (limit == null || limit < requestedLimit)) return false
        if (businessDate != null && currentBusinessDate != null && businessDate != currentBusinessDate) return false
        return (nowMs - monotonicCreatedAtMs) < ttlMs
    }
}

/**
 * All database access for campaigns, members, check-ins and feedback.
 * Row Level Security on the database guarantees users can only write their own rows;
 * every method also scopes writes to the current user id.
 */
class CampaignRepository(
    private val supabase: SupabaseClient,
    private val localizer: Localizer,
    val sessionCacheManager: SessionCacheManager = SessionCacheManager,
    private val monotonicClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val businessDayClock: () -> LocalDate = { VnTime.today() },
) {

    companion object {
        const val CACHE_TTL_MS = 3 * 60 * 1000L // 3 minutes cache TTL
        private const val SHORT_CACHE_TTL_MS = 60 * 1000L // warnings/notifications/logs
        private const val PACKAGES_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val AUTO_ACCEPT_THROTTLE_MS = 10 * 60 * 1000L
        private const val MAX_MAP_ENTRIES = 200

        /**
         * Danh sÃ¡ch cá»™t cá»§a báº£ng `campaigns` khá»›p model [Campaign].
         * Chá»n cá»™t tÆ°á»ng minh thay vÃ¬ `select *` giÃºp giáº£m payload cho Ä‘Æ°á»ng legacy.
         */
        private val CAMPAIGN_COLUMNS = Columns.list(
            "id", "owner_id", "app_name", "package_name", "play_url", "testing_url",
            "description", "target_testers", "target_days", "start_date", "status",
            "icon_url", "test_username", "test_password", "tip_credits", "is_boosted",
            "slot_number", "paused_reason", "admin_note", "completed_at", "created_at",
            "push_business_date", "push_expires_at", "push_order",
        )
    }

    val sessionGeneration get() = sessionCacheManager.generation
    private val mutationVersions = ConcurrentHashMap<String, AtomicLong>()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun monotonicNow(): Long = monotonicClock()
    fun businessDay(): LocalDate = businessDayClock()

    fun getMutationVersion(domain: String): Long =
        mutationVersions.computeIfAbsent(domain) { AtomicLong(1L) }.get()

    fun bumpMutationVersion(domain: String): Long =
        mutationVersions.computeIfAbsent(domain) { AtomicLong(1L) }.incrementAndGet()

    private fun <K, V> evictOldEntries(map: ConcurrentHashMap<K, CacheEntry<V>>, maxCapacity: Int = MAX_MAP_ENTRIES) {
        if (map.size > maxCapacity) {
            val now = monotonicNow()
            map.entries.removeIf { (_, entry) -> (now - entry.monotonicCreatedAtMs) > CACHE_TTL_MS * 2 }
            if (map.size > maxCapacity) {
                val oldest = map.entries.sortedBy { it.value.monotonicCreatedAtMs }.take(map.size - maxCapacity)
                for (item in oldest) {
                    map.remove(item.key, item.value)
                }
            }
        }
    }

    @Volatile
    private var cachedOpenCampaigns: CacheEntry<List<Campaign>>? = null

    @Volatile
    private var cachedPausedCampaigns: CacheEntry<List<Campaign>>? = null

    @Volatile
    private var cachedCompletedCampaigns: CacheEntry<List<Campaign>>? = null

    private val statsCache = ConcurrentHashMap<String, CacheEntry<CampaignStats>>()
    private val profileCache = ConcurrentHashMap<String, CacheEntry<Profile>>()

    @Volatile
    private var cachedMyCampaigns: CacheEntry<List<Campaign>>? = null

    @Volatile
    private var cachedMyJoinedCampaigns: CacheEntry<List<Campaign>>? = null

    @Volatile
    private var cachedLeaderboard: CacheEntry<List<LeaderboardUser>>? = null
    @Volatile
    private var cachedTestLeaderboard: CacheEntry<List<LeaderboardUser>>? = null

    private val myRankCache = ConcurrentHashMap<String, CacheEntry<MyRank>>()

    @Volatile
    private var cachedMyProfile: CacheEntry<Profile>? = null

    @Volatile
    private var cachedTodayCheckins: CacheEntry<Int>? = null

    @Volatile
    private var cachedPendingReports: CacheEntry<Int>? = null

    @Volatile
    private var cachedQuestSummary: CacheEntry<QuestSummary>? = null

    private val ownerStatsCache = ConcurrentHashMap<String, CacheEntry<CampaignOwnerStats>>()
    private val campaignCache = ConcurrentHashMap<String, CacheEntry<Campaign>>()

    @Volatile
    private var cachedSlotsInfo: CacheEntry<List<SlotInfo>>? = null
    @Volatile
    private var cachedUserSlots: CacheEntry<List<UserSlot>>? = null

    private val reportsCache = ConcurrentHashMap<String, CacheEntry<List<TesterReport>>>()
    private val testersCache = ConcurrentHashMap<String, CacheEntry<List<TesterItem>>>()

    @Volatile
    private var cachedAllProfiles: CacheEntry<List<Profile>>? = null

    private val membersCache = ConcurrentHashMap<String, CacheEntry<List<CampaignMember>>>()
    private val checkinsCache = ConcurrentHashMap<String, CacheEntry<List<Checkin>>>()
    private val feedbacksCache = ConcurrentHashMap<String, CacheEntry<List<Feedback>>>()
    private val detailCache = ConcurrentHashMap<String, CacheEntry<CampaignDetailResult>>()

    @Volatile
    private var cachedUnreadWarnings: CacheEntry<List<UserWarning>>? = null
    @Volatile
    private var cachedWeeklyWarnings: CacheEntry<Int>? = null
    @Volatile
    private var cachedCreditLogs: CacheEntry<List<CreditLog>>? = null
    @Volatile
    private var cachedTopupPackages: CacheEntry<List<TopupPackage>>? = null
    @Volatile
    private var cachedNotifications: CacheEntry<List<UserNotification>>? = null
    @Volatile
    private var cachedHomeFeed: CacheEntry<HomeFeedResult>? = null
    @Volatile
    private var cachedDashboard: CacheEntry<MyDashboardResult>? = null
    @Volatile
    private var cachedOpenMissions: CacheEntry<List<Mission>>? = null

    @Volatile
    private var lastAutoAcceptRunMs: Long = 0L

    private val flightMap = ConcurrentHashMap<String, CompletableDeferred<Any?>>()

    private suspend fun <T> deduped(
        domain: String,
        key: String,
        forceRefresh: Boolean,
        isFresh: () -> Boolean,
        getCached: () -> T?,
        fetch: suspend () -> T,
    ): T {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion(domain)
        val curUid = supabase.auth.currentUserOrNull()?.id

        if (!forceRefresh && isFresh()) {
            getCached()?.let { return it }
        }

        val flightKey = "$domain:$key:$gen:$mutVer:$curUid"

        var isNew = false
        val deferred = flightMap.computeIfAbsent(flightKey) {
            isNew = true
            CompletableDeferred()
        }

        if (isNew) {
            repositoryScope.launch {
                try {
                    val result = fetch()
                    flightMap.remove(flightKey, deferred)
                    deferred.complete(result)
                } catch (t: Throwable) {
                    flightMap.remove(flightKey, deferred)
                    deferred.completeExceptionally(t)
                } finally {
                    flightMap.remove(flightKey, deferred)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return deferred.await() as T
    }

    fun clearMemoryCache() {
        sessionGeneration.incrementAndGet()
        bumpMutationVersion("campaigns")
        bumpMutationVersion("profile")
        bumpMutationVersion("reports")
        bumpMutationVersion("membership")
        bumpMutationVersion("stats")
        bumpMutationVersion("notifications")
        bumpMutationVersion("leaderboard")
        bumpMutationVersion("user_slots")
        bumpMutationVersion("dashboard")
        bumpMutationVersion("quests")
        bumpMutationVersion("topup")

        cachedOpenCampaigns = null
        cachedPausedCampaigns = null
        cachedCompletedCampaigns = null
        statsCache.clear()
        profileCache.clear()
        cachedMyCampaigns = null
        cachedMyJoinedCampaigns = null
        cachedLeaderboard = null
        cachedTestLeaderboard = null
        myRankCache.clear()
        cachedMyProfile = null
        cachedTodayCheckins = null
        cachedPendingReports = null
        cachedQuestSummary = null
        ownerStatsCache.clear()
        campaignCache.clear()
        cachedSlotsInfo = null
        cachedUserSlots = null
        cachedOpenMissions = null
        reportsCache.clear()
        testersCache.clear()
        cachedAllProfiles = null
        membersCache.clear()
        checkinsCache.clear()
        feedbacksCache.clear()
        detailCache.clear()
        cachedUnreadWarnings = null
        cachedWeeklyWarnings = null
        cachedCreditLogs = null
        cachedTopupPackages = null
        cachedNotifications = null
        cachedHomeFeed = null
        cachedDashboard = null
    }

    fun invalidateProfile(uid: String? = null) {
        bumpMutationVersion("profile")
        cachedMyProfile = null
        cachedCreditLogs = null
        if (uid != null) {
            profileCache.remove(uid)
        } else {
            profileCache.clear()
        }
        invalidateDashboardCache()
    }

    fun invalidateCampaign(campaignId: String) {
        bumpMutationVersion("campaigns")
        bumpMutationVersion("user_slots")
        campaignCache.remove(campaignId)
        cachedOpenCampaigns = null
        cachedPausedCampaigns = null
        cachedCompletedCampaigns = null
        cachedMyCampaigns = null
        cachedMyJoinedCampaigns = null
        cachedHomeFeed = null
        cachedSlotsInfo = null
        statsCache.remove(campaignId)
        ownerStatsCache.remove(campaignId)
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
    }

    fun invalidateMembership(campaignId: String) {
        bumpMutationVersion("membership")
        bumpMutationVersion("campaigns")
        bumpMutationVersion("stats")
        bumpMutationVersion("dashboard")
        bumpMutationVersion("user_slots")
        membersCache.remove(campaignId)
        testersCache.remove(campaignId)
        ownerStatsCache.remove(campaignId)
        statsCache.remove(campaignId)
        cachedMyJoinedCampaigns = null
        cachedOpenCampaigns = null
        cachedPausedCampaigns = null
        cachedCompletedCampaigns = null
        cachedHomeFeed = null
        cachedSlotsInfo = null
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
    }

    fun invalidateReportsAndStats(campaignId: String) {
        bumpMutationVersion("reports")
        bumpMutationVersion("stats")
        bumpMutationVersion("membership")
        bumpMutationVersion("campaigns")
        bumpMutationVersion("dashboard")
        reportsCache.keys.removeIf { it.startsWith(campaignId) }
        checkinsCache.remove(campaignId)
        feedbacksCache.remove(campaignId)
        ownerStatsCache.remove(campaignId)
        testersCache.remove(campaignId)
        statsCache.remove(campaignId)
        cachedPendingReports = null
        cachedTodayCheckins = null
        cachedHomeFeed = null
        invalidateDetailCache(campaignId)
        invalidateDashboardCache()
    }

    fun peekCampaign(id: String): Campaign? {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("campaigns")
        val now = monotonicNow()
        val open = cachedOpenCampaigns?.takeIf { it.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = now) }?.value?.find { it.id == id }
        if (open != null) return open
        val my = cachedMyCampaigns?.takeIf { it.isFresh(CACHE_TTL_MS, gen, mutVer, supabase.auth.currentUserOrNull()?.id, nowMs = now) }?.value?.find { it.id == id }
        if (my != null) return my
        val joined = cachedMyJoinedCampaigns?.takeIf { it.isFresh(CACHE_TTL_MS, gen, mutVer, supabase.auth.currentUserOrNull()?.id, nowMs = now) }?.value?.find { it.id == id }
        if (joined != null) return joined
        return campaignCache[id]?.takeIf { it.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = now) }?.value
    }

    fun peekCampaignOwnerStats(campaignId: String): CampaignOwnerStats? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("stats")
        return ownerStatsCache[campaignId]?.takeIf { it.isFresh(CACHE_TTL_MS, gen, mutVer, uid, currentBusinessDate = businessDay(), nowMs = monotonicNow()) }?.value
    }

    fun peekCampaignReports(campaignId: String, canManage: Boolean = false): List<TesterReport>? {
        val uid = supabase.auth.currentUserOrNull()?.id
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("reports")
        val scope = uid
        return reportsCache["${campaignId}_$canManage"]?.takeIf { it.isFresh(CACHE_TTL_MS, gen, mutVer, scope, nowMs = monotonicNow()) }?.value
    }

    fun peekCampaignTesters(campaignId: String): List<TesterItem>? {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("membership")
        return testersCache[campaignId]?.takeIf { it.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) }?.value
    }

    private fun uid(): String =
        supabase.auth.currentUserOrNull()?.id
            ?: throw IllegalStateException(localizer.get(R.string.campaign_login_required))
// ---------------------------------------------------------------- campaigns

        suspend fun listOpenCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("campaigns")
        val isFresh = { cachedOpenCampaigns?.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true }
        return deduped("campaigns", "open", forceRefresh, isFresh, { cachedOpenCampaigns?.value }) {
            val result = supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                filter { eq("status", "open") }
                order("created_at", Order.DESCENDING)
            }.decodeList<Campaign>()
            if (gen == sessionGeneration.get()) {
                cachedOpenCampaigns = CacheEntry(result, monotonicNow(), gen, mutVer, null, isPublic = true)
            }
            result
        }
    }

    /** Tab "App táº¡m dá»«ng" trÃªn Trang chá»§. */
    suspend fun listPausedCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("campaigns")
        val isFresh = { cachedPausedCampaigns?.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true }
        return deduped("campaigns", "paused", forceRefresh, isFresh, { cachedPausedCampaigns?.value }) {
            val result = try {
                supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                    filter { eq("status", "paused") }
                    order("created_at", Order.DESCENDING)
                }.decodeList<Campaign>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val existing = cachedPausedCampaigns?.value
                if (existing != null) return@deduped existing
                throw e
            }
            if (gen == sessionGeneration.get()) {
                cachedPausedCampaigns = CacheEntry(result, monotonicNow(), gen, mutVer, null, isPublic = true)
            }
            result
        }
    }

    /** Tab "ÄÃ£ hoÃ n thÃ nh Kiá»ƒm thá»­" trÃªn Trang chá»§ (lÃªn production hoáº·c háº¿t 30 ngÃ y). */
    suspend fun listCompletedCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("campaigns")
        val isFresh = { cachedCompletedCampaigns?.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true }
        return deduped("campaigns", "completed", forceRefresh, isFresh, { cachedCompletedCampaigns?.value }) {
            val result = try {
                supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                    filter { isIn("status", listOf("production", "completed")) }
                    order("created_at", Order.DESCENDING)
                }.decodeList<Campaign>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val existing = cachedCompletedCampaigns?.value
                if (existing != null) return@deduped existing
                throw e
            }
            if (gen == sessionGeneration.get()) {
                cachedCompletedCampaigns = CacheEntry(result, monotonicNow(), gen, mutVer, null, isPublic = true)
            }
            result
        }
    }

    /** XÃ³a cache danh sÃ¡ch campaign theo tráº¡ng thÃ¡i (dÃ¹ng sau khi sync vÃ²ng Ä‘á»i). */
    private fun invalidateCampaignLists() {
        bumpMutationVersion("campaigns")
        bumpMutationVersion("user_slots")
        cachedOpenCampaigns = null
        cachedPausedCampaigns = null
        cachedCompletedCampaigns = null
        cachedMyCampaigns = null
        statsCache.clear()
        cachedSlotsInfo = null
        cachedHomeFeed = null
    }

    /** Dashboard tá»•ng há»£p pháº£i táº£i láº¡i khi dá»¯ liá»‡u cÃ¡ nhÃ¢n thay Ä‘á»•i. */
    private fun invalidateDashboardCache() {
        bumpMutationVersion("dashboard")
        cachedDashboard = null
    }

    /** XÃ³a cache fast-path cá»§a mÃ n chi tiáº¿t cho má»i ngÆ°á»i dÃ¹ng hiá»‡n cÃ³. */
    private fun invalidateDetailCache(campaignId: String) {
        detailCache.keys.removeIf { it.startsWith("$campaignId|") }
    }

    /** Tá»± Ä‘á»™ng duyá»‡t bÃ¡o cÃ¡o quÃ¡ háº¡n tá»‘i Ä‘a 1 láº§n / 10 phÃºt Ä‘á»ƒ tiáº¿t kiá»‡m round-trip. */
    private suspend fun maybeAutoAcceptExpiredReports() {
        val now = monotonicNow()
        if (now - lastAutoAcceptRunMs < AUTO_ACCEPT_THROTTLE_MS) return
        lastAutoAcceptRunMs = now
        try {
            autoAcceptExpiredReports()
        } catch (_: Exception) {
        }
    }

    /**
     * Äá»“ng bá»™ vÃ²ng Ä‘á»i campaign phÃ­a server: hoÃ n thÃ nh app quÃ¡ 30 ngÃ y hiá»ƒn thá»‹,
     * táº¡m dá»«ng app cá»§a chá»§ app thiáº¿u nhiá»‡m vá»¥ ngÃ y 7 ngÃ y liÃªn tá»¥c.
     * Best-effort: bá» qua náº¿u offline hoáº·c DB chÆ°a cháº¡y migration 017.
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
            // Migration chÆ°a cháº¡y hoáº·c máº¡ng lá»—i â€” khÃ´ng cháº·n táº£i Trang chá»§.
        }
    }

    /** Chá»§ app báº­t test láº¡i app Ä‘ang táº¡m dá»«ng (RPC kiá»ƒm tra cá»­a sá»• 30 ngÃ y + chá»§ app Ä‘Ã£ hoáº¡t Ä‘á»™ng láº¡i). */
    suspend fun resumeCampaign(campaignId: String): ResumeCampaignResult {
        val result = supabase.postgrest.rpc(
            "resume_campaign",
            ResumeCampaignParams(campaignId = campaignId)
        ).decodeAs<ResumeCampaignResult>()
        invalidateCampaign(campaignId)
        invalidateProfile()
        invalidateDashboardCache()
        return result
    }
    suspend fun getCampaign(id: String, forceRefresh: Boolean = false): Campaign? {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("campaigns")
        if (!forceRefresh) {
            peekCampaign(id)?.let { return it }
        }
        val isFresh = { campaignCache[id]?.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true }
        return deduped("campaigns", "get:$id", forceRefresh, isFresh, { campaignCache[id]?.value }) {
            val result = supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                filter { eq("id", id) }
                limit(1)
            }.decodeList<Campaign>().firstOrNull()
            if (gen == sessionGeneration.get() && result != null) {
                campaignCache[id] = CacheEntry(result, monotonicNow(), gen, mutVer, null, isPublic = true)
            }
            result
        }
    }
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
        bumpMutationVersion("campaigns")
        bumpMutationVersion("dashboard")
        bumpMutationVersion("profile")
        bumpMutationVersion("user_slots")
        cachedOpenCampaigns = null
        cachedMyCampaigns = null
        cachedMyProfile = null
        cachedHomeFeed = null
        cachedSlotsInfo = null
        cachedUserSlots = null
        toInsert.ownerId?.let { invalidateProfile(it) }
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
        invalidateCampaign(campaignId)
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

    /** Tráº¡ng thÃ¡i phiÃªn Ä‘Äƒng nháº­p Ä‘á»ƒ ViewModel pháº£n á»©ng khi session khÃ´i phá»¥c xong. */
    val sessionStatus: kotlinx.coroutines.flow.StateFlow<io.github.jan.supabase.auth.status.SessionStatus>
        get() = supabase.auth.sessionStatus
    suspend fun myCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val user = supabase.auth.currentUserOrNull() ?: return emptyList()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("campaigns")
        val isFresh = { cachedMyCampaigns?.isFresh(CACHE_TTL_MS, gen, mutVer, user.id, nowMs = monotonicNow()) == true }
        return deduped("campaigns", "myCampaigns", forceRefresh, isFresh, { cachedMyCampaigns?.value }) {
            val result = supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                filter { eq("owner_id", user.id) }
                order("created_at", Order.DESCENDING)
            }.decodeList<Campaign>()
            if (gen == sessionGeneration.get() && user.id == supabase.auth.currentUserOrNull()?.id) {
                cachedMyCampaigns = CacheEntry(result, monotonicNow(), gen, mutVer, user.id)
            }
            result
        }
    }

    // ---------------------------------------------------------------- members

    suspend fun joinCampaign(campaignId: String) {
        supabase.from("campaign_members")
            .insert(CampaignMember(campaignId = campaignId, userId = uid()))
        invalidateMembership(campaignId)
    }

    suspend fun leaveCampaign(campaignId: String) {
        val userId = uid()
        supabase.from("campaign_members").delete {
            filter {
                eq("campaign_id", campaignId)
                eq("user_id", userId)
            }
        }
        invalidateMembership(campaignId)
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
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("membership")
        val isFresh = { membersCache[campaignId]?.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true }
        return deduped("membership", "members:$campaignId", forceRefresh, isFresh, { membersCache[campaignId]?.value }) {
            val result = supabase.from("campaign_members").select {
                filter { eq("campaign_id", campaignId) }
                order("joined_at", Order.ASCENDING)
            }.decodeList<CampaignMember>()
            if (gen == sessionGeneration.get()) {
                membersCache[campaignId] = CacheEntry(result, monotonicNow(), gen, mutVer, null)
            }
            result
        }
    }

    suspend fun myJoinedCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val user = supabase.auth.currentUserOrNull() ?: return emptyList()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("campaigns")
        val isFresh = { cachedMyJoinedCampaigns?.isFresh(CACHE_TTL_MS, gen, mutVer, user.id, nowMs = monotonicNow()) == true }
        return deduped("campaigns", "myJoinedCampaigns", forceRefresh, isFresh, { cachedMyJoinedCampaigns?.value }) {
            val memberRows = supabase.from("campaign_members").select {
                filter { eq("user_id", user.id) }
            }.decodeList<CampaignMember>()
            val ids = memberRows.map { it.campaignId }.distinct()
            if (ids.isEmpty()) {
                if (gen == sessionGeneration.get()) {
                    cachedMyJoinedCampaigns = CacheEntry(emptyList(), monotonicNow(), gen, mutVer, user.id)
                }
                return@deduped emptyList()
            }
            val campaigns = supabase.from("campaigns").select(columns = CAMPAIGN_COLUMNS) {
                filter { isIn("id", ids) }
                order("created_at", Order.DESCENDING)
            }.decodeList<Campaign>()
            val filtered = campaigns.filter { it.ownerId != user.id }
            if (gen == sessionGeneration.get() && user.id == supabase.auth.currentUserOrNull()?.id) {
                cachedMyJoinedCampaigns = CacheEntry(filtered, monotonicNow(), gen, mutVer, user.id)
            }
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
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("reports")
        val isFresh = { checkinsCache[campaignId]?.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true }
        return deduped("reports", "checkins:$campaignId", forceRefresh, isFresh, { checkinsCache[campaignId]?.value }) {
            val result = supabase.from("checkins").select {
                filter { eq("campaign_id", campaignId) }
                order("checkin_date", Order.DESCENDING)
            }.decodeList<Checkin>()
            if (gen == sessionGeneration.get()) {
                checkinsCache[campaignId] = CacheEntry(result, monotonicNow(), gen, mutVer, null)
            }
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
        thumbnailUrl: String? = null,
        missionId: String? = null,
    ): SubmitTesterReportResult {
        val params = SubmitTesterReportParams(
            campaignId = campaignId,
            content = comment?.trim()?.ifBlank { null },
            screenshotUrl = screenshotUrl,
            rating = rating.coerceIn(1, 5),
            imageHash = imageHash,
            clientSubmitId = clientSubmitId,
            thumbnailUrl = thumbnailUrl,
            missionId = missionId,
        )
        val result = try {
            supabase.postgrest.rpc(
                function = "submit_tester_report",
                parameters = params,
            ).decodeAs<SubmitTesterReportResult>()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!isMissingSubmitRpc(e)) throw e

            // Với yêu cầu mission mới: nếu backend chưa có RPC hỗ trợ thì báo lỗi, KHÔNG fallback bỏ missionId
            if (missionId != null) {
                throw IllegalStateException("server_update_required", e)
            }

            // 7 tham số thất bại do thiếu hàm trong schema cache (chưa có migration 045).
            // Thử chữ ký 6 tham số (migration 033/037): giữ nguyên clientSubmitId để bảo toàn idempotency, chỉ bỏ thumbnailUrl.
            try {
                supabase.postgrest.rpc(
                    function = "submit_tester_report",
                    parameters = SubmitTesterReportSixParams(
                        campaignId = campaignId,
                        content = params.content,
                        screenshotUrl = params.screenshotUrl,
                        rating = params.rating,
                        imageHash = params.imageHash,
                        clientSubmitId = clientSubmitId,
                    ),
                ).decodeAs<SubmitTesterReportResult>()
            } catch (e2: Exception) {
                if (e2 is CancellationException) throw e2
                if (!isMissingSubmitRpc(e2)) throw e2

                // Cả chữ ký 7 tham số và 6 tham số đều không tồn tại trong schema cache.
                if (requireIdempotency) {
                    throw IllegalStateException("report_idempotency_unavailable", e2)
                }

                // Chỉ khi caller cho phép (requireIdempotency = false) mới fallback về 5 tham số cũ (pre-033).
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
        }
        val uid = uid()
        invalidateReportsAndStats(campaignId)
        invalidateProfile(uid)
        invalidateMembership(campaignId)
        bumpMutationVersion("quests")
        cachedOpenMissions = null
        cachedCreditLogs = null
        return result
    }

    private fun isMissingRpc(error: Exception, functionName: String? = null): Boolean {
        if (error is CancellationException) return false
        if (error is PostgrestRestException) {
            return error.code.equals("PGRST202", ignoreCase = true) && error.statusCode == 404
        }
        if (error is RestException && error.statusCode != 404) return false
        val message = error.message.orEmpty()
        return message.contains("PGRST202", ignoreCase = true) &&
            message.contains("could not find the function", ignoreCase = true)
    }

    private fun isMissingSubmitRpc(error: Exception): Boolean {
        return isMissingRpc(error, "submit_tester_report")
    }

    suspend fun getFeedbacks(campaignId: String, forceRefresh: Boolean = false): List<Feedback> {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("reports")
        val isFresh = { feedbacksCache[campaignId]?.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true }
        return deduped("reports", "feedbacks:$campaignId", forceRefresh, isFresh, { feedbacksCache[campaignId]?.value }) {
            val result = supabase.from("feedbacks").select {
                filter { eq("campaign_id", campaignId) }
                order("created_at", Order.DESCENDING)
            }.decodeList<Feedback>()
            if (gen == sessionGeneration.get()) {
                feedbacksCache[campaignId] = CacheEntry(result, monotonicNow(), gen, mutVer, null)
            }
            result
        }
    }
// ---------------------------------------------------------------- profiles

        suspend fun getProfiles(userIds: List<String>, forceRefresh: Boolean = false): Map<String, Profile> {
        if (userIds.isEmpty()) return emptyMap()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("profile")
        val now = monotonicNow()
        val result = mutableMapOf<String, Profile>()
        val missingIds = mutableListOf<String>()

        if (!forceRefresh) {
            for (id in userIds) {
                val cached = profileCache[id]
                if (cached != null && cached.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow())) {
                    result[id] = cached.value
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e
            }
            val fetchTime = monotonicNow()
            if (gen == sessionGeneration.get()) {
                for (p in fetched) {
                    profileCache[p.id] = CacheEntry(p, fetchTime, gen, mutVer, null)
                    result[p.id] = p
                }
                evictOldEntries(profileCache)
            }
        }
        return result
    }

    suspend fun getMyProfile(forceRefresh: Boolean = false): Profile? {
        val user = supabase.auth.currentUserOrNull() ?: return null
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("profile")
        val isFresh = { cachedMyProfile?.isFresh(CACHE_TTL_MS, gen, mutVer, user.id, nowMs = monotonicNow()) == true }
        return deduped("profile", "myProfile", forceRefresh, isFresh, { cachedMyProfile?.value }) {
            val profile = getProfiles(listOf(user.id), forceRefresh = forceRefresh)[user.id]
            if (gen == sessionGeneration.get() && user.id == supabase.auth.currentUserOrNull()?.id) {
                if (profile != null) {
                    cachedMyProfile = CacheEntry(profile, monotonicNow(), gen, mutVer, user.id)
                }
            }
            profile
        }
    }

    suspend fun getTodayCheckinCount(forceRefresh: Boolean = false): Int {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return 0
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("reports")
        val isFresh = {
            cachedTodayCheckins?.isFresh(CACHE_TTL_MS, gen, mutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true
        }
        return deduped("reports", "todayCheckins", forceRefresh, isFresh, { cachedTodayCheckins?.value }) {
            val today = requestDate.toString()
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val existing = cachedTodayCheckins?.takeIf {
                    it.sessionGeneration == gen && it.scopeUid == uid && it.mutationVersion == mutVer && it.businessDate == requestDate
                }?.value
                if (existing != null) return@deduped existing
                throw e
            }
            if (gen == sessionGeneration.get() && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedTodayCheckins = CacheEntry(count, monotonicNow(), gen, mutVer, uid, businessDate = requestDate)
            }
            count
        }
    }

    suspend fun getMyCreditLogs(limit: Long = 20, forceRefresh: Boolean = false): List<CreditLog> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("notifications")
        val isFresh = {
            cachedCreditLogs?.isFresh(SHORT_CACHE_TTL_MS, gen, mutVer, uid, requestedLimit = limit, nowMs = monotonicNow()) == true
        }
        val cachedSlice = { cachedCreditLogs?.value?.take(limit.toInt()) }
        return deduped("notifications", "creditLogs:$limit", forceRefresh, isFresh, cachedSlice) {
            val result = try {
                supabase.from("credit_logs").select {
                    filter { eq("user_id", uid) }
                    order("created_at", Order.DESCENDING)
                    limit(limit)
                }.decodeList<CreditLog>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val existing = cachedCreditLogs?.takeIf {
                    it.sessionGeneration == gen && it.scopeUid == uid && it.mutationVersion == mutVer
                }?.value?.take(limit.toInt())
                if (existing != null) return@deduped existing
                throw e
            }
            if (gen == sessionGeneration.get() && mutVer == getMutationVersion("notifications") && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedCreditLogs = CacheEntry(result, monotonicNow(), gen, mutVer, uid, limit = limit)
            }
            result
        }.take(limit.toInt())
    }
// ---------------------------------------------------------------- leaderboard

    /**
     * Bảng xếp hạng công khai.
     * @param sort "credit" = top theo Credits (mặc định, cơ chế cũ);
     *             "test" = top theo tổng lượt test toàn thời gian.
     */
        suspend fun getLeaderboard(sort: String = "credit", forceRefresh: Boolean = false): List<LeaderboardUser> {
        val isTest = sort == "test"
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("leaderboard")
        val isFresh = {
            if (isTest) cachedTestLeaderboard?.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true
            else cachedLeaderboard?.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true
        }
        val cached = if (isTest) cachedTestLeaderboard?.value else cachedLeaderboard?.value
        return deduped("leaderboard", if (isTest) "test" else "credit", forceRefresh, isFresh, { cached }) {
            try {
                val rpcList = supabase.postgrest.rpc(
                    "get_leaderboard_v2",
                    LeaderboardParams(limit = 100, sort = if (isTest) "test" else "credit")
                ).decodeAs<List<LeaderboardUser>>()
                if (gen == sessionGeneration.get()) {
                    val entry = CacheEntry(rpcList, monotonicNow(), gen, mutVer, null, isPublic = true)
                    if (isTest) cachedTestLeaderboard = entry else cachedLeaderboard = entry
                }
                return@deduped rpcList
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMissingRpc(e, "get_leaderboard_v2")) throw e
            }
            val res = try {
                val profiles = supabase.from("profiles").select {
                    order("credits", Order.DESCENDING)
                    limit(100)
                }.decodeList<Profile>()

                if (profiles.isEmpty()) return@deduped emptyList()

                val userIds = profiles.map { it.id }
                val checkins = supabase.from("checkins").select {
                    filter { isIn("user_id", userIds) }
                }.decodeList<Checkin>()

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

                val ranked = if (isTest) mapped.filter { it.checkinCount > 0 } else mapped

                ranked.mapIndexed { index, user ->
                    user.copy(rank = index + 1)
                }
            } catch (e: CancellationException) {
                throw e
            }
            if (gen == sessionGeneration.get()) {
                val entry = CacheEntry(res, monotonicNow(), gen, mutVer, null, isPublic = true)
                if (isTest) cachedTestLeaderboard = entry else cachedLeaderboard = entry
            }
            res
        }
    }

    suspend fun getMyRank(sort: String = "credit", forceRefresh: Boolean = false): MyRank? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("leaderboard")
        val isFresh = { myRankCache[sort]?.isFresh(CACHE_TTL_MS, gen, mutVer, uid, nowMs = monotonicNow()) == true }
        return deduped("leaderboard", "myRank:$sort", forceRefresh, isFresh, { myRankCache[sort]?.value }) {
            try {
                val rpcResult = supabase.postgrest.rpc("get_my_rank_v1", MyRankParams(sort)).decodeAs<MyRank>()
                if (rpcResult.rank > 0) {
                    if (gen == sessionGeneration.get() && uid == supabase.auth.currentUserOrNull()?.id) {
                        myRankCache[sort] = CacheEntry(rpcResult, monotonicNow(), gen, mutVer, uid)
                    }
                    return@deduped rpcResult
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMissingRpc(e, "get_my_rank_v1")) throw e
            }

            val fromList = try {
                getLeaderboard(sort, forceRefresh).firstOrNull { it.userId == uid }
            } catch (_: Exception) {
                null
            }
            if (fromList != null && fromList.rank > 0) {
                val rankObj = MyRank(
                    rank = fromList.rank,
                    credits = fromList.credits,
                    checkinCount = fromList.checkinCount,
                    campaignsCount = fromList.campaignsCount,
                )
                if (gen == sessionGeneration.get() && uid == supabase.auth.currentUserOrNull()?.id) {
                    myRankCache[sort] = CacheEntry(rankObj, monotonicNow(), gen, mutVer, uid)
                }
                return@deduped rankObj
            }

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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (approx != null && approx.rank > 0 && gen == sessionGeneration.get() && uid == supabase.auth.currentUserOrNull()?.id) {
                myRankCache[sort] = CacheEntry(approx, monotonicNow(), gen, mutVer, uid)
            }
            approx
        }
    }

    suspend fun getCampaignOwnerStats(campaignId: String, forceRefresh: Boolean = false): CampaignOwnerStats {
        val uid = supabase.auth.currentUserOrNull()?.id
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("stats")
        val isFresh = { !forceRefresh && ownerStatsCache[campaignId]?.isFresh(CACHE_TTL_MS, gen, mutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        if (!forceRefresh) {
            val cached = ownerStatsCache[campaignId]
            if (cached != null && cached.isFresh(CACHE_TTL_MS, gen, mutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow())) {
                return cached.value
            }
        }
        return deduped("stats", "ownerStats:$campaignId", forceRefresh, isFresh, { ownerStatsCache[campaignId]?.value }) {
            val members = getMembers(campaignId, forceRefresh)
            val today = requestDate.toString()
            val checkins = getCheckins(campaignId, forceRefresh)
            val feedbacks = getFeedbacks(campaignId, forceRefresh)
            val avgRating = if (feedbacks.isNotEmpty()) {
                feedbacks.map { it.rating }.average().toFloat()
            } else null

            val reports = getCampaignReports(campaignId, canManage = true, forceRefresh = forceRefresh)
            val todayCheckins = (
                checkins.filter { it.checkinDate == today }.map { it.userId } +
                reports.filter { it.isCreatedToday() && !it.isRejected }.map { it.userId }
            ).distinct().size
            val pendingCount = reports.count { it.status == "pending" }
            val totalReports = reports.size

            val stats = CampaignOwnerStats(
                installedTestersCount = members.size,
                testedTodayCount = todayCheckins,
                totalReportsCount = totalReports,
                pendingReportsCount = pendingCount,
                averageRating = avgRating,
            )
            if (gen == sessionGeneration.get() && mutVer == getMutationVersion("stats") && uid == supabase.auth.currentUserOrNull()?.id) {
                ownerStatsCache[campaignId] = CacheEntry(stats, monotonicNow(), gen, mutVer, uid, businessDate = requestDate)
                evictOldEntries(ownerStatsCache)
            }
            stats
        }
    }

    suspend fun getPendingReportsCountForOwner(forceRefresh: Boolean = false): Int {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return 0
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("reports")
        val isFresh = { cachedPendingReports?.isFresh(CACHE_TTL_MS, gen, mutVer, uid, nowMs = monotonicNow()) == true }
        return deduped("reports", "pendingReports", forceRefresh, isFresh, { cachedPendingReports?.value }) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val existing = cachedPendingReports?.takeIf {
                    it.sessionGeneration == gen && it.scopeUid == uid && it.mutationVersion == mutVer
                }?.value
                if (existing != null) return@deduped existing
                throw e
            }
            if (gen == sessionGeneration.get() && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedPendingReports = CacheEntry(count, monotonicNow(), gen, mutVer, uid)
            }
            count
        }
    }
    suspend fun getCampaignTesters(campaignId: String, forceRefresh: Boolean = false): List<TesterItem> {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("membership")
        if (!forceRefresh) {
            val cached = testersCache[campaignId]
            if (cached != null && cached.isFresh(CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow())) {
                return cached.value
            }
        }
        val members = getMembers(campaignId, forceRefresh)
        if (members.isEmpty()) return emptyList()
        val userIds = members.map { it.userId }
        val profilesMap = getProfiles(userIds, forceRefresh)
        val checkins = getCheckins(campaignId, forceRefresh).filter { it.userId in userIds }
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
        if (gen == sessionGeneration.get() && mutVer == getMutationVersion("membership")) {
            testersCache[campaignId] = CacheEntry(result, monotonicNow(), gen, mutVer, null)
            evictOldEntries(testersCache)
        }
        return result
    }

    suspend fun getCampaignReports(campaignId: String, canManage: Boolean = false, forceRefresh: Boolean = false): List<TesterReport> {
        val uid = supabase.auth.currentUserOrNull()?.id
        val cacheKey = "${campaignId}_$canManage"
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("reports")
        val scope = uid
        val isFresh = { reportsCache[cacheKey]?.isFresh(CACHE_TTL_MS, gen, mutVer, scope, nowMs = monotonicNow()) == true }
        return deduped("reports", cacheKey, forceRefresh, isFresh, { reportsCache[cacheKey]?.value }) {
            val now = monotonicNow()
            if (!canManage) {
                maybeAutoAcceptExpiredReports()
            }

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
                    if (rpcError is CancellationException) throw rpcError
                    if (!isMissingRpc(rpcError, "get_campaign_reports_for_owner")) throw rpcError
                    fetchReportsDirect(campaignId)
                }
                if (gen == sessionGeneration.get() && mutVer == getMutationVersion("reports") && uid == supabase.auth.currentUserOrNull()?.id) {
                    reportsCache[cacheKey] = CacheEntry(rpcResult, now, gen, mutVer, scope)
                }
                return@deduped rpcResult
            }

            val finalResult = fetchReportsDirect(campaignId)
            if (gen == sessionGeneration.get() && mutVer == getMutationVersion("reports") && uid == supabase.auth.currentUserOrNull()?.id) {
                reportsCache[cacheKey] = CacheEntry(finalResult, now, gen, mutVer, scope)
            }
            finalResult
        }
    }

    /** Truy váº¥n tháº³ng báº£ng `tester_reports` (theo RLS) + gáº¯n profile tester. */
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
        val result = supabase.postgrest.rpc(
            function = "approve_tester_report",
            parameters = ApproveReportParams(reportId = reportId, bonus = bonusCredits)
        ).decodeAs<ApproveReportResult>()
        if (result.success) {
            val uid = supabase.auth.currentUserOrNull()?.id
            invalidateReportsAndStats(report.campaignId)
            invalidateProfile(uid)
            invalidateProfile(report.userId)
            cachedCreditLogs = null
        }
        return result
    }

    suspend fun rejectReport(report: TesterReport, reason: String? = null): RejectReportResult {
        val reportId = report.id ?: return RejectReportResult(false)
        val result = supabase.postgrest.rpc(
            function = "reject_tester_report",
            parameters = RejectReportParams(reportId = reportId, reason = reason)
        ).decodeAs<RejectReportResult>()
        if (result.success) {
            val uid = supabase.auth.currentUserOrNull()?.id
            invalidateReportsAndStats(report.campaignId)
            invalidateProfile(uid)
            invalidateProfile(report.userId)
            cachedCreditLogs = null
        }
        return result
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
        val uid = supabase.auth.currentUserOrNull()?.id
        invalidateProfile(uid)
        cachedCreditLogs = null
        return result
    }

    suspend fun getUnreadWarnings(forceRefresh: Boolean = false): List<UserWarning> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("notifications")
        val isFresh = { cachedUnreadWarnings?.isFresh(SHORT_CACHE_TTL_MS, gen, mutVer, uid, nowMs = monotonicNow()) == true }
        return deduped("notifications", "unreadWarnings", forceRefresh, isFresh, { cachedUnreadWarnings?.value }) {
            val result = try {
                supabase.from("user_warnings").select {
                    filter {
                        eq("user_id", uid)
                        eq("acknowledged", false)
                    }
                    order("created_at", Order.DESCENDING)
                }.decodeList<UserWarning>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val existing = cachedUnreadWarnings?.takeIf {
                    it.sessionGeneration == gen && it.scopeUid == uid && it.mutationVersion == mutVer
                }?.value
                if (existing != null) return@deduped existing
                throw e
            }
            if (gen == sessionGeneration.get() && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedUnreadWarnings = CacheEntry(result, monotonicNow(), gen, mutVer, uid)
            }
            result
        }
    }

    suspend fun acknowledgeWarning(warningId: String) {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return
        supabase.from("user_warnings").update(buildJsonObject { put("acknowledged", true) }) {
            filter {
                eq("id", warningId)
                eq("user_id", uid)
            }
        }
        bumpMutationVersion("notifications")
        cachedUnreadWarnings = null
        invalidateDashboardCache()
    }

    suspend fun getWeeklyWarningsCount(forceRefresh: Boolean = false): Int {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return 0
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("notifications")
        val isFresh = { cachedWeeklyWarnings?.isFresh(SHORT_CACHE_TTL_MS, gen, mutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        return deduped("notifications", "weeklyWarnings", forceRefresh, isFresh, { cachedWeeklyWarnings?.value }) {
            val result = try {
                val sevenDaysAgo = requestDate.minusDays(6).toString()
                supabase.from("user_warnings").select {
                    filter {
                        eq("user_id", uid)
                        gte("warning_date", sevenDaysAgo)
                    }
                }.decodeList<UserWarning>().size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val existing = cachedWeeklyWarnings?.takeIf {
                    it.sessionGeneration == gen && it.scopeUid == uid && it.mutationVersion == mutVer && it.businessDate == requestDate
                }?.value
                if (existing != null) return@deduped existing
                throw e
            }
            if (gen == sessionGeneration.get() && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedWeeklyWarnings = CacheEntry(result, monotonicNow(), gen, mutVer, uid, businessDate = requestDate)
            }
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
        invalidateCampaign(campaignId)
        val uid = supabase.auth.currentUserOrNull()?.id
        invalidateProfile(uid)
        cachedCreditLogs = null
        return result
    }

    /**
     * Mua lượt Đẩy app lên đầu danh sách kiểm thử (RPC push_campaign, migration 052).
     * Trừ đúng 100 Credit và cập nhật thứ tự ưu tiên trong cùng 1 transaction nguyên tử.
     * Chặn race condition đổi tài khoản bằng expectedUid và expectedGeneration.
     */
    suspend fun pushCampaign(campaignId: String, clientRequestId: String): PushCampaignResult {
        val expectedUid = supabase.auth.currentUserOrNull()?.id
            ?: throw IllegalStateException("Not authenticated")
        val expectedGeneration = SessionCacheManager.currentGeneration()

        val result = supabase.postgrest.rpc(
            function = "push_campaign",
            parameters = buildJsonObject {
                put("p_campaign_id", campaignId)
                put("p_client_request_id", clientRequestId)
            }
        ).decodeAs<PushCampaignResult>()

        val currentUid = supabase.auth.currentUserOrNull()?.id
        val currentGeneration = SessionCacheManager.currentGeneration()

        // R05: Nếu tài khoản hoặc session đã thay đổi trong khi chờ RPC, tuyệt đối không
        // ghi số dư hay metadata vào snapshot/cache của tài khoản mới.
        if (currentUid == expectedUid && currentGeneration == expectedGeneration) {
            invalidateProfile(expectedUid)
            cachedCreditLogs = null
            invalidateCampaign(campaignId)
            bumpMutationVersion("campaigns")
            bumpMutationVersion("profile")
            bumpMutationVersion("credit_logs")
            bumpMutationVersion("dashboard")

            // Đồng bộ in-memory snapshot có guard tài khoản và generation
            SessionCacheManager.updateCachedData(
                expectedUserId = expectedUid,
                expectedGeneration = expectedGeneration
            ) { cached ->
                val updatedProfile = cached.userProfile?.copy(credits = result.balanceAfter)
                val updatedDisplay = cached.displayItems.map { d ->
                    if (d.campaign.id == campaignId) {
                        d.copy(
                            campaign = d.campaign.copy(
                                pushBusinessDate = result.businessDate,
                                pushExpiresAt = result.expiresAt,
                                pushOrder = result.pushOrder
                            )
                        )
                    } else d
                }
                val reSorted = com.ntt.crosstest.ui.viewmodels.DailyHomeOrder.sortOpenCampaigns(
                    updatedDisplay,
                    cached.dateVn ?: VnTime.today().toString()
                )
                cached.copy(
                    userProfile = updatedProfile,
                    userCredits = result.balanceAfter,
                    displayItems = reSorted
                )
            }
        }

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
        invalidateCampaign(campaignId)
        return result
    }

    suspend fun adminPauseCampaign(campaignId: String, reason: String? = null): AdminCampaignControlResult {
        val result = supabase.postgrest.rpc(
            "admin_pause_campaign",
            AdminPauseCampaignParams(campaignId = campaignId, reason = reason?.trim()?.ifBlank { null })
        ).decodeAs<AdminCampaignControlResult>()
        invalidateCampaign(campaignId)
        return result
    }

    suspend fun adminResumeCampaign(campaignId: String): AdminCampaignControlResult {
        val result = supabase.postgrest.rpc(
            "admin_resume_campaign",
            CampaignIdParams(campaignId = campaignId)
        ).decodeAs<AdminCampaignControlResult>()
        invalidateCampaign(campaignId)
        return result
    }

    private fun invalidateAfterAdminCampaignControl(campaignId: String) {
        invalidateCampaign(campaignId)
        invalidateReportsAndStats(campaignId)
    }

    suspend fun deleteCampaign(campaignId: String) {
        val myProfile = getMyProfile()
        if (myProfile?.isAdmin == true) {
            try {
                adminDeleteCampaign(campaignId)
                return
            } catch (_: Exception) {
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
        invalidateCampaign(campaignId)
    }
    suspend fun getCampaignsStatsMap(campaignIds: List<String>, forceRefresh: Boolean = false): Map<String, CampaignStats> {
        if (campaignIds.isEmpty()) return emptyMap()
        val currentUid = supabase.auth.currentUserOrNull()?.id
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("stats")
        val isFresh = {
            campaignIds.all { id -> statsCache[id]?.isFresh(CACHE_TTL_MS, gen, mutVer, currentUid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        }
        val distinctSorted = campaignIds.distinct().sorted()
        val flightKey = "statsMap:" + distinctSorted.joinToString(",")
        return deduped(
            "stats",
            flightKey,
            forceRefresh,
            isFresh,
            { campaignIds.mapNotNull { id -> statsCache[id]?.let { id to it.value } }.toMap() },
        ) {
            fun cacheAll(stats: Map<String, CampaignStats>) {
                val at = monotonicNow()
                if (gen == sessionGeneration.get() && currentUid == supabase.auth.currentUserOrNull()?.id) {
                    for ((statId, stat) in stats) {
                        if (stat.statsDate == null || stat.statsDate == requestDate.toString()) {
                            statsCache[statId] = CacheEntry(stat, at, gen, mutVer, currentUid, businessDate = requestDate)
                        }
                    }
                    evictOldEntries(statsCache)
                }
            }
            try {
                val rpcStats = supabase.postgrest.rpc(
                    "get_campaigns_stats",
                    CampaignIdsParams(campaignIds = distinctSorted)
                ).decodeAs<Map<String, CampaignStats>>()
                cacheAll(rpcStats)
                return@deduped rpcStats.filterKeys { it in campaignIds }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMissingRpc(e, "get_campaigns_stats")) throw e
            }
            val uid = currentUid
            val today = requestDate.toString()

            val members = supabase.from("campaign_members").select {
                filter { isIn("campaign_id", campaignIds) }
            }.decodeList<CampaignMember>()
            val membersMap = members.groupBy { it.campaignId }.mapValues { it.value.size }

            val todayCheckins = supabase.from("checkins").select {
                filter {
                    isIn("campaign_id", campaignIds)
                    eq("checkin_date", today)
                }
            }.decodeList<Checkin>()

            val todayCountMap = todayCheckins.groupBy { it.campaignId }.mapValues { it.value.size }
            val userCheckedInIds = if (uid != null) {
                todayCheckins.filter { it.userId == uid }.map { it.campaignId }.toSet()
            } else emptySet()

            val userSubmittedTodayCampIds = if (uid != null) {
                val reports = supabase.from("tester_reports").select {
                    filter {
                        isIn("campaign_id", campaignIds)
                        eq("user_id", uid)
                    }
                    order("created_at", Order.DESCENDING)
                }.decodeList<TesterReport>()
                reports.filter { it.isCreatedToday() && !it.isRejected }.map { it.campaignId }.toSet()
            } else emptySet()

            val testedTodayIds = userCheckedInIds + userSubmittedTodayCampIds

            val resultMap = campaignIds.associateWith { id ->
                CampaignStats(
                    installedCount = membersMap[id] ?: 0,
                    todayTestedCount = todayCountMap[id] ?: 0,
                    hasTestedToday = testedTodayIds.contains(id),
                    homeTestedCount = todayCountMap[id] ?: 0,
                    statsDate = today,
                )
            }
            cacheAll(resultMap)
            resultMap.filterKeys { it in campaignIds }
        }
    }

    suspend fun getHomeFeed(forceRefresh: Boolean = false): HomeFeedResult? {
        val currentUid = supabase.auth.currentUserOrNull()?.id
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val dashboardMutVer = getMutationVersion("dashboard")
        val campMutVer = getMutationVersion("campaigns")
        val statsMutVer = getMutationVersion("stats")
        val isFresh = { cachedHomeFeed?.isFresh(CACHE_TTL_MS, gen, dashboardMutVer, currentUid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        return deduped("dashboard", "homeFeed", forceRefresh, isFresh, { cachedHomeFeed?.value }) {
            val result = try {
                supabase.postgrest.rpc("get_home_feed").decodeAs<HomeFeedResult>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMissingRpc(e, "get_home_feed")) throw e
                return@deduped null
            }
            val now = monotonicNow()
            val hasStaleStats = result.stats.values.any { it.statsDate != null && it.statsDate != requestDate.toString() }
            if (!hasStaleStats && gen == sessionGeneration.get() && dashboardMutVer == getMutationVersion("dashboard") && currentUid == supabase.auth.currentUserOrNull()?.id) {
                cachedHomeFeed = CacheEntry(result, now, gen, dashboardMutVer, currentUid, businessDate = requestDate)
            }
            if (gen == sessionGeneration.get() && campMutVer == getMutationVersion("campaigns")) {
                cachedOpenCampaigns = CacheEntry(result.open, now, gen, campMutVer, null, isPublic = true)
                cachedPausedCampaigns = CacheEntry(result.paused, now, gen, campMutVer, null, isPublic = true)
                cachedCompletedCampaigns = CacheEntry(result.completed, now, gen, campMutVer, null, isPublic = true)
            }
            if (!hasStaleStats && gen == sessionGeneration.get() && statsMutVer == getMutationVersion("stats") && currentUid == supabase.auth.currentUserOrNull()?.id) {
                if (result.stats.isNotEmpty()) {
                    for ((statId, stat) in result.stats) {
                        statsCache[statId] = CacheEntry(stat, now, gen, statsMutVer, currentUid, businessDate = requestDate)
                    }
                    evictOldEntries(statsCache)
                }
            }
            result
        }
    }

    suspend fun getMyDashboard(forceRefresh: Boolean = false): MyDashboardResult? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val dashboardMutVer = getMutationVersion("dashboard")
        val campMutVer = getMutationVersion("campaigns")
        val profMutVer = getMutationVersion("profile")
        val isFresh = { cachedDashboard?.isFresh(SHORT_CACHE_TTL_MS, gen, dashboardMutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        return deduped("dashboard", "myDashboard", forceRefresh, isFresh, { cachedDashboard?.value }) {
            val result = try {
                supabase.postgrest.rpc("get_my_dashboard").decodeAs<MyDashboardResult>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMissingRpc(e, "get_my_dashboard")) throw e
                return@deduped null
            }
            val now = monotonicNow()
            if (gen == sessionGeneration.get() && dashboardMutVer == getMutationVersion("dashboard") && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedDashboard = CacheEntry(result, now, gen, dashboardMutVer, uid, businessDate = requestDate)
            }
            if (gen == sessionGeneration.get() && campMutVer == getMutationVersion("campaigns") && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedMyCampaigns = CacheEntry(result.myCampaigns, now, gen, campMutVer, uid)
                cachedMyJoinedCampaigns = CacheEntry(result.joinedCampaigns, now, gen, campMutVer, uid)
            }
            if (gen == sessionGeneration.get() && profMutVer == getMutationVersion("profile") && uid == supabase.auth.currentUserOrNull()?.id) {
                result.profile?.let {
                    cachedMyProfile = CacheEntry(it, now, gen, profMutVer, uid)
                    profileCache[uid] = CacheEntry(it, now, gen, profMutVer, null)
                }
            }
            result
        }
    }
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
        val requestDate = businessDay()
        val cacheKey = "${campaignId}|${uid}"
        val gen = sessionGeneration.get()
        val campMutVer = getMutationVersion("campaigns")
        val reportsMutVer = getMutationVersion("reports")
        val profMutVer = getMutationVersion("profile")
        val isFresh = { detailCache[cacheKey]?.isFresh(CACHE_TTL_MS, gen, campMutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        return deduped("campaigns", "detail:$cacheKey", forceRefresh, isFresh, { detailCache[cacheKey]?.value }) {
            val now = monotonicNow()
            val result = try {
                supabase.postgrest.rpc("get_campaign_detail", CampaignIdParams(campaignId = campaignId))
                    .decodeAs<CampaignDetailResult>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMissingRpc(e, "get_campaign_detail")) throw e
                return@deduped null
            }
            if (gen == sessionGeneration.get() && campMutVer == getMutationVersion("campaigns") && uid == supabase.auth.currentUserOrNull()?.id) {
                detailCache[cacheKey] = CacheEntry(result, now, gen, campMutVer, uid, businessDate = requestDate)
                result.campaign.id?.let { campaignCache[it] = CacheEntry(result.campaign, now, gen, campMutVer, null, businessDate = requestDate) }
                evictOldEntries(detailCache)
                evictOldEntries(campaignCache)
            }
            if (gen == sessionGeneration.get() && reportsMutVer == getMutationVersion("reports")) {
                feedbacksCache[campaignId] = CacheEntry(result.feedbacks, now, gen, reportsMutVer, null)
                val reportsWithProfiles = result.reports.map { report ->
                    report.copy(testerProfile = result.profiles[report.userId])
                }
                val reportsScope = uid
                val reportsKey = "${campaignId}_${result.canManage}"
                reportsCache[reportsKey] = CacheEntry(reportsWithProfiles, now, gen, reportsMutVer, reportsScope)
                evictOldEntries(feedbacksCache)
                evictOldEntries(reportsCache)
            }
            if (gen == sessionGeneration.get() && profMutVer == getMutationVersion("profile")) {
                for ((profileId, profile) in result.profiles) {
                    profileCache[profileId] = CacheEntry(profile, now, gen, profMutVer, null)
                }
                evictOldEntries(profileCache)
                result.myProfile?.let {
                    if (uid == supabase.auth.currentUserOrNull()?.id) {
                        cachedMyProfile = CacheEntry(it, now, gen, profMutVer, uid)
                    }
                }
            }
            result
        }
    }

    // ---------------------------------------------------------------- slots

    suspend fun getUserSlots(forceRefresh: Boolean = false): List<UserSlot> {
        val user = supabase.auth.currentUserOrNull() ?: return emptyList()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("user_slots")
        val isFresh = { cachedUserSlots?.isFresh(CACHE_TTL_MS, gen, mutVer, user.id, nowMs = monotonicNow()) == true }
        return deduped("user_slots", "userSlots", forceRefresh, isFresh, { cachedUserSlots?.value }) {
            val result = try {
                supabase.from("user_slots").select {
                    filter { eq("user_id", user.id) }
                    order("slot_number", Order.ASCENDING)
                }.decodeList<UserSlot>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val existing = cachedUserSlots?.takeIf {
                    it.sessionGeneration == gen && it.scopeUid == user.id && it.mutationVersion == mutVer
                }?.value
                if (existing != null) return@deduped existing
                throw e
            }
            if (gen == sessionGeneration.get() && user.id == supabase.auth.currentUserOrNull()?.id) {
                cachedUserSlots = CacheEntry(result, monotonicNow(), gen, mutVer, user.id)
            }
            result
        }
    }

    suspend fun unlockOrRenewSlot(slotNumber: Int): UnlockSlotResult {
        val result = supabase.postgrest.rpc(
            "unlock_or_renew_slot",
            UnlockSlotParams(slotNumber)
        ).decodeAs<UnlockSlotResult>()
        val uid = supabase.auth.currentUserOrNull()?.id
        invalidateProfile(uid)
        bumpMutationVersion("user_slots")
        cachedSlotsInfo = null
        cachedUserSlots = null
        cachedAllProfiles = null
        invalidateDashboardCache()
        return result
    }

    suspend fun getMySlotsInfo(forceRefresh: Boolean = false): List<SlotInfo> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("user_slots")
        val isFresh = { cachedSlotsInfo?.isFresh(CACHE_TTL_MS, gen, mutVer, uid, nowMs = monotonicNow()) == true }
        return deduped("user_slots", "slotsInfo", forceRefresh, isFresh, { cachedSlotsInfo?.value }) {
            val userSlots = getUserSlots(forceRefresh = forceRefresh)
            val myCamps = myCampaigns(forceRefresh = forceRefresh)
            val activeCamps = myCamps.filter { it.isOpen || it.isPaused }
            val result = (1..6).map { slotNum ->
                if (slotNum == 1) {
                    val camp = activeCamps.find { it.slotNumber == 1 }
                    SlotInfo(slotNumber = 1, isPermanent = true, isUnlocked = true, currentCampaign = camp)
                } else {
                    val userSlot = userSlots.find { it.slotNumber == slotNum }
                    val isUnlocked = userSlot != null
                    val isExpired = userSlot?.isExpired ?: true
                    val camp = if (isUnlocked && !isExpired) activeCamps.find { it.slotNumber == slotNum } else null
                    SlotInfo(
                        slotNumber = slotNum,
                        isPermanent = false,
                        isUnlocked = isUnlocked,
                        cost = SlotInfo.costForSlot(slotNum),
                        expiresAt = userSlot?.expiresAt,
                        remainingDays = userSlot?.remainingDays ?: 0,
                        currentCampaign = camp,
                    )
                }
            }
            if (gen == sessionGeneration.get() && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedSlotsInfo = CacheEntry(result, monotonicNow(), gen, mutVer, uid)
            }
            result
        }
    }

    // ---------------------------------------------------------------- admin operations

    suspend fun getAllProfiles(forceRefresh: Boolean = false): List<Profile> {
        val uid = supabase.auth.currentUserOrNull()?.id
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("profile")
        val isFresh = { cachedAllProfiles?.isFresh(CACHE_TTL_MS, gen, mutVer, uid, nowMs = monotonicNow()) == true }
        return deduped("profile", "allProfiles", forceRefresh, isFresh, { cachedAllProfiles?.value }) {
            val list = supabase.from("profiles").select {
                order("created_at", Order.DESCENDING)
                limit(500)
            }.decodeList<Profile>()
            if (gen == sessionGeneration.get() && mutVer == getMutationVersion("profile") && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedAllProfiles = CacheEntry(list, monotonicNow(), gen, mutVer, uid)
            }
            list
        }
    }

    suspend fun adminSetUserLock(
        targetUserId: String,
        locked: Boolean,
        reason: String? = null
    ): AdminSetUserLockResult {
        val result = supabase.postgrest.rpc(
            "admin_set_user_lock",
            AdminSetUserLockParams(
                targetUserId = targetUserId,
                locked = locked,
                reason = reason,
            )
        ).decodeAs<AdminSetUserLockResult>()
        bumpMutationVersion("profile")
        cachedAllProfiles = null
        invalidateProfile(targetUserId)
        return result
    }

    suspend fun adminGetHomeStatsConfig(): HomeStatsPolicyConfig {
        return supabase.postgrest.rpc("admin_get_home_stats_config").decodeAs<HomeStatsPolicyConfig>()
    }

    suspend fun adminSetHomeStatsCycle(cycleDays: Int, expectedRevision: String? = null): HomeStatsPolicyConfig {
        return supabase.postgrest.rpc(
            "admin_set_home_stats_cycle",
            AdminSetHomeStatsCycleParams(cycleDays = cycleDays, expectedRevision = expectedRevision)
        ).decodeAs<HomeStatsPolicyConfig>()
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
        bumpMutationVersion("profile")
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
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("topup")
        val isFresh = { cachedTopupPackages?.isFresh(PACKAGES_CACHE_TTL_MS, gen, mutVer, null, nowMs = monotonicNow()) == true }
        return deduped("topup", "topupPackages", forceRefresh, isFresh, { cachedTopupPackages?.value }) {
            val result = try {
                val packages = supabase.from("topup_packages").select {
                    filter { eq("is_active", true) }
                    order("sort_order", Order.ASCENDING)
                }.decodeList<TopupPackage>()
                if (packages.isNotEmpty()) packages else com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES
            }
            if (gen == sessionGeneration.get()) {
                cachedTopupPackages = CacheEntry(result, monotonicNow(), gen, mutVer, null, isPublic = true)
            }
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
        val uid = supabase.auth.currentUserOrNull()?.id
        invalidateProfile(uid)
        cachedCreditLogs = null
        invalidateDashboardCache()
        return result
    }

    // ---------------------------------------------------------------- notifications

    suspend fun getMyNotifications(limit: Long = 50, forceRefresh: Boolean = false): List<UserNotification> {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("notifications")
        val isFresh = {
            cachedNotifications?.isFresh(SHORT_CACHE_TTL_MS, gen, mutVer, currentUid, requestedLimit = limit, nowMs = monotonicNow()) == true
        }
        val cachedSlice = { cachedNotifications?.value?.take(limit.toInt()) }
        return deduped("notifications", "notifications:$limit", forceRefresh, isFresh, cachedSlice) {
            val result = try {
                supabase.from("notifications").select {
                    filter { eq("user_id", currentUid) }
                    order("created_at", Order.DESCENDING)
                    limit(limit)
                }.decodeList<UserNotification>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val existing = cachedNotifications?.takeIf {
                    it.sessionGeneration == gen && it.scopeUid == currentUid && it.mutationVersion == getMutationVersion("notifications")
                }?.value?.take(limit.toInt())
                if (existing != null) return@deduped existing
                throw e
            }
            if (gen == sessionGeneration.get() && mutVer == getMutationVersion("notifications") && currentUid == supabase.auth.currentUserOrNull()?.id) {
                cachedNotifications = CacheEntry(result, monotonicNow(), gen, mutVer, currentUid, limit = limit)
            }
            result
        }.take(limit.toInt())
    }

    suspend fun getNotifications(limit: Long = 30, forceRefresh: Boolean = false): List<UserNotification> =
        getMyNotifications(limit = limit, forceRefresh = forceRefresh)

    /** Cáº­p nháº­t server trÆ°á»›c, cache sau. NÃ©m lá»—i khi máº¡ng tháº¥t báº¡i (caller tá»± hoÃ n tÃ¡c UI). */
    suspend fun markNotificationAsRead(id: String) {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
        supabase.from("notifications").update(buildJsonObject { put("is_read", true) }) {
            filter {
                eq("id", id)
                eq("user_id", currentUid)
            }
        }
        bumpMutationVersion("notifications")
        cachedNotifications = null
        invalidateDashboardCache()
    }

    /** Cáº­p nháº­t server trÆ°á»›c, cache sau. NÃ©m lá»—i khi máº¡ng tháº¥t báº¡i (caller tá»± hoÃ n tÃ¡c UI). */
    suspend fun markAllNotificationsAsRead() {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
        supabase.from("notifications").update(buildJsonObject { put("is_read", true) }) {
            filter {
                eq("user_id", currentUid)
                eq("is_read", false)
            }
        }
        bumpMutationVersion("notifications")
        cachedNotifications = null
        invalidateDashboardCache()
    }

    /** Cáº­p nháº­t server trÆ°á»›c, cache sau. NÃ©m lá»—i khi máº¡ng tháº¥t báº¡i (caller tá»± hoÃ n tÃ¡c UI). */
    suspend fun deleteNotification(id: String) {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
        supabase.from("notifications").delete {
            filter {
                eq("id", id)
                eq("user_id", currentUid)
            }
        }
        bumpMutationVersion("notifications")
        cachedNotifications = null
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
        val uid = supabase.auth.currentUserOrNull()?.id
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("quests")
        val isFresh = { cachedQuestSummary?.isFresh(CACHE_TTL_MS, gen, mutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        return deduped("quests", "questSummary", forceRefresh, isFresh, { cachedQuestSummary?.value }) {
            val summary = try {
                supabase.postgrest.rpc("get_user_quest_summary").decodeAs<QuestSummary>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isMissingRpc(e, "get_user_quest_summary")) throw e
                val today = requestDate.toString()
                val dailyCount = if (uid != null) {
                    supabase.from("checkins").select {
                        filter {
                            eq("user_id", uid)
                            eq("checkin_date", today)
                        }
                    }.decodeList<Checkin>().size
                } else 0
                QuestSummary(dailyCount = dailyCount)
            }
            if (gen == sessionGeneration.get() && uid == supabase.auth.currentUserOrNull()?.id) {
                cachedQuestSummary = CacheEntry(summary, monotonicNow(), gen, mutVer, uid, businessDate = requestDate)
            }
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
                val uid = supabase.auth.currentUserOrNull()?.id
                invalidateProfile(uid)
                bumpMutationVersion("quests")
                cachedQuestSummary = null
                cachedCreditLogs = null
                cachedNotifications = null
                invalidateDashboardCache()
            }
            result
        } catch (e: CancellationException) {
            throw e
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

    @Volatile
    private var serverTimeOffsetMs: Long? = null

    fun updateServerClock(serverNowIso: String?) {
        if (serverNowIso.isNullOrBlank()) return
        try {
            val serverEpochMs = java.time.Instant.parse(serverNowIso).toEpochMilli()
            val mono = monotonicNow()
            serverTimeOffsetMs = serverEpochMs - mono
        } catch (_: Exception) {}
    }

    fun estimatedServerEpochMs(): Long {
        val offset = serverTimeOffsetMs
        return if (offset != null) {
            monotonicNow() + offset
        } else {
            System.currentTimeMillis()
        }
    }

    fun isMissionActiveAndUnexpired(mission: Mission, nowEpochMs: Long = estimatedServerEpochMs()): Boolean {
        if (!mission.isActive && !mission.isPaused) return false
        val endsAt = mission.endsAt ?: return true
        return try {
            val endEpochMs = java.time.Instant.parse(endsAt).toEpochMilli()
            nowEpochMs < endEpochMs
        } catch (_: Exception) {
            true
        }
    }

    /** Danh sách nhiệm vụ cho tab "Nhiệm vụ App"; lọc bỏ nhiệm vụ quá hạn theo deadline. */
    suspend fun getOpenMissions(forceRefresh: Boolean = false): List<Mission> {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion("quests")
        val curUid = supabase.auth.currentUserOrNull()?.id
        val isFresh = {
            cachedOpenMissions?.isFresh(CACHE_TTL_MS, gen, mutVer, curUid, nowMs = monotonicNow()) == true &&
                cachedOpenMissions?.value?.all { isMissionActiveAndUnexpired(it) } == true
        }
        val cachedFiltered = {
            cachedOpenMissions?.value?.filter { isMissionActiveAndUnexpired(it) }
        }
        return deduped("quests", "openMissions", forceRefresh, isFresh, cachedFiltered) {
            val result = supabase.postgrest.rpc("get_open_missions").decodeAs<OpenMissionsResult>()
            updateServerClock(result.serverNow)
            val filtered = result.missions.filter { isMissionActiveAndUnexpired(it) }
            if (gen == sessionGeneration.get() &&
                curUid == supabase.auth.currentUserOrNull()?.id &&
                mutVer == getMutationVersion("quests")
            ) {
                cachedOpenMissions = CacheEntry(filtered, monotonicNow(), gen, mutVer, curUid)
            }
            filtered
        }
    }

    /** Lấy danh sách ID các campaign của người dùng hiện đang có mission đang hoạt động (migration 044). */
    suspend fun getMyActiveMissionCampaignIds(): List<String> {
        return try {
            val res = supabase.postgrest.rpc("get_my_active_mission_campaign_ids").decodeAs<List<String>>()
            res
        } catch (_: Exception) {
            // Fallback nếu chạy trên backend cũ chưa có RPC của migration 044
            getOpenMissions()
                .filter { it.isMine && (it.status == "open" || it.status == "full" || it.status == "paused_by_creator") }
                .mapNotNull { it.campaignId.takeIf(String::isNotBlank) }
                .distinct()
        }
    }

    /** Chá»§ app má»Ÿ nhiá»‡m vá»¥: server trá»« phÃ­ 50 Credit Ã— sá»‘ ngÃ y vÃ  kiá»ƒm tra sá»‘ dÆ° â‰¥ 300. */
    suspend fun createMission(params: CreateMissionParams): CreateMissionResult {
        val result = supabase.postgrest.rpc("create_mission", params).decodeAs<CreateMissionResult>()
        val uid = supabase.auth.currentUserOrNull()?.id
        invalidateProfile(uid)
        cachedCreditLogs = null
        bumpMutationVersion("quests")
        cachedOpenMissions = null
        invalidateDashboardCache()
        return result
    }

    /** NgÆ°á»i tham gia nháº­n nhiá»‡m vá»¥ (server kiá»ƒm tra cÃ²n chá»—, chÆ°a tham gia, cÃ²n háº¡n). */
    suspend fun joinMission(missionId: String): JoinMissionResult {
        val result = supabase.postgrest.rpc(
            "join_mission",
            JoinMissionParams(missionId = missionId)
        ).decodeAs<JoinMissionResult>()
        bumpMutationVersion("quests")
        cachedOpenMissions = null
        return result
    }

    /** Nhiá»‡m vá»¥ hiá»‡n táº¡i cá»§a 1 app cho mÃ n quáº£n lÃ½ cá»§a chá»§ nhiá»‡m vá»¥ (migration 040). */
    suspend fun getCampaignMission(campaignId: String): Mission? {
        val result = supabase.postgrest.rpc(
            "get_campaign_mission",
            CampaignIdParams(campaignId = campaignId)
        ).decodeAs<CampaignMissionResult>()
        return result.mission
    }

    /** Chá»§ nhiá»‡m vá»¥ dá»«ng háº³n nhiá»‡m vá»¥: khÃ´ng nháº­n ngÆ°á»i má»›i, khÃ´ng tráº£ thÆ°á»Ÿng trong lÃºc dá»«ng. */
    suspend fun pauseMission(missionId: String): MissionActionResult =
        missionAction("pause_mission", missionId)

    /** Má»Ÿ láº¡i nhiá»‡m vá»¥ Ä‘Ã£ dá»«ng; thá»i háº¡n gá»‘c giá»¯ nguyÃªn. */
    suspend fun resumeMission(missionId: String): MissionActionResult =
        missionAction("resume_mission", missionId)

    /** XÃ³a má»m nhiá»‡m vá»¥ (áº©n khá»i danh sÃ¡ch, khÃ´ng hoÃ n phÃ­). */
    suspend fun deleteMission(missionId: String): MissionActionResult =
        missionAction("delete_mission", missionId)

    private suspend fun missionAction(function: String, missionId: String): MissionActionResult {
        val result = supabase.postgrest.rpc(
            function,
            MissionIdParams(missionId = missionId)
        ).decodeAs<MissionActionResult>()
        bumpMutationVersion("quests")
        cachedOpenMissions = null
        return result
    }

    // =========================================================================
    // THƯỞNG CẬP NHẬT PHIÊN BẢN APP (APP UPDATE REWARD)
    // =========================================================================

    suspend fun publishAppUpdate(
        campaignId: String,
        versionName: String,
        targetVersionCode: Long,
        releaseNotes: String,
        clientOperationId: String? = null,
        expectedCurrentReleaseId: String? = null,
    ): Result<PublishAppUpdateResult> = runCatching {
        val params = PublishAppUpdateParams(
            campaignId = campaignId,
            versionName = versionName,
            targetVersionCode = targetVersionCode,
            releaseNotes = releaseNotes,
            clientOperationId = clientOperationId,
            expectedCurrentReleaseId = expectedCurrentReleaseId,
        )
        val result = supabase.postgrest.rpc("publish_app_update", params).decodeAs<PublishAppUpdateResult>()
        val uid = supabase.auth.currentUserOrNull()?.id
        invalidateProfile(uid)
        cachedCreditLogs = null
        invalidateCampaign(campaignId)
        result
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        throw e
    }

    suspend fun claimAppUpdateReward(
        releaseId: String,
        observedVersionCode: Long,
        campaignId: String? = null,
    ): Result<ClaimAppUpdateResult> = runCatching {
        val params = ClaimAppUpdateParams(
            releaseId = releaseId,
            observedVersionCode = observedVersionCode,
        )
        val result = supabase.postgrest.rpc("claim_app_update_reward", params).decodeAs<ClaimAppUpdateResult>()
        if (result.success) {
            val uid = supabase.auth.currentUserOrNull()?.id
            invalidateProfile(uid)
            cachedCreditLogs = null
            detailCache.entries.removeIf { it.value.value.appUpdate?.id == releaseId }
            campaignId?.let { invalidateDetailCache(it) }
        }
        result
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        throw e
    }

    suspend fun closeAppUpdateRelease(
        releaseId: String,
        reason: String = "manual_close",
        campaignId: String? = null,
    ): Result<CloseAppUpdateReleaseResult> = runCatching {
        val params = CloseAppUpdateReleaseParams(
            releaseId = releaseId,
            reason = reason,
        )
        val result = supabase.postgrest.rpc("close_app_update_release", params).decodeAs<CloseAppUpdateReleaseResult>()
        if (result.success) {
            val uid = supabase.auth.currentUserOrNull()?.id
            invalidateProfile(uid)
            cachedCreditLogs = null
            detailCache.entries.removeIf { it.value.value.appUpdate?.id == releaseId }
            campaignId?.let { invalidateDetailCache(it) }
        }
        result
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        throw e
    }

    suspend fun getCampaignUpdateHistory(campaignId: String): Result<List<AppUpdateRelease>> = runCatching {
        val params = CampaignIdParams(campaignId = campaignId)
        supabase.postgrest.rpc("get_campaign_update_history", params).decodeAs<List<AppUpdateRelease>>()
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        throw e
    }

}