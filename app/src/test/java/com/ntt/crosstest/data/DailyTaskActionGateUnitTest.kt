package com.ntt.crosstest.data

import com.ntt.crosstest.R
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTaskActionGateUnitTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val todayVn = "2026-11-12"

    private fun makeValidJson(
        statusDate: String? = todayVn,
        met: Any? = true,
        historyReady: Any? = true,
        carryoverReady: Any? = true,
        carryoverRemainingTests: Any? = 0,
    ): String {
        val fields = mutableListOf<String>()
        if (statusDate != null) fields.add("\"status_date\": \"$statusDate\"")
        fields.add("\"mode\": \"owner\"")
        fields.add("\"active_app_count\": 2")
        fields.add("\"required_tests\": 6")
        fields.add("\"effective_required_tests\": 6")
        fields.add("\"completed_tests\": 6")
        if (met != null) fields.add("\"met\": $met")
        if (historyReady != null) fields.add("\"history_ready\": $historyReady")
        if (carryoverReady != null) fields.add("\"carryover_ready\": $carryoverReady")
        if (carryoverRemainingTests != null) fields.add("\"carryover_remaining_tests\": $carryoverRemainingTests")
        return "{ ${fields.joinToString(", ")} }"
    }

    private fun makeCampaign(status: String = "open", pausedReason: String? = null) = Campaign(
        id = "camp-123",
        ownerId = "owner-123",
        appName = "Test App",
        packageName = "com.test.app",
        status = status,
        pausedReason = pausedReason,
        slotNumber = 1,
    )

    // =========================================================================
    // 1. DECODING & MISSING FIELD DETECTION TESTS
    // =========================================================================

    @Test
    fun testValidCompletePayloadDecodesAndPassesGate() {
        val raw = makeValidJson()
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        assertEquals(true, parsed.rawMet)
        assertEquals(true, parsed.rawHistoryReady)
        assertEquals(true, parsed.rawCarryoverReady)
        assertEquals(0, parsed.rawCarryoverRemainingTests)

        val camp = makeCampaign("open")
        val result = DailyTaskActionGate.evaluate(DailyTaskAction.DELETE, camp, parsed, todayVn = todayVn)
        assertTrue(result.isAllowed)
    }

    @Test
    fun testOmittedHistoryReadyDecodesToNullAndBlocksGate() {
        val raw = makeValidJson(historyReady = null)
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        assertNull(parsed.rawHistoryReady)
        // Default getter remains true for UI safety
        assertTrue(parsed.historyReady)

        val result = DailyTaskActionGate.evaluate(DailyTaskAction.DELETE, makeCampaign("open"), parsed, todayVn = todayVn)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        assertEquals(ActionGateBlockReason.DataPendingOrStale, (result as DailyTaskActionGateResult.Blocked).reason)
    }

    @Test
    fun testNullHistoryReadyDecodesToNullAndBlocksGate() {
        val raw = """{ "status_date": "$todayVn", "met": true, "history_ready": null, "carryover_ready": true, "carryover_remaining_tests": 0 }"""
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        assertNull(parsed.rawHistoryReady)
        val result = DailyTaskActionGate.evaluate(DailyTaskAction.PRODUCTION, makeCampaign("open"), parsed, todayVn = todayVn)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        assertEquals(ActionGateBlockReason.DataPendingOrStale, (result as DailyTaskActionGateResult.Blocked).reason)
    }

    @Test
    fun testOmittedCarryoverReadyDecodesToNullAndBlocksGate() {
        val raw = makeValidJson(carryoverReady = null)
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        assertNull(parsed.rawCarryoverReady)
        val result = DailyTaskActionGate.evaluate(DailyTaskAction.RESUME, makeCampaign("paused", "owner_manual"), parsed, todayVn = todayVn)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        assertEquals(ActionGateBlockReason.DataPendingOrStale, (result as DailyTaskActionGateResult.Blocked).reason)
    }

    @Test
    fun testOmittedCarryoverRemainingTestsDecodesToNullAndBlocksGate() {
        val raw = makeValidJson(carryoverRemainingTests = null)
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        assertNull(parsed.rawCarryoverRemainingTests)
        val result = DailyTaskActionGate.evaluate(DailyTaskAction.DELETE, makeCampaign("open"), parsed, todayVn = todayVn)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        assertEquals(ActionGateBlockReason.DataPendingOrStale, (result as DailyTaskActionGateResult.Blocked).reason)
    }

    @Test
    fun testNegativeCarryoverRemainingTestsBlocksGateAsInvalidData() {
        val raw = makeValidJson(carryoverRemainingTests = -1)
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        assertEquals(-1, parsed.rawCarryoverRemainingTests)
        val result = DailyTaskActionGate.evaluate(DailyTaskAction.PRODUCTION, makeCampaign("open"), parsed, todayVn = todayVn)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        assertEquals(ActionGateBlockReason.DataPendingOrStale, (result as DailyTaskActionGateResult.Blocked).reason)
    }

    @Test
    fun testPositiveCarryoverRemainingTestsBlocksGateWithDebt() {
        val raw = makeValidJson(carryoverRemainingTests = 4)
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        assertEquals(4, parsed.rawCarryoverRemainingTests)
        val result = DailyTaskActionGate.evaluate(DailyTaskAction.DELETE, makeCampaign("open"), parsed, todayVn = todayVn)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        val blocked = result as DailyTaskActionGateResult.Blocked
        assertEquals(ActionGateBlockReason.UnresolvedCarryoverDebt(4), blocked.reason)
        assertEquals(4, blocked.formatArg)
    }

    @Test
    fun testOmittedMetDecodesToNullAndBlocksGateAsPending() {
        val raw = makeValidJson(met = null)
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        assertNull(parsed.rawMet)
        val result = DailyTaskActionGate.evaluate(DailyTaskAction.DELETE, makeCampaign("open"), parsed, todayVn = todayVn)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        assertEquals(ActionGateBlockReason.DataPendingOrStale, (result as DailyTaskActionGateResult.Blocked).reason)
    }

    @Test
    fun testMetFalseBlocksGateAsUnmet() {
        val raw = makeValidJson(met = false)
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        assertEquals(false, parsed.rawMet)
        val result = DailyTaskActionGate.evaluate(DailyTaskAction.PRODUCTION, makeCampaign("open"), parsed, todayVn = todayVn)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        val blocked = result as DailyTaskActionGateResult.Blocked
        assertEquals(ActionGateBlockReason.UnmetDailyTask, blocked.reason)
        // With diff = 6 - 6 = 0, no positive remainder is fabricated; uses generic message
        assertNull(blocked.formatArg)
        assertEquals(com.ntt.crosstest.R.string.oad_gate_error_unmet_daily_task, blocked.messageRes)

        // When canonical difference is known positive, uses dynamic format
        val withRemaining = parsed.copy(completedTests = 3)
        val resultWithRemaining = DailyTaskActionGate.evaluate(DailyTaskAction.PRODUCTION, makeCampaign("open"), withRemaining, todayVn = todayVn)
        assertTrue(resultWithRemaining is DailyTaskActionGateResult.Blocked)
        val blockedWithRemaining = resultWithRemaining as DailyTaskActionGateResult.Blocked
        assertEquals(3, blockedWithRemaining.formatArg)
        assertEquals(com.ntt.crosstest.R.string.oad_gate_error_unmet_daily_task_format, blockedWithRemaining.messageRes)
    }

    @Test
    fun testStaleDateBlocksGate() {
        val raw = makeValidJson(statusDate = "2026-11-11")
        val parsed = json.decodeFromString<DailyTaskStatus>(raw)

        val result = DailyTaskActionGate.evaluate(DailyTaskAction.DELETE, makeCampaign("open"), parsed, todayVn = todayVn)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        assertEquals(ActionGateBlockReason.DataPendingOrStale, (result as DailyTaskActionGateResult.Blocked).reason)
    }

    // =========================================================================
    // 2. ACTION-SPECIFIC BEHAVIOR TESTS
    // =========================================================================

    @Test
    fun testManualPauseGatedOnlyWhenAppWasActive() {
        val parsedUnmet = json.decodeFromString<DailyTaskStatus>(makeValidJson(met = false))

        // Open app -> manual pause reduces quota -> BLOCKED
        val openCamp = makeCampaign("open")
        val openResult = DailyTaskActionGate.evaluate(DailyTaskAction.MANUAL_PAUSE, openCamp, parsedUnmet, todayVn = todayVn)
        assertTrue(openResult is DailyTaskActionGateResult.Blocked)

        // Auto-paused app -> manual pause reduces quota -> BLOCKED
        val autoPausedCamp = makeCampaign("paused", "owner_inactive")
        val autoResult = DailyTaskActionGate.evaluate(DailyTaskAction.MANUAL_PAUSE, autoPausedCamp, parsedUnmet, todayVn = todayVn)
        assertTrue(autoResult is DailyTaskActionGateResult.Blocked)

        // Already manually paused app -> manual pause does not reduce quota -> ALLOWED
        val alreadyPausedCamp = makeCampaign("paused", "owner_manual")
        val pausedResult = DailyTaskActionGate.evaluate(DailyTaskAction.MANUAL_PAUSE, alreadyPausedCamp, parsedUnmet, todayVn = todayVn)
        assertTrue(pausedResult.isAllowed)

        // Completed app -> manual pause does not reduce quota -> ALLOWED
        val completedCamp = makeCampaign("completed")
        val completedResult = DailyTaskActionGate.evaluate(DailyTaskAction.MANUAL_PAUSE, completedCamp, parsedUnmet, todayVn = todayVn)
        assertTrue(completedResult.isAllowed)
    }

    @Test
    fun testAccountLockedBlocksAllActionsRegardlessOfTaskCompletion() {
        val parsedMet = json.decodeFromString<DailyTaskStatus>(makeValidJson(met = true))
        val camp = makeCampaign("open")

        for (action in DailyTaskAction.values()) {
            val result = DailyTaskActionGate.evaluate(action, camp, parsedMet, isAccountLocked = true, todayVn = todayVn)
            assertTrue(result is DailyTaskActionGateResult.Blocked)
            assertEquals(ActionGateBlockReason.AccountLocked, (result as DailyTaskActionGateResult.Blocked).reason)
        }
    }

    @Test
    fun testAdminBypassAllowsAllActionsEvenWhenUnmetOrLocked() {
        val parsedUnmet = json.decodeFromString<DailyTaskStatus>(makeValidJson(met = false, carryoverRemainingTests = 10))
        val camp = makeCampaign("open")

        for (action in DailyTaskAction.values()) {
            val result = DailyTaskActionGate.evaluate(action, camp, parsedUnmet, isAccountLocked = true, isAdmin = true, todayVn = todayVn)
            assertTrue(result.isAllowed)
        }
    }
}
