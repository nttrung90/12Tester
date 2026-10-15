package com.ntt.crosstest.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import java.time.LocalDate

/**
 * All database access for campaigns, members, check-ins and feedback.
 * Row Level Security on the database guarantees users can only write their own rows;
 * every method also scopes writes to the current user id.
 */
class CampaignRepository(private val supabase: SupabaseClient) {

    companion object {
        const val CACHE_TTL_MS = 3 * 60 * 1000L // 3 minutes cache TTL
    }

    private var cachedOpenCampaigns: List<Campaign>? = null
    private var openCampaignsTimestamp: Long = 0L

    private var cachedStatsMap: Map<String, CampaignStats>? = null
    private var statsMapTimestamp: Long = 0L

    private val profileCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Profile, Long>>()

    private var cachedMyCampaigns: List<Campaign>? = null
    private var myCampaignsTimestamp: Long = 0L

    private var cachedMyJoinedCampaigns: List<Campaign>? = null
    private var myJoinedCampaignsTimestamp: Long = 0L

    private var cachedLeaderboard: List<LeaderboardUser>? = null
    private var leaderboardTimestamp: Long = 0L

    private var cachedMyProfile: Profile? = null
    private var myProfileTimestamp: Long = 0L

    private var cachedTodayCheckins: Int? = null
    private var todayCheckinsTimestamp: Long = 0L

    private var cachedPendingReports: Int? = null
    private var pendingReportsTimestamp: Long = 0L

    private var cachedQuestSummary: QuestSummary? = null
    private var questSummaryTimestamp: Long = 0L

    private val ownerStatsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<CampaignOwnerStats, Long>>()
    private val campaignCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Campaign, Long>>()
    private var cachedSlotsInfo: List<SlotInfo>? = null
    private var slotsInfoTimestamp: Long = 0L
    private val reportsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<TesterReport>, Long>>()
    private val testersCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<TesterItem>, Long>>()
    private var cachedAllProfiles: List<Profile>? = null
    private var allProfilesTimestamp: Long = 0L

    fun clearMemoryCache() {
        cachedOpenCampaigns = null
        openCampaignsTimestamp = 0L
        cachedStatsMap = null
        statsMapTimestamp = 0L
        profileCache.clear()
        cachedMyCampaigns = null
        myCampaignsTimestamp = 0L
        cachedMyJoinedCampaigns = null
        myJoinedCampaignsTimestamp = 0L
        cachedLeaderboard = null
        leaderboardTimestamp = 0L
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
        reportsCache.clear()
        testersCache.clear()
        cachedAllProfiles = null
        allProfilesTimestamp = 0L
    }

    private fun uid(): String =
        supabase.auth.currentUserOrNull()?.id
            ?: throw IllegalStateException("Bạn cần đăng nhập trước.")

    // ---------------------------------------------------------------- campaigns

