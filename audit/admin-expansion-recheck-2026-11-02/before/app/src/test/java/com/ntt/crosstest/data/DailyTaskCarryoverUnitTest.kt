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
        val waivedStatus = DailyTaskStatus(
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
        val memberStatus = DailyTaskStatus(
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
        assertEquals(4, memberStatus.carryInTests)
    }

    private fun DailyTaskStatus(
        statusDate: String? = null,
        mode: String = "member",
        activeAppCount: Int = 0,
        required_tests: Int = 0,
        available_apps: Int = 0,
        effective_required_tests: Int = 0,
        completed_tests: Int = 0,
        logged_in_today: Boolean = false,
        is_penalty_exempt: Boolean = false,
        missed_streak: Int = 0,
        next_penalty_percent: Int = 1,
        met: Boolean = false,
        base_required_tests: Int = 0,
        carry_in_tests: Int = 0,
        carryover_remaining_tests: Int = 0,
        carryover_ready: Boolean = true,
        carryover_effective_from: String? = null,
    ): DailyTaskStatus {
        return DailyTaskStatus(
            statusDate = statusDate,
            mode = mode,
            activeAppCount = activeAppCount,
            requiredTests = required_tests,
            availableApps = available_apps,
            effectiveRequiredTests = effective_required_tests,
            completedTests = completed_tests,
            loggedInToday = logged_in_today,
            isPenaltyExempt = is_penalty_exempt,
            missedStreak = missed_streak,
            nextPenaltyPercent = next_penalty_percent,
            met = met,
            baseRequiredTests = base_required_tests,
            carryInTests = carry_in_tests,
            carryoverRemainingTests = carryover_remaining_tests,
            carryoverReady = carryover_ready,
            carryoverEffectiveFrom = carryover_effective_from,
        )
    }
}
