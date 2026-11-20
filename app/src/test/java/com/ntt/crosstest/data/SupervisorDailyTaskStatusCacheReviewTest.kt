package com.ntt.crosstest.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Missing server data must stay missing when a status is persisted and read back. */
class SupervisorDailyTaskStatusCacheReviewTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val day = "2026-11-12"
    private val campaign = Campaign(id = "cache-review", ownerId = "owner", appName = "App", packageName = "cache.review", status = "open")

    private fun verifyMissingFieldSurvivesCacheRoundTrip(key: String) {
        val fields = linkedMapOf(
            "status_date" to "\"$day\"", "mode" to "\"owner\"", "met" to "true",
            "history_ready" to "true", "carryover_ready" to "true", "carryover_remaining_tests" to "0"
        )
        fields.remove(key)
        val wire = fields.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
        val original = json.decodeFromString(DailyTaskStatus.serializer(), wire)
        val before = DailyTaskActionGate.evaluate(DailyTaskAction.PRODUCTION, campaign, original, todayVn = day)
        assertTrue("incomplete wire status must block before persistence", before is DailyTaskActionGateResult.Blocked)
        assertEquals(ActionGateBlockReason.DataPendingOrStale, (before as DailyTaskActionGateResult.Blocked).reason)

        val persisted = json.encodeToString(DailyTaskStatus.serializer(), original)
        val restored = json.decodeFromString(DailyTaskStatus.serializer(), persisted)
        val after = DailyTaskActionGate.evaluate(DailyTaskAction.PRODUCTION, campaign, restored, todayVn = day)
        assertTrue("cache must not manufacture an allowed status from omitted $key", after is DailyTaskActionGateResult.Blocked)
        assertEquals(ActionGateBlockReason.DataPendingOrStale, (after as DailyTaskActionGateResult.Blocked).reason)
    }

    @Test fun missingHistoryReadinessRemainsPendingAfterPersistence() = verifyMissingFieldSurvivesCacheRoundTrip("history_ready")
    @Test fun missingCarryoverReadinessRemainsPendingAfterPersistence() = verifyMissingFieldSurvivesCacheRoundTrip("carryover_ready")
    @Test fun missingDebtRemainsPendingAfterPersistence() = verifyMissingFieldSurvivesCacheRoundTrip("carryover_remaining_tests")

    private fun verifyUnknownMetIsPending(metField: String) {
        val wire = """{"status_date":"$day",$metField"history_ready":true,"carryover_ready":true,"carryover_remaining_tests":0}"""
        val task = json.decodeFromString(DailyTaskStatus.serializer(), wire)
        val result = DailyTaskActionGate.evaluate(DailyTaskAction.DELETE, campaign, task, todayVn = day)
        assertTrue(result is DailyTaskActionGateResult.Blocked)
        assertEquals("missing completion data requires retry, not a claim of unmet user work", ActionGateBlockReason.DataPendingOrStale, (result as DailyTaskActionGateResult.Blocked).reason)
    }
    @Test fun omittedCompletionFlagIsPending() = verifyUnknownMetIsPending("")
    @Test fun nullCompletionFlagIsPending() = verifyUnknownMetIsPending("\"met\":null,")
}