    suspend fun listOpenCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        if (!forceRefresh && cachedOpenCampaigns != null && System.currentTimeMillis() - openCampaignsTimestamp < CACHE_TTL_MS) {
            return cachedOpenCampaigns!!
        }
        val result = supabase.from("campaigns").select {
            filter { eq("status", "open") }
            order("created_at", Order.DESCENDING)
        }.decodeList<Campaign>()
        cachedOpenCampaigns = result
        openCampaignsTimestamp = System.currentTimeMillis()
        return result
    }

    suspend fun searchCampaigns(query: String): List<Campaign> =
        supabase.from("campaigns").select {
            filter {
                eq("status", "open")
                ilike("app_name", "%$query%")
            }
            order("created_at", Order.DESCENDING)
        }.decodeList<Campaign>()

    suspend fun getCampaign(id: String, forceRefresh: Boolean = false): Campaign? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedOpenCampaigns?.find { it.id == id }?.let { return it }
            cachedMyCampaigns?.find { it.id == id }?.let { return it }
            cachedMyJoinedCampaigns?.find { it.id == id }?.let { return it }
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
     * Finds the next open campaign on the Home screen that the current user has NOT tested today,
     * excluding the current campaign and any apps owned by the user.
     */
    suspend fun getNextUntestedCampaign(currentCampaignId: String): Campaign? {
        val currentUid = supabase.auth.currentUserOrNull()?.id
        val openCamps = listOpenCampaigns(forceRefresh = true)
        val candidates = openCamps.filter { camp ->
            camp.id != null && camp.id != currentCampaignId && (currentUid == null || camp.ownerId != currentUid)
        }
        if (candidates.isEmpty()) return null

        val candidateIds = candidates.mapNotNull { it.id }
        val statsMap = getCampaignsStatsMap(candidateIds, forceRefresh = true)

        return candidates.firstOrNull { camp ->
            val stats = statsMap[camp.id]
            stats?.hasTestedToday != true
        }
    }

    suspend fun createCampaign(campaign: Campaign) {
        val myProfile = getMyProfile()
        if ((myProfile?.credits ?: 0) <= 10) {
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
    }

    /** Owner-only: update any field (used for closing a campaign). */
    suspend fun updateCampaign(campaign: Campaign) {
        val campId = requireNotNull(campaign.id) { "Campaign id is required" }
        supabase.from("campaigns").upsert(campaign.copy(ownerId = uid()))
        cachedOpenCampaigns = null
        cachedMyCampaigns = null
        cachedStatsMap = null
        ownerStatsCache.remove(campId)
    }

    suspend fun updateCampaignIcon(campaignId: String, iconUrl: String) {
        val user = supabase.auth.currentUserOrNull() ?: return
        val current = getCampaign(campaignId) ?: return
        if (current.ownerId == user.id) {
            updateCampaign(current.copy(iconUrl = iconUrl))
        }
    }

    suspend fun closeCampaign(campaignId: String) {
        val current = getCampaign(campaignId)
            ?: throw IllegalStateException("Không tìm thấy chiến dịch.")
        updateCampaign(current.copy(status = "closed"))
        cachedOpenCampaigns = null
        cachedMyCampaigns = null
        cachedStatsMap = null
        ownerStatsCache.remove(campaignId)
    }

    fun isAuthenticated(): Boolean = supabase.auth.currentUserOrNull() != null

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    suspend fun myCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val user = supabase.auth.currentUserOrNull() ?: return emptyList()
        if (!forceRefresh && cachedMyCampaigns != null && System.currentTimeMillis() - myCampaignsTimestamp < CACHE_TTL_MS) {
            return cachedMyCampaigns!!
        }
        val result = supabase.from("campaigns").select {
            filter { eq("owner_id", user.id) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Campaign>()
        cachedMyCampaigns = result
        myCampaignsTimestamp = System.currentTimeMillis()
        return result
    }

    // ---------------------------------------------------------------- members

    suspend fun joinCampaign(campaignId: String) {
        supabase.from("campaign_members")
            .insert(CampaignMember(campaignId = campaignId, userId = uid()))
        cachedMyJoinedCampaigns = null
        cachedStatsMap = null
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
        cachedStatsMap = null
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

    suspend fun getMembers(campaignId: String): List<CampaignMember> =
        supabase.from("campaign_members").select {
            filter { eq("campaign_id", campaignId) }
            order("joined_at", Order.ASCENDING)
        }.decodeList<CampaignMember>()

    suspend fun myJoinedCampaigns(forceRefresh: Boolean = false): List<Campaign> {
        val user = supabase.auth.currentUserOrNull() ?: return emptyList()
        if (!forceRefresh && cachedMyJoinedCampaigns != null && System.currentTimeMillis() - myJoinedCampaignsTimestamp < CACHE_TTL_MS) {
            return cachedMyJoinedCampaigns!!
        }
        val memberRows = supabase.from("campaign_members").select {
            filter { eq("user_id", user.id) }
        }.decodeList<CampaignMember>()
        val ids = memberRows.map { it.campaignId }.distinct()
        if (ids.isEmpty()) {
            cachedMyJoinedCampaigns = emptyList()
            myJoinedCampaignsTimestamp = System.currentTimeMillis()
            return emptyList()
        }
        val campaigns = supabase.from("campaigns").select {
            filter { isIn("id", ids) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Campaign>()
        val filtered = campaigns.filter { it.ownerId != user.id }
        cachedMyJoinedCampaigns = filtered
        myJoinedCampaignsTimestamp = System.currentTimeMillis()
        return filtered
    }

    // ---------------------------------------------------------------- check-ins

    suspend fun hasCheckedInToday(campaignId: String, userId: String? = null): Boolean {
        val uid = userId ?: supabase.auth.currentUserOrNull()?.id ?: return false
        val today = LocalDate.now().toString()
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
     * One check-in per user per day. The UNIQUE(campaign_id, user_id, checkin_date)
     * constraint is the final guard; we pre-check first for a friendly message.
     * @throws IllegalStateException with message "ALREADY_CHECKED_IN" when duplicated.
     */
    suspend fun checkinToday(campaignId: String, note: String? = null) {
        val userId = uid()
        if (hasCheckedInToday(campaignId, userId)) {
            throw IllegalStateException("ALREADY_CHECKED_IN")
        }
        try {
            supabase.from("checkins").insert(
                Checkin(
                    campaignId = campaignId,
                    userId = userId,
                    checkinDate = LocalDate.now().toString(),
                    note = note?.takeIf { it.isNotBlank() },
                )
            )
            cachedStatsMap = null
            cachedTodayCheckins = null
            cachedLeaderboard = null
            cachedMyProfile = null
            cachedQuestSummary = null
        } catch (e: Exception) {
            val msg = e.message.orEmpty().lowercase()
            if ("23505" in msg || "duplicate" in msg) {
                throw IllegalStateException("ALREADY_CHECKED_IN")
            }
            throw e
        }
    }

    suspend fun getCheckins(campaignId: String): List<Checkin> =
        supabase.from("checkins").select {
            filter { eq("campaign_id", campaignId) }
            order("checkin_date", Order.DESCENDING)
        }.decodeList<Checkin>()

    // ---------------------------------------------------------------- feedback

    /** One feedback row per user per campaign (upsert = update my own review). */
    suspend fun submitFeedback(
        campaignId: String,
        rating: Int,
        comment: String,
        screenshotUrl: String? = null,
    ) {
        val userId = uid()
        try {
            // Priority 1: Use atomic security definer RPC (Migration 012)
            supabase.postgrest.rpc(
                function = "submit_tester_report",
                parameters = SubmitTesterReportParams(
                    campaignId = campaignId,
                    content = comment.trim(),
                    screenshotUrl = screenshotUrl,
                    rating = rating.coerceIn(1, 5),
                )
            ).decodeAs<SubmitTesterReportResult>()
        } catch (rpcErr: Exception) {
            // Fallback: Direct table operations if RPC not yet migrated
            supabase.from("feedbacks").upsert(
                Feedback(
                    campaignId = campaignId,
                    userId = userId,
                    rating = rating.coerceIn(1, 5),
                    comment = comment.trim(),
                )
            )
            if (comment.isNotBlank() || !screenshotUrl.isNullOrBlank()) {
                val existingTodayReport = getMyTodayReport(campaignId)
                val alreadyCheckedIn = hasCheckedInToday(campaignId, userId)

                if (existingTodayReport?.id != null) {
                    supabase.from("tester_reports").update(
                        mapOf(
                            "content" to comment.trim(),
                            "screenshot_url" to (screenshotUrl ?: existingTodayReport.screenshotUrl),
                            "rating" to rating.coerceIn(1, 5),
                            "status" to if (existingTodayReport.isApproved || alreadyCheckedIn) "approved" else "pending",
                            "rejection_reason" to null,
                        )
                    ) {
                        filter { eq("id", existingTodayReport.id) }
                    }
                } else {
                    val initialStatus = if (alreadyCheckedIn) "approved" else "pending"
                    supabase.from("tester_reports").insert(
                        mapOf(
                            "campaign_id" to campaignId,
                            "user_id" to userId,
                            "report_type" to "feedback",
                            "content" to comment.trim(),
                            "screenshot_url" to screenshotUrl,
                            "rating" to rating.coerceIn(1, 5),
                            "status" to initialStatus,
                            "bonus_awarded" to if (alreadyCheckedIn) 2 else 0,
                        )
                    )
                }
            }
        }
        cachedStatsMap = null
        cachedTodayCheckins = null
        cachedQuestSummary = null
        cachedMyProfile = null
        ownerStatsCache.remove(campaignId)
    }

    suspend fun getFeedbacks(campaignId: String): List<Feedback> =
        supabase.from("feedbacks").select {
            filter { eq("campaign_id", campaignId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Feedback>()

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
        if (!forceRefresh && cachedMyProfile != null && System.currentTimeMillis() - myProfileTimestamp < CACHE_TTL_MS) {
            return cachedMyProfile
        }
        val profile = getProfiles(listOf(user.id), forceRefresh = forceRefresh)[user.id]
        cachedMyProfile = profile
        myProfileTimestamp = System.currentTimeMillis()
        return profile
    }

    suspend fun getTodayCheckinCount(forceRefresh: Boolean = false): Int {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return 0
        if (!forceRefresh && cachedTodayCheckins != null && System.currentTimeMillis() - todayCheckinsTimestamp < CACHE_TTL_MS) {
            return cachedTodayCheckins!!
        }
        val today = LocalDate.now().toString()
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
        return count
    }

    suspend fun getMyCreditLogs(limit: Long = 20): List<CreditLog> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        return try {
            supabase.from("credit_logs").select {
                filter { eq("user_id", uid) }
                order("created_at", Order.DESCENDING)
                limit(limit)
            }.decodeList<CreditLog>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- leaderboard

    suspend fun getLeaderboard(forceRefresh: Boolean = false): List<LeaderboardUser> {
        if (!forceRefresh && cachedLeaderboard != null && System.currentTimeMillis() - leaderboardTimestamp < CACHE_TTL_MS) {
            return cachedLeaderboard!!
        }
        val res = try {
            val profiles = supabase.from("profiles").select {
                order("credits", Order.DESCENDING)
                limit(100)
            }.decodeList<Profile>()

            if (profiles.isEmpty()) return emptyList()

            val userIds = profiles.map { it.id }
            val checkins = try {
                supabase.from("checkins").select {
                    filter { isIn("user_id", userIds) }
                }.decodeList<Checkin>()
            } catch (_: Exception) {
                emptyList()
            }

            val checkinsByUser = checkins.groupBy { it.userId }

            val sorted = profiles.map { profile ->
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
                compareByDescending<LeaderboardUser> { it.credits }
                    .thenByDescending { it.checkinCount }
                    .thenByDescending { it.campaignsCount }
            )

            sorted.mapIndexed { index, user ->
                user.copy(rank = index + 1)
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (res.isNotEmpty() || cachedLeaderboard == null) {
            cachedLeaderboard = res
            leaderboardTimestamp = System.currentTimeMillis()
        }
        return res
    }

    // ---------------------------------------------------------------- owner app management

    suspend fun getCampaignOwnerStats(campaignId: String, forceRefresh: Boolean = false): CampaignOwnerStats {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = ownerStatsCache[campaignId]
            if (cached != null && now - cached.second < CACHE_TTL_MS) {
                return cached.first
            }
        }
        val members = try { getMembers(campaignId) } catch (_: Exception) { emptyList() }
        val today = LocalDate.now().toString()
        val checkins = try {
            supabase.from("checkins").select {
                filter { eq("campaign_id", campaignId) }
            }.decodeList<Checkin>()
        } catch (_: Exception) {
            emptyList()
        }
        val feedbacks = try {
            getFeedbacks(campaignId)
        } catch (_: Exception) {
            emptyList<Feedback>()
        }
        val avgRating = if (feedbacks.isNotEmpty()) {
            feedbacks.map { it.rating }.average().toFloat()
        } else null

        val reports = try { getCampaignReports(campaignId, isOwner = true) } catch (_: Exception) { emptyList() }
        val todayCheckins = (
            checkins.filter { it.checkinDate == today }.map { it.userId } +
            reports.filter { it.isCreatedToday() && !it.isRejected }.map { it.userId }
        ).distinct().size
        val pendingCount = reports.count { it.status == "pending" }
        val totalReports = reports.size.coerceAtLeast(checkins.size)

        val stats = CampaignOwnerStats(
            installedTestersCount = members.size,
            testedTodayCount = todayCheckins,
            totalReportsCount = totalReports,
            pendingReportsCount = pendingCount,
            averageRating = avgRating,
        )
        ownerStatsCache[campaignId] = Pair(stats, now)
        return stats
    }

    suspend fun getPendingReportsCountForOwner(forceRefresh: Boolean = false): Int {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return 0
        if (!forceRefresh && cachedPendingReports != null && System.currentTimeMillis() - pendingReportsTimestamp < CACHE_TTL_MS) {
            return cachedPendingReports!!
        }
        val count = try {
            val myCamps = myCampaigns(forceRefresh = forceRefresh)
            val campIds = myCamps.mapNotNull { it.id }
            if (campIds.isEmpty()) return 0
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
        return count
    }

    suspend fun getCampaignTesters(campaignId: String, forceRefresh: Boolean = false): List<TesterItem> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = testersCache[campaignId]
            if (cached != null && now - cached.second < CACHE_TTL_MS) {
                return cached.first
            }
        }
        val members = try { getMembers(campaignId) } catch (_: Exception) { emptyList() }
        if (members.isEmpty()) return emptyList()
        val userIds = members.map { it.userId }
        val profilesMap = try { getProfiles(userIds) } catch (_: Exception) { emptyMap() }
        val checkins = try {
            supabase.from("checkins").select {
                filter {
                    eq("campaign_id", campaignId)
                    isIn("user_id", userIds)
                }
            }.decodeList<Checkin>()
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

    suspend fun getCampaignReports(campaignId: String, isOwner: Boolean = false, forceRefresh: Boolean = false): List<TesterReport> {
        val cacheKey = "${campaignId}_$isOwner"
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = reportsCache[cacheKey]
            if (cached != null && now - cached.second < CACHE_TTL_MS) {
                return cached.first
            }
        }
        try {
            autoAcceptExpiredReports()
        } catch (_: Exception) {}

        val result = if (isOwner) {
            // Priority 1: Use security definer RPC for Owner (Migration 012)
            try {
                val rpcReports = supabase.postgrest.rpc(
                    function = "get_campaign_reports_for_owner",
                    parameters = GetOwnerReportsParams(campaignId = campaignId)
                ).decodeAs<List<TesterReport>>()
                if (rpcReports.isNotEmpty()) {
                    val userIds = rpcReports.map { it.userId }.distinct()
                    val profiles = getProfiles(userIds)
                    rpcReports.map { rep ->
                        rep.copy(testerProfile = profiles[rep.userId])
                    }
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        if (result.isNotEmpty()) {
            reportsCache[cacheKey] = Pair(result, now)
            return result
        }

        // Direct table query (works for tester querying own reports, or fallback)
        val finalResult = try {
            val list = supabase.from("tester_reports").select {
                filter { eq("campaign_id", campaignId) }
                order("created_at", Order.DESCENDING)
            }.decodeList<TesterReport>()
            if (list.isNotEmpty()) {
                val userIds = list.map { it.userId }.distinct()
                val profiles = getProfiles(userIds)
                list.map { it.copy(testerProfile = profiles[it.userId]) }
            } else {
                val checkinsWithNotes = supabase.from("checkins").select {
                    filter {
                        eq("campaign_id", campaignId)
                        neq("note", "")
                    }
                    order("created_at", Order.DESCENDING)
                }.decodeList<Checkin>()
                val userIds = checkinsWithNotes.map { it.userId }.distinct()
                val profiles = getProfiles(userIds)
                checkinsWithNotes.map { c ->
                    TesterReport(
                        id = c.id,
                        campaignId = c.campaignId,
                        userId = c.userId,
                        reportType = "daily_test",
                        content = c.note ?: "Check-in test app",
                        status = "approved",
                        createdAt = c.createdAt,
                        testerProfile = profiles[c.userId],
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        reportsCache[cacheKey] = Pair(finalResult, now)
        return finalResult
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
        return try {
            supabase.postgrest.rpc(
                function = "approve_tester_report",
                parameters = ApproveReportParams(reportId = reportId, bonus = bonusCredits)
            ).decodeAs<ApproveReportResult>()
        } catch (e: Exception) {
            // Fallback for offline or legacy tables
            val updated = report.copy(
                status = "approved",
                bonusAwarded = 2 + bonusCredits,
                reviewedAt = LocalDate.now().toString(),
            )
            try {
                supabase.from("tester_reports").upsert(updated)
                addCredits(report.userId, 2 + bonusCredits, "test_approved", "Chủ app duyệt kết quả kiểm thử (+2 Credit)")
            } catch (_: Exception) {}
            ApproveReportResult(success = true, reportId = reportId, testerId = report.userId, status = "approved")
        }
    }

    suspend fun rejectReport(report: TesterReport, reason: String? = null): RejectReportResult {
        val reportId = report.id ?: return RejectReportResult(false)
        reportsCache.remove("${report.campaignId}_true")
        reportsCache.remove("${report.campaignId}_false")
        ownerStatsCache.remove(report.campaignId)
        cachedPendingReports = null
        return try {
            supabase.postgrest.rpc(
                function = "reject_tester_report",
                parameters = RejectReportParams(reportId = reportId, reason = reason)
            ).decodeAs<RejectReportResult>()
        } catch (e: Exception) {
            // Fallback
            val updated = report.copy(
                status = "rejected",
                rejectionReason = reason,
                reviewedAt = LocalDate.now().toString(),
            )
            try {
                supabase.from("tester_reports").upsert(updated)
            } catch (_: Exception) {}
            RejectReportResult(success = true, reportId = reportId, testerId = report.userId, status = "rejected")
        }
    }

    suspend fun autoAcceptExpiredReports(): Int {
        return try {
            supabase.postgrest.rpc(function = "auto_accept_expired_reports").decodeAs<Int>()
        } catch (_: Exception) {
            0
        }
    }

    suspend fun selfUnlockAccount(): SelfUnlockResult {
        return supabase.postgrest.rpc(function = "self_unlock_account").decodeAs<SelfUnlockResult>()
    }

    suspend fun getUnreadWarnings(): List<UserWarning> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        return try {
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
    }

    suspend fun acknowledgeWarning(warningId: String) {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return
        try {
            supabase.from("user_warnings").update(mapOf("acknowledged" to true)) {
                filter {
                    eq("id", warningId)
                    eq("user_id", uid)
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun getWeeklyWarningsCount(): Int {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return 0
        return try {
            val sevenDaysAgo = LocalDate.now().minusDays(6).toString()
            supabase.from("user_warnings").select {
                filter {
                    eq("user_id", uid)
                    gte("warning_date", sevenDaysAgo)
                }
            }.decodeList<UserWarning>().size
        } catch (_: Exception) {
            0
        }
    }

    suspend fun updateCampaignStatus(campaignId: String, newStatus: String) {
        val current = getCampaign(campaignId) ?: return
        updateCampaign(current.copy(status = newStatus))
    }

    suspend fun updateCampaignTip(campaignId: String, tipCredits: Int, isBoosted: Boolean) {
        val current = getCampaign(campaignId) ?: return
        updateCampaign(current.copy(tipCredits = tipCredits, isBoosted = isBoosted))
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
        val current = getCampaign(campaignId) ?: return
        updateCampaign(
            current.copy(
                appName = appName,
                description = description,
                playUrl = playUrl,
                testingUrl = testingUrl,
                testUsername = testUsername,
                testPassword = testPassword,
            )
        )
    }

    suspend fun adminDeleteCampaign(campaignId: String, reason: String? = null): AdminDeleteCampaignResult {
        val result = supabase.postgrest.rpc(
            "admin_delete_campaign",
            AdminDeleteCampaignParams(campaignId = campaignId, reason = reason)
        ).decodeAs<AdminDeleteCampaignResult>()
        cachedOpenCampaigns = null
        cachedMyCampaigns = null
        cachedStatsMap = null
        ownerStatsCache.remove(campaignId)
        return result
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
        cachedStatsMap = null
        ownerStatsCache.remove(campaignId)
    }

    suspend fun addCredits(userId: String, amount: Int, action: String, note: String) {
        try {
            val profile = getProfiles(listOf(userId))[userId] ?: return
            val newBalance = profile.credits + amount
            supabase.from("profiles").update(mapOf("credits" to newBalance)) {
                filter { eq("id", userId) }
            }
            supabase.from("credit_logs").insert(
                CreditLog(
                    userId = userId,
                    amount = amount,
                    action = action,
                    note = note,
                    balance = newBalance,
                )
            )
        } catch (_: Exception) {
            // Non-critical
        }
    }

    suspend fun getCampaignsStatsMap(campaignIds: List<String>, forceRefresh: Boolean = false): Map<String, CampaignStats> {
        if (campaignIds.isEmpty()) return emptyMap()
        if (!forceRefresh && cachedStatsMap != null && System.currentTimeMillis() - statsMapTimestamp < CACHE_TTL_MS) {
            val cached = cachedStatsMap!!
            if (campaignIds.all { cached.containsKey(it) }) {
                return cached
            }
        }
        val uid = supabase.auth.currentUserOrNull()?.id
        val today = LocalDate.now().toString()

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
        cachedStatsMap = resultMap
        statsMapTimestamp = System.currentTimeMillis()
        return resultMap
    }

    // ---------------------------------------------------------------- slots

    suspend fun getUserSlots(): List<UserSlot> {
        val user = supabase.auth.currentUserOrNull() ?: return emptyList()
        return try {
            supabase.from("user_slots").select {
                filter { eq("user_id", user.id) }
                order("slot_number", Order.ASCENDING)
            }.decodeList<UserSlot>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun unlockOrRenewSlot(slotNumber: Int): UnlockSlotResult {
        val result = supabase.postgrest.rpc(
            "unlock_or_renew_slot",
            UnlockSlotParams(slotNumber)
        ).decodeAs<UnlockSlotResult>()
        cachedMyProfile = null
        cachedSlotsInfo = null
        return result
    }

    suspend fun getMySlotsInfo(forceRefresh: Boolean = false): List<SlotInfo> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedSlotsInfo != null && now - slotsInfoTimestamp < CACHE_TTL_MS) {
            return cachedSlotsInfo!!
        }
        val userSlots = getUserSlots()
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
        return result
    }

    // ---------------------------------------------------------------- admin operations

    suspend fun getAllProfiles(forceRefresh: Boolean = false): List<Profile> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedAllProfiles != null && now - allProfilesTimestamp < CACHE_TTL_MS) {
            return cachedAllProfiles!!
        }
        val list = supabase.from("profiles").select {
            order("created_at", Order.DESCENDING)
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
        return supabase.postgrest.rpc(
            "admin_adjust_credits",
            AdminAdjustCreditsParams(
                targetUserId = targetUserId,
                amount = amount,
                reason = reason,
            )
        ).decodeAs<AdminAdjustCreditsResult>()
    }

    // ---------------------------------------------------------------- credit top-up

    suspend fun getTopupPackages(): List<TopupPackage> {
        return try {
            val packages = supabase.from("topup_packages").select {
                filter { eq("is_active", true) }
                order("sort_order", Order.ASCENDING)
            }.decodeList<TopupPackage>()
            if (packages.isNotEmpty()) packages else com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES
        } catch (_: Exception) {
            com.ntt.crosstest.util.TopupConfig.DEFAULT_PACKAGES
        }
    }

    suspend fun processTopupOrder(
        packageId: String,
        purchaseToken: String? = null,
        orderRef: String? = null,
        paymentMethod: String = "google_play",
        rawReceipt: String? = null,
    ): ProcessTopupResult {
        return supabase.postgrest.rpc(
            "process_topup_order",
            ProcessTopupParams(
                packageId = packageId,
                purchaseToken = purchaseToken,
                orderRef = orderRef,
                paymentMethod = paymentMethod,
                rawReceipt = rawReceipt,
            )
        ).decodeAs<ProcessTopupResult>()
    }

    // ---------------------------------------------------------------- notifications

    suspend fun getMyNotifications(limit: Long = 50): List<UserNotification> {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        return try {
            supabase.from("notifications").select {
                filter { eq("user_id", currentUid) }
                order("created_at", Order.DESCENDING)
                limit(limit)
            }.decodeList<UserNotification>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getUnreadNotificationsCount(): Int {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return 0
        return try {
            val unreadList = supabase.from("notifications").select {
                filter {
                    eq("user_id", currentUid)
                    eq("is_read", false)
                }
            }.decodeList<UserNotification>()
            unreadList.size
        } catch (_: Exception) {
            0
        }
    }

    suspend fun markNotificationAsRead(id: String) {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
        try {
            supabase.from("notifications").update(mapOf("is_read" to true)) {
                filter {
                    eq("id", id)
                    eq("user_id", currentUid)
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun markAllNotificationsAsRead() {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
        try {
            supabase.from("notifications").update(mapOf("is_read" to true)) {
                filter {
                    eq("user_id", currentUid)
                    eq("is_read", false)
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun deleteNotification(id: String) {
        val currentUid = supabase.auth.currentUserOrNull()?.id ?: return
        try {
            supabase.from("notifications").delete {
                filter {
                    eq("id", id)
                    eq("user_id", currentUid)
                }
            }
        } catch (_: Exception) {}
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
        if (!forceRefresh && cachedQuestSummary != null && System.currentTimeMillis() - questSummaryTimestamp < CACHE_TTL_MS) {
            return cachedQuestSummary!!
        }
        val summary = try {
            supabase.postgrest.rpc("get_user_quest_summary").decodeAs<QuestSummary>()
        } catch (_: Exception) {
            val uid = supabase.auth.currentUserOrNull()?.id
            val today = LocalDate.now().toString()
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
        return summary
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
            }
            result
        } catch (e: Exception) {
            ClaimQuestResult(
                success = false,
                questType = questType,
                milestone = milestone,
                error = e.message,
                message = e.message ?: "Không thể kết nối đến máy chủ."
            )
        }
    }
}

data class CampaignStats(
    val installedCount: Int = 0,
    val todayTestedCount: Int = 0,
    val hasTestedToday: Boolean = false,
)
