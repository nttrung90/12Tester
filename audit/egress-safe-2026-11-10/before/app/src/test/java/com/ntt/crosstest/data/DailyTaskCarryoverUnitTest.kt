package com.ntt.crosstest.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTaskCarryoverUnitTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testLegacyJsonCompatibility() {
        val legacyJson = """
            {
                "status_date": "2026-10-29",
                "mode": "owner",
                "active_app_count": 2,
                "required_tests": 6,
                "available_apps": 20,
                "effective_required_tests": 6,
                "completed_tests": 4,
                "logged_in_today": true,
                "is_penalty_exempt": false,
                "missed_streak": 0,
                "next_penalty_percent": 1,
                "met": false
            }
        """.trimIndent()

        val parsed = json.decodeFromString<DailyTaskStatus>(legacyJson)
        assertEquals("owner", parsed.mode)
        assertEquals(6, parsed.requiredTests)
        assertEquals(4, parsed.completedTests)
        // Các trường mới phải nhận default an toàn
        assertEquals(0, parsed.baseRequiredTests)
        assertEquals(0, parsed.carryInTests)
        assertEquals(0, parsed.carryoverRemainingTests)
        assertTrue(parsed.carryoverReady)
        assertNull(parsed.carryoverEffectiveFrom)
        assertFalse(parsed.isPendingCarryover)
        assertFalse(parsed.hasCarryIn)
        assertFalse(parsed.hasRemainingCarryover)
    }

    @Test
    fun testCarryoverNewFieldsParsing() {
        val newJson = """
            {
                "status_date": "2026-10-30",
                "mode": "owner",
                "active_app_count": 2,
                "required_tests": 8,
                "available_apps": 20,
                "effective_required_tests": 8,
                "completed_tests": 5,
                "logged_in_today": true,
                "is_penalty_exempt": false,
                "missed_streak": 1,
                "next_penalty_percent": 2,
                "met": false,
                "base_required_tests": 6,
                "carry_in_tests": 2,
                "carryover_remaining_tests": 3,
                "carryover_ready": true,
                "carryover_effective_from": "2026-10-30"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<DailyTaskStatus>(newJson)
        assertEquals(6, parsed.baseRequiredTests)
        assertEquals(2, parsed.carryInTests)
        assertEquals(8, parsed.requiredTests)
        assertEquals(8, parsed.effectiveRequiredTests)
        assertEquals(3, parsed.carryoverRemainingTests)
        assertTrue(parsed.carryoverReady)
        assertFalse(parsed.isPendingCarryover)
        assertTrue(parsed.hasCarryIn)
        assertTrue(parsed.hasRemainingCarryover)
        assertEquals("2026-10-30", parsed.carryoverEffectiveFrom)
    }

    @Test
    fun testPendingCarryoverState() {
        val pendingJson = """
            {
                "status_date": "2026-10-31",
                "mode": "owner",
                "active_app_count": 2,
                "required_tests": 6,
                "available_apps": 20,
                "effective_required_tests": 6,
                "completed_tests": 1,
                "logged_in_today": true,
                "is_penalty_exempt": false,
                "missed_streak": 1,
                "next_penalty_percent": 2,
                "met": false,
                "base_required_tests": 6,
                "carry_in_tests": 0,
                "carryover_remaining_tests": 0,
                "carryover_ready": false,
                "carryover_effective_from": "2026-10-30"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<DailyTaskStatus>(pendingJson)
        assertFalse(parsed.carryoverReady)
        assertTrue(parsed.isPendingCarryover)
        assertFalse(parsed.met)
        // Khi chưa ready, không được coi là waived
        assertFalse(parsed.isWaived)
    }

    @Test
    fun testWaivedStateOnlyWhenReadyAndZeroTarget() {
        // Owner không có app open nào khác để test (A=0 -> effectiveRequiredTests = 0)
        val waivedStatus = createStatus(
            statusDate = "2026-10-30",
            mode = "owner",
            activeAppCount = 1,
            required_tests = 3,
            available_apps = 0,
            effective_required_tests = 0,
            carryover_ready = true,
        )
        assertTrue(waivedStatus.isWaived)

        // Nếu carryover chưa ready thì KHÔNG được coi là waived
        val notWaivedBecausePending = waivedStatus.copy(carryoverReady = false)
        assertFalse(notWaivedBecausePending.isWaived)
        assertTrue(notWaivedBecausePending.isPendingCarryover)
    }

    @Test
    fun testMemberStatusProperties() {
        val memberStatus = createStatus(
            statusDate = "2026-10-30",
            mode = "member",
            activeAppCount = 0,
            required_tests = 0,
            effective_required_tests = 0,
            carry_in_tests = 4, // Từng là owner có phần thiếu
            carryover_remaining_tests = 4,
            carryover_ready = true,
            met = true,
            logged_in_today = true,
        )
        assertTrue(memberStatus.isMember)
        assertFalse(memberStatus.isOwner)
        assertTrue(memberStatus.met)
        assertTrue(memberStatus.hasCarryIn)
        assertTrue(memberStatus.hasRemainingCarryover)
        assertEquals(4, memberStatus.carryInTests)
        assertEquals(4, memberStatus.carryoverRemainingTests)
        assertFalse(memberStatus.isPendingCarryover)
    }

    @Test
    fun testMemberPendingCarryoverIsNotDebtFree() {
        val pendingMember = createStatus(
            statusDate = "2026-11-05",
            mode = "member",
            activeAppCount = 0,
            required_tests = 0,
            effective_required_tests = 0,
            carry_in_tests = 0,
            carryover_remaining_tests = 0,
            carryover_ready = false, // Yesterday unfinalized
            history_ready = true,
            met = true,
            logged_in_today = true,
        )
        assertTrue(pendingMember.isMember)
        assertTrue(pendingMember.met)
        assertFalse(pendingMember.isPendingCarryover)
        assertTrue(pendingMember.isCarryoverHistoryPending)
        assertFalse("Pending member carryover must NOT be presented as confirmed remaining debt", pendingMember.hasRemainingCarryover)
    }

    @Test
    fun testOwnerMetWithRemainingPreservedDebt() {
        // C=8, A=5, D=5: met=true, remaining=3
        val metWithDebt = createStatus(
            statusDate = "2026-11-05",
            mode = "owner",
            activeAppCount = 2,
            required_tests = 14,
            available_apps = 5,
            effective_required_tests = 5,
            completed_tests = 5,
            carry_in_tests = 8,
            carryover_remaining_tests = 3,
            carryover_ready = true,
            history_ready = true,
            met = true,
        )
        assertTrue(metWithDebt.met)
        assertEquals(3, metWithDebt.carryoverRemainingTests)
        assertTrue(metWithDebt.hasRemainingCarryover)
    }

    @Test
    fun testDailyTaskCardSourceContractGuardsRemainingCarryover() {
        val cardSource = java.io.File("src/main/java/com/ntt/crosstest/ui/components/DailyTaskCard.kt").readText()
        val start = cardSource.indexOf("if (isAuthenticated && met && !exempt && !waived)")
        val end = cardSource.indexOf("\n        return", start)
        assertTrue("Compact card branch must exist", start >= 0 && end > start)
        val compactBranch = cardSource.substring(start, end)
        assertTrue("Compact card branch must check carryoverRemainingTests or hasRemainingCarryover",
            compactBranch.contains("carryoverRemainingTests") || compactBranch.contains("hasRemainingCarryover"))
        assertTrue("Compact card must render preserved debt notice",
            compactBranch.contains("daily_task_preserved_debt_notice"))
    }

    @Test
    fun testDailyTaskCardSourceGuardsDisabledCarryoverMath() {
        val cardSource = java.io.File("src/main/java/com/ntt/crosstest/ui/components/DailyTaskCard.kt").readText()
        assertTrue(
            "DailyTaskCard must guard daily_owner_goal_with_carryover with requiredTests > baseRequiredTests",
            cardSource.contains("isCarryoverIncludedInRequired") &&
                cardSource.contains("(dailyTask?.requiredTests ?: 0) > (dailyTask?.baseRequiredTests ?: 0)")
        )
        assertTrue(
            "DailyTaskCard expanded else branch must require hasCarryIn == true before showing preserved debt notice",
            cardSource.contains("dailyTask?.hasCarryIn == true && dailyTask.hasRemainingCarryover && !dailyTask.isCarryoverHistoryPending")
        )
    }

    @Test
    fun testSessionCacheManagerSanitizeForDateClearsStaleDailyTask() {
        val todayStr = com.ntt.crosstest.util.VnTime.today().toString()
        val yesterdayStr = com.ntt.crosstest.util.VnTime.today().minusDays(1).toString()

        // 1. Old session snapshot where dailyTask.statusDate is null -> must clear dailyTask to null
        val snapshotOldWithNullStatusDate = CachedSessionData(
            userId = "u1",
            dateVn = yesterdayStr,
            dailyTask = createStatus(statusDate = null, mode = "owner", required_tests = 3),
        )
        assertNull(
            "sanitizeForDate must clear dailyTask when session date is old and statusDate is null",
            snapshotOldWithNullStatusDate.sanitizeForDate().dailyTask
        )

        // 2. Snapshot where dateVn is null & timestamp is 0L (isOldDate == false), but dailyTask.statusDate is yesterday
        val snapshotUnknownDateWithStaleDailyTask = CachedSessionData(
            userId = "u1",
            dateVn = null,
            timestamp = 0L,
            dailyTask = createStatus(statusDate = yesterdayStr, mode = "owner", required_tests = 3),
        )
        assertNull(
            "sanitizeForDate must clear dailyTask when dailyTask.statusDate != todayStr even if isOldDate is false",
            snapshotUnknownDateWithStaleDailyTask.sanitizeForDate().dailyTask
        )

        // 3. Snapshot from today with matching statusDate -> must preserve dailyTask
        val snapshotToday = CachedSessionData(
            userId = "u1",
            dateVn = todayStr,
            dailyTask = createStatus(statusDate = todayStr, mode = "owner", required_tests = 3),
        )
        assertEquals(
            todayStr,
            snapshotToday.sanitizeForDate().dailyTask?.statusDate
        )
    }

    @Test
    fun testCampaignRepositoryFallbackFiltersExcludeMissionReports() {
        val repoSource = java.io.File("src/main/java/com/ntt/crosstest/data/CampaignRepository.kt").readText()
        assertTrue(
            "getTodayCheckinCount must exclude mission reports",
            repoSource.contains("it.isCreatedToday() && (it.isPending || it.isApproved) && !it.isMissionReport")
        )
        val nonRejectedNonMissionCount =
            Regex("""it\.isCreatedToday\(\)\s*&&\s*!it\.isRejected\s*&&\s*!it\.isMissionReport""")
                .findAll(repoSource)
                .count()
        assertEquals(
            "getCampaignOwnerStats and getCampaignsStatsMap fallbacks in CampaignRepository must exclude mission reports",
            2,
            nonRejectedNonMissionCount
        )
        val detailVmSource = java.io.File("src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt").readText()
        assertTrue(
            "CampaignDetailViewModel must exclude mission reports when computing todayTested",
            detailVmSource.contains("it.isCreatedToday() && !it.isRejected && !it.isMissionReport")
        )
    }

    private fun createStatus(
        statusDate: String? = null,
        mode: String = "member",
        activeAppCount: Int = 0,
        required_tests: Int = 0,
        requiredTests: Int = required_tests,
        available_apps: Int = 0,
        availableApps: Int = available_apps,
        effective_required_tests: Int = 0,
        effectiveRequiredTests: Int = effective_required_tests,
        completed_tests: Int = 0,
        completedTests: Int = completed_tests,
        logged_in_today: Boolean = false,
        loggedInToday: Boolean = logged_in_today,
        is_penalty_exempt: Boolean = false,
        isPenaltyExempt: Boolean = is_penalty_exempt,
        missed_streak: Int = 0,
        missedStreak: Int = missed_streak,
        next_penalty_percent: Int = 1,
        nextPenaltyPercent: Int = next_penalty_percent,
        met: Boolean = false,
        base_required_tests: Int = 0,
        baseRequiredTests: Int = base_required_tests,
        carry_in_tests: Int = 0,
        carryInTests: Int = carry_in_tests,
        carryover_remaining_tests: Int = 0,
        carryoverRemainingTests: Int = carryover_remaining_tests,
        carryover_ready: Boolean = true,
        carryoverReady: Boolean = carryover_ready,
        history_ready: Boolean = true,
        historyReady: Boolean = history_ready,
        carryover_effective_from: String? = null,
        carryoverEffectiveFrom: String? = carryover_effective_from,
    ): DailyTaskStatus {
        return DailyTaskStatus(
            statusDate = statusDate,
            mode = mode,
            activeAppCount = activeAppCount,
            requiredTests = requiredTests,
            availableApps = availableApps,
            effectiveRequiredTests = effectiveRequiredTests,
            completedTests = completedTests,
            loggedInToday = loggedInToday,
            isPenaltyExempt = isPenaltyExempt,
            missedStreak = missedStreak,
            nextPenaltyPercent = nextPenaltyPercent,
            met = met,
            baseRequiredTests = baseRequiredTests,
            carryInTests = carryInTests,
            carryoverRemainingTests = carryoverRemainingTests,
            carryoverReady = carryoverReady,
            historyReady = historyReady,
            carryoverEffectiveFrom = carryoverEffectiveFrom,
        )
    }
}
