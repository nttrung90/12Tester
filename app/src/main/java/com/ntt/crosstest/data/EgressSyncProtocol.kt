package com.ntt.crosstest.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate

/**
 * Immutable snapshot tying the cached domain payload atomically to its server block tokens
 * under a specific session generation, mutation version, scope UID, and business date.
 */
data class ScopedSyncSnapshot<T>(
    val payload: T,
    val tokens: Map<String, String>,
    val scopeUid: String?,
    val sessionGeneration: Long,
    val mutationVersion: Long,
    val businessDate: LocalDate,
    val monotonicCreatedAtMs: Long,
)

sealed interface MergeResult<out T> {
    data class Success<T>(
        val payload: T,
        val tokens: Map<String, String>,
        val isAllUnchanged: Boolean,
        val changedBlockCount: Int,
        val totalBlockCount: Int,
        val wireBytesEstimate: Long?,
        val decodedBytesEstimate: Long,
    ) : MergeResult<T>

    data class NeedsFullRepair(val reason: String) : MergeResult<Nothing>
    data class ProtocolError(val message: String) : MergeResult<Nothing>
}

/**
 * Strict protocol validator and atomic merger for versioned conditional sync v2 responses.
 * Enforces canonical JSON structural requirements, prevents silent default pollution,
 * and detects missing base data requiring bounded full repairs.
 */
object EgressSyncProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private val SHA256_HEX_REGEX = Regex("^[a-fA-F0-9]{64}$")

    /**
     * Validates a block object from raw JSON:
     * - Must be a JsonObject
     * - Must contain "token" as a 64-char hex string
     * - Must contain "changed" as an unquoted boolean primitive
     * - If changed == true: must contain "value" key (even if JSON null)
     * - If changed == false: does not require "value" key
     */
    private fun validateBlockEnvelope(
        blockElement: JsonElement?,
    ): Pair<String, Boolean>? {
        val obj = blockElement as? JsonObject ?: return null
        val tokenPrim = obj["token"] as? JsonPrimitive ?: return null
        if (!tokenPrim.isString || !SHA256_HEX_REGEX.matches(tokenPrim.content)) return null
        val token = tokenPrim.content

        val changedPrim = obj["changed"] as? JsonPrimitive ?: return null
        if (changedPrim.isString) return null
        val changed = changedPrim.booleanOrNull ?: return null

        if (changed && !obj.containsKey("value")) {
            return null
        }
        return Pair(token, changed)
    }

    private inline fun <reified T : Any> parseRequiredBlock(
        blockKey: String,
        blocksObj: JsonObject,
        baseSnapshot: ScopedSyncSnapshot<*>?,
        newTokens: MutableMap<String, String>,
        getBaseValue: () -> T,
        onChanged: () -> Unit,
    ): T {
        val blockElement = blocksObj[blockKey]
            ?: throw IllegalStateException("Missing required block envelope: $blockKey")
        val (token, changed) = validateBlockEnvelope(blockElement)
            ?: throw IllegalStateException("Malformed block envelope: $blockKey")
        val value: T = if (changed) {
            val blockObj = blockElement as JsonObject
            val valElem = blockObj["value"] ?: throw IllegalStateException("Missing value key for changed block $blockKey")
            if (valElem is kotlinx.serialization.json.JsonNull) {
                throw IllegalStateException("Non-nullable block $blockKey cannot have null value")
            }
            val decoded = json.decodeFromJsonElement<T>(valElem)
            onChanged()
            decoded
        } else {
            if (baseSnapshot == null) throw IllegalStateException("Unchanged block $blockKey but base snapshot is missing")
            val expectedToken = baseSnapshot.tokens[blockKey]
            if (expectedToken == null || expectedToken != token) {
                throw IllegalStateException("Token mismatch for unchanged block $blockKey: expected $expectedToken, got $token")
            }
            getBaseValue()
        }
        newTokens[blockKey] = token
        return value
    }

    private inline fun <reified T : Any> parseNullableBlock(
        blockKey: String,
        blocksObj: JsonObject,
        baseSnapshot: ScopedSyncSnapshot<*>?,
        newTokens: MutableMap<String, String>,
        getBaseValue: () -> T?,
        onChanged: () -> Unit,
    ): T? {
        val blockElement = blocksObj[blockKey]
            ?: throw IllegalStateException("Missing required block envelope: $blockKey")
        val (token, changed) = validateBlockEnvelope(blockElement)
            ?: throw IllegalStateException("Malformed block envelope: $blockKey")
        val value: T? = if (changed) {
            val blockObj = blockElement as JsonObject
            val valElem = blockObj["value"] ?: throw IllegalStateException("Missing value key for changed block $blockKey")
            val decoded = if (valElem is kotlinx.serialization.json.JsonNull) {
                null
            } else {
                json.decodeFromJsonElement<T>(valElem)
            }
            onChanged()
            decoded
        } else {
            if (baseSnapshot == null) throw IllegalStateException("Unchanged block $blockKey but base snapshot is missing")
            val expectedToken = baseSnapshot.tokens[blockKey]
            if (expectedToken == null || expectedToken != token) {
                throw IllegalStateException("Token mismatch for unchanged block $blockKey: expected $expectedToken, got $token")
            }
            getBaseValue()
        }
        newTokens[blockKey] = token
        return value
    }

    // ========================================================================
    // HOME FEED SYNC V2
    // ========================================================================

    fun validateAndMergeHomeFeed(
        rawJson: String,
        baseSnapshot: ScopedSyncSnapshot<HomeFeedResult>?,
        expectedBusinessDate: LocalDate,
    ): MergeResult<HomeFeedResult> {
        val root = try {
            json.parseToJsonElement(rawJson) as? JsonObject
        } catch (e: Exception) {
            return MergeResult.NeedsFullRepair("Invalid JSON root: malformed data")
        } ?: return MergeResult.NeedsFullRepair("Root must be a JSON object")

        val schemaPrim = root["schema_version"] as? JsonPrimitive
        if (schemaPrim == null || schemaPrim.isString || schemaPrim.intOrNull != 2) {
            return MergeResult.NeedsFullRepair("Invalid schema_version; expected 2")
        }

        val bizDatePrim = root["business_date"] as? JsonPrimitive
        if (bizDatePrim == null || !bizDatePrim.isString || bizDatePrim.content != expectedBusinessDate.toString()) {
            return MergeResult.NeedsFullRepair("Business date mismatch")
        }

        val blocksObj = root["blocks"] as? JsonObject
            ?: return MergeResult.NeedsFullRepair("Missing or invalid 'blocks' object")

        val requiredKeys = listOf("open", "paused", "completed", "owner_names", "stats")
        for (key in requiredKeys) {
            if (!blocksObj.containsKey(key)) {
                return MergeResult.NeedsFullRepair("Missing required block: $key")
            }
        }

        val basePayload = baseSnapshot?.payload
        val newTokens = mutableMapOf<String, String>()
        var changedCount = 0
        val onChanged: () -> Unit = { changedCount++ }

        return try {
            val open = parseRequiredBlock<List<Campaign>>("open", blocksObj, baseSnapshot, newTokens, { basePayload!!.open }, onChanged)
            val paused = parseRequiredBlock<List<Campaign>>("paused", blocksObj, baseSnapshot, newTokens, { basePayload!!.paused }, onChanged)
            val completed = parseRequiredBlock<List<Campaign>>("completed", blocksObj, baseSnapshot, newTokens, { basePayload!!.completed }, onChanged)
            val ownerNames = parseRequiredBlock<Map<String, String>>("owner_names", blocksObj, baseSnapshot, newTokens, { basePayload!!.ownerNames }, onChanged)
            val stats = parseRequiredBlock<Map<String, CampaignStats>>("stats", blocksObj, baseSnapshot, newTokens, { basePayload!!.stats }, onChanged)

            val decodedBytes = rawJson.toByteArray(Charsets.UTF_8).size.toLong()
            MergeResult.Success(
                payload = HomeFeedResult(
                    open = open,
                    paused = paused,
                    completed = completed,
                    ownerNames = ownerNames,
                    stats = stats,
                ),
                tokens = newTokens,
                isAllUnchanged = (changedCount == 0),
                changedBlockCount = changedCount,
                totalBlockCount = requiredKeys.size,
                wireBytesEstimate = null,
                decodedBytesEstimate = decodedBytes,
            )
        } catch (e: Exception) {
            MergeResult.NeedsFullRepair("HomeFeed merge failed: malformed data")
        }
    }

    // ========================================================================
    // DASHBOARD SYNC V2
    // ========================================================================

    fun validateAndMergeDashboard(
        rawJson: String,
        baseSnapshot: ScopedSyncSnapshot<MyDashboardResult>?,
        expectedBusinessDate: LocalDate,
    ): MergeResult<MyDashboardResult> {
        val root = try {
            json.parseToJsonElement(rawJson) as? JsonObject
        } catch (e: Exception) {
            return MergeResult.NeedsFullRepair("Invalid JSON root: malformed data")
        } ?: return MergeResult.NeedsFullRepair("Root must be a JSON object")

        val schemaPrim = root["schema_version"] as? JsonPrimitive
        if (schemaPrim == null || schemaPrim.isString || schemaPrim.intOrNull != 2) {
            return MergeResult.NeedsFullRepair("Invalid schema_version; expected 2")
        }

        val bizDatePrim = root["business_date"] as? JsonPrimitive
        if (bizDatePrim == null || !bizDatePrim.isString || bizDatePrim.content != expectedBusinessDate.toString()) {
            return MergeResult.NeedsFullRepair("Business date mismatch")
        }

        val blocksObj = root["blocks"] as? JsonObject
            ?: return MergeResult.NeedsFullRepair("Missing or invalid 'blocks' object")

        val requiredKeys = listOf(
            "profile", "my_campaigns", "joined_campaigns", "leaderboard",
            "today_checkin_count", "pending_reports_count", "unread_warnings",
            "weekly_warnings_count", "credit_logs", "topup_packages",
            "notifications", "owner_stats", "slots", "quest_summary", "daily_task"
        )
        for (key in requiredKeys) {
            if (!blocksObj.containsKey(key)) {
                return MergeResult.NeedsFullRepair("Missing required block: $key")
            }
        }

        val basePayload = baseSnapshot?.payload
        val newTokens = mutableMapOf<String, String>()
        var changedCount = 0
        val onChanged: () -> Unit = { changedCount++ }

        return try {
            val profile = parseNullableBlock<Profile>("profile", blocksObj, baseSnapshot, newTokens, { basePayload?.profile }, onChanged)
            val myCampaigns = parseRequiredBlock<List<Campaign>>("my_campaigns", blocksObj, baseSnapshot, newTokens, { basePayload!!.myCampaigns }, onChanged)
            val joinedCampaigns = parseRequiredBlock<List<Campaign>>("joined_campaigns", blocksObj, baseSnapshot, newTokens, { basePayload!!.joinedCampaigns }, onChanged)
            val leaderboard = parseRequiredBlock<List<LeaderboardUser>>("leaderboard", blocksObj, baseSnapshot, newTokens, { basePayload!!.leaderboard }, onChanged)
            val todayCheckins = parseRequiredBlock<Int>("today_checkin_count", blocksObj, baseSnapshot, newTokens, { basePayload!!.todayCheckinCount }, onChanged)
            val pendingReports = parseRequiredBlock<Int>("pending_reports_count", blocksObj, baseSnapshot, newTokens, { basePayload!!.pendingReportsCount }, onChanged)
            val unreadWarnings = parseRequiredBlock<List<UserWarning>>("unread_warnings", blocksObj, baseSnapshot, newTokens, { basePayload!!.unreadWarnings }, onChanged)
            val weeklyWarnings = parseRequiredBlock<Int>("weekly_warnings_count", blocksObj, baseSnapshot, newTokens, { basePayload!!.weeklyWarningsCount }, onChanged)
            val creditLogs = parseRequiredBlock<List<CreditLog>>("credit_logs", blocksObj, baseSnapshot, newTokens, { basePayload!!.creditLogs }, onChanged)
            val topupPackages = parseRequiredBlock<List<TopupPackage>>("topup_packages", blocksObj, baseSnapshot, newTokens, { basePayload!!.topupPackages }, onChanged)
            val notifications = parseRequiredBlock<List<UserNotification>>("notifications", blocksObj, baseSnapshot, newTokens, { basePayload!!.notifications }, onChanged)
            val ownerStats = parseRequiredBlock<Map<String, CampaignOwnerStats>>("owner_stats", blocksObj, baseSnapshot, newTokens, { basePayload!!.ownerStats }, onChanged)
            val slots = parseNullableBlock<List<UserSlot>>("slots", blocksObj, baseSnapshot, newTokens, { basePayload?.slots }, onChanged)
            val questSummary = parseNullableBlock<QuestSummary>("quest_summary", blocksObj, baseSnapshot, newTokens, { basePayload?.questSummary }, onChanged)
            val dailyTask = parseNullableBlock<DailyTaskStatus>("daily_task", blocksObj, baseSnapshot, newTokens, { basePayload?.dailyTask }, onChanged)

            val decodedBytes = rawJson.toByteArray(Charsets.UTF_8).size.toLong()
            MergeResult.Success(
                payload = MyDashboardResult(
                    profile = profile,
                    myCampaigns = myCampaigns,
                    joinedCampaigns = joinedCampaigns,
                    leaderboard = leaderboard,
                    todayCheckinCount = todayCheckins,
                    pendingReportsCount = pendingReports,
                    unreadWarnings = unreadWarnings,
                    weeklyWarningsCount = weeklyWarnings,
                    creditLogs = creditLogs,
                    topupPackages = topupPackages,
                    notifications = notifications,
                    ownerStats = ownerStats,
                    slots = slots,
                    questSummary = questSummary,
                    dailyTask = dailyTask,
                ),
                tokens = newTokens,
                isAllUnchanged = (changedCount == 0),
                changedBlockCount = changedCount,
                totalBlockCount = requiredKeys.size,
                wireBytesEstimate = null,
                decodedBytesEstimate = decodedBytes,
            )
        } catch (e: Exception) {
            MergeResult.NeedsFullRepair("Dashboard merge failed: malformed data")
        }
    }

    // ========================================================================
    // CAMPAIGN DETAIL SYNC V2
    // ========================================================================

    fun validateAndMergeCampaignDetail(
        rawJson: String,
        baseSnapshot: ScopedSyncSnapshot<CampaignDetailResult>?,
        expectedBusinessDate: LocalDate,
    ): MergeResult<CampaignDetailResult> {
        val root = try {
            json.parseToJsonElement(rawJson) as? JsonObject
        } catch (e: Exception) {
            return MergeResult.NeedsFullRepair("Invalid JSON root: malformed data")
        } ?: return MergeResult.NeedsFullRepair("Root must be a JSON object")

        val schemaPrim = root["schema_version"] as? JsonPrimitive
        if (schemaPrim == null || schemaPrim.isString || schemaPrim.intOrNull != 2) {
            return MergeResult.NeedsFullRepair("Invalid schema_version; expected 2")
        }

        val bizDatePrim = root["business_date"] as? JsonPrimitive
        if (bizDatePrim == null || !bizDatePrim.isString || bizDatePrim.content != expectedBusinessDate.toString()) {
            return MergeResult.NeedsFullRepair("Business date mismatch")
        }

        val blocksObj = root["blocks"] as? JsonObject
            ?: return MergeResult.NeedsFullRepair("Missing or invalid 'blocks' object")

        val requiredKeys = listOf(
            "campaign", "is_member", "is_owner", "is_actual_owner", "can_manage",
            "checked_in_confirmed_today", "testers", "feedbacks", "reports", "profiles",
            "today_tested_count", "reports_count", "pending_reports_count", "tester_reports_count",
            "avg_rating", "my_checkin_dates", "my_profile", "unread_warnings",
            "weekly_warnings_count", "app_update"
        )
        for (key in requiredKeys) {
            if (!blocksObj.containsKey(key)) {
                return MergeResult.NeedsFullRepair("Missing required block: $key")
            }
        }

        val basePayload = baseSnapshot?.payload
        val newTokens = mutableMapOf<String, String>()
        var changedCount = 0
        val onChanged: () -> Unit = { changedCount++ }

        return try {
            val campaign = parseRequiredBlock<Campaign>("campaign", blocksObj, baseSnapshot, newTokens, { basePayload!!.campaign }, onChanged)
            val isMember = parseRequiredBlock<Boolean>("is_member", blocksObj, baseSnapshot, newTokens, { basePayload!!.isMember }, onChanged)
            val isOwner = parseRequiredBlock<Boolean>("is_owner", blocksObj, baseSnapshot, newTokens, { basePayload!!.isOwner }, onChanged)
            val isActualOwner = parseRequiredBlock<Boolean>("is_actual_owner", blocksObj, baseSnapshot, newTokens, { basePayload!!.isActualOwner }, onChanged)
            val canManage = parseRequiredBlock<Boolean>("can_manage", blocksObj, baseSnapshot, newTokens, { basePayload!!.canManage }, onChanged)
            val checkedInConfirmedToday = parseRequiredBlock<Boolean>("checked_in_confirmed_today", blocksObj, baseSnapshot, newTokens, { basePayload!!.checkedInConfirmedToday }, onChanged)
            val testers = parseRequiredBlock<List<CampaignDetailTester>>("testers", blocksObj, baseSnapshot, newTokens, { basePayload!!.testers }, onChanged)
            val feedbacks = parseRequiredBlock<List<Feedback>>("feedbacks", blocksObj, baseSnapshot, newTokens, { basePayload!!.feedbacks }, onChanged)
            val reports = parseRequiredBlock<List<TesterReport>>("reports", blocksObj, baseSnapshot, newTokens, { basePayload!!.reports }, onChanged)
            val profiles = parseRequiredBlock<Map<String, Profile>>("profiles", blocksObj, baseSnapshot, newTokens, { basePayload!!.profiles }, onChanged)
            val todayTestedCount = parseRequiredBlock<Int>("today_tested_count", blocksObj, baseSnapshot, newTokens, { basePayload!!.todayTestedCount }, onChanged)
            val reportsCount = parseRequiredBlock<Int>("reports_count", blocksObj, baseSnapshot, newTokens, { basePayload!!.reportsCount }, onChanged)
            val pendingReportsCount = parseRequiredBlock<Int>("pending_reports_count", blocksObj, baseSnapshot, newTokens, { basePayload!!.pendingReportsCount }, onChanged)
            val testerReportsCount = parseRequiredBlock<Int>("tester_reports_count", blocksObj, baseSnapshot, newTokens, { basePayload!!.testerReportsCount }, onChanged)
            val avgRating = parseNullableBlock<Float>("avg_rating", blocksObj, baseSnapshot, newTokens, { basePayload?.avgRating }, onChanged)
            val myCheckinDates = parseRequiredBlock<List<String>>("my_checkin_dates", blocksObj, baseSnapshot, newTokens, { basePayload!!.myCheckinDates }, onChanged)
            val myProfile = parseNullableBlock<Profile>("my_profile", blocksObj, baseSnapshot, newTokens, { basePayload?.myProfile }, onChanged)
            val unreadWarnings = parseRequiredBlock<List<UserWarning>>("unread_warnings", blocksObj, baseSnapshot, newTokens, { basePayload!!.unreadWarnings }, onChanged)
            val weeklyWarningsCount = parseRequiredBlock<Int>("weekly_warnings_count", blocksObj, baseSnapshot, newTokens, { basePayload!!.weeklyWarningsCount }, onChanged)
            val appUpdate = parseNullableBlock<AppUpdateRelease>("app_update", blocksObj, baseSnapshot, newTokens, { basePayload?.appUpdate }, onChanged)
            val ownerStats = if (blocksObj.containsKey("owner_stats")) {
                parseNullableBlock<OwnerActivityStats>("owner_stats", blocksObj, baseSnapshot, newTokens, { basePayload?.ownerStats }, onChanged)
            } else {
                basePayload?.ownerStats
            }

            val decodedBytes = rawJson.toByteArray(Charsets.UTF_8).size.toLong()
            MergeResult.Success(
                payload = CampaignDetailResult(
                    campaign = campaign,
                    isMember = isMember,
                    isOwner = isOwner,
                    isActualOwner = isActualOwner,
                    canManage = canManage,
                    checkedInConfirmedToday = checkedInConfirmedToday,
                    testers = testers,
                    feedbacks = feedbacks,
                    reports = reports,
                    profiles = profiles,
                    todayTestedCount = todayTestedCount,
                    reportsCount = reportsCount,
                    pendingReportsCount = pendingReportsCount,
                    testerReportsCount = testerReportsCount,
                    avgRating = avgRating,
                    myCheckinDates = myCheckinDates,
                    myProfile = myProfile,
                    unreadWarnings = unreadWarnings,
                    weeklyWarningsCount = weeklyWarningsCount,
                    appUpdate = appUpdate,
                    ownerStats = ownerStats,
                ),
                tokens = newTokens,
                isAllUnchanged = (changedCount == 0),
                changedBlockCount = changedCount,
                totalBlockCount = newTokens.size,
                wireBytesEstimate = null,
                decodedBytesEstimate = decodedBytes,
            )
        } catch (e: Exception) {
            MergeResult.NeedsFullRepair("CampaignDetail merge failed: malformed data")
        }
    }
}
